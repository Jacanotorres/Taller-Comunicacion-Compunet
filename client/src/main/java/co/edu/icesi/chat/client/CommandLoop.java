package co.edu.icesi.chat.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import co.edu.icesi.chat.ChatException;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.UnknownException;

/**
 * Bucle de la CLI. Corre en el hilo principal, leyendo comandos del teclado,
 * independiente de los hilos de Ice que imprimen los eventos asíncronos.
 *
 * Para agregar un comando: registrarlo en {@link #registerCommands()}.
 */
public class CommandLoop {

    /** Acción de un comando; recibe el texto después del nombre del comando. */
    @FunctionalInterface
    interface Action {
        void run(String args) throws ChatException;
    }

    private record Command(String usage, String description, Action action) {
    }

    private final ClientContext context;
    private final Console console;
    private final CallSession calls;
    private final ConferenceSession conference;
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private Action plainTextAction;
    private volatile boolean running = true;

    public CommandLoop(ClientContext context, Console console, CallSession calls, ConferenceSession conference) {
        this.context = context;
        this.console = console;
        this.calls = calls;
        this.conference = conference;
        registerCommands();
    }

    private void registerCommands() {
        register("/help", "/help", "Muestra esta ayuda", args -> printHelp());
        new ChatCommands(context, console).registerIn(this);
        new FileCommands(context, console).registerIn(this);
        new CallCommands(context, console, calls).registerIn(this);
        new VoiceCommands(context, console, calls, conference).registerIn(this);
        register("/quit", "/quit", "Cierra el cliente", args -> running = false);
    }

    void register(String name, String usage, String description, Action action) {
        commands.put(name, new Command(usage, description, action));
    }

    /** Acción para el texto que no empieza por '/' (mensaje a la sala activa). */
    void onPlainText(Action action) {
        this.plainTextAction = action;
    }

    public void run() {
        console.plain("");
        console.plain("=== Chat Ice — Computación en Internet I ===");
        console.info("Escriba /help para ver los comandos disponibles.");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (running) {
            console.showPrompt();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                console.error("No se pudo leer la entrada: " + e.getMessage());
                break;
            }
            if (line == null) {
                break; // EOF (Ctrl+D)
            }
            execute(line.strip());
        }
        context.shutdown();
        console.info("Hasta luego.");
    }

    private void execute(String line) {
        if (line.isEmpty()) {
            return;
        }

        Action action;
        String args;
        if (line.startsWith("/")) {
            int space = line.indexOf(' ');
            String name = (space < 0 ? line : line.substring(0, space)).toLowerCase();
            Command command = commands.get(name);
            if (command == null) {
                console.error("Comando desconocido: " + name + ". Escriba /help.");
                return;
            }
            action = command.action();
            args = space < 0 ? "" : line.substring(space + 1).strip();
        } else {
            action = plainTextAction;
            args = line;
        }

        try {
            action.run(args);
        } catch (ChatException e) {
            // Excepciones Slice de la aplicación (usuario no existe, sala no existe, ...)
            console.error(e.reason);
        } catch (UnknownException e) {
            // Excepción no declarada en el servidor (ej. operación aún no implementada)
            console.error("El servidor no pudo procesar la solicitud: " + e.unknown);
        } catch (LocalException e) {
            console.error("Problema de comunicación con el servidor: " + e.ice_id());
        }
    }

    private void printHelp() {
        console.plain("Comandos disponibles:");
        for (Command command : commands.values()) {
            console.plain(String.format("  %-28s %s", command.usage(), command.description()));
        }
        console.plain("  Texto sin '/' se envía a la sala activa (la que muestra el prompt).");
    }
}

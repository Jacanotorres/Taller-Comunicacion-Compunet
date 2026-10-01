package co.edu.icesi.chat.client;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Salida de consola segura entre hilos.
 *
 * La CLI lee del teclado en el hilo principal mientras los callbacks de Ice
 * imprimen desde otros hilos. Todos los métodos están sincronizados y, antes de
 * imprimir un evento asíncrono, se borra la línea del prompt y se vuelve a
 * dibujar después, para que los mensajes entrantes no se mezclen con lo que el
 * usuario está escribiendo.
 */
public class Console {

    private static final String CLEAR_LINE = "\r\033[2K";
    private static final String RESET = "\033[0m";
    private static final String GRAY = "\033[90m";
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    private String prompt = "> ";

    /** Cambia el prompt (ej. al iniciar sesión: "juan> "). */
    public synchronized void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public synchronized void showPrompt() {
        out.print(prompt);
        out.flush();
    }

    /** Respuesta directa a un comando del usuario (hilo de la CLI). */
    public synchronized void info(String message) {
        out.println(GRAY + message + RESET);
    }

    public synchronized void success(String message) {
        out.println(GREEN + message + RESET);
    }

    public synchronized void error(String message) {
        out.println(RED + "Error: " + message + RESET);
    }

    public synchronized void plain(String message) {
        out.println(message);
    }

    /** Eco de algo que el propio usuario envió (hilo de la CLI), con la hora como los eventos. */
    public synchronized void echo(String message) {
        out.println(GRAY + "[" + LocalTime.now().format(TIME) + "] " + RESET + message);
    }

    /** Evento que llega por callback (hilo de Ice): no debe romper el prompt. */
    public synchronized void event(String message) {
        out.print(CLEAR_LINE);
        out.println(GRAY + "[" + LocalTime.now().format(TIME) + "] " + RESET + message);
        out.print(prompt);
        out.flush();
    }

    public static String red(String text) {
        return RED + text + RESET;
    }

    public static String gray(String text) {
        return GRAY + text + RESET;
    }

    public static String cyan(String text) {
        return CYAN + text + RESET;
    }

    public static String yellow(String text) {
        return YELLOW + text + RESET;
    }
}

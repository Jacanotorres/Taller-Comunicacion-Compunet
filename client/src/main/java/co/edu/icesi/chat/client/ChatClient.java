package co.edu.icesi.chat.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import co.edu.icesi.chat.ChatServicePrx;
import co.edu.icesi.chat.ClientCallbackPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

/**
 * Punto de entrada del cliente.
 *
 * 1. Conecta con el ChatService del servidor (proxy en config/client.config).
 * 2. Crea un adaptador de objetos SIN endpoints y registra el servant de callback.
 * 3. Al iniciar sesión, ese adaptador se asocia a la conexión ya abierta con el
 *    servidor (conexión bidireccional, ver ClientContext.bindConnection): el
 *    servidor invoca los callbacks por la misma conexión TCP, así el cliente no
 *    necesita abrir puertos ni ser alcanzable desde afuera.
 * 4. Arranca la CLI en el hilo principal.
 */
public class ChatClient {

    public static void main(String[] args) {
        Console console = new Console();

        List<String> extraArgs = new ArrayList<>();
        try (Communicator communicator = Util.initialize(args, extraArgs)) {
            // Ice solo lee de la línea de comandos las propiedades Ice.*; las de la
            // aplicación (ej. --ChatService.Proxy=... que envía -Phost) se leen aparte.
            communicator.getProperties().parseCommandLineOptions("ChatService", extraArgs.toArray(new String[0]));

            ChatServicePrx service = connect(communicator, console);
            if (service == null) {
                return;
            }

            // Adaptador sin endpoints: solo recibe invocaciones por conexiones ya abiertas.
            ObjectAdapter callbackAdapter = communicator.createObjectAdapter("");
            FileAssembler files = new FileAssembler(Path.of("downloads"), console);
            ClientCallbackPrx callback = ClientCallbackPrx.uncheckedCast(
                    callbackAdapter.addWithUUID(new ClientCallbackI(console, files)));
            callbackAdapter.activate();

            ClientContext context = new ClientContext(communicator, service, callback, callbackAdapter);
            try {
                new CommandLoop(context, console).run();
            } finally {
                files.close(); // borra los archivos a medias antes de salir
            }
        }
    }

    private static ChatServicePrx connect(Communicator communicator, Console console) {
        String proxyString = communicator.getProperties().getProperty("ChatService.Proxy");
        console.info("Conectando a " + proxyString + " ...");
        try {
            ChatServicePrx service = ChatServicePrx.checkedCast(communicator.propertyToProxy("ChatService.Proxy"));
            if (service == null) {
                console.error("El objeto remoto no es un ChatService válido.");
                return null;
            }
            console.info("Conectado al servidor.");
            return service;
        } catch (LocalException e) {
            console.error("No fue posible conectar con el servidor: " + e.ice_id()
                    + ". ¿Está corriendo ./gradlew runServer?");
            return null;
        }
    }
}
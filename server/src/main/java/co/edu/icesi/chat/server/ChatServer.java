package co.edu.icesi.chat.server;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

/**
 * Punto de entrada del servidor.
 *
 * Crea el communicator de Ice, publica el objeto "ChatService" en el adaptador
 * "ChatAdapter" (endpoints definidos en config/server.config) y queda
 * atendiendo invocaciones hasta que se detenga el proceso (Ctrl+C).
 */
public class ChatServer {

    public static final String ADAPTER_NAME = "ChatAdapter";
    public static final String SERVICE_IDENTITY = "ChatService";

    public static void main(String[] args) {
        try (Communicator communicator = Util.initialize(args)) {
            // Ctrl+C: apaga el communicator de forma ordenada
            Runtime.getRuntime().addShutdownHook(new Thread(communicator::shutdown));

            ObjectAdapter adapter = communicator.createObjectAdapter(ADAPTER_NAME);
            adapter.add(new ChatServiceI(adapter), Util.stringToIdentity(SERVICE_IDENTITY));
            adapter.activate();

            Log.info("Servidor de chat escuchando en: "
                    + communicator.getProperties().getProperty(ADAPTER_NAME + ".Endpoints"));
            Log.info("Presione Ctrl+C para detener.");

            communicator.waitForShutdown();
            Log.info("Servidor detenido.");
        }
    }
}

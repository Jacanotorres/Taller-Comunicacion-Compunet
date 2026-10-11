package co.edu.icesi.chat.server;

import java.net.SocketException;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

/**
 * Punto de entrada del servidor.
 *
 * Crea el communicator de Ice, publica el objeto "ChatService" en el adaptador
 * "ChatAdapter" (endpoints definidos en config/server.config) y queda
 * atendiendo invocaciones hasta que se detenga el proceso (Ctrl+C).
 *
 * También abre el relay UDP de las conferencias de voz (propiedad AudioRelay.Port).
 */
public class ChatServer {

    public static final String ADAPTER_NAME = "ChatAdapter";
    public static final String SERVICE_IDENTITY = "ChatService";
    public static final int DEFAULT_RELAY_PORT = 10001;

    public static void main(String[] args) throws SocketException {
        try (Communicator communicator = Util.initialize(args);
                AudioRelay relay = new AudioRelay(communicator.getProperties()
                        .getPropertyAsIntWithDefault("AudioRelay.Port", DEFAULT_RELAY_PORT))) {
            // Ctrl+C: apaga el communicator de forma ordenada
            Runtime.getRuntime().addShutdownHook(new Thread(communicator::shutdown));

            ObjectAdapter adapter = communicator.createObjectAdapter(ADAPTER_NAME);
            ChatHub hub = new ChatHub(adapter, relay);
            adapter.add(new ChatServiceI(hub), Util.stringToIdentity(SERVICE_IDENTITY));
            adapter.activate();
            relay.start();

            Log.info("Servidor de chat escuchando en: "
                    + communicator.getProperties().getProperty(ADAPTER_NAME + ".Endpoints"));
            Log.info("Relay de audio de las conferencias escuchando en UDP " + relay.port());
            Log.info("Presione Ctrl+C para detener.");

            communicator.waitForShutdown();
            Log.info("Servidor detenido.");
        }
    }
}

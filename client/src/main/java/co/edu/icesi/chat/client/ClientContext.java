package co.edu.icesi.chat.client;

import co.edu.icesi.chat.ChatServicePrx;
import co.edu.icesi.chat.ClientCallbackPrx;
import co.edu.icesi.chat.SessionPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectAdapter;

/**
 * Estado del cliente compartido entre la CLI y los callbacks.
 * Los campos de sesión son volatile porque los leen hilos distintos
 * (hilo de la CLI e hilos de despacho de Ice).
 */
public class ClientContext {

    private final Communicator communicator;
    private final ChatServicePrx service;
    private final ClientCallbackPrx callback;
    private final ObjectAdapter callbackAdapter;

    private volatile SessionPrx session;
    private volatile String nickname;
    private volatile String activeRoom;
    private volatile boolean closing;
    private final java.util.List<Runnable> sessionEndListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ClientContext(Communicator communicator, ChatServicePrx service, ClientCallbackPrx callback,
            ObjectAdapter callbackAdapter) {
        this.communicator = communicator;
        this.service = service;
        this.callback = callback;
        this.callbackAdapter = callbackAdapter;
    }

    public Communicator communicator() {
        return communicator;
    }

    public ChatServicePrx service() {
        return service;
    }

    public ClientCallbackPrx callback() {
        return callback;
    }

    /**
     * Prepara la conexión con el servidor para recibir callbacks (bidireccional).
     *
     * Se llama antes de cada login porque, si la conexión anterior se perdió, Ice
     * abre una nueva y hay que volver a asociarle el adaptador de callbacks.
     *
     * @param onLost se ejecuta (en un hilo de Ice) si la conexión se cae con sesión activa
     */
    public Connection bindConnection(Runnable onLost) {
        Connection connection = service.ice_getConnection();
        connection.setAdapter(callbackAdapter);
        connection.setCloseCallback(closed -> {
            if (!closing && session != null) {
                endSession();
                onLost.run();
            }
        });
        return connection;
    }

    public SessionPrx session() {
        return session;
    }

    
    public void onSessionEnd(Runnable listener) { sessionEndListeners.add(listener); }
    public String nickname() {
        return nickname;
    }

    public boolean isLoggedIn() {
        return session != null;
    }

    public void startSession(String nickname, SessionPrx session) {
        this.nickname = nickname;
        this.session = session;
    }

    public void endSession() {
        this.session = null;
        this.nickname = null;
        this.activeRoom = null;
        sessionEndListeners.forEach(Runnable::run);

    }

    /** Sala a la que se envía el texto escrito sin comando; null si no hay. */
    public String activeRoom() {
        return activeRoom;
    }

    public void setActiveRoom(String activeRoom) {
        this.activeRoom = activeRoom;
    }

    /** Cierre ordenado al salir del cliente: avisa al servidor para que libere la sesión. */
    public void shutdown() {
        closing = true;
        SessionPrx current = session;
        if (current != null) {
            try {
                current.logout();
            } catch (LocalException e) {
                // el servidor ya no está; no hay nada que limpiar
            }
            endSession();
        }
    }
}

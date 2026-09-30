package co.edu.icesi.chat.client;

import co.edu.icesi.chat.ChatServicePrx;
import co.edu.icesi.chat.ClientCallbackPrx;
import co.edu.icesi.chat.SessionPrx;
import com.zeroc.Ice.Communicator;

/**
 * Estado del cliente compartido entre la CLI y los callbacks.
 * Los campos de sesión son volatile porque los leen hilos distintos
 * (hilo de la CLI e hilos de despacho de Ice).
 */
public class ClientContext {

    private final Communicator communicator;
    private final ChatServicePrx service;
    private final ClientCallbackPrx callback;

    private volatile SessionPrx session;
    private volatile String nickname;

    public ClientContext(Communicator communicator, ChatServicePrx service, ClientCallbackPrx callback) {
        this.communicator = communicator;
        this.service = service;
        this.callback = callback;
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

    public SessionPrx session() {
        return session;
    }

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
    }
}

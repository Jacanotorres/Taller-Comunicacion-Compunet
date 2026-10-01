package co.edu.icesi.chat.server;

import co.edu.icesi.chat.ClientCallbackPrx;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.Identity;

/**
 * Datos de un usuario conectado. Es inmutable, así que puede compartirse entre
 * hilos sin sincronización. Se compara por referencia: dos sesiones del mismo
 * nickname en momentos distintos son objetos distintos.
 */
public final class ClientSession {

    private final String nickname;
    private final ClientCallbackPrx callback;
    private final Connection connection;
    private final Identity sessionId;

    public ClientSession(String nickname, ClientCallbackPrx callback, Connection connection, Identity sessionId) {
        this.nickname = nickname;
        this.callback = callback;
        this.connection = connection;
        this.sessionId = sessionId;
    }

    public String nickname() {
        return nickname;
    }

    /** Clave para comparar nicknames sin distinguir mayúsculas. */
    public String key() {
        return Names.keyOf(nickname);
    }

    /** Proxy de callback fijado a la conexión bidireccional del cliente. */
    public ClientCallbackPrx callback() {
        return callback;
    }

    public Connection connection() {
        return connection;
    }

    /** Identidad del servant Session de este usuario en el adaptador. */
    public Identity sessionId() {
        return sessionId;
    }

    @Override
    public String toString() {
        return nickname;
    }
}

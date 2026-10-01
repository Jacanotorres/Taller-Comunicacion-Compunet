package co.edu.icesi.chat.server;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import co.edu.icesi.chat.NicknameInUseException;
import co.edu.icesi.chat.UserNotFoundException;
import com.zeroc.Ice.Connection;

/**
 * Usuarios conectados. Thread-safe: varios hilos del pool de Ice atienden
 * logins, logouts y mensajes al mismo tiempo.
 *
 * La unicidad del nickname se garantiza con putIfAbsent, que es atómico: si dos
 * clientes piden el mismo nickname a la vez, solo uno gana.
 */
public class UserRegistry {

    private final ConcurrentMap<String, ClientSession> users = new ConcurrentHashMap<>();

    public void register(ClientSession session) throws NicknameInUseException {
        ClientSession existing = users.putIfAbsent(session.key(), session);
        if (existing != null) {
            throw new NicknameInUseException("El nickname '" + session.nickname()
                    + "' ya está en uso por otra sesión activa.");
        }
    }

    /**
     * Elimina la sesión solo si sigue siendo la registrada.
     *
     * @return true si se eliminó; false si ya no estaba (limpieza repetida)
     */
    public boolean unregister(ClientSession session) {
        return users.remove(session.key(), session);
    }

    public ClientSession find(String nickname) throws UserNotFoundException {
        ClientSession session = nickname == null ? null : users.get(Names.keyOf(nickname));
        if (session == null) {
            throw new UserNotFoundException("El usuario '" + nickname + "' no existe o está desconectado.");
        }
        return session;
    }

    public boolean isActive(ClientSession session) {
        return users.get(session.key()) == session;
    }

    /** Vista de las sesiones; se puede recorrer mientras otros hilos la modifican. */
    public Collection<ClientSession> all() {
        return users.values();
    }

    public List<ClientSession> byConnection(Connection connection) {
        return users.values().stream().filter(s -> s.connection() == connection).toList();
    }

    public String[] nicknames() {
        return users.values().stream()
                .map(ClientSession::nickname)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toArray(String[]::new);
    }
}

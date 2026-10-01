package co.edu.icesi.chat.server;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sala de chat. Los miembros se guardan en un mapa concurrente; las altas y
 * bajas las hace RoomManager de forma atómica.
 */
public final class Room {

    private final String name;
    private final String owner;
    private final ConcurrentMap<String, ClientSession> members = new ConcurrentHashMap<>();

    Room(String name, ClientSession owner) {
        this.name = name;
        this.owner = owner.nickname();
        members.put(owner.key(), owner);
    }

    public String name() {
        return name;
    }

    public String owner() {
        return owner;
    }

    public boolean hasMember(ClientSession session) {
        return members.get(session.key()) == session;
    }

    /** Vista de los miembros; se puede recorrer mientras otros hilos la modifican. */
    public Collection<ClientSession> members() {
        return members.values();
    }

    public int size() {
        return members.size();
    }

    public String[] memberNames() {
        return members.values().stream()
                .map(ClientSession::nickname)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toArray(String[]::new);
    }

    // Solo RoomManager modifica la membresía, dentro de compute() del mapa de salas.

    boolean add(ClientSession session) {
        return members.putIfAbsent(session.key(), session) == null;
    }

    boolean remove(ClientSession session) {
        return members.remove(session.key(), session);
    }
}

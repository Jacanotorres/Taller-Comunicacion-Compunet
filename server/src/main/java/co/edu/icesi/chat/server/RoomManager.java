package co.edu.icesi.chat.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

import co.edu.icesi.chat.InvalidNameException;
import co.edu.icesi.chat.NotRoomMemberException;
import co.edu.icesi.chat.RoomAlreadyExistsException;
import co.edu.icesi.chat.RoomInfo;
import co.edu.icesi.chat.RoomNotFoundException;

/**
 * Catálogo de salas. Thread-safe.
 *
 * Toda alta o baja de miembros se hace dentro de computeIfPresent() del mapa de
 * salas, que bloquea solo la entrada de esa sala. Así "sale el último miembro y
 * se borra la sala" y "entra alguien a esa misma sala" no pueden cruzarse.
 * Una sala sin miembros se elimina.
 */
public class RoomManager {

    /** Resultado de join: la sala y si el usuario realmente entró (o ya estaba). */
    public record Join(Room room, boolean added) {
    }

    private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();

    /** Crea la sala con su creador como primer miembro. */
    public Room create(String name, ClientSession owner) throws RoomAlreadyExistsException, InvalidNameException {
        Names.validate(name, "nombre de sala");
        Room room = new Room(name, owner);
        if (rooms.putIfAbsent(Names.keyOf(name), room) != null) {
            throw new RoomAlreadyExistsException("Ya existe una sala llamada '" + name + "'.");
        }
        return room;
    }

    public Room get(String name) throws RoomNotFoundException {
        Room room = name == null ? null : rooms.get(Names.keyOf(name));
        if (room == null) {
            throw new RoomNotFoundException("La sala '" + name + "' no existe.");
        }
        return room;
    }

    /** Devuelve la sala solo si el usuario pertenece a ella. */
    public Room requireMember(String name, ClientSession session)
            throws RoomNotFoundException, NotRoomMemberException {
        Room room = get(name);
        if (!room.hasMember(session)) {
            throw new NotRoomMemberException("No perteneces a la sala '" + room.name() + "'. Usa /join " + room.name());
        }
        return room;
    }

    public Join join(String name, ClientSession session) throws RoomNotFoundException {
        boolean[] added = {false};
        Room room = name == null ? null : rooms.computeIfPresent(Names.keyOf(name), (key, r) -> {
            added[0] = r.add(session);
            return r;
        });
        if (room == null) {
            throw new RoomNotFoundException("La sala '" + name + "' no existe.");
        }
        return new Join(room, added[0]);
    }

    public Room leave(String name, ClientSession session) throws RoomNotFoundException, NotRoomMemberException {
        Room[] found = {null};
        boolean[] removed = {false};
        if (name != null) {
            rooms.computeIfPresent(Names.keyOf(name), (key, r) -> {
                found[0] = r;
                removed[0] = r.remove(session);
                return r.size() == 0 ? null : r;
            });
        }
        if (found[0] == null) {
            throw new RoomNotFoundException("La sala '" + name + "' no existe.");
        }
        if (!removed[0]) {
            throw new NotRoomMemberException("No perteneces a la sala '" + found[0].name() + "'.");
        }
        return found[0];
    }

    /**
     * Saca al usuario de todas sus salas (logout o desconexión).
     *
     * @return las salas en las que estaba
     */
    public List<Room> removeFromAll(ClientSession session) {
        List<Room> left = new ArrayList<>();
        for (String key : rooms.keySet()) {
            rooms.computeIfPresent(key, (k, r) -> {
                if (r.remove(session)) {
                    left.add(r);
                }
                return r.size() == 0 ? null : r;
            });
        }
        return left;
    }

    /** @param voiceActive indica si la sala tiene una conferencia de voz en curso */
    public RoomInfo[] list(Predicate<Room> voiceActive) {
        return rooms.values().stream()
                .sorted(Comparator.comparing(Room::name, String.CASE_INSENSITIVE_ORDER))
                .map(r -> new RoomInfo(r.name(), r.owner(), r.size(), voiceActive.test(r)))
                .toArray(RoomInfo[]::new);
    }
}

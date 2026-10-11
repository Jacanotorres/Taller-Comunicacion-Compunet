package co.edu.icesi.chat.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import co.edu.icesi.chat.AudioEndpoint;
import co.edu.icesi.chat.CallException;
import co.edu.icesi.chat.NotRoomMemberException;
import co.edu.icesi.chat.RoomNotFoundException;

/**
 * Conferencias de voz en salas (RF-06): quién está en la llamada de voz de cada sala.
 *
 * Ice solo hace la señalización (entrar, salir, avisos); el audio va por UDP a través del
 * {@link AudioRelay}, al que este gestor le entrega la tabla de rutas cada vez que cambia.
 *
 * Reglas: una persona está como máximo en una conferencia, y nunca en una conferencia y en una
 * llamada 1 a 1 a la vez. Solo los miembros de la sala pueden entrar a su conferencia; al salir de
 * la sala o desconectarse, se sale también de la conferencia.
 *
 * Concurrencia: el estado se protege con el MISMO lock que CallManager (su monitor). Así la
 * regla "llamada 1 a 1 o conferencia, no ambas" se comprueba y se aplica de forma atómica entre
 * los dos gestores. Como en CallManager, las secciones críticas son cortas, sin E/S, y las
 * notificaciones a los clientes se envían fuera del lock y de forma asíncrona.
 */
public class ConferenceManager {

    /** Un participante de una conferencia. Inmutable. */
    private record Participant(ClientSession session, Room room, InetSocketAddress address, int id) {
    }

    private final ChatHub hub;
    private final AudioRelay relay;
    private final Object lock;

    // Protegido por "lock"
    private final Map<Room, Map<ClientSession, Participant>> byRoom = new HashMap<>();
    private final Map<ClientSession, Participant> byUser = new HashMap<>();
    private int nextId;

    public ConferenceManager(ChatHub hub, AudioRelay relay, CallManager calls) {
        this.hub = hub;
        this.relay = relay;
        this.lock = calls;
    }

    // ------------------------------------------------------------------
    // Operaciones que llegan desde SessionI
    // ------------------------------------------------------------------

    /**
     * {@code me} entra a la conferencia de la sala.
     *
     * @return el endpoint UDP del relay, al que el cliente debe enviar su audio
     */
    public AudioEndpoint join(ClientSession me, String roomName, AudioEndpoint myAudio)
            throws RoomNotFoundException, NotRoomMemberException, CallException {
        Room room = hub.rooms().requireMember(roomName, me);
        InetSocketAddress address = AudioAddresses.toSocketAddress(AudioAddresses.observed(me, myAudio));
        AudioEndpoint relayEndpoint = new AudioEndpoint(AudioAddresses.localHost(me), relay.port());

        synchronized (lock) {
            Participant current = byUser.get(me);
            if (current != null) {
                throw new CallException("Ya estás en la llamada de voz de #" + current.room().name()
                        + ". Usa /leavevoice primero.");
            }
            if (hub.calls().isBusy(me)) {
                throw new CallException("Estás en una llamada 1 a 1. Usa /hangup primero.");
            }
            for (Participant other : byUser.values()) {
                if (other.address().equals(address)) {
                    throw new CallException("Ese puerto UDP ya lo usa otro participante.");
                }
            }
            Participant participant = new Participant(me, room, address, nextId);
            nextId = (nextId + 1) & 0xFFFF; // el id viaja en 2 bytes
            byUser.put(me, participant);
            byRoom.computeIfAbsent(room, r -> new LinkedHashMap<>()).put(me, participant);
            publishRoutes();
        }

        // Si mientras tanto salió de la sala o se cerró su sesión, la limpieza de esos casos pudo
        // correr antes de que quedara registrado aquí. Se revierte para no dejar un participante
        // fantasma (el mismo patrón que joinRoom y CallManager.start).
        if (!hub.users().isActive(me) || !room.hasMember(me)) {
            remove(me, null);
            throw new CallException("No se pudo entrar a la llamada de voz: ya no estás en #" + room.name() + ".");
        }

        hub.broadcast(room.members(), me, cb -> cb.voiceParticipantJoinedAsync(room.name(), me.nickname()));
        Log.info("VOICE  " + me.nickname() + " entró a la voz de #" + room.name()
                + " (" + participants(room) + " en la llamada)");
        return relayEndpoint;
    }

    /** {@code me} sale de la conferencia de la sala, sin cortar a los demás. */
    public void leave(ClientSession me, String roomName) throws RoomNotFoundException, CallException {
        Participant participant;
        synchronized (lock) {
            Participant current = byUser.get(me);
            boolean sameRoom = current != null && Names.keyOf(current.room().name()).equals(Names.keyOf(roomName));
            participant = sameRoom ? remove(me, null) : null;
        }
        if (participant == null) {
            hub.rooms().get(roomName); // RoomNotFoundException si la sala no existe
            throw new CallException("No estás en la llamada de voz de #" + roomName + ".");
        }
        notifyLeft(participant, me);
        Log.info("VOICE  " + me.nickname() + " salió de la voz de #" + participant.room().name());
    }

    /** Lo llama SessionI.leaveRoom: quien sale de la sala sale también de su conferencia. */
    public void onLeaveRoom(ClientSession me, Room room) {
        Participant participant = remove(me, room);
        if (participant != null) {
            notifyLeft(participant, null);
            // Ya no es miembro, así que no le llega el aviso de la sala: se le avisa aparte para
            // que su cliente cierre el audio.
            hub.notify(me, cb -> cb.voiceParticipantLeftAsync(room.name(), me.nickname()));
            Log.info("VOICE  " + me.nickname() + " salió de la voz de #" + room.name() + " (dejó la sala)");
        }
    }

    /** Lo llama ChatHub.disconnect: los demás deben enterarse de que ya no está. */
    public void onDisconnect(ClientSession session) {
        Participant participant = remove(session, null);
        if (participant != null) {
            notifyLeft(participant, session);
            Log.info("VOICE  " + session.nickname() + " salió de la voz de #" + participant.room().name()
                    + " (se desconectó)");
        }
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    /** Hay una conferencia en curso en la sala (para RoomInfo.voiceActive). */
    public boolean isActive(Room room) {
        synchronized (lock) {
            return byRoom.containsKey(room);
        }
    }

    /** El usuario está en alguna conferencia (CallManager lo usa para no mezclar llamadas). */
    public boolean isParticipant(ClientSession session) {
        synchronized (lock) {
            return byUser.containsKey(session);
        }
    }

    private int participants(Room room) {
        synchronized (lock) {
            Map<ClientSession, Participant> members = byRoom.get(room);
            return members == null ? 0 : members.size();
        }
    }

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    /**
     * Saca al usuario de su conferencia (si {@code onlyRoom} no es null, solo si está en esa).
     *
     * @return el participante eliminado, o null si no estaba
     */
    private Participant remove(ClientSession session, Room onlyRoom) {
        synchronized (lock) {
            Participant participant = byUser.get(session);
            if (participant == null || (onlyRoom != null && participant.room() != onlyRoom)) {
                return null;
            }
            byUser.remove(session);
            Map<ClientSession, Participant> members = byRoom.get(participant.room());
            members.remove(session);
            if (members.isEmpty()) {
                byRoom.remove(participant.room()); // la conferencia termina con su último participante
            }
            publishRoutes();
            return participant;
        }
    }

    /** Con el lock tomado: recalcula a quién reenvía el relay lo que llega de cada participante. */
    private void publishRoutes() {
        Map<SocketAddress, AudioRelay.Route> routes = new HashMap<>();
        for (Map<ClientSession, Participant> members : byRoom.values()) {
            for (Participant sender : members.values()) {
                List<SocketAddress> targets = new ArrayList<>();
                for (Participant receiver : members.values()) {
                    if (receiver != sender) { // nadie recibe su propia voz (eco)
                        targets.add(receiver.address());
                    }
                }
                routes.put(sender.address(), new AudioRelay.Route(sender.id(), List.copyOf(targets)));
            }
        }
        relay.updateRoutes(routes);
    }

    /** Avisa a los miembros de la sala (menos a {@code except}) que el participante salió de la voz. */
    private void notifyLeft(Participant participant, ClientSession except) {
        Room room = participant.room();
        String nickname = participant.session().nickname();
        hub.broadcast(room.members(), except, cb -> cb.voiceParticipantLeftAsync(room.name(), nickname));
    }
}

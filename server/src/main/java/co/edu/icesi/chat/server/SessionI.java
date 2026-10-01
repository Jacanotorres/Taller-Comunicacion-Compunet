package co.edu.icesi.chat.server;

import co.edu.icesi.chat.AudioEndpoint;
import co.edu.icesi.chat.CallException;
import co.edu.icesi.chat.ChatMessage;
import co.edu.icesi.chat.FileChunk;
import co.edu.icesi.chat.FileTransferException;
import co.edu.icesi.chat.InvalidNameException;
import co.edu.icesi.chat.NotRoomMemberException;
import co.edu.icesi.chat.RoomAlreadyExistsException;
import co.edu.icesi.chat.RoomInfo;
import co.edu.icesi.chat.RoomNotFoundException;
import co.edu.icesi.chat.Session;
import co.edu.icesi.chat.UserNotFoundException;
import com.zeroc.Ice.Current;

/**
 * Servant de la sesión de un usuario autenticado. Hay una instancia por cliente
 * conectado, así que cada operación ya sabe quién la invoca.
 *
 * No guarda estado mutable: todo el estado compartido vive en ChatHub
 * (UserRegistry y RoomManager), que es thread-safe.
 */
public class SessionI implements Session {

    private final ClientSession me;
    private final ChatHub hub;

    public SessionI(ClientSession me, ChatHub hub) {
        this.me = me;
        this.hub = hub;
    }

    // ------------------------------------------------------------------
    // Sesión y presencia (RF-01)
    // ------------------------------------------------------------------

    @Override
    public String[] listUsers(Current current) {
        return hub.users().nicknames();
    }

    @Override
    public void logout(Current current) {
        hub.disconnect(me, "logout");
    }

    // ------------------------------------------------------------------
    // Mensajería privada (RF-02)
    // ------------------------------------------------------------------

    @Override
    public void sendPrivateMessage(String to, String text, Current current) throws UserNotFoundException {
        ClientSession target = hub.users().find(to);
        ChatMessage message = new ChatMessage(me.nickname(), "", text, System.currentTimeMillis());

        // Despacho asíncrono: esta operación retorna (confirmación al remitente)
        // sin esperar a que el destinatario procese el mensaje.
        hub.notify(target, cb -> cb.privateMessageAsync(message));
        Log.info("MSG    " + me.nickname() + " -> " + target.nickname());
    }

    // ------------------------------------------------------------------
    // Salas (RF-03)
    // ------------------------------------------------------------------

    @Override
    public void createRoom(String name, Current current)
            throws RoomAlreadyExistsException, InvalidNameException {
        Room room = hub.rooms().create(name, me);
        Log.info("ROOM   " + me.nickname() + " creó #" + room.name());
    }

    @Override
    public RoomInfo[] listRooms(Current current) {
        return hub.rooms().list();
    }

    @Override
    public void joinRoom(String name, Current current) throws RoomNotFoundException {
        RoomManager.Join join = hub.rooms().join(name, me);
        if (!join.added()) {
            return; // ya era miembro
        }
        Room room = join.room();

        // Si la sesión se cerró mientras entraba (desconexión concurrente), se revierte
        // para no dejar un miembro fantasma en la sala.
        if (!hub.users().isActive(me)) {
            hub.rooms().removeFromAll(me);
            return;
        }

        hub.broadcast(room.members(), me, cb -> cb.roomMemberJoinedAsync(room.name(), me.nickname()));
        Log.info("ROOM   " + me.nickname() + " entró a #" + room.name());
    }

    @Override
    public void leaveRoom(String name, Current current)
            throws RoomNotFoundException, NotRoomMemberException {
        Room room = hub.rooms().leave(name, me);
        hub.broadcast(room.members(), me, cb -> cb.roomMemberLeftAsync(room.name(), me.nickname()));
        Log.info("ROOM   " + me.nickname() + " salió de #" + room.name());
    }

    @Override
    public String[] listRoomMembers(String name, Current current) throws RoomNotFoundException {
        return hub.rooms().get(name).memberNames();
    }

    @Override
    public void sendRoomMessage(String room, String text, Current current)
            throws RoomNotFoundException, NotRoomMemberException {
        // Aislamiento: solo un miembro puede publicar, y solo los miembros reciben.
        Room target = hub.rooms().requireMember(room, me);
        ChatMessage message = new ChatMessage(me.nickname(), target.name(), text, System.currentTimeMillis());

        // Difusión a todos los miembros menos al emisor (sin duplicado).
        hub.broadcast(target.members(), me, cb -> cb.roomMessageAsync(message));
        Log.info("MSG    " + me.nickname() + " -> #" + target.name());
    }

    // ------------------------------------------------------------------
    // Archivos (RF-04) — Etapa 3
    // ------------------------------------------------------------------

    @Override
    public void sendFileChunkToUser(String to, FileChunk chunk, Current current)
            throws UserNotFoundException, FileTransferException {
        throw pending("sendFileChunkToUser", 3);
    }

    @Override
    public void sendFileChunkToRoom(String room, FileChunk chunk, Current current)
            throws RoomNotFoundException, NotRoomMemberException, FileTransferException {
        throw pending("sendFileChunkToRoom", 3);
    }

    // ------------------------------------------------------------------
    // Llamadas 1 a 1 (RF-05) — Etapa 4
    // ------------------------------------------------------------------

    @Override
    public void startCall(String to, AudioEndpoint myAudio, Current current)
            throws UserNotFoundException, CallException {
        throw pending("startCall", 4);
    }

    @Override
    public void answerCall(String caller, boolean accept, AudioEndpoint myAudio, Current current)
            throws CallException {
        throw pending("answerCall", 4);
    }

    @Override
    public void hangup(Current current) throws CallException {
        throw pending("hangup", 4);
    }

    // ------------------------------------------------------------------
    // Conferencias de voz (RF-06) — Etapa 5
    // ------------------------------------------------------------------

    @Override
    public AudioEndpoint joinVoice(String room, AudioEndpoint myAudio, Current current)
            throws RoomNotFoundException, NotRoomMemberException, CallException {
        throw pending("joinVoice", 5);
    }

    @Override
    public void leaveVoice(String room, Current current) throws RoomNotFoundException, CallException {
        throw pending("leaveVoice", 5);
    }

    private static UnsupportedOperationException pending(String operation, int stage) {
        return new UnsupportedOperationException(operation + " pendiente de implementar (Etapa " + stage + ")");
    }
}

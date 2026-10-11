package co.edu.icesi.chat.server;

import co.edu.icesi.chat.AudioEndpoint;
import co.edu.icesi.chat.CallException;
import co.edu.icesi.chat.ChatMessage;
import co.edu.icesi.chat.FileChunk;
import co.edu.icesi.chat.FileTransferException;
import co.edu.icesi.chat.FileTransferLimits;
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
 * (UserRegistry, RoomManager, CallManager y ConferenceManager), que es thread-safe.
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
        return hub.rooms().list(hub.conferences()::isActive);
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
        hub.conferences().onLeaveRoom(me, room); // si estaba en la voz de la sala, sale también
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
        requireValid(chunk);
        ClientSession target = hub.users().find(to);
        if (target == me) {
            throw new FileTransferException("No puedes enviarte un archivo a ti mismo.");
        }

        // El servidor no guarda el archivo: solo reenvía cada fragmento al callback del
        // destinatario, de forma asíncrona (un receptor lento no bloquea un hilo del servidor).
        hub.notify(target, cb -> cb.fileChunkAsync(me.nickname(), "", chunk));
        logFile(chunk, target.nickname());
    }

    @Override
    public void sendFileChunkToRoom(String room, FileChunk chunk, Current current)
            throws RoomNotFoundException, NotRoomMemberException, FileTransferException {
        requireValid(chunk);
        // Aislamiento: solo un miembro puede enviar, y solo los miembros reciben.
        Room target = hub.rooms().requireMember(room, me);

        // A todos los miembros menos al emisor (no recibe su propio archivo).
        hub.broadcast(target.members(), me, cb -> cb.fileChunkAsync(me.nickname(), target.name(), chunk));
        logFile(chunk, "#" + target.name());
    }

    /** Rechaza fragmentos incoherentes antes de reenviarlos a nadie (ver FileTransferLimits). */
    private static void requireValid(FileChunk chunk) throws FileTransferException {
        String problem = FileTransferLimits.check(chunk);
        if (problem != null) {
            throw new FileTransferException(problem);
        }
    }

    /** Solo registra el primer y el último fragmento, para no inundar el log. */
    private void logFile(FileChunk chunk, String destination) {
        if (chunk.index == 0 || chunk.index == chunk.meta.totalChunks - 1) {
            Log.info("FILE   " + me.nickname() + " -> " + destination + " '" + chunk.meta.fileName
                    + "' fragmento " + (chunk.index + 1) + "/" + chunk.meta.totalChunks);
        }
    }

    // ------------------------------------------------------------------
    // Llamadas 1 a 1 (RF-05) — Etapa 4
    // ------------------------------------------------------------------

    @Override
    public void startCall(String to, AudioEndpoint myAudio, Current current)
            throws UserNotFoundException, CallException {
        hub.calls().start(me, to, myAudio);
    }

    @Override
    public void answerCall(String caller, boolean accept, AudioEndpoint myAudio, Current current)
            throws CallException {
        hub.calls().answer(me, caller, accept, myAudio);
    }

    @Override
    public void hangup(Current current) throws CallException {
        hub.calls().hangup(me);
    }

    // ------------------------------------------------------------------
    // Conferencias de voz (RF-06) — Etapa 5
    // ------------------------------------------------------------------

    @Override
    public AudioEndpoint joinVoice(String room, AudioEndpoint myAudio, Current current)
            throws RoomNotFoundException, NotRoomMemberException, CallException {
        return hub.conferences().join(me, room, myAudio);
    }

    @Override
    public void leaveVoice(String room, Current current) throws RoomNotFoundException, CallException {
        hub.conferences().leave(me, room);
    }
}
package co.edu.icesi.chat.server;

import co.edu.icesi.chat.AudioEndpoint;
import co.edu.icesi.chat.CallException;
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
 * conectado, así que cada operación ya sabe quién la invoca (nickname).
 *
 * Esqueleto de la Etapa 1: cada bloque indica en qué etapa se implementa.
 */
public class SessionI implements Session {

    private final String nickname;

    public SessionI(String nickname) {
        this.nickname = nickname;
    }

    public String getNickname() {
        return nickname;
    }

    // ------------------------------------------------------------------
    // Sesión y presencia (RF-01) — Etapa 2
    // ------------------------------------------------------------------

    @Override
    public String[] listUsers(Current current) {
        throw pending("listUsers", 2);
    }

    @Override
    public void logout(Current current) {
        throw pending("logout", 2);
    }

    // ------------------------------------------------------------------
    // Mensajería privada (RF-02) — Etapa 2
    // ------------------------------------------------------------------

    @Override
    public void sendPrivateMessage(String to, String text, Current current) throws UserNotFoundException {
        throw pending("sendPrivateMessage", 2);
    }

    // ------------------------------------------------------------------
    // Salas (RF-03) — Etapa 2
    // ------------------------------------------------------------------

    @Override
    public void createRoom(String name, Current current)
            throws RoomAlreadyExistsException, InvalidNameException {
        throw pending("createRoom", 2);
    }

    @Override
    public RoomInfo[] listRooms(Current current) {
        throw pending("listRooms", 2);
    }

    @Override
    public void joinRoom(String name, Current current) throws RoomNotFoundException {
        throw pending("joinRoom", 2);
    }

    @Override
    public void leaveRoom(String name, Current current)
            throws RoomNotFoundException, NotRoomMemberException {
        throw pending("leaveRoom", 2);
    }

    @Override
    public String[] listRoomMembers(String name, Current current) throws RoomNotFoundException {
        throw pending("listRoomMembers", 2);
    }

    @Override
    public void sendRoomMessage(String room, String text, Current current)
            throws RoomNotFoundException, NotRoomMemberException {
        throw pending("sendRoomMessage", 2);
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

// Interfaces que implementa el SERVIDOR.
//
// Patrón de sesión: ChatService solo expone login(). Tras autenticarse, el
// cliente recibe un proxy a su propia Session, que identifica al usuario en
// cada operación (no hay que enviar el nickname en cada llamada y nadie puede
// suplantar a otro usuario).
#pragma once

#include <Types.ice>
#include <Callback.ice>

[["java:package:co.edu.icesi"]]
module chat
{
    interface Session
    {
        // --- Sesión y presencia (RF-01) ---
        StringSeq listUsers();
        void logout();

        // --- Mensajería privada (RF-02) ---
        void sendPrivateMessage(string to, string text)
            throws UserNotFoundException;

        // --- Salas (RF-03) ---
        void createRoom(string name)
            throws RoomAlreadyExistsException, InvalidNameException;
        RoomInfoSeq listRooms();
        void joinRoom(string name)
            throws RoomNotFoundException;
        void leaveRoom(string name)
            throws RoomNotFoundException, NotRoomMemberException;
        StringSeq listRoomMembers(string name)
            throws RoomNotFoundException;
        void sendRoomMessage(string room, string text)
            throws RoomNotFoundException, NotRoomMemberException;

        // --- Archivos (RF-04) ---
        void sendFileChunkToUser(string to, FileChunk chunk)
            throws UserNotFoundException, FileTransferException;
        void sendFileChunkToRoom(string room, FileChunk chunk)
            throws RoomNotFoundException, NotRoomMemberException, FileTransferException;

        // --- Llamadas 1 a 1 (RF-05) ---
        void startCall(string to, AudioEndpoint myAudio)
            throws UserNotFoundException, CallException;
        void answerCall(string caller, bool accept, AudioEndpoint myAudio)
            throws CallException;
        void hangup()
            throws CallException;

        // --- Conferencias en salas (RF-06, relay centralizado) ---
        /** Devuelve el endpoint UDP del relay del servidor al que enviar audio. */
        AudioEndpoint joinVoice(string room, AudioEndpoint myAudio)
            throws RoomNotFoundException, NotRoomMemberException, CallException;
        void leaveVoice(string room)
            throws RoomNotFoundException, CallException;
    }

    interface ChatService
    {
        /**
         * Inicia sesión y registra el proxy de callback del cliente.
         * Lanza NicknameInUseException si el nickname ya tiene una sesión activa.
         */
        Session* login(string nickname, ClientCallback* callback)
            throws InvalidNameException, NicknameInUseException;
    }
}

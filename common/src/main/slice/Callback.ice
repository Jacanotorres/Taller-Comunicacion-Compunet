// Interfaz que implementa el CLIENTE para que el servidor le notifique
// eventos de forma asíncrona (callbacks por conexión bidireccional).
#pragma once

#include <Types.ice>

[["java:package:co.edu.icesi"]]
module chat
{
    interface ClientCallback
    {
        // --- Presencia ---
        void userConnected(string nickname);
        void userDisconnected(string nickname);

        // --- Mensajería ---
        void privateMessage(ChatMessage msg);
        void roomMessage(ChatMessage msg);

        // --- Salas: avisos a los miembros cuando alguien entra o sale ---
        void roomMemberJoined(string room, string nickname);
        void roomMemberLeft(string room, string nickname);

        // --- Archivos: sender = remitente, room vacío si es privado ---
        void fileChunk(string sender, string room, FileChunk chunk);

        // --- Llamadas 1 a 1 ---
        void incomingCall(string caller, AudioEndpoint callerAudio);
        void callAccepted(string callee, AudioEndpoint calleeAudio);
        void callRejected(string callee);
        void callEnded(string peer);

        // --- Conferencias de voz en salas ---
        void voiceParticipantJoined(string room, string nickname);
        void voiceParticipantLeft(string room, string nickname);
    }
}

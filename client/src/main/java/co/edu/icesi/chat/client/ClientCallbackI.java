package co.edu.icesi.chat.client;

import co.edu.icesi.chat.AudioEndpoint;
import co.edu.icesi.chat.ChatMessage;
import co.edu.icesi.chat.ClientCallback;
import co.edu.icesi.chat.FileChunk;
import com.zeroc.Ice.Current;

/**
 * Servant de callback del cliente: el servidor lo invoca para notificar eventos.
 *
 * Se ejecuta en hilos del pool de Ice, nunca en el hilo de la CLI, así que no
 * debe bloquearse (nada de leer del teclado ni esperar aquí).
 */
public class ClientCallbackI implements ClientCallback {

    private final Console console;
    private final FileAssembler files;
    private final CallSession calls;
    private final ConferenceSession conference;

    public ClientCallbackI(Console console, FileAssembler files, CallSession calls, ConferenceSession conference) {
        this.console = console;
        this.files = files;
        this.calls = calls;
        this.conference = conference;
    }

    // --- Presencia ---

    @Override
    public void userConnected(String nickname, Current current) {
        console.event(Console.yellow("* " + nickname + " se conectó"));
    }

    @Override
    public void userDisconnected(String nickname, Current current) {
        console.event(Console.yellow("* " + nickname + " se desconectó"));
    }

    // --- Mensajería ---

    @Override
    public void privateMessage(ChatMessage msg, Current current) {
        console.event(Console.cyan("[privado] " + msg.sender) + ": " + msg.text);
    }

    @Override
    public void roomMessage(ChatMessage msg, Current current) {
        console.event(Console.cyan("[#" + msg.room + "] " + msg.sender) + ": " + msg.text);
    }

    @Override
    public void roomMemberJoined(String room, String nickname, Current current) {
        console.event(Console.yellow("* " + nickname + " entró a #" + room));
    }

    @Override
    public void roomMemberLeft(String room, String nickname, Current current) {
        console.event(Console.yellow("* " + nickname + " salió de #" + room));
    }

    // --- Archivos (Etapa 3) ---

    @Override
    public void fileChunk(String sender, String room, FileChunk chunk, Current current) {
        // Solo encola: la escritura en disco la hace el hilo de FileAssembler, no este hilo de Ice.
        files.onChunk(sender, room, chunk);
    }

    // --- Llamadas 1 a 1 (Etapa 4) ---

    @Override
    public void incomingCall(String caller, AudioEndpoint callerAudio, Current current) {
        // Solo encola: CallSession procesa el aviso en su propio hilo, no en este hilo de Ice.
        calls.onIncomingCall(caller, callerAudio);
    }

    @Override
    public void callAccepted(String callee, AudioEndpoint calleeAudio, Current current) {
        calls.onCallAccepted(callee, calleeAudio);
    }

    @Override
    public void callRejected(String callee, Current current) {
        calls.onCallRejected(callee);
    }

    @Override
    public void callEnded(String peer, Current current) {
        calls.onCallEnded(peer);
    }

    // --- Conferencias (Etapa 5) ---

    @Override
    public void voiceParticipantJoined(String room, String nickname, Current current) {
        // Solo encola: ConferenceSession procesa el aviso en su propio hilo.
        conference.onParticipantJoined(room, nickname);
    }

    @Override
    public void voiceParticipantLeft(String room, String nickname, Current current) {
        conference.onParticipantLeft(room, nickname);
    }
}
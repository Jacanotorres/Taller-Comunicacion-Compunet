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

    public ClientCallbackI(Console console, FileAssembler files) {
        this.console = console;
        this.files = files;
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
        
        files.onChunk(sender, room, chunk);
        
    }

    // --- Llamadas 1 a 1 (Etapa 4) ---

    @Override
    public void incomingCall(String caller, AudioEndpoint callerAudio, Current current) {
        // TODO Etapa 4: guardar la llamada pendiente para /accept o /reject.
        console.event(Console.yellow("Llamada entrante de " + caller));
    }

    @Override
    public void callAccepted(String callee, AudioEndpoint calleeAudio, Current current) {
        // TODO Etapa 4: iniciar envío/recepción de audio UDP hacia calleeAudio.
        console.event(Console.yellow(callee + " aceptó la llamada"));
    }

    @Override
    public void callRejected(String callee, Current current) {
        console.event(Console.yellow(callee + " rechazó la llamada"));
    }

    @Override
    public void callEnded(String peer, Current current) {
        // TODO Etapa 4: detener audio y cerrar sockets UDP.
        console.event(Console.yellow("La llamada con " + peer + " terminó"));
    }

    // --- Conferencias (Etapa 5) ---

    @Override
    public void voiceParticipantJoined(String room, String nickname, Current current) {
        console.event(Console.yellow("[#" + room + "] " + nickname + " entró a la llamada de voz"));
    }

    @Override
    public void voiceParticipantLeft(String room, String nickname, Current current) {
        console.event(Console.yellow("[#" + room + "] " + nickname + " salió de la llamada de voz"));
    }
}

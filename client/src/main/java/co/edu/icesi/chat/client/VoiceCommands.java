package co.edu.icesi.chat.client;

import co.edu.icesi.chat.ChatException;

/**
 * Comandos de conferencias de voz en salas (RF-06): /voice, /leavevoice, /mute y /unmute.
 *
 * /mute y /unmute sirven tanto en una conferencia como en una llamada 1 a 1. La lógica y el estado
 * están en {@link ConferenceSession} y {@link CallSession}.
 */
public class VoiceCommands {

    private final ClientContext context;
    private final Console console;
    private final CallSession calls;
    private final ConferenceSession conference;

    public VoiceCommands(ClientContext context, Console console, CallSession calls, ConferenceSession conference) {
        this.context = context;
        this.console = console;
        this.calls = calls;
        this.conference = conference;
    }

    public void registerIn(CommandLoop loop) {
        loop.register("/voice", "/voice [sala]", "Entra a la llamada de voz de una sala (por defecto, la activa)", this::voice);
        loop.register("/leavevoice", "/leavevoice", "Sale de la llamada de voz de la sala", args -> conference.leave());
        loop.register("/mute", "/mute", "Silencia tu micrófono en la llamada", args -> mute(true));
        loop.register("/unmute", "/unmute", "Vuelve a activar tu micrófono", args -> mute(false));
    }

    private void voice(String args) throws ChatException {
        if (!context.isLoggedIn()) {
            console.error("Primero inicia sesión con /login <nickname>.");
            return;
        }
        if (args.contains(" ")) {
            console.error("Uso: /voice [sala]");
            return;
        }
        String room = args.isEmpty() ? context.activeRoom() : args;
        if (room == null) {
            console.error("No hay sala activa. Usa /voice <sala>.");
            return;
        }
        conference.join(room);
    }

    private void mute(boolean muted) {
        if (!context.isLoggedIn()) {
            console.error("Primero inicia sesión con /login <nickname>.");
            return;
        }
        if (!conference.setMuted(muted) && !calls.setMuted(muted)) {
            console.error("No estás en ninguna llamada.");
            return;
        }
        console.info(muted
                ? "Micrófono silenciado: los demás ya no te oyen. Usa /unmute para volver a hablar."
                : "Micrófono activo.");
    }
}

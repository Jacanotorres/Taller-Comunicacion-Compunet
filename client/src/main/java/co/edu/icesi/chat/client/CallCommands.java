package co.edu.icesi.chat.client;

import co.edu.icesi.chat.ChatException;

/**
 * Comandos de llamadas de voz 1 a 1 (RF-05): /call, /accept, /reject y /hangup.
 *
 * Solo validan los argumentos; la lógica y el estado de la llamada están en {@link CallSession}.
 */
public class CallCommands {

    private final ClientContext context;
    private final Console console;
    private final CallSession calls;

    public CallCommands(ClientContext context, Console console, CallSession calls) {
        this.context = context;
        this.console = console;
        this.calls = calls;
    }

    public void registerIn(CommandLoop loop) {
        loop.register("/call", "/call <usuario>", "Llama por voz a un usuario", this::call);
        loop.register("/accept", "/accept", "Acepta la llamada entrante", args -> calls.accept());
        loop.register("/reject", "/reject", "Rechaza la llamada entrante", args -> calls.reject());
        loop.register("/hangup", "/hangup", "Cuelga la llamada (o cancela la que estás haciendo)", args -> calls.hangup());
    }

    private void call(String args) throws ChatException {
        if (!context.isLoggedIn()) {
            console.error("Primero inicia sesión con /login <nickname>.");
            return;
        }
        if (args.isEmpty() || args.contains(" ")) {
            console.error("Uso: /call <usuario>");
            return;
        }
        if (args.equalsIgnoreCase(context.nickname())) {
            console.error("No puedes llamarte a ti mismo.");
            return;
        }
        calls.call(args);
    }
}
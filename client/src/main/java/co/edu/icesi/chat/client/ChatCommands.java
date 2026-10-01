package co.edu.icesi.chat.client;

import co.edu.icesi.chat.ChatException;
import co.edu.icesi.chat.RoomInfo;
import co.edu.icesi.chat.SessionPrx;
import com.zeroc.Ice.Connection;

/**
 * Comandos de sesión, presencia, mensajería privada y salas (RF-01, RF-02, RF-03).
 *
 * Todos corren en el hilo de la CLI. Las invocaciones al servidor son síncronas
 * (solo bloquean la CLI un instante); los mensajes entrantes llegan por otros
 * hilos a ClientCallbackI.
 */
public class ChatCommands {

    private final ClientContext context;
    private final Console console;

    public ChatCommands(ClientContext context, Console console) {
        this.context = context;
        this.console = console;
    }

    public void registerIn(CommandLoop loop) {
        loop.register("/login", "/login <nickname>", "Inicia sesión", this::login);
        loop.register("/logout", "/logout", "Cierra la sesión", args -> logout());
        loop.register("/users", "/users", "Lista los usuarios conectados", args -> users());
        loop.register("/msg", "/msg <usuario> <texto>", "Mensaje privado", this::privateMessage);
        loop.register("/create", "/create <sala>", "Crea una sala y entra a ella", this::createRoom);
        loop.register("/rooms", "/rooms", "Lista las salas disponibles", args -> rooms());
        loop.register("/join", "/join <sala>", "Entra a una sala", this::joinRoom);
        loop.register("/leave", "/leave <sala>", "Sale de una sala", this::leaveRoom);
        loop.register("/members", "/members <sala>", "Lista los miembros de una sala", this::members);
        loop.register("/room", "/room <sala> [texto]", "Envía a una sala, o la deja como sala activa", this::room);
        loop.onPlainText(this::plainText);
    }

    // ------------------------------------------------------------------
    // Sesión y presencia
    // ------------------------------------------------------------------

    private void login(String args) throws ChatException {
        if (context.isLoggedIn()) {
            console.error("Ya iniciaste sesión como " + context.nickname() + ". Usa /logout primero.");
            return;
        }
        if (args.isEmpty() || args.contains(" ")) {
            usage("/login <nickname>");
            return;
        }

        Connection connection = context.bindConnection(this::connectionLost);
        // El proxy de sesión se fija a la misma conexión: todo el tráfico (invocaciones
        // y callbacks) usa una sola conexión TCP, y si esta se cae no se reconecta en silencio.
        SessionPrx session = context.service().login(args, context.callback()).ice_fixed(connection);

        context.startSession(args, session);
        refreshPrompt();
        console.success("Sesión iniciada como " + args + ".");
        users();
    }

    private void logout() {
        SessionPrx session = requireSession();
        if (session == null) {
            return;
        }
        session.logout();
        context.endSession();
        refreshPrompt();
        console.success("Sesión cerrada.");
    }

    private void connectionLost() {
        refreshPrompt();
        console.event(Console.red("Se perdió la conexión con el servidor. Usa /login para reconectar."));
    }

    private void users() {
        SessionPrx session = requireSession();
        if (session == null) {
            return;
        }
        String[] users = session.listUsers();
        StringBuilder line = new StringBuilder("En línea (" + users.length + "): ");
        for (int i = 0; i < users.length; i++) {
            line.append(i > 0 ? ", " : "").append(users[i]);
            if (users[i].equalsIgnoreCase(context.nickname())) {
                line.append(" (tú)");
            }
        }
        console.plain(line.toString());
    }

    // ------------------------------------------------------------------
    // Mensajería privada
    // ------------------------------------------------------------------

    private void privateMessage(String args) throws ChatException {
        SessionPrx session = requireSession();
        if (session == null) {
            return;
        }
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            usage("/msg <usuario> <texto>");
            return;
        }
        String to = parts[0];
        String text = parts[1].strip();
        if (to.equalsIgnoreCase(context.nickname())) {
            console.error("No puedes enviarte un mensaje a ti mismo.");
            return;
        }

        // Si retorna sin excepción, el servidor recibió el mensaje y lo despachó al destinatario.
        session.sendPrivateMessage(to, text);
        console.echo(Console.cyan("[privado → " + to + "]") + " " + text + Console.gray("  ✓ entregado al servidor"));
    }

    // ------------------------------------------------------------------
    // Salas
    // ------------------------------------------------------------------

    private void createRoom(String args) throws ChatException {
        SessionPrx session = requireSession();
        if (session == null) {
            return;
        }
        if (args.isEmpty() || args.contains(" ")) {
            usage("/create <sala>");
            return;
        }
        session.createRoom(args);
        activate(args);
        console.success("Sala #" + args + " creada. Lo que escribas sin '/' se envía a ella.");
    }

    private void rooms() {
        SessionPrx session = requireSession();
        if (session == null) {
            return;
        }
        RoomInfo[] rooms = session.listRooms();
        if (rooms.length == 0) {
            console.plain("No hay salas. Crea una con /create <sala>.");
            return;
        }
        console.plain("Salas disponibles (" + rooms.length + "):");
        for (RoomInfo room : rooms) {
            console.plain(String.format("  #%-20s %2d miembro(s)   creada por %s%s",
                    room.name, room.memberCount, room.owner, room.voiceActive ? "   [voz activa]" : ""));
        }
    }

    private void joinRoom(String args) throws ChatException {
        SessionPrx session = requireSession();
        if (session == null) {
            return;
        }
        if (args.isEmpty() || args.contains(" ")) {
            usage("/join <sala>");
            return;
        }
        session.joinRoom(args);
        activate(args);
        console.success("Entraste a #" + args + ". Miembros: " + String.join(", ", session.listRoomMembers(args)));
    }

    private void leaveRoom(String args) throws ChatException {
        SessionPrx session = requireSession();
        if (session == null) {
            return;
        }
        if (args.isEmpty() || args.contains(" ")) {
            usage("/leave <sala>");
            return;
        }
        session.leaveRoom(args);
        if (args.equalsIgnoreCase(context.activeRoom())) {
            context.setActiveRoom(null);
            refreshPrompt();
        }
        console.success("Saliste de #" + args + ".");
    }

    private void members(String args) throws ChatException {
        SessionPrx session = requireSession();
        if (session == null) {
            return;
        }
        if (args.isEmpty() || args.contains(" ")) {
            usage("/members <sala>");
            return;
        }
        String[] members = session.listRoomMembers(args);
        console.plain("Miembros de #" + args + " (" + members.length + "): " + String.join(", ", members));
    }

    private void room(String args) throws ChatException {
        SessionPrx session = requireSession();
        if (session == null) {
            return;
        }
        String[] parts = args.split("\\s+", 2);
        if (parts[0].isEmpty()) {
            usage("/room <sala> [texto]");
            return;
        }
        String room = parts[0];
        if (parts.length < 2 || parts[1].isBlank()) {
            // Sin texto: solo cambia la sala activa (debe ser miembro)
            if (!isMember(session, room)) {
                console.error("No perteneces a la sala '" + room + "'. Usa /join " + room);
                return;
            }
            activate(room);
            console.info("Sala activa: #" + room);
            return;
        }
        sendToRoom(session, room, parts[1].strip());
    }

    private void plainText(String text) throws ChatException {
        SessionPrx session = requireSession();
        if (session == null) {
            return;
        }
        String room = context.activeRoom();
        if (room == null) {
            console.error("No hay sala activa. Usa /join <sala>, o /msg <usuario> <texto> para un privado.");
            return;
        }
        sendToRoom(session, room, text);
    }

    private void sendToRoom(SessionPrx session, String room, String text) throws ChatException {
        session.sendRoomMessage(room, text);
        // El servidor no devuelve el mensaje al emisor, así que se muestra localmente.
        console.echo(Console.cyan("[#" + room + "] tú") + ": " + text);
    }

    private boolean isMember(SessionPrx session, String room) throws ChatException {
        for (String member : session.listRoomMembers(room)) {
            if (member.equalsIgnoreCase(context.nickname())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private SessionPrx requireSession() {
        SessionPrx session = context.session();
        if (session == null) {
            console.error("Primero inicia sesión con /login <nickname>.");
        }
        return session;
    }

    private void activate(String room) {
        context.setActiveRoom(room);
        refreshPrompt();
    }

    private void refreshPrompt() {
        String nickname = context.nickname();
        String room = context.activeRoom();
        if (nickname == null) {
            console.setPrompt("> ");
        } else if (room == null) {
            console.setPrompt(nickname + "> ");
        } else {
            console.setPrompt(nickname + " #" + room + "> ");
        }
    }

    private void usage(String usage) {
        console.error("Uso: " + usage);
    }
}

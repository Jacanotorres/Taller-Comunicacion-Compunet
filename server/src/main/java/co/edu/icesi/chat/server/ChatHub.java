package co.edu.icesi.chat.server;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import co.edu.icesi.chat.ClientCallbackPrx;
import co.edu.icesi.chat.InvalidNameException;
import co.edu.icesi.chat.NicknameInUseException;
import co.edu.icesi.chat.SessionPrx;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.Identity;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectAdapter;

/**
 * Núcleo del servidor: estado compartido (usuarios y salas), ciclo de vida de
 * las sesiones y envío de notificaciones a los clientes.
 *
 * No tiene estado propio mutable; delega en UserRegistry, RoomManager, CallManager
 * y ConferenceManager, que son thread-safe.
 */
public class ChatHub {

    private static final String SESSION_CATEGORY = "session";

    private final ObjectAdapter adapter;
    private final UserRegistry users = new UserRegistry();
    private final RoomManager rooms = new RoomManager();
    private final CallManager calls = new CallManager(this);
    private final ConferenceManager conferences;

    public ChatHub(ObjectAdapter adapter, AudioRelay relay) {
        this.adapter = adapter;
        this.conferences = new ConferenceManager(this, relay, calls);
    }

    public UserRegistry users() {
        return users;
    }

    public RoomManager rooms() {
        return rooms;
    }
    
    public CallManager calls() {
        return calls;
    }

    public ConferenceManager conferences() {
        return conferences;
    }


    // ------------------------------------------------------------------
    // Ciclo de vida de la sesión
    // ------------------------------------------------------------------

    public SessionPrx login(String nickname, ClientCallbackPrx callback, Connection connection)
            throws InvalidNameException, NicknameInUseException {
        Names.validate(nickname, "nickname");
        if (callback == null) {
            throw new IllegalArgumentException("login sin proxy de callback");
        }

        // El cliente no tiene endpoints propios: sus callbacks viajan por la misma
        // conexión TCP que él abrió (bidireccional). ice_fixed ata el proxy a ella.
        ClientCallbackPrx fixedCallback = callback.ice_fixed(connection);
        Identity sessionId = new Identity(UUID.randomUUID().toString(), SESSION_CATEGORY);
        ClientSession session = new ClientSession(nickname, fixedCallback, connection, sessionId);

        users.register(session); // atómico: lanza NicknameInUseException si ya existe
        adapter.add(new SessionI(session, this), sessionId);

        // Si la conexión se cae (cliente cerrado a la fuerza, red caída, sin heartbeats),
        // Ice avisa aquí y se limpia la sesión. Si ya estaba cerrada, se invoca de inmediato.
        connection.setCloseCallback(this::connectionClosed);

        Log.info("LOGIN  " + nickname + " (" + users.all().size() + " en línea)");
        broadcast(users.all(), session, cb -> cb.userConnectedAsync(nickname));

        return SessionPrx.uncheckedCast(adapter.createProxy(sessionId));
    }

    /**
     * Cierra la sesión y libera sus recursos. Es idempotente: puede llamarse por
     * /logout y luego otra vez al cerrarse la conexión, y solo actúa la primera vez.
     */
    public void disconnect(ClientSession session, String cause) {
        if (!users.unregister(session)) {
            return;
        }
        try {
            adapter.remove(session.sessionId());
        } catch (LocalException e) {
            // el adaptador ya se destruyó (servidor apagándose) o el servant ya no estaba
        }

        conferences.onDisconnect(session); // antes que las salas: los avisos van a sus miembros
        for (Room room : rooms.removeFromAll(session)) {
            broadcast(room.members(), session, cb -> cb.roomMemberLeftAsync(room.name(), session.nickname()));
        }
        calls.onDisconnect(session); // si estaba en una llamada, avisa al otro
        Log.info("LOGOUT " + session.nickname() + " [" + cause + "] (" + users.all().size() + " en línea)");
        broadcast(users.all(), session, cb -> cb.userDisconnectedAsync(session.nickname()));
    }

    private void connectionClosed(Connection connection) {
        for (ClientSession session : users.byConnection(connection)) {
            disconnect(session, "conexión perdida");
        }
    }

    // ------------------------------------------------------------------
    // Notificaciones a clientes
    // ------------------------------------------------------------------

    /**
     * Invoca un callback de forma asíncrona: el hilo del servidor no espera la
     * respuesta del cliente, así un cliente lento no frena a los demás.
     */
    public void notify(ClientSession target, Function<ClientCallbackPrx, CompletableFuture<Void>> call) {
        try {
            call.apply(target.callback()).whenComplete((result, error) -> {
                if (error != null) {
                    // La limpieza la hace el close callback de la conexión.
                    Log.warn("No se pudo notificar a " + target.nickname() + ": " + error);
                }
            });
        } catch (LocalException e) {
            Log.warn("No se pudo notificar a " + target.nickname() + ": " + e);
        }
    }

    /** Notifica a todos los destinatarios excepto a {@code except} (puede ser null). */
    public void broadcast(Collection<ClientSession> targets, ClientSession except,
            Function<ClientCallbackPrx, CompletableFuture<Void>> call) {
        for (ClientSession target : targets) {
            if (target != except) {
                notify(target, call);
            }
        }
    }
}

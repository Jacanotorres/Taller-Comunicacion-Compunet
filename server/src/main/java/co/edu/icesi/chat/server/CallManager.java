package co.edu.icesi.chat.server;

import java.util.HashMap;
import java.util.Map;

import co.edu.icesi.chat.AudioEndpoint;
import co.edu.icesi.chat.CallException;
import co.edu.icesi.chat.UserNotFoundException;
import com.zeroc.Ice.ConnectionInfo;
import com.zeroc.Ice.IPConnectionInfo;
import com.zeroc.Ice.LocalException;

/**
 * Señalización de las llamadas de voz 1 a 1 (RF-05).
 *
 * Ice solo se usa para coordinar quién llama a quién: el audio viaja por UDP directo entre los
 * dos clientes y el servidor nunca lo ve. Por eso aquí solo hay estado de llamadas, no de audio.
 *
 * Una llamada pasa por dos estados: "timbrando" (la inició el que llama) y "activa" (la aceptó
 * el otro). Cada usuario puede estar como máximo en una llamada.
 *
 * Concurrencia: todo el estado vive en un mapa protegido con un solo lock (synchronized). Las
 * secciones críticas son de pocas líneas y no hacen E/S, así que la contención es mínima. Las
 * notificaciones a los clientes se envían SIEMPRE fuera del lock, y de forma asíncrona.
 */
public class CallManager {

    /** Una llamada entre dos usuarios. Inmutable: se puede compartir entre hilos. */
    public record Call(ClientSession caller, ClientSession callee) {

        public ClientSession other(ClientSession me) {
            return me == caller ? callee : caller;
        }
    }

    /** Estado mutable de una llamada; solo se toca con el lock de CallManager tomado. */
    private static final class Entry {
        final Call call;
        boolean active;

        Entry(Call call) {
            this.call = call;
        }
    }

    private final ChatHub hub;
    private final Map<ClientSession, Entry> byUser = new HashMap<>(); // protegido por "this"

    public CallManager(ChatHub hub) {
        this.hub = hub;
    }

    // ------------------------------------------------------------------
    // Operaciones que llegan desde SessionI
    // ------------------------------------------------------------------

    /** {@code me} llama a {@code to}: queda timbrando y se avisa al destinatario. */
    public void start(ClientSession me, String to, AudioEndpoint myAudio)
            throws UserNotFoundException, CallException {
        ClientSession target = hub.users().find(to);
        if (target == me) {
            throw new CallException("No puedes llamarte a ti mismo.");
        }
        AudioEndpoint audio = observed(me, myAudio);

        register(me, target); // atómico: falla si alguno de los dos ya está en una llamada

        // Si alguno se desconectó justo ahora, ChatHub.disconnect pudo haber corrido antes de
        // que se registrara la llamada. Se revierte para no dejar una llamada fantasma.
        if (!hub.users().isActive(me) || !hub.users().isActive(target)) {
            end(me);
            throw new CallException("'" + target.nickname() + "' se desconectó.");
        }

        hub.notify(target, cb -> cb.incomingCallAsync(me.nickname(), audio));
        Log.info("CALL   " + me.nickname() + " -> " + target.nickname() + " (timbrando)");
    }

    /** {@code me} responde a la llamada que le hizo {@code caller}. */
    public void answer(ClientSession me, String caller, boolean accept, AudioEndpoint myAudio)
            throws CallException {
        if (accept) {
            AudioEndpoint audio = observed(me, myAudio);
            Call call = activate(me, caller);
            hub.notify(call.caller(), cb -> cb.callAcceptedAsync(me.nickname(), audio));
            Log.info("CALL   " + me.nickname() + " aceptó la llamada de " + call.caller().nickname());
        } else {
            Call call = dropRinging(me, caller);
            hub.notify(call.caller(), cb -> cb.callRejectedAsync(me.nickname()));
            Log.info("CALL   " + me.nickname() + " rechazó la llamada de " + call.caller().nickname());
        }
    }

    /** {@code me} cuelga (o cancela la llamada que estaba timbrando). */
    public void hangup(ClientSession me) throws CallException {
        Call call = end(me);
        if (call == null) {
            throw new CallException("No estás en ninguna llamada.");
        }
        hub.notify(call.other(me), cb -> cb.callEndedAsync(me.nickname()));
        Log.info("CALL   " + me.nickname() + " colgó (con " + call.other(me).nickname() + ")");
    }

    /** Lo llama ChatHub.disconnect: si el usuario estaba en una llamada, el otro debe enterarse. */
    public void onDisconnect(ClientSession session) {
        Call call = end(session);
        if (call != null) {
            hub.notify(call.other(session), cb -> cb.callEndedAsync(session.nickname()));
            Log.info("CALL   terminada: " + session.nickname() + " se desconectó");
        }
    }

    // ------------------------------------------------------------------
    // Estado (todas con el lock tomado)
    // ------------------------------------------------------------------

    private synchronized void register(ClientSession caller, ClientSession callee) throws CallException {
        if (byUser.containsKey(caller)) {
            throw new CallException("Ya estás en una llamada. Usa /hangup para terminarla.");
        }
        if (byUser.containsKey(callee)) {
            throw new CallException("'" + callee.nickname() + "' está ocupado en otra llamada.");
        }
        Entry entry = new Entry(new Call(caller, callee));
        byUser.put(caller, entry);
        byUser.put(callee, entry);
    }

    private synchronized Call activate(ClientSession callee, String callerName) throws CallException {
        Entry entry = ringingFor(callee, callerName);
        entry.active = true;
        return entry.call;
    }

    private synchronized Call dropRinging(ClientSession callee, String callerName) throws CallException {
        Entry entry = ringingFor(callee, callerName);
        remove(entry);
        return entry.call;
    }

    /** Termina la llamada del usuario (timbrando o activa); null si no estaba en ninguna. */
    private synchronized Call end(ClientSession user) {
        Entry entry = byUser.get(user);
        if (entry == null) {
            return null;
        }
        remove(entry);
        return entry.call;
    }

    private Entry ringingFor(ClientSession callee, String callerName) throws CallException {
        Entry entry = byUser.get(callee);
        boolean valid = entry != null && !entry.active && entry.call.callee() == callee
                && entry.call.caller().key().equals(Names.keyOf(callerName));
        if (!valid) {
            throw new CallException("No tienes una llamada entrante de '" + callerName + "'.");
        }
        return entry;
    }

    private void remove(Entry entry) {
        byUser.remove(entry.call.caller(), entry);
        byUser.remove(entry.call.callee(), entry);
    }


    // Dirección UDP de cada participante
   

    /**
     * Valida el puerto que declara el cliente y completa el host con la dirección que el
     * servidor ve en su conexión TCP. El cliente suele no conocer bien su propia IP (varias
     * tarjetas, VPN), y no se confía en el host que declare: así nadie puede pedir que el audio
     * de otro usuario se envíe a una máquina ajena.
     */
    private static AudioEndpoint observed(ClientSession me, AudioEndpoint requested) throws CallException {
        if (requested == null || requested.port < 1 || requested.port > 65535) {
            throw new CallException("El puerto UDP de audio no es válido.");
        }
        return new AudioEndpoint(remoteHost(me), requested.port);
    }

    private static String remoteHost(ClientSession me) throws CallException {
        try {
            ConnectionInfo info = me.connection().getInfo();
            while (info != null) {
                if (info instanceof IPConnectionInfo ip) {
                    return ip.remoteAddress;
                }
                info = info.underlying;
            }
        } catch (LocalException e) {
            // la conexión ya se cerró
        }
        throw new CallException("No se pudo determinar tu dirección de red.");
    }
}
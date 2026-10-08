package co.edu.icesi.chat.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import co.edu.icesi.chat.AudioEndpoint;
import co.edu.icesi.chat.CallException;
import co.edu.icesi.chat.ChatException;
import co.edu.icesi.chat.SessionPrx;

/**
 * Estado de la llamada de voz 1 a 1 de este cliente (RF-05).
 *
 * Un cliente solo puede estar en una llamada a la vez. Estados:
 *
 *   IDLE      sin llamada
 *   CALLING   yo llamé y espero respuesta
 *   INCOMING  me están llamando y todavía no respondo
 *   ACTIVE    llamada en curso, con audio fluyendo por UDP
 *
 * Lo usan dos tipos de hilos: el de la CLI (comandos /call, /accept, ...) y los de Ice (avisos del
 * servidor). Todo el estado se protege con synchronized(this). Los avisos de Ice no se procesan en
 * el hilo de Ice: se encolan en un hilo propio ("call-events"), porque abrir el micrófono o cerrar
 * el audio puede tardar y los callbacks no deben bloquearse.
 */
public class CallSession {

    private enum State { IDLE, CALLING, INCOMING, ACTIVE }

    private final Console console;
    private final AudioDevices devices;
    private final ExecutorService events = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "call-events");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ClientContext context;

    // Todo lo siguiente se lee y escribe con el lock de "this" tomado
    private State state = State.IDLE;
    private String peer;
    private AudioEndpoint peerAudio;
    private VoiceChannel channel;

    CallSession(Console console, AudioDevices devices) {
        this.console = console;
        this.devices = devices;
    }

    /**
     * Se llama una vez, cuando ya existe el ClientContext (este objeto se necesita antes, para
     * crear el servant de callbacks). También hace que la llamada se corte si la sesión termina
     * (/logout, conexión caída, salir del cliente).
     */
    public void attach(ClientContext context) {
        this.context = context;
        context.onSessionEnd(this::close);
    }

    // ------------------------------------------------------------------
    // Comandos del usuario (hilo de la CLI)
    // ------------------------------------------------------------------

    public void call(String to) throws ChatException {
        SessionPrx session = session();
        VoiceChannel ch;
        synchronized (this) {
            requireIdle();
            ch = openChannel();
            channel = ch;
            state = State.CALLING;
            peer = to;
        }
        try {
            // El host se deja vacío: lo completa el servidor con la dirección que ve de este cliente
            session.startCall(to, new AudioEndpoint("", ch.localPort()));
        } catch (ChatException | RuntimeException e) {
            resetIfCurrent(ch); // el usuario no existe, está ocupado, se cayó la red...
            throw e;
        }
        console.info("Llamando a " + to + "... (/hangup para cancelar)");
    }

    public void accept() throws ChatException {
        SessionPrx session = session();
        VoiceChannel ch;
        String caller;
        synchronized (this) {
            if (state != State.INCOMING) {
                throw new CallException("No tienes ninguna llamada entrante.");
            }
            ch = openChannel();
            channel = ch;
            caller = peer;
        }
        try {
            session.answerCall(caller, true, new AudioEndpoint("", ch.localPort()));
        } catch (ChatException | RuntimeException e) {
            resetIfCurrent(ch); // por ejemplo, quien llamaba ya colgó
            throw e;
        }

        boolean started;
        synchronized (this) {
            if (channel != ch) {
                return; // quien llamaba colgó mientras aceptábamos; el aviso ya se mostró
            }
            started = startAudio(ch, peerAudio);
            if (started) {
                state = State.ACTIVE;
            }
        }
        if (!started) {
            abortCall(session, ch);
            return;
        }
        console.success("En llamada con " + caller + ". Usa /hangup para colgar (mejor con audífonos, para evitar eco).");
    }

    public void reject() throws ChatException {
        SessionPrx session = session();
        String caller;
        synchronized (this) {
            if (state != State.INCOMING) {
                throw new CallException("No tienes ninguna llamada entrante.");
            }
            caller = peer;
        }
        try {
            session.answerCall(caller, false, new AudioEndpoint("", 0));
        } finally {
            close();
        }
        console.info("Rechazaste la llamada de " + caller + ".");
    }

    public void hangup() throws ChatException {
        SessionPrx session = session();
        String other;
        synchronized (this) {
            if (state == State.IDLE) {
                throw new CallException("No estás en ninguna llamada.");
            }
            other = peer;
        }
        try {
            session.hangup();
        } catch (CallException e) {
            // el servidor ya la había terminado (por ejemplo, el otro colgó a la vez)
        } finally {
            close();
        }
        console.info("Llamada con " + other + " terminada.");
    }

    // ------------------------------------------------------------------
    // Avisos del servidor (llegan por ClientCallbackI, en hilos de Ice)
    // ------------------------------------------------------------------

    public void onIncomingCall(String caller, AudioEndpoint callerAudio) {
        submit(() -> incoming(caller, callerAudio));
    }

    public void onCallAccepted(String callee, AudioEndpoint calleeAudio) {
        submit(() -> accepted(callee, calleeAudio));
    }

    public void onCallRejected(String callee) {
        submit(() -> rejected(callee));
    }

    public void onCallEnded(String peerName) {
        submit(() -> ended(peerName));
    }

    private void incoming(String caller, AudioEndpoint callerAudio) {
        boolean busy;
        synchronized (this) {
            busy = state != State.IDLE;
            if (!busy) {
                state = State.INCOMING;
                peer = caller;
                peerAudio = callerAudio;
            }
        }
        if (busy) {
            // El servidor ya impide llamar a alguien ocupado; esto solo cubre una carrera de
            // milisegundos. Se rechaza sin bloquear (invocación asíncrona).
            SessionPrx session = context.session();
            if (session != null) {
                session.answerCallAsync(caller, false, new AudioEndpoint("", 0)).exceptionally(e -> null);
            }
            return;
        }
        console.event(Console.yellow("☎ Llamada entrante de " + caller + ".") + " Usa /accept o /reject.");
    }

    private void accepted(String callee, AudioEndpoint calleeAudio) {
        VoiceChannel ch;
        boolean started;
        synchronized (this) {
            if (state != State.CALLING || !callee.equalsIgnoreCase(peer)) {
                return; // ya la cancelé
            }
            peer = callee;
            ch = channel;
            started = startAudio(ch, calleeAudio);
            if (started) {
                state = State.ACTIVE;
            }
        }
        if (started) {
            console.event(Console.cyan("✓ " + callee + " aceptó la llamada.") + " Ya pueden hablar; /hangup para colgar.");
        } else {
            abortCall(context.session(), ch);
        }
    }

    private void rejected(String callee) {
        boolean mine;
        synchronized (this) {
            mine = state == State.CALLING && callee.equalsIgnoreCase(peer);
            if (mine) {
                resetLocked();
            }
        }
        if (mine) {
            console.event(Console.yellow(callee + " rechazó la llamada."));
        }
    }

    private void ended(String peerName) {
        State previous;
        synchronized (this) {
            previous = state;
            if (state == State.IDLE || !peerName.equalsIgnoreCase(peer)) {
                return; // aviso de una llamada que ya no es la mía
            }
            resetLocked();
        }
        console.event(Console.yellow(previous == State.INCOMING
                ? peerName + " canceló la llamada."
                : "La llamada con " + peerName + " terminó."));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Termina cualquier llamada y libera el audio. Es idempotente. */
    public void close() {
        synchronized (this) {
            resetLocked();
        }
    }

    /** Con el lock tomado: vuelve a IDLE y libera socket, hilos y dispositivos de audio. */
    private void resetLocked() {
        VoiceChannel old = channel;
        state = State.IDLE;
        peer = null;
        peerAudio = null;
        channel = null;
        if (old != null) {
            old.close();
        }
    }

    private void resetIfCurrent(VoiceChannel ch) {
        synchronized (this) {
            if (channel == ch) {
                resetLocked();
            }
        }
        ch.close();
    }

    /** Con el lock tomado: abre micrófono y altavoz y empieza a enviar/recibir hacia {@code audio}. */
    private boolean startAudio(VoiceChannel ch, AudioEndpoint audio) {
        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(audio.host), audio.port);
            ch.start(address, devices);
            return true;
        } catch (IOException | RuntimeException e) {
            console.event(Console.red("No se pudo iniciar el audio: " + e.getMessage()));
            return false;
        }
    }

    /** El audio no arrancó: se cuelga en el servidor (sin esperar) y se libera lo local. */
    private void abortCall(SessionPrx session, VoiceChannel ch) {
        if (session != null) {
            session.hangupAsync().exceptionally(e -> null);
        }
        resetIfCurrent(ch);
        console.event(Console.red("La llamada se canceló."));
    }

    /** Llamado desde un hilo de audio si el audio se rompe a mitad de la llamada. */
    private void audioFailed(String message) {
        submit(() -> {
            boolean inCall;
            synchronized (this) {
                inCall = state == State.ACTIVE;
                if (inCall) {
                    resetLocked();
                }
            }
            if (inCall) {
                console.event(Console.red(message + " Se terminó la llamada."));
                SessionPrx session = context.session();
                if (session != null) {
                    session.hangupAsync().exceptionally(e -> null);
                }
            }
        });
    }

    private VoiceChannel openChannel() throws CallException {
        try {
            return new VoiceChannel(this::audioFailed);
        } catch (SocketException e) {
            throw new CallException("No se pudo abrir un puerto UDP para el audio: " + e.getMessage());
        }
    }

    private void requireIdle() throws CallException {
        switch (state) {
            case CALLING -> throw new CallException("Ya estás llamando a " + peer + ". Usa /hangup para cancelar.");
            case INCOMING -> throw new CallException("Tienes una llamada entrante de " + peer + ". Usa /accept o /reject.");
            case ACTIVE -> throw new CallException("Ya estás en una llamada con " + peer + ". Usa /hangup para terminarla.");
            case IDLE -> { }
        }
    }

    private SessionPrx session() throws CallException {
        SessionPrx session = context.session();
        if (session == null) {
            throw new CallException("Primero inicia sesión con /login <nickname>.");
        }
        return session;
    }

    private void submit(Runnable task) {
        try {
            events.execute(task);
        } catch (RejectedExecutionException e) {
            // el cliente se está cerrando
        }
    }
}
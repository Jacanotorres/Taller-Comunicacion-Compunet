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
 * Participación de este cliente en la conferencia de voz de una sala (RF-06).
 *
 * Se está como máximo en una conferencia. Al entrar, el servidor devuelve el endpoint UDP de su
 * relay: el audio propio se envía ahí y lo de los demás llega desde ahí, mezclado localmente por
 * {@link AudioMixer}. Ice solo se usa para entrar, salir y recibir avisos.
 *
 * Lo usan el hilo de la CLI (comandos) y los hilos de Ice (avisos). El estado se protege con
 * synchronized(this); los avisos de Ice se procesan en el hilo propio "voice-events", porque
 * cerrar el audio puede tardar y los callbacks no deben bloquearse (igual que CallSession).
 */
public class ConferenceSession {

    private final Console console;
    private final AudioDevices devices;
    private final ExecutorService events = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "voice-events");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ClientContext context;

    // Todo lo siguiente se lee y escribe con el lock de "this" tomado
    private String room;            // null = fuera de toda conferencia
    private VoiceChannel channel;

    ConferenceSession(Console console, AudioDevices devices) {
        this.console = console;
        this.devices = devices;
    }

    /** Se llama una vez, cuando ya existe el ClientContext. La conferencia termina con la sesión. */
    public void attach(ClientContext context) {
        this.context = context;
        context.onSessionEnd(this::close);
    }

    // ------------------------------------------------------------------
    // Comandos del usuario (hilo de la CLI)
    // ------------------------------------------------------------------

    public void join(String roomName) throws ChatException {
        SessionPrx session = session();
        VoiceChannel ch;
        synchronized (this) {
            if (room != null) {
                throw new CallException("Ya estás en la llamada de voz de #" + room + ". Usa /leavevoice primero.");
            }
            ch = openChannel();
            channel = ch;
            room = roomName;
        }

        AudioEndpoint relay;
        try {
            // Como en las llamadas 1 a 1, solo se declara el puerto; el host lo pone el servidor.
            relay = session.joinVoice(roomName, new AudioEndpoint("", ch.localPort()));
        } catch (ChatException | RuntimeException e) {
            resetIfCurrent(ch); // no es miembro, ya está en una llamada 1 a 1, se cayó la red...
            throw e;
        }

        String failure = null;
        synchronized (this) {
            if (channel != ch) {
                return; // la sesión terminó o salió de la sala mientras entraba; ya se avisó
            }
            try {
                ch.start(new InetSocketAddress(InetAddress.getByName(relay.host), relay.port), devices, true);
            } catch (IOException | RuntimeException e) {
                failure = e.getMessage();
                resetLocked();
            }
        }
        if (failure != null) {
            session.leaveVoiceAsync(roomName).exceptionally(e -> null);
            throw new CallException("No se pudo iniciar el audio: " + failure);
        }
        console.success("Estás en la llamada de voz de #" + roomName
                + ". Usa /mute, /unmute y /leavevoice (mejor con audífonos, para evitar eco).");
    }

    public void leave() throws ChatException {
        SessionPrx session = session();
        String current;
        synchronized (this) {
            if (room == null) {
                throw new CallException("No estás en ninguna llamada de voz de sala.");
            }
            current = room;
        }
        try {
            session.leaveVoice(current);
        } catch (ChatException e) {
            // el servidor ya te había sacado (por ejemplo, la sala se cerró)
        } finally {
            close();
        }
        console.info("Saliste de la llamada de voz de #" + current + ".");
    }

    /**
     * Silencia o reactiva el micrófono en la conferencia.
     *
     * @return false si no se está en ninguna conferencia
     */
    public synchronized boolean setMuted(boolean muted) {
        if (room == null) {
            return false;
        }
        channel.setMuted(muted);
        return true;
    }

    // ------------------------------------------------------------------
    // Avisos del servidor (llegan por ClientCallbackI, en hilos de Ice)
    // ------------------------------------------------------------------

    public void onParticipantJoined(String roomName, String nickname) {
        submit(() -> console.event(Console.yellow("[#" + roomName + "] " + nickname + " entró a la llamada de voz")));
    }

    public void onParticipantLeft(String roomName, String nickname) {
        submit(() -> participantLeft(roomName, nickname));
    }

    private void participantLeft(String roomName, String nickname) {
        String me = context.nickname();
        if (me != null && nickname.equalsIgnoreCase(me)) {
            // El servidor me sacó porque dejé la sala (/leave): se libera el audio local.
            boolean mine;
            synchronized (this) {
                mine = room != null && room.equalsIgnoreCase(roomName);
                if (mine) {
                    resetLocked();
                }
            }
            if (mine) {
                console.event(Console.yellow("Saliste de la llamada de voz de #" + roomName + " al dejar la sala."));
            }
            return;
        }
        console.event(Console.yellow("[#" + roomName + "] " + nickname + " salió de la llamada de voz"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Sale de la conferencia localmente y libera el audio. Es idempotente. */
    public synchronized void close() {
        resetLocked();
    }

    /** Con el lock tomado: libera socket, hilos y dispositivos de audio. */
    private void resetLocked() {
        VoiceChannel old = channel;
        room = null;
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

    /** Llamado desde un hilo de audio si el audio se rompe a mitad de la conferencia. */
    private void audioFailed(String message) {
        submit(() -> {
            String left;
            synchronized (this) {
                left = room;
                resetLocked();
            }
            if (left != null) {
                console.event(Console.red(message + " Saliste de la llamada de voz de #" + left + "."));
                SessionPrx session = context.session();
                if (session != null) {
                    session.leaveVoiceAsync(left).exceptionally(e -> null);
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

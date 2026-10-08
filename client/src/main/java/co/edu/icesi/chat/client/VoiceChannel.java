package co.edu.icesi.chat.client;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.function.Consumer;

/**
 * Canal de voz de un cliente: un socket UDP y los dos hilos que lo usan (enviar y recibir).
 *
 * Se usa el MISMO socket para enviar y recibir: el puerto que se anuncia al otro es el desde el
 * que también se le envía, algo necesario para atravesar NAT y que simplifica los puertos.
 *
 * El socket se abre al construir (puerto 0: el sistema asigna uno libre) porque el puerto hay
 * que anunciarlo al servidor ANTES de saber si la llamada será aceptada. El micrófono y el
 * altavoz solo se abren en {@link #start}, cuando de verdad empieza el audio.
 *
 * {@link #close} es idempotente y libera todo: hilos, socket, micrófono y altavoz.
 */
final class VoiceChannel implements AutoCloseable {

    private static final long JOIN_MILLIS = 500;

    private final DatagramSocket socket;
    private final Consumer<String> errors;

    private boolean closed;
    private AudioSender sender;
    private AudioReceiver receiver;
    private Thread senderThread;
    private Thread receiverThread;
    private AudioSource source;
    private AudioSink sink;

    /** @param errors se invoca (desde un hilo de audio) si el audio falla en medio de la llamada */
    VoiceChannel(Consumer<String> errors) throws SocketException {
        this.socket = new DatagramSocket(0);
        this.errors = errors;
    }

    int localPort() {
        return socket.getLocalPort();
    }

    /** Abre micrófono y altavoz y empieza a enviar y recibir audio con {@code peer}. */
    synchronized void start(InetSocketAddress peer, AudioDevices devices) throws IOException {
        if (closed) {
            throw new IOException("El canal de voz ya está cerrado.");
        }
        AudioSource in = null;
        AudioSink out = null;
        try {
            in = devices.openSource();
            out = devices.openSink();
        } catch (IOException e) {
            if (in != null) {
                in.close();
            }
            throw e;
        }
        source = in;
        sink = out;

        sender = new AudioSender(socket, peer, in, errors);
        receiver = new AudioReceiver(socket, out, errors);
        senderThread = daemon(sender, "voice-sender");
        receiverThread = daemon(receiver, "voice-receiver");
        receiverThread.start();
        senderThread.start();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (sender != null) {
            sender.stop();
            receiver.stop();
        }
        // Cerrar el socket desbloquea al receptor; cerrar el micrófono, al emisor.
        socket.close();
        if (source != null) {
            source.close();
        }
        if (sink != null) {
            sink.close();
        }
        join(senderThread);
        join(receiverThread);
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
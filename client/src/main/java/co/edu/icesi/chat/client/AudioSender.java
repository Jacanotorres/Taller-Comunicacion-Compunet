package co.edu.icesi.chat.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.function.Consumer;

/**
 * Hilo que toma audio del micrófono y lo envía por UDP, un datagrama por cada 20 ms.
 *
 * El propio micrófono marca el ritmo: {@link AudioSource#read} bloquea hasta tener un paquete
 * completo, así que no hace falta ningún temporizador.
 *
 * Silenciado (mute): se sigue leyendo el micrófono, para que no se acumule audio viejo que saldría
 * de golpe al reactivarlo, pero no se envía nada. La llamada sigue abierta.
 */
final class AudioSender implements Runnable {

    private final DatagramSocket socket;
    private final SocketAddress destination;
    private final AudioSource source;
    private final Consumer<String> errors;
    private volatile boolean running = true;
    private volatile boolean muted;

    AudioSender(DatagramSocket socket, SocketAddress destination, AudioSource source, Consumer<String> errors) {
        this.socket = socket;
        this.destination = destination;
        this.source = source;
        this.errors = errors;
    }

    @Override
    public void run() {
        byte[] frame = new byte[AudioSpec.FRAME_BYTES];
        while (running) {
            try {
                if (!source.read(frame)) {
                    break;
                }
                if (muted) {
                    continue;
                }
                // send copia los datos al enviar, así que se puede reutilizar el mismo arreglo
                socket.send(new DatagramPacket(frame, frame.length, destination));
            } catch (IOException | RuntimeException e) {
                if (running) {
                    errors.accept("Falló el envío de audio: " + e.getMessage());
                }
                break;
            }
        }
    }

    void setMuted(boolean muted) {
        this.muted = muted;
    }

    void stop() {
        running = false;
    }
}
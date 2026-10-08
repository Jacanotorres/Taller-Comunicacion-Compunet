package co.edu.icesi.chat.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.function.Consumer;

/**
 * Hilo dedicado a recibir audio por UDP y reproducirlo. Vive en su propio hilo (no en uno de Ice)
 * porque {@code receive} bloquea hasta que llega un datagrama.
 *
 * Termina cuando se cierra el socket: eso hace que {@code receive} lance una excepción y el bucle
 * salga. Es la forma estándar de interrumpir una recepción bloqueada.
 */
final class AudioReceiver implements Runnable {

    private final DatagramSocket socket;
    private final AudioSink sink;
    private final Consumer<String> errors;
    private volatile boolean running = true;

    AudioReceiver(DatagramSocket socket, AudioSink sink, Consumer<String> errors) {
        this.socket = socket;
        this.sink = sink;
        this.errors = errors;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[AudioSpec.MAX_PACKET_BYTES];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running) {
            try {
                packet.setLength(buffer.length); // receive lo deja del tamaño del último datagrama
                socket.receive(packet);
            } catch (IOException e) {
                if (running) {
                    errors.accept("Falló la recepción de audio: " + e.getMessage());
                }
                break;
            }

            // Un datagrama que no es PCM de 16 bits (vacío o de longitud impar) no es audio nuestro.
            int length = packet.getLength();
            if (length == 0 || length % 2 != 0) {
                continue;
            }
            try {
                sink.play(buffer, length);
            } catch (RuntimeException e) {
                if (running) {
                    errors.accept("Falló la reproducción de audio: " + e.getMessage());
                }
                break;
            }
        }
    }

    void stop() {
        running = false;
    }
}
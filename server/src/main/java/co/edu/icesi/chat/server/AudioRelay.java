package co.edu.icesi.chat.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.Map;

/**
 * Relay de audio de las conferencias (RF-06, opción "relay centralizado" del taller).
 *
 * Un único socket UDP recibe los paquetes de voz de todos los participantes y reenvía cada uno a
 * los demás participantes de la misma sala, nunca de vuelta a quien lo envió (eso sería eco).
 * Ice no transporta audio: solo coordina quién está en cada conferencia (ConferenceManager).
 *
 * A cada paquete reenviado se le antepone el id del emisor (2 bytes, big-endian). Todo llega al
 * cliente desde la misma dirección (el relay), y sin ese id no podría separar las voces para
 * mezclarlas (ver AudioMixer en el cliente).
 *
 * Concurrencia: solo el hilo "audio-relay" usa el socket. La tabla de rutas es inmutable y se
 * reemplaza entera (campo volatile) cada vez que alguien entra o sale, así el hilo la lee en cada
 * paquete sin tomar ningún lock.
 */
final class AudioRelay implements AutoCloseable {

    static final int HEADER_BYTES = 2;

    /** Ningún paquete de voz es tan grande (un paquete de 20 ms mide 640 bytes). */
    private static final int MAX_PAYLOAD_BYTES = 2048;

    /** A dónde reenviar lo que llega de un participante, y con qué id marcarlo. */
    record Route(int senderId, List<SocketAddress> targets) {
    }

    private final DatagramSocket socket;
    private final Thread thread;
    private volatile Map<SocketAddress, Route> routes = Map.of();

    AudioRelay(int port) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.thread = new Thread(this::run, "audio-relay");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    /** Reemplaza la tabla de rutas. La clave es la dirección UDP desde la que envía cada participante. */
    void updateRoutes(Map<SocketAddress, Route> newRoutes) {
        routes = Map.copyOf(newRoutes);
    }

    private void run() {
        byte[] in = new byte[MAX_PAYLOAD_BYTES];
        byte[] out = new byte[HEADER_BYTES + MAX_PAYLOAD_BYTES];
        DatagramPacket packet = new DatagramPacket(in, in.length);

        while (!socket.isClosed()) {
            try {
                packet.setLength(in.length);
                socket.receive(packet);
            } catch (IOException e) {
                if (socket.isClosed()) {
                    break; // close(): el servidor se está apagando
                }
                Log.warn("Relay de audio: fallo al recibir: " + e.getMessage());
                continue;
            }

            // Solo se reenvía lo que viene de un participante registrado por Ice; el resto se
            // descarta (nadie ajeno puede inyectar audio en una conferencia).
            Route route = routes.get(packet.getSocketAddress());
            int length = packet.getLength();
            if (route == null || length == 0) {
                continue;
            }

            out[0] = (byte) (route.senderId() >>> 8);
            out[1] = (byte) route.senderId();
            System.arraycopy(in, 0, out, HEADER_BYTES, length);
            for (SocketAddress target : route.targets()) {
                try {
                    // send copia los datos, así que se puede reutilizar el mismo arreglo
                    socket.send(new DatagramPacket(out, HEADER_BYTES + length, target));
                } catch (IOException e) {
                    // Un destino inalcanzable no debe cortar el audio de los demás.
                }
            }
        }
    }

    @Override
    public void close() {
        socket.close(); // desbloquea receive y termina el hilo
    }
}

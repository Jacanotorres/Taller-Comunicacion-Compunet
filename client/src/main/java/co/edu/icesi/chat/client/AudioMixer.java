package co.edu.icesi.chat.client;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Mezclador de voces de una conferencia. Se pone delante del altavoz: recibe los paquetes del
 * relay y cada 20 ms reproduce la SUMA de un paquete de cada participante que esté hablando.
 *
 * Por qué hace falta: en una conferencia llegan N-1 flujos de audio por el mismo socket. Si se
 * reprodujeran uno tras otro, el altavoz recibiría (N-1) veces más audio del que puede tocar, se
 * llenaría y descartaría paquetes (voz entrecortada y cada vez más retrasada). Mezclar mantiene el
 * ritmo de un paquete cada 20 ms sin importar cuántos hablen.
 *
 * Cada paquete trae al inicio el id del emisor (2 bytes, ver AudioRelay en el servidor); con él
 * se separa una cola por participante. Las colas son cortas: si alguien acumula retraso se
 * descarta lo más viejo, para que la conversación no se atrase.
 *
 * Hilos: {@link #play} lo llama el hilo receptor de UDP; la mezcla la hace el hilo propio
 * "voice-mixer". Las colas se protegen con el lock de this (secciones de microsegundos).
 */
final class AudioMixer implements AudioSink {

    static final int HEADER_BYTES = 2;

    /** Máximo de paquetes en espera por participante (100 ms). */
    private static final int MAX_QUEUED_FRAMES = 5;

    /** Silencio inicial en el altavoz (60 ms) para absorber la imprecisión del temporizador. */
    private static final int PREFILL_FRAMES = 3;

    private static final long TICK_NANOS = AudioSpec.FRAME_MILLIS * 1_000_000L;
    private static final long JOIN_MILLIS = 500;

    private final AudioSink out;
    private final Map<Integer, ArrayDeque<byte[]>> queues = new HashMap<>(); // protegido por this
    private final Thread thread;
    private volatile boolean running = true;

    AudioMixer(AudioSink out) {
        this.out = out;
        this.thread = new Thread(this::run, "voice-mixer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void play(byte[] data, int length) {
        // Todos los clientes envían paquetes de 20 ms; otro tamaño no es audio de la conferencia.
        if (length != HEADER_BYTES + AudioSpec.FRAME_BYTES) {
            return;
        }
        int sender = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        byte[] frame = Arrays.copyOfRange(data, HEADER_BYTES, length);
        synchronized (this) {
            ArrayDeque<byte[]> queue = queues.computeIfAbsent(sender, id -> new ArrayDeque<>());
            if (queue.size() >= MAX_QUEUED_FRAMES) {
                queue.poll();
            }
            queue.add(frame);
        }
    }

    private void run() {
        byte[] silence = new byte[AudioSpec.FRAME_BYTES];
        byte[] mixed = new byte[AudioSpec.FRAME_BYTES];
        int[] sum = new int[AudioSpec.FRAME_BYTES / 2];
        for (int i = 0; i < PREFILL_FRAMES; i++) {
            out.play(silence, silence.length);
        }

        long next = System.nanoTime();
        while (running) {
            // Se escribe un paquete en cada tick (silencio si nadie habla) para que el colchón
            // del altavoz se mantenga y no haya cortes cuando alguien vuelve a hablar.
            byte[] frame = mixInto(sum) ? toPcm(sum, mixed) : silence;
            try {
                out.play(frame, frame.length);
            } catch (RuntimeException e) {
                break; // el altavoz se cerró
            }

            // Tiempo absoluto: si un tick se atrasa, el siguiente se adelanta y el ritmo promedio
            // se mantiene exacto aunque el temporizador del sistema sea impreciso.
            next += TICK_NANOS;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            } else if (wait < -5 * TICK_NANOS) {
                next = System.nanoTime(); // pausa larga (sistema ocupado): no recuperar de golpe
            }
        }
    }

    /** Suma un paquete de cada participante con audio pendiente; false si no hay ninguno. */
    private synchronized boolean mixInto(int[] sum) {
        Arrays.fill(sum, 0);
        boolean any = false;
        Iterator<ArrayDeque<byte[]>> it = queues.values().iterator();
        while (it.hasNext()) {
            byte[] frame = it.next().poll();
            if (frame == null) {
                it.remove(); // dejó de enviar (silenciado o salió); se crea de nuevo si vuelve
                continue;
            }
            any = true;
            for (int i = 0; i < sum.length; i++) {
                sum[i] += (short) ((frame[2 * i] & 0xFF) | (frame[2 * i + 1] << 8)); // little-endian
            }
        }
        return any;
    }

    /** Convierte la suma a PCM de 16 bits, recortando para que dos voces fuertes no se desborden. */
    private static byte[] toPcm(int[] sum, byte[] pcm) {
        for (int i = 0; i < sum.length; i++) {
            int sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum[i]));
            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
        try {
            thread.join(JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.close();
    }
}

package co.edu.icesi.chat.client;

import javax.sound.sampled.AudioFormat;

/**
 * Formato del audio de las llamadas. Es el mismo para todos los clientes, porque el audio viaja
 * crudo (sin cabecera): quien recibe lo reproduce asumiendo este formato.
 *
 * PCM de 16 bits, mono, 16 kHz, en paquetes de 20 ms (640 bytes). Un paquete tan pequeño cabe en
 * un datagrama sin fragmentarse y mantiene baja la latencia; perder uno solo se oye como un
 * chasquido de 20 ms, no como un corte.
 */
final class AudioSpec {

    static final float SAMPLE_RATE = 16_000f;
    static final int FRAME_MILLIS = 20;

    /** 16 kHz * 2 bytes por muestra * 20 ms = 640 bytes. */
    static final int FRAME_BYTES = (int) (SAMPLE_RATE * 2 * FRAME_MILLIS / 1000);

    /** Se descarta cualquier datagrama más grande: no puede ser audio nuestro. */
    static final int MAX_PACKET_BYTES = FRAME_BYTES * 2;

    private AudioSpec() {
    }

    static AudioFormat format() {
        return new AudioFormat(SAMPLE_RATE, 16, 1, true, false); // con signo, little-endian
    }
}
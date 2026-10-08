package co.edu.icesi.chat.client;

import java.io.IOException;

/** Origen del audio que se envía (normalmente el micrófono). */
interface AudioSource extends AutoCloseable {

    /**
     * Llena {@code frame} con audio; bloquea hasta tenerlo (unos 20 ms en un micrófono real).
     *
     * @return false si la fuente se cerró y no habrá más audio
     */
    boolean read(byte[] frame) throws IOException;

    @Override
    void close();
}
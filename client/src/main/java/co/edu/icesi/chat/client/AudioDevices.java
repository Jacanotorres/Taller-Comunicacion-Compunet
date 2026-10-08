package co.edu.icesi.chat.client;

import java.io.IOException;

/**
 * Fábrica de micrófono y altavoz. Existe para que el resto del cliente no dependa del hardware:
 * la implementación real es {@link SystemAudio}, y las pruebas pueden usar dispositivos falsos.
 */
interface AudioDevices {

    AudioSource openSource() throws IOException;

    AudioSink openSink() throws IOException;
}
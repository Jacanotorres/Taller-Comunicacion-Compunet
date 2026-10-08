package co.edu.icesi.chat.client;

/** Destino del audio que se recibe (normalmente los altavoces). */
interface AudioSink extends AutoCloseable {

    /** Reproduce {@code length} bytes de audio. No debe bloquearse esperando al altavoz. */
    void play(byte[] data, int length);

    @Override
    void close();
}
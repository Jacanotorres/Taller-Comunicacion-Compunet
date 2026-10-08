package co.edu.icesi.chat.client;

import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** Micrófono y altavoz reales del equipo, con javax.sound.sampled. */
final class SystemAudio implements AudioDevices {

    @Override
    public AudioSource openSource() throws IOException {
        return new Microphone();
    }

    @Override
    public AudioSink openSink() throws IOException {
        return new Speaker();
    }

    private static final class Microphone implements AudioSource {
        private final TargetDataLine line;

        Microphone() throws IOException {
            TargetDataLine opened = null;
            try {
                AudioFormat format = AudioSpec.format();
                opened = AudioSystem.getTargetDataLine(format);
                // Buffer de pocos paquetes: menos retraso entre hablar y que se envíe
                opened.open(format, AudioSpec.FRAME_BYTES * 4);
                opened.start();
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                if (opened != null) {
                    opened.close();
                }
                throw new IOException("No se pudo abrir el micrófono (¿permiso o dispositivo ocupado?): "
                        + e.getMessage(), e);
            }
            this.line = opened;
        }

        @Override
        public boolean read(byte[] frame) {
            int total = 0;
            while (total < frame.length) {
                int read = line.read(frame, total, frame.length - total);
                if (read <= 0) {
                    return false; // la línea se cerró
                }
                total += read;
            }
            return true;
        }

        @Override
        public void close() {
            line.stop();
            line.close();
        }
    }

    private static final class Speaker implements AudioSink {
        private final SourceDataLine line;

        Speaker() throws IOException {
            SourceDataLine opened = null;
            try {
                AudioFormat format = AudioSpec.format();
                opened = AudioSystem.getSourceDataLine(format);
                // ~160 ms de colchón para absorber las variaciones de llegada de los paquetes
                opened.open(format, AudioSpec.FRAME_BYTES * 8);
                opened.start();
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                if (opened != null) {
                    opened.close();
                }
                throw new IOException("No se pudo abrir el altavoz: " + e.getMessage(), e);
            }
            this.line = opened;
        }

        @Override
        public void play(byte[] data, int length) {
            // Si el colchón está lleno, se descarta el paquete: esperar acumularía retraso
            // (y bloquearía el hilo que recibe de la red).
            if (line.available() >= length) {
                line.write(data, 0, length);
            }
        }

        @Override
        public void close() {
            line.stop();
            line.close();
        }
    }
}
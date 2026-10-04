package co.edu.icesi.chat.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import co.edu.icesi.chat.ChatException;
import co.edu.icesi.chat.FileChunk;
import co.edu.icesi.chat.FileMeta;
import co.edu.icesi.chat.FileTransferLimits;
import co.edu.icesi.chat.SessionPrx;
import com.zeroc.Ice.LocalException;

/**
 * Envía archivos por Ice en fragmentos de 64 KB (RF-04).
 *
 * El envío corre en un hilo aparte ("file-sender") para que la CLI no se congele mientras
 * se manda un archivo grande. Los archivos se envían de a uno, en orden de llegada.
 *
 * Cada fragmento es una invocación síncrona a la sesión: el siguiente no sale hasta que el
 * servidor confirma el anterior. Así hay un solo fragmento "en vuelo" y no se acumulan en
 * memoria; el costo es una ida y vuelta por fragmento, despreciable en la red del laboratorio.
 */
public class FileSender {

    private final ClientContext context;
    private final Console console;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "file-sender");
        thread.setDaemon(true);
        return thread;
    });

    public FileSender(ClientContext context, Console console) {
        this.context = context;
        this.console = console;
    }

    /**
     * Encola el envío (la CLI ya validó que el archivo existe y es legible).
     *
     * @param target nickname del destinatario o nombre de la sala (sin '#')
     */
    public void send(String target, boolean toRoom, Path file, long size) {
        worker.execute(() -> transfer(target, toRoom, file, size));
    }

    private void transfer(String target, boolean toRoom, Path file, long size) {
        String destination = toRoom ? "#" + target : target;
        String name = file.getFileName().toString();
        int totalChunks = FileTransferLimits.chunksFor(size);
        FileMeta meta = new FileMeta(UUID.randomUUID().toString(), name, size, totalChunks);
        MessageDigest sha256 = FileFormat.newSha256();
        long started = System.nanoTime();
        int nextReport = 25;

        console.event(Console.yellow("↑ Enviando '" + name + "' (" + FileFormat.humanSize(size) + ", "
                + totalChunks + " fragmento(s)) a " + destination));
        try (InputStream in = Files.newInputStream(file)) {
            for (int index = 0; index < totalChunks; index++) {
                byte[] data = in.readNBytes(FileTransferLimits.chunkSizeAt(size, index));
                if (data.length != FileTransferLimits.chunkSizeAt(size, index)) {
                    fail(name, destination, "el archivo cambió de tamaño mientras se enviaba");
                    return;
                }
                sha256.update(data);

                SessionPrx session = context.session();
                if (session == null) {
                    fail(name, destination, "la sesión se cerró");
                    return;
                }
                FileChunk chunk = new FileChunk(meta, index, data);
                if (toRoom) {
                    session.sendFileChunkToRoom(target, chunk);
                } else {
                    session.sendFileChunkToUser(target, chunk);
                }

                int percent = (int) ((index + 1) * 100L / totalChunks);
                if (totalChunks >= 16 && percent >= nextReport && percent < 100) {
                    console.event(Console.yellow("↑ '" + name + "': " + percent + "%"));
                    while (nextReport <= percent) {
                        nextReport += 25;
                    }
                }
            }
        } catch (ChatException e) {
            fail(name, destination, e.reason); // usuario o sala inexistente, no eres miembro, ...
            return;
        } catch (LocalException e) {
            fail(name, destination, "problema de comunicación con el servidor (" + e.ice_id() + ")");
            return;
        } catch (IOException e) {
            fail(name, destination, "no se pudo leer el archivo: " + e.getMessage());
            return;
        }

        double seconds = (System.nanoTime() - started) / 1e9;
        console.event(Console.cyan("✓ Archivo enviado") + ": '" + name + "' a " + destination + " ("
                + FileFormat.humanSize(size) + String.format(Locale.ROOT, " en %.1f s", seconds) + ")"
                + Console.gray("  SHA-256: " + FileFormat.hex(sha256)));
    }

    private void fail(String name, String destination, String reason) {
        console.event(Console.red("No se pudo enviar '" + name + "' a " + destination + ": " + reason));
    }
}
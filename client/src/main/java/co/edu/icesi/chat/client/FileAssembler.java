package co.edu.icesi.chat.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import co.edu.icesi.chat.FileChunk;
import co.edu.icesi.chat.FileMeta;
import co.edu.icesi.chat.FileTransferLimits;

/**
 * Reconstruye en disco los archivos que llegan por fragmentos (RF-04).
 *
 * Concurrencia: {@link #onChunk} lo invocan varios hilos de Ice a la vez, y no debe
 * bloquearlos con E/S de disco. Por eso solo encola el fragmento; TODO el trabajo y TODO
 * el estado (mapa de transferencias en curso) viven en un único hilo ("file-assembler").
 * Un estado que toca un solo hilo no necesita locks, y las escrituras no se pisan.
 *
 * Los fragmentos pueden llegar desordenados o repetidos: cada uno se escribe en su posición
 * (index * CHUNK_SIZE) y un BitSet lleva la cuenta de cuáles se han recibido.
 *
 * El archivo se arma como ".recibiendo-xxxx.part" y solo se renombra a su nombre final cuando
 * está completo y con el tamaño correcto, así nunca queda un archivo truncado con nombre bueno.
 */
public class FileAssembler {

    /** Una transferencia sin datos durante este tiempo se considera abandonada. */
    private static final long STALE_AFTER_MS = 120_000;

    private final Path downloads;
    private final Console console;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "file-assembler");
        thread.setDaemon(true);
        return thread;
    });

    /** Solo lo toca el hilo "file-assembler". */
    private final Map<String, Incoming> incoming = new HashMap<>();

    public FileAssembler(Path downloads, Console console) {
        this.downloads = downloads;
        this.console = console;
    }

    /** Llamado desde ClientCallbackI (hilo de Ice): solo encola, no bloquea. */
    public void onChunk(String sender, String room, FileChunk chunk) {
        try {
            worker.execute(() -> process(sender, room, chunk));
        } catch (RejectedExecutionException e) {
            // el cliente se está cerrando
        }
    }

    /** Al salir: termina lo pendiente y borra los archivos a medias. */
    public void close() {
        try {
            worker.execute(this::discardAll);
        } catch (RejectedExecutionException e) {
            return;
        }
        worker.shutdown();
        try {
            worker.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Todo lo de abajo corre en el hilo "file-assembler"
    // ------------------------------------------------------------------

    private void process(String sender, String room, FileChunk chunk) {
        try {
            discardStale();

            String problem = FileTransferLimits.check(chunk);
            if (problem != null) {
                console.event(Console.red("Fragmento de " + sender + " descartado: " + problem));
                return;
            }

            FileMeta meta = chunk.meta;
            // Se identifica por remitente + sala + id: dos remitentes no pueden pisarse el id.
            String key = sender + "|" + room + "|" + meta.transferId;
            Incoming transfer = incoming.get(key);
            if (transfer == null) {
                Files.createDirectories(downloads); // la primera vez la carpeta aún no existe
                transfer = new Incoming(sender, room, meta, Files.createTempFile(downloads, ".recibiendo-", ".part"));
                incoming.put(key, transfer);
                console.event(Console.yellow("↓ Recibiendo '" + meta.fileName + "' (" + FileFormat.humanSize(meta.totalSize)
                        + ") de " + sender + (room.isEmpty() ? "" : " en #" + room)));
            } else if (!transfer.sameMeta(meta)) {
                cancel(key, transfer, "los metadatos cambiaron a mitad de la transferencia");
                return;
            }

            try {
                transfer.store(chunk);
            } catch (IOException e) {
                cancel(key, transfer, "error de disco: " + e.getMessage());
                return;
            }

            if (transfer.isComplete()) {
                finish(key, transfer);
            } else {
                reportProgress(transfer);
            }
        } catch (IOException | RuntimeException e) {
            // Este hilo no debe morir: un archivo fallido no puede dejar sin servicio a los demás.
            console.event(Console.red("No se pudo procesar un archivo recibido de " + sender + ": " + e));
        }
    }

    private void reportProgress(Incoming transfer) {
        if (transfer.meta.totalChunks < 16) {
            return; // archivos pequeños: basta con el aviso final
        }
        int percent = (int) (transfer.count * 100L / transfer.meta.totalChunks);
        if (percent >= transfer.nextReport) {
            console.event(Console.yellow("↓ '" + transfer.meta.fileName + "': " + percent + "%"));
            while (transfer.nextReport <= percent) {
                transfer.nextReport += 25;
            }
        }
    }

    private void finish(String key, Incoming transfer) {
        incoming.remove(key);
        try {
            transfer.channel.close();
            long size = Files.size(transfer.partial);
            if (size != transfer.meta.totalSize) {
                transfer.discard();
                console.event(Console.red("'" + transfer.meta.fileName + "' llegó con " + size + " bytes y se esperaban "
                        + transfer.meta.totalSize + "; se descartó."));
                return;
            }
            Path target = moveToFinalName(transfer.partial, safeName(transfer.meta.fileName));
            console.event(Console.cyan("✓ Archivo recibido") + ": " + target + " (" + FileFormat.humanSize(size) + ") de "
                    + transfer.sender + (transfer.room.isEmpty() ? "" : " en #" + transfer.room)
                    + Console.gray("  SHA-256: " + FileFormat.sha256(target)));
        } catch (IOException e) {
            transfer.discard();
            console.event(Console.red("No se pudo guardar '" + transfer.meta.fileName + "': " + e.getMessage()));
        }
    }

    private void cancel(String key, Incoming transfer, String reason) {
        incoming.remove(key);
        transfer.discard();
        console.event(Console.red("Se canceló la recepción de '" + transfer.meta.fileName + "' de "
                + transfer.sender + ": " + reason));
    }

    private void discardStale() {
        long now = System.currentTimeMillis();
        incoming.values().removeIf(transfer -> {
            if (now - transfer.lastActivity <= STALE_AFTER_MS) {
                return false;
            }
            transfer.discard();
            console.event(Console.red("Se descartó '" + transfer.meta.fileName + "' de " + transfer.sender
                    + ": no llegaron más datos en 2 minutos."));
            return true;
        });
    }

    private void discardAll() {
        incoming.values().forEach(Incoming::discard);
        incoming.clear();
    }

    /** Mueve el archivo completo a su nombre final sin sobrescribir ninguno existente. */
    private Path moveToFinalName(Path partial, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 0; n < 1000; n++) {
            Path target = downloads.resolve(n == 0 ? name : base + " (" + n + ")" + extension);
            try {
                // Sin REPLACE_EXISTING: si el nombre ya existe lanza FileAlreadyExistsException
                return Files.move(partial, target);
            } catch (FileAlreadyExistsException e) {
                // probar el siguiente sufijo: "foto (1).png", "foto (2).png", ...
            }
        }
        throw new IOException("Demasiados archivos con el nombre '" + name + "'");
    }

    /**
     * El nombre lo envía otro cliente y no es de fiar: se le quita cualquier ruta
     * ("../../x" o "C:\\x") y los caracteres que el sistema de archivos no admite, para que
     * nadie pueda escribir fuera de downloads/.
     */
    static String safeName(String raw) {
        String name = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").strip();
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1).stripTrailing();
        }
        return name.isEmpty() ? "archivo" : name;
    }

    /** Estado de una transferencia en curso. Solo lo usa el hilo "file-assembler". */
    private static final class Incoming {
        final String sender;
        final String room;
        final FileMeta meta;
        final Path partial;
        final FileChannel channel;
        final BitSet received;
        int count;
        int nextReport = 25;
        long lastActivity = System.currentTimeMillis();

        Incoming(String sender, String room, FileMeta meta, Path partial) throws IOException {
            this.sender = sender;
            this.room = room;
            this.meta = meta;
            this.partial = partial;
            this.received = new BitSet(meta.totalChunks);
            this.channel = FileChannel.open(partial, StandardOpenOption.WRITE);
        }

        boolean sameMeta(FileMeta other) {
            return meta.totalSize == other.totalSize && meta.totalChunks == other.totalChunks
                    && meta.fileName.equals(other.fileName);
        }

        /** Escribe el fragmento en su posición; un fragmento repetido se ignora. */
        void store(FileChunk chunk) throws IOException {
            lastActivity = System.currentTimeMillis();
            if (received.get(chunk.index)) {
                return;
            }
            ByteBuffer data = ByteBuffer.wrap(chunk.data);
            long position = (long) chunk.index * FileTransferLimits.CHUNK_SIZE;
            while (data.hasRemaining()) {
                position += channel.write(data, position);
            }
            received.set(chunk.index);
            count++;
        }

        boolean isComplete() {
            return count == meta.totalChunks;
        }

        void discard() {
            try {
                channel.close();
            } catch (IOException e) {
                // ya estaba cerrado
            }
            try {
                Files.deleteIfExists(partial);
            } catch (IOException e) {
                // no se pudo borrar; queda un .part que el usuario puede eliminar a mano
            }
        }
    }
}
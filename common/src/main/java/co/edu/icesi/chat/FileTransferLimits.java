package co.edu.icesi.chat;

/**
 * Reglas de la transferencia de archivos (RF-04) que comparten servidor y cliente.
 *
 * Todos los fragmentos miden exactamente CHUNK_SIZE bytes, salvo el último. Gracias a eso
 * la posición de un fragmento en el archivo es siempre {@code index * CHUNK_SIZE}, y el
 * receptor puede escribirlo en su lugar aunque lleguen desordenados.
 *
 * 64 KB deja un margen enorme respecto a Ice.MessageSizeMax (2048 KB en los .config).
 */
public final class FileTransferLimits {

    /** Tamaño de cada fragmento: 64 KB. */
    public static final int CHUNK_SIZE = 64 * 1024;

    /** Tamaño máximo de un archivo: protege al receptor de transferencias absurdas. */
    public static final long MAX_FILE_SIZE = 256L * 1024 * 1024;

    public static final int MAX_NAME_LENGTH = 255;
    public static final int MAX_ID_LENGTH = 64;

    private FileTransferLimits() {
    }

    /** Cantidad de fragmentos de un archivo; uno vacío viaja en un solo fragmento sin datos. */
    public static int chunksFor(long totalSize) {
        return totalSize == 0 ? 1 : (int) ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }

    /** Bytes que debe traer el fragmento {@code index}. */
    public static int chunkSizeAt(long totalSize, int index) {
        int chunks = chunksFor(totalSize);
        return index < chunks - 1 ? CHUNK_SIZE : (int) (totalSize - (long) (chunks - 1) * CHUNK_SIZE);
    }

    /**
     * Comprueba que un fragmento sea coherente consigo mismo.
     *
     * @return null si es válido; si no, el motivo en español (listo para mostrar al usuario)
     */
    public static String check(FileChunk chunk) {
        if (chunk == null || chunk.meta == null || chunk.data == null) {
            return "El fragmento del archivo está incompleto.";
        }
        FileMeta meta = chunk.meta;
        if (meta.transferId == null || meta.transferId.isBlank() || meta.transferId.length() > MAX_ID_LENGTH) {
            return "Identificador de transferencia inválido.";
        }
        if (meta.fileName == null || meta.fileName.isBlank() || meta.fileName.length() > MAX_NAME_LENGTH) {
            return "Nombre de archivo inválido (vacío o de más de " + MAX_NAME_LENGTH + " caracteres).";
        }
        if (meta.totalSize < 0 || meta.totalSize > MAX_FILE_SIZE) {
            return "Tamaño de archivo no permitido (máximo " + (MAX_FILE_SIZE / (1024 * 1024)) + " MB).";
        }
        int expectedChunks = chunksFor(meta.totalSize);
        if (meta.totalChunks != expectedChunks) {
            return "Cantidad de fragmentos incorrecta: para " + meta.totalSize + " bytes se esperaban "
                    + expectedChunks + " y se declararon " + meta.totalChunks + ".";
        }
        if (chunk.index < 0 || chunk.index >= meta.totalChunks) {
            return "Índice de fragmento fuera de rango (" + chunk.index + " de " + meta.totalChunks + ").";
        }
        int expectedLength = chunkSizeAt(meta.totalSize, chunk.index);
        if (chunk.data.length != expectedLength) {
            return "Tamaño de fragmento inválido: se esperaban " + expectedLength + " bytes y llegaron "
                    + chunk.data.length + ".";
        }
        return null;
    }
}
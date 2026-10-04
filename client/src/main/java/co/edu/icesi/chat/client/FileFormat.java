package co.edu.icesi.chat.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Utilidades de presentación e integridad para la transferencia de archivos. */
final class FileFormat {

    private FileFormat() {
    }

    /** 1536 -> "1.5 KB". */
    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    /** SHA-256 de un archivo en disco; sirve para comparar con el hash que imprime el emisor. */
    static String sha256(Path file) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest);
    }
}
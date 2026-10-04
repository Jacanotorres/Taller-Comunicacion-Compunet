package co.edu.icesi.chat.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import co.edu.icesi.chat.ChatException;
import co.edu.icesi.chat.FileTransferLimits;

/**
 * Comandos de transferencia de archivos (RF-04): /sendfile.
 *
 * Valida lo que se puede validar en el momento (archivo, tamaño, destino) y delega el envío
 * en {@link FileSender}, que corre en su propio hilo.
 */
public class FileCommands {

    private final ClientContext context;
    private final Console console;
    private final FileSender sender;

    public FileCommands(ClientContext context, Console console) {
        this.context = context;
        this.console = console;
        this.sender = new FileSender(context, console);
    }

    public void registerIn(CommandLoop loop) {
        loop.register("/sendfile", "/sendfile <usuario|#sala> <ruta>",
                "Envía un archivo a un usuario o a una sala", this::sendFile);
    }

    private void sendFile(String args) throws ChatException {
        if (!context.isLoggedIn()) {
            console.error("Primero inicia sesión con /login <nickname>.");
            return;
        }
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            usage();
            return;
        }

        String target = parts[0];
        boolean toRoom = target.startsWith("#");
        String name = toRoom ? target.substring(1) : target;
        if (name.isEmpty()) {
            usage();
            return;
        }
        if (!toRoom && name.equalsIgnoreCase(context.nickname())) {
            console.error("No puedes enviarte un archivo a ti mismo.");
            return;
        }

        Path file;
        try {
            file = Path.of(expand(parts[1].strip()));
        } catch (InvalidPathException e) {
            console.error("Ruta inválida: " + parts[1]);
            return;
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            console.error("No se puede leer '" + file + "': no existe, es una carpeta o no tienes permiso.");
            return;
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            console.error("No se pudo leer el tamaño de '" + file + "': " + e.getMessage());
            return;
        }
        if (size > FileTransferLimits.MAX_FILE_SIZE) {
            console.error("El archivo pesa " + FileFormat.humanSize(size) + " y el máximo permitido es "
                    + FileFormat.humanSize(FileTransferLimits.MAX_FILE_SIZE) + ".");
            return;
        }

        // El resultado (éxito o error) se informa desde el hilo de envío, como un evento.
        sender.send(name, toRoom, file, size);
    }

    /** Quita comillas alrededor de la ruta y expande "~" a la carpeta del usuario. */
    private static String expand(String path) {
        String result = path;
        if (result.length() >= 2 && (result.startsWith("\"") && result.endsWith("\"")
                || result.startsWith("'") && result.endsWith("'"))) {
            result = result.substring(1, result.length() - 1);
        }
        if (result.equals("~") || result.startsWith("~/") || result.startsWith("~\\")) {
            result = System.getProperty("user.home") + result.substring(1);
        }
        return result;
    }

    private void usage() {
        console.error("Uso: /sendfile <usuario|#sala> <ruta>");
    }
}
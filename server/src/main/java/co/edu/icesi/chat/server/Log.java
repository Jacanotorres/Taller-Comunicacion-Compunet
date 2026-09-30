package co.edu.icesi.chat.server;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Log mínimo por consola con hora y nombre del hilo (útil para depurar concurrencia). */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    public static void info(String message) {
        print("INFO ", message);
    }

    public static void warn(String message) {
        print("WARN ", message);
    }

    public static void error(String message, Throwable cause) {
        print("ERROR", message + (cause != null ? " -> " + cause : ""));
    }

    private static synchronized void print(String level, String message) {
        System.out.printf("%s [%s] [%s] %s%n",
                LocalTime.now().format(TIME), level, Thread.currentThread().getName(), message);
    }
}

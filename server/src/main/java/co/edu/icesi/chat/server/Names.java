package co.edu.icesi.chat.server;

import java.util.Locale;
import java.util.regex.Pattern;

import co.edu.icesi.chat.InvalidNameException;

/** Reglas comunes para nicknames y nombres de sala. */
public final class Names {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_.-]{2,20}");

    private Names() {
    }

    /** "Juan" y "juan" son el mismo nombre. */
    public static String keyOf(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static void validate(String name, String what) throws InvalidNameException {
        if (name == null || !VALID.matcher(name).matches()) {
            throw new InvalidNameException("El " + what + " debe tener entre 2 y 20 caracteres: "
                    + "letras, números, punto, guion o guion bajo (sin espacios).");
        }
    }
}

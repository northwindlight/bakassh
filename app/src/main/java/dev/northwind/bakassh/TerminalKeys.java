package dev.northwind.bakassh;

import java.util.Locale;

/** Names mapped to the stable VTermKey values from vterm_keycodes.h. */
final class TerminalKeys {
    private static final String[] NAMES = {"ENTER", "TAB", "BKSP", "ESC", "UP", "DOWN", "LEFT", "RIGHT",
            "INS", "DEL", "HOME", "END", "PGUP", "PGDN"};

    static int code(String name) {
        String key = name.toUpperCase(Locale.ROOT);
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(key)) return i + 1;
        return 0;
    }

    static String text(String name) { return name.equalsIgnoreCase("SPACE") ? " " : name; }
}

package com.hsd.universal.carplay;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared by the app and the shell activation entry point. The file never travels with Android backup. */
public final class BluetoothIdentity {
    public static final String BASENAME = "carplay-local-bluetooth";
    private static final Pattern ADDRESS = Pattern.compile("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}");
    private BluetoothIdentity() {}
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return ADDRESS.matcher(s).matches() && !s.equals("00:00:00:00:00:00")
            && !s.equals("02:00:00:00:00:00") && !s.equals("FF:FF:FF:FF:FF:FF") ? s : null;
    }
}

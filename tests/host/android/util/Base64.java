package android.util;

/** Host adapter only; production uses the Android platform codec. */
public final class Base64 {
    public static final int NO_PADDING = 1, NO_WRAP = 2;
    public static String encodeToString(byte[] bytes, int flags) {
        java.util.Base64.Encoder encoder = java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) encoder = encoder.withoutPadding();
        return encoder.encodeToString(bytes);
    }
    public static byte[] decode(String encoded, int flags) { return java.util.Base64.getDecoder().decode(encoded); }
}

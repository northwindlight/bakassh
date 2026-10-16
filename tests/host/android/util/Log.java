package android.util;

public final class Log {
    public static int w(String tag, String message) {
        System.err.println(tag + ": " + message);
        return 0;
    }
}

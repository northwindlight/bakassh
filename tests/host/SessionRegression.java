package dev.northwind.bakassh;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real simultaneous SSH channels and JNI terminals, with no Android UI owner. */
public final class SessionRegression {
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 6_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError(message);
            Thread.sleep(10);
        }
    }
    private static void send(TerminalSession session, String text) {
        session.send((text + "\n").getBytes(StandardCharsets.UTF_8));
    }
    private static String screen(TerminalSession session) {
        synchronized (NativeTerm.class) {
            StringBuilder text = new StringBuilder();
            int[] row = new int[120 * 5];
            for (int r = 0; r < 24; r++) {
                int count = NativeTerm.getRow(session.handle(), r, row);
                for (int c = 0; c < count; c++) {
                    int cp = row[c * 5];
                    if (cp > 0 && cp <= Character.MAX_CODE_POINT) text.appendCodePoint(cp);
                }
            }
            return text.toString();
        }
    }
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        AtomicInteger updates = new AtomicInteger();
        TerminalSession.Listener listener = new TerminalSession.Listener() {
            public void onData(TerminalSession session) { updates.incrementAndGet(); }
            public void onState(TerminalSession session) { }
        };
        TerminalSession a = new TerminalSession("a", "same-host", "loopback", 80, 24, 80, listener);
        TerminalSession b = new TerminalSession("b", "same-host", "loopback", 80, 24, 80, listener);
        try {
            a.connect("127.0.0.1", port, args[1], "publickey", args[2], null, null, "");
            b.connect("127.0.0.1", port, args[1], "publickey", args[2], null, null, "");
            await(() -> a.state() == TerminalSession.State.CONNECTED && b.state() == TerminalSession.State.CONNECTED,
                    "simultaneous connect: " + a.detail() + "/" + b.detail());
            await(() -> screen(a).contains("VERSION_OK") && screen(b).contains("VERSION_OK"), "background query replies");
            send(a, "tag ALPHA");
            send(b, "tag BRAVO");
            await(() -> screen(a).contains("ALPHA") && screen(b).contains("BRAVO"), "independent screen updates");
            if (screen(a).contains("BRAVO") || screen(b).contains("ALPHA")) throw new AssertionError("cross-session output");
            System.out.println("PASS simultaneous sessions keep independent native screens and query replies without a View");

            a.resize(91, 27);
            send(a, "size");
            send(b, "size");
            await(() -> screen(a).contains("SIZE 91 27") && screen(b).contains("SIZE 80 24"), "independent PTY sizes");
            System.out.println("PASS resizing one session preserves the other PTY");

            a.close();
            a.close();
            send(a, "ignored");
            a.resize(90, 26);
            a.mouse(0, 0, 4, true);
            send(b, "tag SURVIVOR");
            await(() -> screen(b).contains("SURVIVOR"), "closing one connection closes another");
            if (a.handle() != 0 || b.handle() == 0) throw new AssertionError("native ownership");
            System.out.println("PASS closing one session releases its handle and preserves the other connection");

            send(b, "exit");
            await(() -> b.state() == TerminalSession.State.ENDED, "EOF not reflected in session state");
            if (!screen(b).contains("SURVIVOR")) throw new AssertionError("EOF discarded terminal screen");
            System.out.println("PASS EOF releases IO while retaining the final screen for review");
        } finally { a.close(); b.close(); }

        for (int i = 0; i < 20; i++) {
            TerminalSession cancelled = new TerminalSession("cancel-" + i, "cancel", "loopback", 80, 24, 80, listener);
            cancelled.connect("127.0.0.1", port, args[1], "publickey", args[2], null, null, "");
            cancelled.close();
            if (cancelled.handle() != 0 || cancelled.state() != TerminalSession.State.CLOSED)
                throw new AssertionError("cancelled connection revived");
        }
        System.out.println("PASS repeated close during connection cannot revive or use freed terminals");
    }
}

package dev.northwind.bakassh;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Connects only to the isolated loopback SSH peer created by transport.sh. */
public final class TransportRegression {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        SshTransport transport = new SshTransport();
        long h = NativeTerm.create(80, 24);
        StringBuffer received = new StringBuffer();
        CountDownLatch version = new CountDownLatch(1), pong = new CountDownLatch(1);
        CountDownLatch size = new CountDownLatch(1), ended = new CountDownLatch(1);
        try {
            transport.connect("127.0.0.1", port, args[1], args[2], 80, 24, new SshTransport.Listener() {
                @Override public void onBytes(byte[] bytes, int len) {
                    NativeTerm.receive(h, bytes, len, reply -> {
                        try { transport.write(reply); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    });
                    received.append(new String(bytes, 0, len, StandardCharsets.UTF_8));
                    String text = received.toString();
                    if (text.contains("VERSION_OK")) version.countDown();
                    if (text.contains("PONG")) pong.countDown();
                    if (text.contains("SIZE 91 27")) size.countDown();
                }
                @Override public void onStatus(String status) { ended.countDown(); }
            });
            if (!version.await(4, TimeUnit.SECONDS)) throw new AssertionError("reply requires a user key: " + received);
            System.out.println("PASS SSH XTVERSION exchange without user input");
            transport.write("ping\n");
            if (!pong.await(4, TimeUnit.SECONDS)) throw new AssertionError("write failed: " + received);
            transport.resize(91, 27);
            transport.write("size\n");
            if (!size.await(4, TimeUnit.SECONDS)) throw new AssertionError("PTY resize failed: " + received);
            System.out.println("PASS SSH input and PTY resizing");
            transport.write("exit\n");
            if (!ended.await(4, TimeUnit.SECONDS) || transport.isConnected()) throw new AssertionError("EOF leaked a session");
            System.out.println("PASS shell EOF closes SSH resources");
        } finally {
            transport.disconnect();
            // Reader callback has completed before onStatus/ended.
            NativeTerm.destroy(h);
        }

        SshTransport cancelled = new SshTransport();
        cancelled.disconnect();
        try {
            cancelled.connect("127.0.0.1", port, args[1], args[2], 80, 24, null);
            throw new AssertionError("cancelled session reconnected");
        } catch (java.io.IOException expected) { System.out.println("PASS cancelled session cannot reconnect"); }
    }
}

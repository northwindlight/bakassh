package dev.northwind.bakassh;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

public final class ForwardingRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejects(String command) {
        try { SshForwarding.parse(command); throw new AssertionError("accepted: " + command); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) throws Exception {
        String sample = "ssh -N -p2222 -o 'ServerAliveInterval 15' -L 0.0.0.0:8022:localhost:22 -R '[::1]:9000:[2001:db8::1]:443' user@[2001:db8::2]";
        SshForwarding.Plan plan = SshForwarding.parse(sample);
        check(plan.port == 2222 && plan.aliveSeconds == 15 && plan.rules.size() == 2, "parse standard options");
        check(plan.host.equals("2001:db8::2") && plan.rules.get(1).target.equals("2001:db8::1"), "IPv6 brackets");
        check(SshForwarding.parse(plan.command()).command().equals(plan.command()), "graph/command round-trip");
        check(SshForwarding.parse(plan.pretty()).command().equals(plan.command()), "multi-line command round-trip");
        check(SshForwarding.parseDraft("ssh -N -p 2200 user@server").rules.isEmpty(), "draft without rules");
        rejects("ssh -N user@server");
        check(SshForwarding.parse("ssh -N -g -L2222:localhost:22 -luser server").rules.get(0).bind.equals("0.0.0.0"), "-g bind semantics");
        check(SshForwarding.parse("ssh -N \\\n -L 2222:localhost:22 user@server").rules.size() == 1, "line continuation");
        rejects("ssh -L 2222:localhost:22 user@server");
        rejects("ssh -N -L 0:localhost:22 user@server");
        rejects("ssh -N -L 65536:localhost:22 user@server");
        rejects("ssh -N -L 2222:localhost:22 user@server rm -rf /");
        rejects("ssh -N -L 2222:localhost:22 user@server; echo bad");
        rejects("ssh -N -o StrictHostKeyChecking=no -L2222:localhost:22 user@server");
        rejects("ssh -N -o ExitOnForwardFailure=no -L2222:localhost:22 user@server");
        rejects("ssh -N -R '[::1:2222:localhost:22' user@server");
        rejects("ssh -N -D 1080 user@server");
        System.out.println("PASS standard SSH parsing, IPv6, mode round-trip, and rejected shell/security overrides");
        int sshPort = Integer.parseInt(args[0]);
        TestHostKeys trusted = new TestHostKeys(sshPort, args[2]);
        try (ServerSocket echo = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            Thread echoThread = new Thread(() -> {
                try {
                    while (!echo.isClosed()) {
                        Socket socket = echo.accept();
                        Thread worker = new Thread(() -> {
                            try (Socket accepted = socket) { accepted.getInputStream().transferTo(accepted.getOutputStream()); }
                            catch (Exception ignored) { }
                        });
                        worker.setDaemon(true); worker.start();
                    }
                } catch (Exception ignored) { }
            });
            echoThread.setDaemon(true); echoThread.start();
            int localPort = freePort(), remotePort = freePort();
            SshTransport transport = new SshTransport();
            try {
                SshForwarding.Plan forwarding = SshForwarding.parse("ssh -N -p " + sshPort
                        + " -L " + localPort + ":127.0.0.1:" + echo.getLocalPort()
                        + " -R " + remotePort + ":127.0.0.1:" + echo.getLocalPort() + " test-user@127.0.0.1");
                transport.connect("127.0.0.1", sshPort, "test-user", "publickey", args[1], null, null,
                        80, 24, new SshTransport.Listener() {
                            public void onBytes(byte[] b, int count) { throw new AssertionError("-N received terminal data"); }
                            public void onStatus(String message) { }
                        }, trusted.verifier(sshPort), forwarding);
                exchange(localPort); exchange(remotePort);
                check(transport.isConnected(), "forward-only SSH disconnected");
                System.out.println("PASS real SSH -L / direct-tcpip and -R / forwarded-tcpip binary transfers, without shell channels");
            } finally { transport.disconnect(); }
            try (ServerSocket unused = new ServerSocket()) {
                unused.setReuseAddress(true);
                unused.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), localPort));
                System.out.println("PASS SSH disconnect releases local forwarding listener");
            }
            int partialPort = freePort();
            try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                SshTransport partial = new SshTransport();
                try {
                    SshForwarding.Plan conflict = SshForwarding.parse("ssh -N -p " + sshPort
                            + " -L " + partialPort + ":127.0.0.1:" + echo.getLocalPort()
                            + " -L " + occupied.getLocalPort() + ":127.0.0.1:" + echo.getLocalPort() + " test-user@127.0.0.1");
                    partial.connect("127.0.0.1", sshPort, "test-user", "publickey", args[1], null, null,
                            80, 24, null, trusted.verifier(sshPort), conflict);
                    throw new AssertionError("port conflict was ignored");
                } catch (com.jcraft.jsch.JSchException expected) {
                    check(!partial.isConnected(), "partial forwarding remained connected");
                } finally { partial.disconnect(); }
                try (ServerSocket reclaimed = new ServerSocket()) {
                    reclaimed.setReuseAddress(true);
                    reclaimed.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), partialPort));
                }
                System.out.println("PASS a failed second rule rolls back the SSH connection and first listener");
            }
        }
    }
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }
    private static void exchange(int port) throws Exception {
        byte[] payload = new byte[98304]; new java.util.Random(23).nextBytes(payload);
        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setSoTimeout(5000); client.getOutputStream().write(payload);
            check(Arrays.equals(payload, client.getInputStream().readNBytes(payload.length)), "forwarded payload corrupted");
        }
    }
}

package dev.northwind.bakassh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real SSH peer audits authentication: a rejected key must prevent sending credentials. */
public final class HostTrustRegression {
    private static int port;
    private static String client;
    private static Path audit;
    private static final SshTransport.Listener SILENT = new SshTransport.Listener() {
        public void onBytes(byte[] data, int length) { }
        public void onStatus(String message) { }
    };
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static long auth(String user) throws Exception {
        if (!Files.exists(audit)) return 0;
        return Files.readAllLines(audit).stream().filter(line -> line.startsWith(user + " ")).count();
    }
    private static void connect(SshTransport transport, String user, String type, HostKeyTrust trust) throws Exception {
        transport.connect("127.0.0.1", port, user, type, client, null, " password ", 80, 24, SILENT, trust);
    }
    private static void rejected(String user, String type, HostKeyTrust trust) throws Exception {
        SshTransport transport = new SshTransport();
        try {
            try { connect(transport, user, type, trust); throw new AssertionError("untrusted host connected"); }
            catch (java.io.IOException expected) { }
            check(!transport.isConnected() && auth(user) == 0, "credentials were sent before host verification: " + user);
        } finally { transport.disconnect(); }
    }
    public static void main(String[] args) throws Exception {
        port = Integer.parseInt(args[0]); client = args[1]; audit = Paths.get(args[4]);
        byte[] original = TestHostKeys.read(args[2] + ".pub"), rotated = TestHostKeys.read(args[3] + ".pub");
        String endpoint = HostKeyTrust.endpoint("127.0.0.1", port);
        String fingerprint = new String(new ProcessBuilder("ssh-keygen", "-lf", args[2] + ".pub", "-E", "sha256")
                .start().getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("\\s+")[1];
        check(fingerprint.equals(HostKeyTrust.fingerprint(original)), "SHA-256 fingerprint disagrees with OpenSSH");
        check(HostKeyTrust.endpoint(" Example.COM ", 22).equals("[example.com]:22") &&
                HostKeyTrust.endpoint("[::1]", 2222).equals("[::1]:2222"), "endpoint canonicalization");
        System.out.println("PASS host fingerprints match OpenSSH and include an explicit endpoint port");

        rejected("trust-missing-policy", "password", null);
        System.out.println("PASS missing host-verification policy fails closed");
        for (String type : new String[]{"password", "publickey"}) {
            TestHostKeys unknown = new TestHostKeys();
            rejected("trust-decline-" + type, type, new HostKeyTrust("127.0.0.1", port, unknown, challenge -> false));
            check(unknown.load(endpoint) == null, "rejection saved a key");
        }
        System.out.println("PASS rejecting an unknown host sends neither password nor public-key authentication");

        TestHostKeys trusted = new TestHostKeys();
        HostKeyRequest request = new HostKeyRequest(new HostKeyTrust.Challenge(endpoint, original));
        CountDownLatch prompting = new CountDownLatch(1);
        AtomicInteger prompts = new AtomicInteger();
        SshTransport first = new SshTransport();
        FutureTask<Void> connection = new FutureTask<>(() -> {
            connect(first, "trust-approved", "password", new HostKeyTrust("127.0.0.1", port, trusted, challenge -> {
                prompts.incrementAndGet(); prompting.countDown(); return request.await();
            })); return null;
        });
        Thread pending = new Thread(connection); pending.start();
        try {
            check(prompting.await(5, TimeUnit.SECONDS), "first connection did not prompt");
            check(auth("trust-approved") == 0 && trusted.load(endpoint) == null, "approval was assumed before user response");
            request.answer(true);
            connection.get(5, TimeUnit.SECONDS);
            check(first.isConnected() && auth("trust-approved") > 0 && MessageDigest.isEqual(original, trusted.load(endpoint)),
                    "approved key/password authentication failed");
        } finally { request.answer(false); first.disconnect(); pending.join(3000); }
        System.out.println("PASS first connection waits for explicit approval before saving a key and authenticating");
        SshTransport again = new SshTransport();
        try {
            connect(again, "trust-reconnect", "publickey", new HostKeyTrust("127.0.0.1", port, trusted, challenge -> {
                prompts.incrementAndGet(); return false;
            }));
            check(again.isConnected() && prompts.get() == 1, "known key prompted or failed reconnect");
        } finally { again.disconnect(); }
        System.out.println("PASS a matching saved key reconnects without a prompt for a different account/authentication method");

        HostKeyTrust.Store broken = new HostKeyTrust.Store() {
            public byte[] load(String endpoint) { return null; }
            public boolean remember(String endpoint, byte[] key) throws Exception { throw new java.io.IOException("synthetic disk failure"); }
        };
        rejected("trust-write-failure", "password", new HostKeyTrust("127.0.0.1", port, broken, challenge -> true));
        System.out.println("PASS trust storage failure blocks authentication despite a positive response");

        TestHostKeys raced = new TestHostKeys();
        rejected("trust-concurrent-change", "password", new HostKeyTrust("127.0.0.1", port, raced, challenge -> {
            raced.remember(endpoint, rotated); return true;
        }));
        check(MessageDigest.isEqual(rotated, raced.load(endpoint)), "approval overwrote concurrent trust");
        System.out.println("PASS concurrent first approvals cannot overwrite a different key");

        TestHostKeys cancelledStore = new TestHostKeys();
        AtomicReference<HostKeyRequest> cancelRequest = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        SshTransport cancelled = new SshTransport();
        FutureTask<Void> cancelling = new FutureTask<>(() -> {
            try {
                connect(cancelled, "trust-cancel", "password", new HostKeyTrust("127.0.0.1", port, cancelledStore, challenge -> {
                    HostKeyRequest value = new HostKeyRequest(challenge); cancelRequest.set(value); waiting.countDown(); return value.await();
                }));
                throw new AssertionError("cancelled connection succeeded");
            } catch (Exception expected) { return null; }
        });
        Thread cancelledThread = new Thread(cancelling); cancelledThread.start();
        check(waiting.await(5, TimeUnit.SECONDS), "cancel fixture did not prompt");
        cancelled.disconnect(); cancelling.get(5, TimeUnit.SECONDS);
        cancelRequest.get().answer(true);
        check(cancelledStore.load(endpoint) == null && auth("trust-cancel") == 0 && !cancelRequest.get().pending(), "late click approved a cancelled request");
        System.out.println("PASS closing a connection interrupts pending confirmation and rejects late approval");

        TerminalSession session = new TerminalSession("trust-close", "test", endpoint, 80, 24, 80, new TerminalSession.Listener() {
            public void onData(TerminalSession session) { }
            public void onState(TerminalSession session) { }
        });
        try {
            session.connect("127.0.0.1", port, "trust-session", "password", null, null, " password ", "", new TestHostKeys());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (session.hostKeyRequest == null && System.nanoTime() < deadline) Thread.sleep(10);
            HostKeyRequest closing = session.hostKeyRequest;
            check(closing != null && closing.pending() && auth("trust-session") == 0, "service-owned request missing");
            session.close(); closing.answer(true);
            check(!closing.pending() && session.state() == TerminalSession.State.CLOSED && auth("trust-session") == 0,
                    "session close retained an approvable request");
        } finally { session.close(); }
        System.out.println("PASS closing a session cancels its service-owned host-key request");

        Files.copy(Paths.get(args[3]), Paths.get(args[2]), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        AtomicInteger changedPrompt = new AtomicInteger();
        rejected("trust-changed", "password", new HostKeyTrust("127.0.0.1", port, trusted, challenge -> {
            changedPrompt.incrementAndGet(); return true;
        }));
        check(changedPrompt.get() == 0 && MessageDigest.isEqual(original, trusted.load(endpoint)), "changed key prompted/overwrote original trust");
        System.out.println("PASS changed server key is blocked before authentication and never offered automatic replacement");
    }
}

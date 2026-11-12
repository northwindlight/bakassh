package dev.northwind.bakassh;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class SecretBoxRegression {
    private interface Operation { void run() throws Exception; }
    private static void rejects(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("corrupt/foreign ciphertext accepted"); }
        catch (GeneralSecurityException expected) { }
    }
    public static void main(String[] args) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        SecretKey key = generator.generateKey();
        SecretBox box = new SecretBox(create -> key);
        byte[] plain = " 密码 with spaces 🔑 ".getBytes(StandardCharsets.UTF_8);
        byte[] context = "host-uuid\0user\0host:22".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = box.seal(plain, context), other = box.seal(plain, context);
        if (!Arrays.equals(plain, box.open(sealed, context)) || Arrays.equals(sealed, other)) throw new AssertionError("AES-GCM roundtrip/random IV");
        System.out.println("PASS AES-GCM preserves password bytes and uses a fresh randomized IV");
        for (int at : new int[]{1, 12, 13, sealed.length - 1}) {
            byte[] corrupt = sealed.clone(); corrupt[at] ^= 1;
            rejects(() -> box.open(corrupt, context));
        }
        rejects(() -> box.open(sealed, new byte[]{1}));
        SecretBox foreign = new SecretBox(create -> generator.generateKey());
        rejects(() -> foreign.open(sealed, context));
        System.out.println("PASS modified IV/ciphertext/tag, foreign host context and foreign key cannot decrypt");
        for (byte[] invalid : new byte[][]{new byte[0], new byte[28], new byte[30]}) rejects(() -> box.open(invalid, context));
        byte[] badVersion = sealed.clone(); badVersion[0] = 2;
        rejects(() -> box.open(badVersion, context));
        System.out.println("PASS truncated and unsupported secret envelopes fail closed");
        AtomicInteger generated = new AtomicInteger();
        SecretBox missing = new SecretBox(create -> {
            if (create) { generated.incrementAndGet(); return key; }
            throw new GeneralSecurityException("missing device key");
        });
        rejects(() -> missing.open(sealed, context));
        if (generated.get() != 0) throw new AssertionError("decryption generated a replacement key");
        System.out.println("PASS decryption never generates a replacement for a missing device key");
    }
}

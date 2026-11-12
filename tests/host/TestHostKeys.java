package dev.northwind.bakassh;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.security.MessageDigest;

final class TestHostKeys implements HostKeyTrust.Store {
    private final Map<String, byte[]> keys = new HashMap<>();
    TestHostKeys() { }
    TestHostKeys(int port, String publicKey) throws Exception {
        remember(HostKeyTrust.endpoint("127.0.0.1", port), read(publicKey));
    }
    static byte[] read(String path) throws Exception {
        return java.util.Base64.getDecoder().decode(new String(Files.readAllBytes(Paths.get(path)),
                java.nio.charset.StandardCharsets.UTF_8).split("\\s+")[1]);
    }
    @Override public synchronized byte[] load(String endpoint) { byte[] key = keys.get(endpoint); return key == null ? null : key.clone(); }
    @Override public synchronized boolean remember(String endpoint, byte[] key) {
        byte[] old = keys.get(endpoint);
        if (old != null) return MessageDigest.isEqual(old, key);
        keys.put(endpoint, key.clone()); return true;
    }
    HostKeyTrust verifier(int port) { return new HostKeyTrust("127.0.0.1", port, this,
            challenge -> { throw new AssertionError("pre-pinned fixture must never prompt"); }); }
}

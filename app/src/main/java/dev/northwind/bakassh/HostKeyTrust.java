package dev.northwind.bakassh;

import android.util.Base64;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/** Per-endpoint key pinning, called by JSch during key exchange, before authentication. */
final class HostKeyTrust {
    interface Store {
        byte[] load(String endpoint) throws Exception;
        /** Atomic compare-and-insert. Must never overwrite a different trusted key. */
        boolean remember(String endpoint, byte[] key) throws Exception;
    }
    interface Confirmation { boolean confirm(Challenge challenge) throws Exception; }

    static final class Challenge {
        final String endpoint, algorithm, fingerprint;
        Challenge(String endpoint, byte[] key) throws Exception {
            this.endpoint = endpoint;
            algorithm = new HostKey(endpoint, key).getType();
            fingerprint = fingerprint(key);
        }
    }
    static final class Changed extends IOException {
        final Challenge received;
        final String previous;
        Changed(Challenge received, byte[] old) throws Exception {
            super("主机指纹已变化，已阻止连接；请核实服务器身份后在主机菜单中处理");
            this.received = received;
            previous = fingerprint(old);
        }
    }

    private final String endpoint;
    private final Store store;
    private final Confirmation confirmation;
    HostKeyTrust(String host, int port, Store store, Confirmation confirmation) {
        endpoint = endpoint(host, port);
        this.store = store;
        this.confirmation = confirmation;
    }

    static String endpoint(String host, int port) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]"))
            normalized = normalized.substring(1, normalized.length() - 1);
        if (normalized.isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException("主机地址或端口无效");
        return "[" + normalized + "]:" + port;
    }
    static String fingerprint(byte[] key) throws Exception {
        return "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key),
                Base64.NO_WRAP | Base64.NO_PADDING);
    }
    private void verify(byte[] key) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new IOException("主机确认已取消");
        Challenge challenge = new Challenge(endpoint, key);
        byte[] old = store.load(endpoint);
        if (old != null) {
            if (!MessageDigest.isEqual(old, key)) throw new Changed(challenge, old);
            return;
        }
        if (confirmation == null || !confirmation.confirm(challenge) || Thread.currentThread().isInterrupted())
            throw new IOException("未信任此主机，已取消连接");
        if (!store.remember(endpoint, key)) {
            old = store.load(endpoint);
            if (old != null) throw new Changed(challenge, old);
            throw new IOException("无法保存主机指纹，已取消连接");
        }
    }

    Repository repository(String host, int port) {
        if (!endpoint.equals(endpoint(host, port))) throw new IllegalArgumentException("主机校验目标与连接地址不一致");
        return new Repository(host, port);
    }
    final class Repository implements HostKeyRepository {
        private final String protocolHost;
        Exception failure;
        Repository(String host, int port) { protocolHost = port == 22 ? host : "[" + host + "]:" + port; }
        @Override public int check(String host, byte[] key) {
            try { verify(key); return OK; }
            catch (Exception error) { failure = error; return NOT_INCLUDED; }
        }
        @Override public HostKey[] getHostKey() { return getHostKey(null, null); }
        @Override public HostKey[] getHostKey(String host, String type) {
            try {
                if (host != null && !protocolHost.equals(host)) return new HostKey[0];
                byte[] key = store.load(endpoint);
                if (key == null) return new HostKey[0];
                HostKey entry = new HostKey(protocolHost, key);
                return type == null || type.equals(entry.getType()) ? new HostKey[]{entry} : new HostKey[0];
            } catch (Exception error) { return new HostKey[0]; }
        }
        @Override public String getKnownHostsRepositoryID() { return "bakaSSH 主机指纹"; }
        @Override public void add(HostKey key, UserInfo info) { throw new UnsupportedOperationException(); }
        @Override public void remove(String host, String type) { throw new UnsupportedOperationException(); }
        @Override public void remove(String host, String type, byte[] key) { throw new UnsupportedOperationException(); }
    }
}

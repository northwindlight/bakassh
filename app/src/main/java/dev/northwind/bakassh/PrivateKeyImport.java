package dev.northwind.bakassh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 无 Android 依赖的文件读取与私钥校验，文件和粘贴走同一条路径。 */
final class PrivateKeyImport {
    static final int MAX_BYTES = 1024 * 1024;

    static byte[] read(InputStream in) throws IOException {
        if (in == null) throw new IOException("文件不可读取，请重新选择。");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > MAX_BYTES) throw new IOException("文件超过 1 MB，请确认选择的是私钥。");
            out.write(buffer, 0, n);
        }
        if (out.size() == 0) throw new IOException("文件为空。");
        return out.toByteArray();
    }

    static byte[] normalize(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("私钥内容为空。");
        text = text.replace("\uFEFF", "").replace("\r\n", "\n").trim();
        if (text.startsWith("ssh-") || text.startsWith("ecdsa-") || text.contains("BEGIN PUBLIC KEY"))
            throw new IllegalArgumentException("这是公钥文件，请选择没有 .pub 后缀的私钥文件。");
        if (!text.startsWith("-----BEGIN ") || !text.contains("PRIVATE KEY-----"))
            throw new IllegalArgumentException("无法识别私钥，请选择 OpenSSH 或 PEM 格式的私钥文件。");
        byte[] data = (text + "\n").getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("私钥内容超过 1 MB。");
        return data;
    }

    static final class Info {
        final String type;
        final boolean encrypted;
        Info(String type, boolean encrypted) { this.type = type; this.encrypted = encrypted; }
    }

    static Info inspectHeader(byte[] data) throws Exception {
        KeyPair pair;
        try { pair = KeyPair.load(new JSch(), data, null); }
        catch (Exception e) { throw new IllegalArgumentException("无法解析私钥，文件可能损坏或格式不受支持。", e); }
        if (pair == null) throw new IllegalArgumentException("文件中没有可用的私钥。");
        try { return new Info(pair.getKeyTypeString(), pair.isEncrypted()); }
        finally { pair.dispose(); }
    }

    static final class Loaded {
        final KeyPair pair;
        final boolean encrypted;
        Loaded(KeyPair pair, boolean encrypted) { this.pair = pair; this.encrypted = encrypted; }
    }

    static KeyPair load(byte[] data, String passphrase) throws Exception {
        return inspect(data, passphrase).pair;
    }

    static Loaded inspect(byte[] data, String passphrase) throws Exception {
        KeyPair pair;
        try { pair = KeyPair.load(new JSch(), data, null); }
        catch (Exception e) { throw new IllegalArgumentException("无法解析私钥，文件可能损坏或格式不受支持。", e); }
        if (pair == null) throw new IllegalArgumentException("文件中没有可用的私钥。");
        boolean encrypted = pair.isEncrypted();
        if (encrypted) {
            if (passphrase == null || passphrase.isEmpty()) {
                pair.dispose();
                throw new IllegalArgumentException("此私钥已加密，请输入私钥口令后重试。");
            }
            if (!pair.decrypt(passphrase)) {
                pair.dispose();
                throw new IllegalArgumentException("口令不正确，或该加密格式不受支持。请检查后重试。");
            }
        }
        if (pair.getPublicKeyBlob() == null) {
            pair.dispose();
            throw new IllegalArgumentException("无法从私钥读取公钥，请检查文件是否完整。");
        }
        return new Loaded(pair, encrypted);
    }
}

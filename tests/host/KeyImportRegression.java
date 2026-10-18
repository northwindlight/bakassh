package dev.northwind.bakassh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Signature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

public final class KeyImportRegression {
    private static int passed;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        passed++;
    }
    private static void rejected(Runnable action, String part) {
        try { action.run(); throw new AssertionError("expected rejection: " + part); }
        catch (IllegalArgumentException e) { check(e.getMessage().contains(part), e.getMessage()); }
    }
    public static void main(String[] args) throws Exception {
        // Force the same lightweight providers Android uses (desktop MR-JAR differs).
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519");
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA");
        Path dir = Path.of(args[0]);
        for (String file : new String[]{"rsa", "rsa-pem", "rsa-pem-locked", "ecdsa", "ed25519", "ed25519-locked", "rsa-locked", "rsa-pkcs8"}) {
            String source = Files.readString(dir.resolve(file));
            byte[] normalized = PrivateKeyImport.normalize(source);
            String password = file.contains("locked") ? " pass phrase " : "";
            PrivateKeyImport.Info info = PrivateKeyImport.inspectHeader(normalized);
            check(info.encrypted == file.contains("locked"), file + " encryption detection");
            PrivateKeyImport.Loaded loaded = PrivateKeyImport.inspect(normalized, password);
            check(loaded.encrypted == file.contains("locked"), file + " stored encryption preserved");
            KeyPair pair = loaded.pair;
            try {
                String pub = Files.readString(dir.resolve(file + ".pub")).split(" ")[1];
                check(Arrays.equals(java.util.Base64.getDecoder().decode(pub), pair.getPublicKeyBlob()), file + " public key");
                byte[] payload = "bakaSSH import verification".getBytes(StandardCharsets.UTF_8);
                byte[] signature = pair.getSignature(payload);
                Signature verifier = pair.getVerifier();
                verifier.update(payload);
                check(verifier.verify(signature), file + " signing roundtrip");
            } finally { pair.dispose(); }
            KeyPair formatted = PrivateKeyImport.load(PrivateKeyImport.normalize("\uFEFF" + source.replace("\n", "\r\n")), password);
            check(formatted.getPublicKeyBlob() != null, file + " BOM/CRLF");
            formatted.dispose();
        }
        byte[] encrypted = PrivateKeyImport.normalize(Files.readString(dir.resolve("ed25519-locked")));
        try { PrivateKeyImport.load(encrypted, "pass phrase"); throw new AssertionError("trimmed password accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("口令"), "preserve password spaces"); }
        try { PrivateKeyImport.load(encrypted, ""); throw new AssertionError("missing password accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("已加密"), "request passphrase"); }
        rejected(() -> PrivateKeyImport.normalize("ssh-ed25519 AAAA public"), "公钥");
        rejected(() -> PrivateKeyImport.normalize("ordinary text"), "无法识别");
        rejected(() -> PrivateKeyImport.normalize(""), "为空");
        try { PrivateKeyImport.read(new ByteArrayInputStream(new byte[PrivateKeyImport.MAX_BYTES + 1])); throw new AssertionError("oversize accepted"); }
        catch (java.io.IOException expected) { passed++; }
        System.out.println("PASS " + passed + " private-key import checks (Android BC signatures, OpenSSH/PEM/PKCS8, encryption, whitespace, file validation)");
    }
}

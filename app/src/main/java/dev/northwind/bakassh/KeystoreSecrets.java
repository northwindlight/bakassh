package dev.northwind.bakassh;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

final class KeystoreSecrets implements PasswordProtection {
    static final String ALIAS = "bakassh.saved-passwords.v1";
    private final String alias;
    private final SecretBox box;
    KeystoreSecrets() { this(ALIAS); }
    KeystoreSecrets(String alias) { this.alias = alias; box = new SecretBox(this::key); }
    private SecretKey key(boolean create) throws GeneralSecurityException {
        synchronized (KeystoreSecrets.class) {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            try { store.load(null); }
            catch (Exception error) { throw new GeneralSecurityException("Keystore unavailable", error); }
            if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
            if (!create) throw new GeneralSecurityException("saved-password key missing");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            return generator.generateKey();
        }
    }
    @Override public String encrypt(String password, byte[] context) throws GeneralSecurityException {
        byte[] plaintext = password.getBytes(StandardCharsets.UTF_8);
        try { return Base64.encodeToString(box.seal(plaintext, context), Base64.NO_WRAP); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }
    @Override public String decrypt(String encoded, byte[] context) throws GeneralSecurityException {
        byte[] plaintext;
        try { plaintext = box.open(Base64.decode(encoded, Base64.NO_WRAP), context); }
        catch (IllegalArgumentException error) { throw new GeneralSecurityException("invalid saved password", error); }
        try { return new String(plaintext, StandardCharsets.UTF_8); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }
}

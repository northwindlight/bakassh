package dev.northwind.bakassh;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned AES-GCM envelope. The key provider must not create replacement keys on decryption. */
final class SecretBox {
    interface Keys { SecretKey get(boolean create) throws GeneralSecurityException; }
    private final Keys keys;
    SecretBox(Keys keys) { this.keys = keys; }
    byte[] seal(byte[] plaintext, byte[] associatedData) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keys.get(true));
        cipher.updateAAD(associatedData);
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new GeneralSecurityException("unsupported GCM IV");
        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] envelope = new byte[13 + ciphertext.length];
        envelope[0] = 1;
        System.arraycopy(iv, 0, envelope, 1, 12);
        System.arraycopy(ciphertext, 0, envelope, 13, ciphertext.length);
        return envelope;
    }
    byte[] open(byte[] envelope, byte[] associatedData) throws GeneralSecurityException {
        if (envelope.length < 29 || envelope[0] != 1) throw new GeneralSecurityException("invalid secret format");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keys.get(false), new GCMParameterSpec(128, envelope, 1, 12));
        cipher.updateAAD(associatedData);
        return cipher.doFinal(envelope, 13, envelope.length - 13);
    }
}

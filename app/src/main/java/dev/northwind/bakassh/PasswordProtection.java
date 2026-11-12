package dev.northwind.bakassh;

import java.security.GeneralSecurityException;

interface PasswordProtection {
    String encrypt(String password, byte[] context) throws GeneralSecurityException;
    String decrypt(String ciphertext, byte[] context) throws GeneralSecurityException;
}

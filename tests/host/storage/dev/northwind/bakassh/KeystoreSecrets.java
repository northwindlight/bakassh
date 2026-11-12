package dev.northwind.bakassh;

/** No software fallback: host migration tests must explicitly inject their key backend. */
final class KeystoreSecrets implements PasswordProtection {
    public String encrypt(String value, byte[] context) { throw new AssertionError("Android Keystore requires a device"); }
    public String decrypt(String value, byte[] context) { throw new AssertionError("Android Keystore requires a device"); }
}

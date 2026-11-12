package dev.northwind.bakassh;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.security.KeyStore;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual Android Keystore and SharedPreferences; never touches production aliases or preferences. */
final class SecurityStorageRegression {
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    static void verify(Context base) throws Exception {
        String prefix = "security-test-" + java.util.UUID.randomUUID();
        String alias = "bakassh.test.passwords." + prefix;
        Context context = new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        KeystoreSecrets secrets = new KeystoreSecrets(alias);
        SharedPreferences preferences = context.getSharedPreferences("sshterm", Context.MODE_PRIVATE);
        String password = " 密码 with spaces 🔑 ";
        try {
            JSONObject legacy = new JSONObject().put("host", "test.invalid").put("user", "test")
                    .put("name", "test").put("port", 2222).put("authType", "password").put("password", password);
            check(preferences.edit().putString("hosts", new JSONArray().put(legacy).toString()).commit(), "seed isolated legacy preferences");
            List<Host> hosts = HostStore.load(context, secrets);
            String encrypted = preferences.getString("hosts", "");
            check(password.equals(hosts.get(0).password) && !encrypted.contains(password), "migration/password bytes");
            check(!new JSONArray(encrypted).getJSONObject(0).has("password"), "plaintext field remains");
            check(keys.containsAlias(alias) && keys.getKey(alias, null).getEncoded() == null, "Keystore key is missing/exportable");
            check(password.equals(HostStore.load(context, new KeystoreSecrets(alias)).get(0).password), "fresh backend cannot decrypt");
            String oldCipher = hosts.get(0).encryptedPassword;
            HostStore.saveChecked(context, hosts, secrets);
            check(!oldCipher.equals(hosts.get(0).encryptedPassword), "IV/ciphertext reused");

            JSONObject tampered = new JSONArray(preferences.getString("hosts", "")).getJSONObject(0);
            tampered.put("user", "another-user").put("password", "must-not-be-used");
            check(preferences.edit().putString("hosts", new JSONArray().put(tampered).toString()).commit(), "seed tamper");
            Host unavailable = HostStore.load(context, secrets).get(0);
            check(unavailable.passwordUnavailable && unavailable.password.isEmpty(), "AAD mismatch/plaintext downgrade accepted");
            check(!preferences.getString("hosts", "").contains("must-not-be-used"), "legacy downgrade field remains");

            // Restore a valid envelope, then simulate transfer/key deletion without clearing metadata.
            HostStore.saveChecked(context, hosts, secrets);
            String valid = preferences.getString("hosts", "");
            keys.deleteEntry(alias);
            hosts = HostStore.load(context, secrets);
            check(hosts.get(0).passwordUnavailable && hosts.get(0).password.isEmpty() && !keys.containsAlias(alias), "missing key silently replaced");
            HostStore.saveChecked(context, hosts, secrets);
            check(valid.equals(preferences.getString("hosts", "")) && !keys.containsAlias(alias), "unavailable ciphertext discarded");
            hosts.get(0).password = " replacement ";
            HostStore.saveChecked(context, hosts, secrets);
            check(" replacement ".equals(HostStore.load(context, secrets).get(0).password), "explicit password replacement after key loss");
            hosts.get(0).password = "";
            HostStore.saveChecked(context, hosts, secrets);
            check(!new JSONArray(preferences.getString("hosts", "")).getJSONObject(0).has("passwordEncrypted"), "cleared secret remains");

            String source = new JSONArray().put(legacy).toString();
            check(preferences.edit().putString("hosts", source).commit(), "seed migration failure");
            PasswordProtection failing = new PasswordProtection() {
                public String encrypt(String value, byte[] associated) throws java.security.GeneralSecurityException { throw new java.security.GeneralSecurityException("test"); }
                public String decrypt(String value, byte[] associated) throws java.security.GeneralSecurityException { throw new java.security.GeneralSecurityException("test"); }
            };
            try { HostStore.load(context, failing); throw new AssertionError("failed migration succeeded"); }
            catch (IllegalStateException expected) { check(source.equals(preferences.getString("hosts", "")), "failed migration lost source"); }

            KnownHostsStore trusted = new KnownHostsStore(context), reopened = new KnownHostsStore(context);
            String endpoint = HostKeyTrust.endpoint("Test.INVALID", 2222);
            check(trusted.remember(endpoint, new byte[]{1, 2, 3}), "save host key");
            check(java.util.Arrays.equals(reopened.load(endpoint), new byte[]{1, 2, 3}), "host key not persisted");
            check(!reopened.remember(endpoint, new byte[]{4, 5}), "host key automatically replaced");
            String encoded = trusted.encoded(endpoint);
            check(!trusted.forget(endpoint, "stale-record") && trusted.forget(endpoint, encoded), "forget compare failed");
            check(trusted.remember(endpoint, new byte[]{4, 5}) && reopened.load(endpoint)[0] == 4, "explicit key replacement failed");
            context.getSharedPreferences("host_keys", Context.MODE_PRIVATE).edit().putString(endpoint, "!!invalid!!").commit();
            try { trusted.load(endpoint); throw new AssertionError("corrupt trust became unknown"); } catch (java.io.IOException expected) { }
            check(trusted.forget(endpoint, "!!invalid!!"), "cannot remove corrupt trust explicitly");
        } finally {
            keys.deleteEntry(alias);
            base.deleteSharedPreferences(prefix + "sshterm");
            base.deleteSharedPreferences(prefix + "host_keys");
        }
    }
}

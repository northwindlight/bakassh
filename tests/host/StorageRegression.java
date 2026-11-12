package dev.northwind.bakassh;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the actual HostStore/KnownHostsStore with atomic in-memory persistence and JCE keys. */
public final class StorageRegression {
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static final class Preferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        final Map<String, Object> disk = new HashMap<>();
        boolean writable = true, changeMemoryOnFailure;
        public synchronized String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public synchronized int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
        public synchronized float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> changes = new HashMap<>();
                public Editor putString(String k, String v) { changes.put(k, v); return this; }
                public Editor putInt(String k, int v) { changes.put(k, v); return this; }
                public Editor putFloat(String k, float v) { changes.put(k, v); return this; }
                public Editor remove(String k) { changes.put(k, null); return this; }
                public boolean commit() {
                    synchronized (Preferences.this) {
                        if (!writable && !changeMemoryOnFailure) return false;
                        changes.forEach((k, v) -> { if (v == null) values.remove(k); else values.put(k, v); });
                        if (writable) { disk.clear(); disk.putAll(values); }
                        return writable;
                    }
                }
                public void apply() { if (!commit()) throw new AssertionError("write failure"); }
            };
        }
    }
    private static final class TestContext extends Context {
        final Map<String, Preferences> files = new HashMap<>();
        public synchronized Preferences getSharedPreferences(String name, int mode) { return files.computeIfAbsent(name, ignored -> new Preferences()); }
    }
    private static final class Protection implements PasswordProtection {
        SecretKey key;
        boolean writable = true;
        int generated;
        final SecretBox box = new SecretBox(create -> {
            if (key == null) {
                if (!create || !writable) throw new GeneralSecurityException("synthetic unavailable key");
                KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); key = generator.generateKey(); generated++;
            }
            return key;
        });
        public String encrypt(String plain, byte[] context) throws GeneralSecurityException {
            if (!writable) throw new GeneralSecurityException("synthetic encryption failure");
            return Base64.getEncoder().encodeToString(box.seal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8), context));
        }
        public String decrypt(String sealed, byte[] context) throws GeneralSecurityException {
            try { return new String(box.open(Base64.getDecoder().decode(sealed), context), java.nio.charset.StandardCharsets.UTF_8); }
            catch (IllegalArgumentException error) { throw new GeneralSecurityException(error); }
        }
    }
    private static String raw(TestContext context) { return context.getSharedPreferences("sshterm", 0).getString("hosts", null); }
    private static void raw(TestContext context, String value) { context.getSharedPreferences("sshterm", 0).edit().putString("hosts", value).commit(); }
    public static void main(String[] args) throws Exception {
        TestContext context = new TestContext(); Protection protection = new Protection();
        String secret = " 密码 with spaces 🔑 ";
        JSONObject legacyHost = new JSONObject().put("name", "legacy").put("host", "server.example").put("user", "alice")
                .put("port", 22).put("authType", "password").put("password", secret);
        String legacy = new JSONArray().put(legacyHost).toString(); raw(context, legacy);
        List<Host> hosts = HostStore.load(context, protection);
        check(secret.equals(hosts.get(0).password) && !raw(context).contains(secret) && !new JSONArray(raw(context)).getJSONObject(0).has("password"), "legacy migration retained plaintext");
        check(secret.equals(HostStore.load(context, protection).get(0).password), "migrated password cannot reload");
        check(!hosts.get(0).toJson().has("password") && !hosts.get(0).toJson().toString().contains(secret), "metadata serializer emitted plaintext");
        System.out.println("PASS legacy plaintext migrates to authenticated ciphertext without changing password bytes or host metadata");

        String firstCipher = hosts.get(0).encryptedPassword;
        hosts.get(0).name = "renamed";
        HostStore.saveChecked(context, hosts, protection);
        check(!firstCipher.equals(hosts.get(0).encryptedPassword) && secret.equals(HostStore.load(context, protection).get(0).password), "edit reused IV/lost secret");
        System.out.println("PASS repeated saves refresh ciphertext while display-name edits preserve decryption");

        String validRaw = raw(context);
        protection.key = null; int generated = protection.generated;
        hosts = HostStore.load(context, protection);
        check(hosts.get(0).passwordUnavailable && hosts.get(0).password.isEmpty() && protection.generated == generated && raw(context).equals(validRaw), "missing key erased/replaced credentials");
        HostStore.saveChecked(context, hosts, protection);
        check(raw(context).equals(validRaw) && protection.generated == generated, "unrelated save discarded unavailable ciphertext");
        hosts.get(0).password = " replacement ";
        HostStore.saveChecked(context, hosts, protection);
        check(!hosts.get(0).passwordUnavailable && " replacement ".equals(HostStore.load(context, protection).get(0).password), "password cannot be replaced after device-key loss");
        System.out.println("PASS lost device key preserves host/ciphertext, generates no key on read and permits explicit password replacement");

        hosts = HostStore.load(context, protection);
        hosts.get(0).password = "";
        HostStore.saveChecked(context, hosts, protection);
        check(!new JSONArray(raw(context)).getJSONObject(0).has("passwordEncrypted") && HostStore.load(context, protection).get(0).password.isEmpty(), "clearing a password retained ciphertext");
        System.out.println("PASS explicitly clearing a saved password removes its ciphertext");

        hosts.get(0).password = secret; HostStore.saveChecked(context, hosts, protection);
        JSONObject corrupt = new JSONArray(raw(context)).getJSONObject(0);
        String cipher = corrupt.getString("passwordEncrypted");
        corrupt.put("user", "mallory").put("password", "plaintext-fallback");
        raw(context, new JSONArray().put(corrupt).toString());
        hosts = HostStore.load(context, protection);
        check(hosts.get(0).passwordUnavailable && hosts.get(0).password.isEmpty() && hosts.get(0).encryptedPassword.equals(cipher) && !raw(context).contains("plaintext-fallback"), "tamper used plaintext downgrade/discarded secret");
        corrupt = new JSONArray(raw(context)).getJSONObject(0).put("passwordEncrypted", "!!not base64!!"); raw(context, new JSONArray().put(corrupt).toString());
        check(HostStore.load(context, protection).get(0).passwordUnavailable, "malformed ciphertext accepted");
        System.out.println("PASS endpoint/user tampering and malformed ciphertext require reentry and never fall back to legacy plaintext");

        for (boolean diskFailure : new boolean[]{false, true}) {
            raw(context, legacy);
            protection.writable = diskFailure;
            context.getSharedPreferences("sshterm", 0).writable = !diskFailure;
            try { HostStore.load(context, protection); throw new AssertionError("failed migration reported success"); }
            catch (IllegalStateException expected) { check(raw(context).equals(legacy), "failed migration lost legacy data"); }
            context.getSharedPreferences("sshterm", 0).writable = true; protection.writable = true;
        }
        raw(context, "{malformed-json");
        try { HostStore.load(context, protection); throw new AssertionError("malformed configuration appeared empty"); }
        catch (IllegalStateException expected) { check(raw(context).equals("{malformed-json"), "parse failure overwrote source"); }
        System.out.println("PASS encryption/commit/parse failures preserve the source configuration and report failure");

        Preferences hostPreferences = context.getSharedPreferences("sshterm", 0);
        raw(context, legacy); hostPreferences.changeMemoryOnFailure = true; hostPreferences.writable = false;
        try { HostStore.load(context, protection); throw new AssertionError("failed disk commit accepted"); }
        catch (IllegalStateException expected) { check(legacy.equals(hostPreferences.disk.get("hosts")), "failed commit changed disk source"); }
        hostPreferences.writable = true;
        HostStore.load(context, protection);
        check(raw(context).equals(hostPreferences.disk.get("hosts")) && !legacy.equals(hostPreferences.disk.get("hosts")),
                "retry skipped a migration whose previous write only changed memory");
        System.out.println("PASS migration retries a failed disk commit even when SharedPreferences already changed memory");

        KnownHostsStore a = new KnownHostsStore(context), b = new KnownHostsStore(context);
        String endpoint = HostKeyTrust.endpoint("example.com", 22);
        CountDownLatch gate = new CountDownLatch(1); AtomicInteger won = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = workers.submit(() -> { try { gate.await(); if (a.remember(endpoint, new byte[]{1})) won.incrementAndGet(); } catch (Exception e) { throw new RuntimeException(e); } });
            Future<?> f2 = workers.submit(() -> { try { gate.await(); if (b.remember(endpoint, new byte[]{2})) won.incrementAndGet(); } catch (Exception e) { throw new RuntimeException(e); } });
            gate.countDown(); f1.get(3, TimeUnit.SECONDS); f2.get(3, TimeUnit.SECONDS);
            check(won.get() == 1 && Arrays.equals(a.load(endpoint), b.load(endpoint)), "concurrent trust overwrite");
        } finally { workers.shutdownNow(); }
        String encoded = a.encoded(endpoint);
        check(!a.forget(endpoint, "foreign-record") && a.forget(endpoint, encoded) && a.load(endpoint) == null, "forget did not compare the displayed record");
        check(a.remember(endpoint, new byte[]{3}) && b.load(endpoint)[0] == 3, "explicit removal did not allow a new key");
        context.getSharedPreferences("host_keys", 0).edit().putString(endpoint, "!!corrupt!!").commit();
        try { a.load(endpoint); throw new AssertionError("corrupt trust treated as unknown"); } catch (java.io.IOException expected) { }
        check(a.forget(endpoint, "!!corrupt!!"), "corrupt trust cannot be explicitly removed");
        System.out.println("PASS host-key persistence serializes conflicting approvals, supports explicit removal/update and rejects corrupt records");
        Preferences trustPreferences = context.getSharedPreferences("host_keys", 0);
        trustPreferences.changeMemoryOnFailure = true; trustPreferences.writable = false;
        try { a.remember(endpoint, new byte[]{7}); throw new AssertionError("failed trust commit accepted"); }
        catch (java.io.IOException expected) { check(a.load(endpoint) == null, "failed trust commit authorized its in-memory key"); }
        trustPreferences.writable = true; a.remember(endpoint, new byte[]{7}); trustPreferences.writable = false;
        String displayed = a.encoded(endpoint);
        try { a.forget(endpoint, displayed); throw new AssertionError("failed forget commit accepted"); }
        catch (java.io.IOException expected) { check(a.load(endpoint)[0] == 7, "failed forget downgraded trust to unknown"); }
        System.out.println("PASS failed trust save/removal restores secure in-memory state as well as preserving disk state");
    }
}

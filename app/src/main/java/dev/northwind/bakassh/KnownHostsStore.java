package dev.northwind.bakassh;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.IOException;
import java.security.MessageDigest;

/** Public host keys only. Synchronous commits finish before SSH authentication is permitted. */
final class KnownHostsStore implements HostKeyTrust.Store {
    private final SharedPreferences preferences;
    KnownHostsStore(Context context) { preferences = context.getSharedPreferences("host_keys", Context.MODE_PRIVATE); }
    @Override public byte[] load(String endpoint) throws IOException {
        synchronized (KnownHostsStore.class) {
            String encoded = preferences.getString(endpoint, null);
            if (encoded == null) return null;
            // android.util.Base64 会跳过非字母表字符而不报错；先严格校验，损坏记录不能被当成另一把公钥。
            if (!encoded.matches("[A-Za-z0-9+/]+={0,2}") || encoded.length() % 4 != 0)
                throw new IOException("保存的主机指纹损坏，请在主机菜单中核实处理");
            try { return Base64.decode(encoded, Base64.NO_WRAP); }
            catch (IllegalArgumentException error) { throw new IOException("保存的主机指纹损坏，请在主机菜单中核实处理"); }
        }
    }
    @Override public boolean remember(String endpoint, byte[] key) throws IOException {
        synchronized (KnownHostsStore.class) {
            byte[] old = load(endpoint);
            if (old != null) return MessageDigest.isEqual(old, key);
            if (!preferences.edit().putString(endpoint, Base64.encodeToString(key, Base64.NO_WRAP)).commit()) {
                // SharedPreferences changes memory even when its disk commit fails.
                preferences.edit().remove(endpoint).commit();
                throw new IOException("无法保存主机指纹");
            }
            return true;
        }
    }
    String encoded(String endpoint) {
        synchronized (KnownHostsStore.class) { return preferences.getString(endpoint, null); }
    }
    boolean forget(String endpoint, String expected) throws IOException {
        synchronized (KnownHostsStore.class) {
            String old = encoded(endpoint);
            if (old == null || !old.equals(expected)) return false;
            if (!preferences.edit().remove(endpoint).commit()) {
                preferences.edit().putString(endpoint, expected).commit();
                throw new IOException("无法移除主机指纹");
            }
            return true;
        }
    }
}

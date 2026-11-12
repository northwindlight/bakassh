package dev.northwind.bakassh;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 主机列表的持久化（SharedPreferences 里存一段 JSON，够用且零依赖）。 */
public class HostStore {

    private static final String PREF = "sshterm";
    private static final String KEY_HOSTS = "hosts";
    private static final String KEY_COLS = "target_cols";
    private static final String KEY_ZOOM_COLS = "zoom_columns";
    private static final String KEY_INSET = "edge_inset";
    private static final java.util.Map<SharedPreferences, Boolean> pendingCommits = new java.util.WeakHashMap<>();

    public static synchronized List<Host> load(Context ctx) {
        return load(ctx, new KeystoreSecrets());
    }

    static synchronized List<Host> load(Context ctx, PasswordProtection secrets) {
        List<Host> list = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_HOSTS, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                boolean migrate = pendingCommits.containsKey(sp);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Host host = Host.fromJson(o);
                    if (o.has("passwordEncrypted")) {
                        try { host.password = secrets.decrypt(host.encryptedPassword, host.passwordContext()); }
                        catch (java.security.GeneralSecurityException | RuntimeException error) { host.passwordUnavailable = true; }
                    } else host.password = o.optString("password", ""); // Legacy input only.
                    migrate |= o.has("password") || !o.has("id") || o.optString("id").isEmpty();
                    list.add(host);
                }
                if (migrate) saveChecked(ctx, list, secrets);
            } catch (Exception error) {
                throw new IllegalStateException("无法读取或加密迁移主机配置，原数据已保留，请重试", error);
            }
        }
        // 首次运行不播种任何主机：不把开发者的私有主机名/会话名塞进别人的设备
        return list;
    }

    public static synchronized boolean save(Context ctx, List<Host> hosts) {
        try {
            saveChecked(ctx, hosts, new KeystoreSecrets());
            return true;
        } catch (Exception error) {
            android.widget.Toast.makeText(ctx, "保存失败：无法加密或写入主机配置，请重试", android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
    }

    static synchronized void saveChecked(Context ctx, List<Host> hosts, PasswordProtection secrets) throws Exception {
        JSONArray arr = new JSONArray();
        List<String> encrypted = new ArrayList<>();
        for (Host h : hosts) {
            String cipher = h.passwordUnavailable && h.password.isEmpty() ? h.encryptedPassword :
                    h.password.isEmpty() ? "" : secrets.encrypt(h.password, h.passwordContext());
            JSONObject object = h.toJson();
            object.remove("passwordEncrypted");
            if (!cipher.isEmpty()) object.put("passwordEncrypted", cipher);
            encrypted.add(cipher);
            arr.put(object);
        }
        SharedPreferences preferences = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!preferences.edit().putString(KEY_HOSTS, arr.toString()).commit()) {
            pendingCommits.put(preferences, true);
            throw new java.io.IOException("host configuration commit failed");
        }
        pendingCommits.remove(preferences);
        for (int i = 0; i < hosts.size(); i++) {
            Host host = hosts.get(i);
            host.encryptedPassword = encrypted.get(i);
            if (!host.password.isEmpty() || host.encryptedPassword.isEmpty()) host.passwordUnavailable = false;
        }
    }

    public static int getTargetCols(Context ctx, int def) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_COLS, def);
    }

    public static void setTargetCols(Context ctx, int cols) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putInt(KEY_COLS, cols).remove(KEY_ZOOM_COLS).apply();
    }

    public static float getZoomColumns(Context ctx, int def) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return prefs.getFloat(KEY_ZOOM_COLS, prefs.getInt(KEY_COLS, def));
    }

    public static void setZoomColumns(Context ctx, float columns) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_ZOOM_COLS, columns).putInt(KEY_COLS, Math.round(columns)).apply();
    }

    /** 终端四周留边（dp）：贴边对曲面/圆角屏不友好，做成可调。 */
    public static int getEdgeInset(Context ctx, int def) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_INSET, def);
    }

    public static void setEdgeInset(Context ctx, int dp) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putInt(KEY_INSET, dp).apply();
    }

    public static List<Host> getHostsUsingKey(Context ctx, String keyId) {
        List<Host> matched = new ArrayList<>();
        for (Host h : load(ctx)) {
            if ("publickey".equalsIgnoreCase(h.authType) && keyId != null && keyId.equals(h.keyId)) {
                matched.add(h);
            }
        }
        return matched;
    }
}

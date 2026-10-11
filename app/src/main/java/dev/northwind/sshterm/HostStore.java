package dev.northwind.sshterm;

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

    public static List<Host> load(Context ctx) {
        List<Host> list = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_HOSTS, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) list.add(Host.fromJson(o));
                }
            } catch (Throwable t) {
                MainActivity.out((android.app.Activity) ctx, "HostStore.load failed: " + t);
            }
        }
        if (list.isEmpty()) {
            // 首次运行：放一台默认主机，省得对着一片空白
            list.add(new Host("rpi5", "rpi5.northwind.site", 22, "northwind", "tmux attach -t zg"));
            save(ctx, list);
        }
        return list;
    }

    public static void save(Context ctx, List<Host> hosts) {
        try {
            JSONArray arr = new JSONArray();
            for (Host h : hosts) arr.put(h.toJson());
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY_HOSTS, arr.toString()).apply();
        } catch (Throwable t) {
            MainActivity.out((android.app.Activity) ctx, "HostStore.save failed: " + t);
        }
    }

    public static int getTargetCols(Context ctx, int def) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_COLS, def);
    }

    public static void setTargetCols(Context ctx, int cols) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putInt(KEY_COLS, cols).apply();
    }
}

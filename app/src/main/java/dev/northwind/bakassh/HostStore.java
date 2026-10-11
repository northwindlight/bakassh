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
    private static final String KEY_INSET = "edge_inset";

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
        // 首次运行不播种任何主机：不把开发者的私有主机名/会话名塞进别人的设备
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

    /** 终端四周留边（dp）：贴边对曲面/圆角屏不友好，做成可调。 */
    public static int getEdgeInset(Context ctx, int def) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_INSET, def);
    }

    public static void setEdgeInset(Context ctx, int dp) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putInt(KEY_INSET, dp).apply();
    }
}

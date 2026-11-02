package dev.northwind.bakassh;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;

/** Shortcut data and key labels. Layout editing uses structured rows, not text syntax. */
public class ExtraKeys {
    public static final String DEFAULT = KeyLayout.DEFAULT;
    private static final String PREF = "bakassh", LEGACY = "extra_keys", STRUCTURED = "extra_keys_layout_v2";

    static KeyLayout getLayout(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String json = preferences.getString(STRUCTURED, null);
        if (json != null) {
            try { return decode(json); } catch (JSONException | IllegalArgumentException ignored) { }
        }
        try { return KeyLayout.migrateLegacy(preferences.getString(LEGACY, null)); }
        catch (IllegalArgumentException e) { return KeyLayout.defaults(); }
    }

    static String encode(KeyLayout layout) {
        JSONArray rows = new JSONArray();
        for (String[] row : layout.snapshot()) {
            JSONArray keys = new JSONArray();
            for (String key : row) keys.put(key);
            rows.put(keys);
        }
        return rows.toString();
    }

    static KeyLayout decode(String json) throws JSONException {
        JSONArray rows = new JSONArray(json);
        List<String[]> source = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONArray keys = rows.getJSONArray(i);
            String[] row = new String[keys.length()];
            for (int j = 0; j < row.length; j++) row[j] = keys.getString(j);
            source.add(row);
        }
        return new KeyLayout(source);
    }

    static void saveLayout(Context context, KeyLayout layout) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(STRUCTURED, encode(layout)).apply();
    }

    static String label(String key) {
        switch (key.toUpperCase(Locale.ROOT)) {
            case "UP": return "↑";
            case "DOWN": return "↓";
            case "LEFT": return "←";
            case "RIGHT": return "→";
            case "ENTER": return "↵";
            case "TAB": return "Tab";
            case "PGUP": return "PgUp";
            case "PGDN": return "PgDn";
            case "BKSP": return "⌫";
            case "SPACE": return "空格";
            case "KEYBOARD": return "键盘";
            case "PASTE": return "粘贴";
            case "FONT-": return "字号−";
            case "FONT+": return "字号＋";
            default: return key;
        }
    }

    /** 是不是"粘滞修饰键"（点一下点亮，再按下一个键生效）。 */
    public static boolean isModifier(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        return n.equals("CTRL") || n.equals("ALT") || n.equals("SHIFT");
    }

}

package dev.northwind.bakassh;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 扩展按键栏（学 Termux）。
 *
 * Termux 用 termux.properties 里的 `extra-keys = [[...],[...]]` 定制两行按键；
 * 我们照抄这个思路，但用更省事的一行文本表示：
 *
 *     ESC,/,-,HOME,UP,END,PGUP|TAB,CTRL,ALT,LEFT,DOWN,RIGHT,PGDN
 *
 * `|` 分行，`,` 分键。键名大小写不敏感，与 Termux 同名同义；
 * 单个字符（如 `-` `/` `|`）直接发该字符。
 * 另外提供三个 Termux 没有、但手机上很需要的动作：FONT-、FONT+、KEYBOARD、PASTE。
 */
public class ExtraKeys {

    /**
     * 默认两行：**照用户 Termux 的布局**（5 键一行，每键约占屏宽 1/5，键大、字大）。
     * 他 Termux 上第一行是 ESC / 退格 / ALT / ↑ / 菜单，第二行 CTRL / SHIFT / ← / ↓ / →；
     * 我们没有"菜单"这个东西，那一格换成 KEYBOARD（弹键盘）最实用。
     * 想要更密的排布可在主界面「扩展按键」里自己改。
     */
    public static final String DEFAULT =
            "ESC,BKSP,ALT,UP,KEYBOARD|CTRL,SHIFT,LEFT,DOWN,RIGHT";

    private static final String PREF = "bakassh";
    private static final String KEY = "extra_keys";

    /** 上一版默认值：老安装里存的是它，读到就迁移成新默认（用户自己改过的不动）。 */
    private static final String OLD_DEFAULT =
            "ESC,/,-,HOME,UP,END,PGUP,FONT-|TAB,CTRL,ALT,LEFT,DOWN,RIGHT,PGDN,FONT+,KEYBOARD,PASTE";

    public static String get(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String v = sp.getString(KEY, DEFAULT);
        if (v == null || v.trim().isEmpty() || v.equals(OLD_DEFAULT)) return DEFAULT;
        return v;
    }

    public static void set(Context c, String v) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, v).apply();
    }

    /** 解析成两行（行数不限，但界面按行渲染）。 */
    public static List<String[]> parse(String cfg) {
        List<String[]> rows = new ArrayList<>();
        if (cfg == null) return rows;
        for (String rowStr : cfg.split("\\|")) {
            if (rowStr.trim().isEmpty()) continue;
            String[] keys = rowStr.split(",");
            List<String> cleaned = new ArrayList<>();
            for (String k : keys) {
                String t = k.trim();
                if (!t.isEmpty()) cleaned.add(t);
            }
            if (!cleaned.isEmpty()) rows.add(cleaned.toArray(new String[0]));
        }
        return rows;
    }

    /** 是不是"粘滞修饰键"（点一下点亮，再按下一个键生效）。 */
    public static boolean isModifier(String name) {
        String n = name.toUpperCase();
        return n.equals("CTRL") || n.equals("ALT") || n.equals("SHIFT");
    }

    /** 是不是由宿主处理的动作（不发字节）。 */
    public static boolean isAction(String name) {
        String n = name.toUpperCase();
        return isModifier(n) || n.equals("FONT-") || n.equals("FONT+")
                || n.equals("KEYBOARD") || n.equals("PASTE");
    }

    /** 按键名取要发送的字节；返回 null 表示这是动作键（见 {@link #isAction}）。 */
    public static byte[] bytesFor(String name) {
        if (name == null) return null;
        String n = name.toUpperCase();
        switch (n) {
            case "ESC":     return new byte[]{0x1b};
            case "TAB":     return new byte[]{0x09};
            case "ENTER":   return new byte[]{0x0d};
            case "BKSP":    return new byte[]{0x7f};
            case "SPACE":   return new byte[]{0x20};
            case "UP":      return esc("[A");
            case "DOWN":    return esc("[B");
            case "RIGHT":   return esc("[C");
            case "LEFT":    return esc("[D");
            case "HOME":    return esc("[H");
            case "END":     return esc("[F");
            case "PGUP":    return esc("[5~");
            case "PGDN":    return esc("[6~");
            case "INS":     return esc("[2~");
            case "DEL":     return esc("[3~");
            default: break;
        }
        if (isAction(n)) return null;
        // 单字符：直接发（支持中文/符号）
        return name.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] esc(String tail) {
        byte[] t = tail.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[t.length + 1];
        out[0] = 0x1b;
        System.arraycopy(t, 0, out, 1, t.length);
        return out;
    }

    /** 设置界面用的说明文字。 */
    public static String legend() {
        return "用「|」分行、「,」分键。可用键名：\n"
                + "ESC TAB ENTER BKSP SPACE DEL INS\n"
                + "UP DOWN LEFT RIGHT HOME END PGUP PGDN\n"
                + "CTRL ALT SHIFT（粘滞修饰键）\n"
                + "FONT- FONT+（字号）KEYBOARD（键盘）PASTE（粘贴）\n"
                + "也可以直接写单个字符，例如 / - | ~";
    }
}

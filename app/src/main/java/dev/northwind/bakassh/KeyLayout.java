package dev.northwind.bakassh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Editable rows of keys, independent of Android and the old delimiter format. */
final class KeyLayout {
    static final int MAX_ROWS = 4, MAX_KEYS = 10, MAX_TEXT = 64;
    static final String DEFAULT = "ESC,TAB,ALT,UP,KEYBOARD|CTRL,SHIFT,LEFT,DOWN,RIGHT";
    /**
     * 历史默认方案。用户没改过布局时，迁移到新的默认；改过的保持原样。
     * 新默认把 Ctrl 挪到第二行、补上 Shift 和键盘，去掉 Home / End。
     */
    private static final String[] DEPRECATED_DEFAULTS = {
            "ESC,BKSP,ALT,UP,KEYBOARD|CTRL,SHIFT,LEFT,DOWN,RIGHT",
            "ESC,TAB,CTRL,ALT,UP|HOME,END,LEFT,DOWN,RIGHT",
            "ESC,/,-,HOME,UP,END,PGUP,FONT-|TAB,CTRL,ALT,LEFT,DOWN,RIGHT,PGDN,FONT+,KEYBOARD,PASTE",
    };
    private final List<List<String>> rows = new ArrayList<>();

    KeyLayout(List<String[]> source) {
        // Older custom layouts may exceed the editor's limits for adding new keys.
        if (source.isEmpty() || source.size() > 64) throw new IllegalArgumentException("快捷键行数无效");
        for (String[] row : source) {
            if (row.length == 0 || row.length > 128) throw new IllegalArgumentException("按键数量无效");
            List<String> keys = new ArrayList<>();
            for (String key : row) { validateKey(key); keys.add(key); }
            rows.add(keys);
        }
    }

    static KeyLayout defaults() { return fromLegacy(DEFAULT); }
    static KeyLayout migrateLegacy(String text) {
        if (text == null || text.trim().isEmpty()) return defaults();
        for (String deprecated : DEPRECATED_DEFAULTS) if (text.equals(deprecated)) return defaults();
        return fromLegacy(text);
    }
    static KeyLayout fromLegacy(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String raw : text.split("\\|")) {
            List<String> keys = new ArrayList<>();
            for (String key : raw.split(",")) if (!key.trim().isEmpty()) keys.add(key.trim());
            if (!keys.isEmpty()) rows.add(keys.toArray(new String[0]));
        }
        return new KeyLayout(rows);
    }
    private static void validateKey(String key) {
        if (key == null || key.trim().isEmpty() || key.codePointCount(0, key.length()) > MAX_TEXT)
            throw new IllegalArgumentException("请输入 1–64 个字符");
        for (int i = 0; i < key.length(); i++)
            if (Character.isISOControl(key.charAt(i))) throw new IllegalArgumentException("按键文字不能包含控制字符");
    }

    int rowCount() { return rows.size(); }
    int size(int row) { return rows.get(row).size(); }
    String key(int row, int col) { return rows.get(row).get(col); }
    List<String[]> snapshot() {
        List<String[]> copy = new ArrayList<>();
        for (List<String> row : rows) copy.add(row.toArray(new String[0]));
        return copy;
    }
    void replace(int row, int col, String key) { validateKey(key); rows.get(row).set(col, key); }
    int addKey(int row, String key) {
        validateKey(key);
        if (size(row) >= MAX_KEYS) throw new IllegalArgumentException("每行最多 10 个按键");
        rows.get(row).add(key);
        return size(row) - 1;
    }
    int addRow() {
        if (rowCount() >= MAX_ROWS) throw new IllegalArgumentException("最多 4 行快捷键");
        rows.add(new ArrayList<>(Collections.singletonList("TAB")));
        return rowCount() - 1;
    }
    void removeRow(int row) {
        if (rowCount() == 1) throw new IllegalArgumentException("至少保留一行快捷键");
        rows.remove(row);
    }
    void swapRows(int first, int second) { Collections.swap(rows, first, second); }
    void remove(int row, int col) {
        if (size(row) == 1 && rowCount() == 1) throw new IllegalArgumentException("至少保留一个按键");
        rows.get(row).remove(col);
        if (rows.get(row).isEmpty()) rows.remove(row);
    }
    /** Move before the target index, allowing index == row size to append. */
    int[] move(int fromRow, int fromCol, int toRow, int toCol) {
        if (toCol < 0 || toCol > size(toRow)) throw new IllegalArgumentException("目标位置无效");
        if (fromRow != toRow && size(toRow) >= MAX_KEYS) throw new IllegalArgumentException("目标行已有 10 个按键");
        String key = rows.get(fromRow).remove(fromCol);
        if (fromRow == toRow && toCol > fromCol) toCol--;
        rows.get(toRow).add(toCol, key);
        if (fromRow != toRow && rows.get(fromRow).isEmpty()) {
            rows.remove(fromRow);
            if (toRow > fromRow) toRow--;
        }
        return new int[]{toRow, toCol};
    }
}

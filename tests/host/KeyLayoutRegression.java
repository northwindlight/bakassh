package dev.northwind.bakassh;

import java.util.Arrays;
import java.util.Collections;

public final class KeyLayoutRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String content(KeyLayout layout) {
        StringBuilder text = new StringBuilder();
        for (String[] row : layout.snapshot()) text.append(Arrays.toString(row));
        return text.toString();
    }
    private static void rejected(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) {
        KeyLayout layout = KeyLayout.defaults();
        check(content(layout).contains("TAB") && !content(layout).contains("BKSP"), "default lacks Tab");
        check(content(KeyLayout.migrateLegacy("ESC,BKSP,ALT,UP,KEYBOARD|CTRL,SHIFT,LEFT,DOWN,RIGHT"))
                .equals(content(layout)), "old default did not migrate");
        KeyLayout custom = KeyLayout.migrateLegacy("ESC,TAB,/|CTRL,c,RIGHT");
        check(custom.size(0) == 3 && custom.key(1, 1).equals("c"), "custom legacy layout overwritten");
        KeyLayout wide = KeyLayout.migrateLegacy("a,b,c,d,e,f,g,h,i,j,k");
        check(wide.size(0) == 11, "wide legacy layout discarded");
        System.out.println("PASS Tab default migration preserves existing custom layouts");

        String[] original = {"TAB", "ESC"};
        layout = new KeyLayout(Collections.singletonList(original));
        original[0] = "broken";
        layout.snapshot().get(0)[0] = "changed";
        check(layout.key(0, 0).equals("TAB"), "layout aliases input or snapshot");
        layout.replace(0, 0, "|");
        layout.addKey(0, ",");
        check(layout.key(0, 0).equals("|") && layout.key(0, 2).equals(","), "delimiter symbols lost");
        System.out.println("PASS independent draft/snapshot and literal comma/pipe keys");

        layout = KeyLayout.fromLegacy("a,b,c|d,e");
        int[] selected = layout.move(0, 0, 0, 3);
        check(content(layout).equals("[b, c, a][d, e]") && selected[1] == 2, "same row move");
        selected = layout.move(0, 2, 1, 1);
        check(content(layout).equals("[b, c][d, a, e]") && selected[0] == 1 && selected[1] == 1, "cross row move");
        System.out.println("PASS graphical left/right and cross-row sorting");

        layout = KeyLayout.fromLegacy("a|b,c");
        selected = layout.move(0, 0, 1, 2);
        check(content(layout).equals("[b, c, a]") && selected[0] == 0 && selected[1] == 2, "empty source row index");
        layout.remove(0, 2); layout.remove(0, 1);
        KeyLayout single = layout;
        rejected(() -> single.remove(0, 0), "removed last key");
        rejected(() -> single.removeRow(0), "removed last row");
        check(content(single).equals("[b]"), "failed removal mutated layout");
        System.out.println("PASS drag across final key of row and minimum layout protection");

        layout = KeyLayout.fromLegacy("a,b,c,d,e,f,g,h,i,j|x");
        KeyLayout full = layout;
        String before = content(full);
        rejected(() -> full.move(1, 0, 0, 0), "overfilled target row");
        rejected(() -> full.addKey(0, "z"), "overfilled row");
        check(content(full).equals(before), "invalid move lost key");
        full.addRow(); full.addRow();
        rejected(full::addRow, "added fifth row");
        rejected(() -> full.replace(0, 0, "\033"), "accepted terminal control");
        rejected(() -> full.replace(0, 0, ""), "accepted empty key");
        System.out.println("PASS capacity and invalid-input failures preserve draft");
    }
}

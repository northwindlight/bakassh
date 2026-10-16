package dev.northwind.bakassh;

import java.nio.charset.StandardCharsets;

final class SelectionRegression {
    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> got <" + actual + ">");
    }

    public static void main(String[] args) {
        long h = NativeTerm.create(12, 3);
        try {
            byte[] text = "\033[1;1Hhello world!\033[2;1H中文 abc\033[3;1Hlast line".getBytes(StandardCharsets.UTF_8);
            NativeTerm.pushBytes(h, text, text.length);
            int[][] rows = new int[3][12 * 5];
            for (int r = 0; r < 3; r++) NativeTerm.getRow(h, r, rows[r]);
            TerminalSelection selection = new TerminalSelection(12, rows);

            selection.selectWord(0, 7);
            equal("world", selection.text());
            selection.moveAnchor(0, 6);
            selection.moveFocus(2, 3);
            equal("world!\n中文 abc\nlast", selection.text());
            System.out.println("PASS continuous multiline selection");

            selection.moveAnchor(2, 3);
            selection.moveFocus(0, 6);
            equal("world!\n中文 abc\nlast", selection.text());
            System.out.println("PASS reversed selection endpoints");

            selection.selectWord(1, 1); // second cell of a wide glyph
            equal("中文", selection.text());
            selection.moveAnchor(1, 3);
            selection.moveFocus(1, 3);
            equal("文", selection.text());
            System.out.println("PASS wide character selection without padding cells");

            byte[] update = "\033[2Jnew output".getBytes(StandardCharsets.UTF_8);
            NativeTerm.pushBytes(h, update, update.length);
            rows[1][0] = 'X';
            equal("文", selection.text());
            selection.selectAll();
            equal("hello world!\n中文 abc\nlast line", selection.text());
            System.out.println("PASS selection snapshot survives remote output and source mutation");
        } finally { NativeTerm.destroy(h); }
    }
}

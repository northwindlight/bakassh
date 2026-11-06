package dev.northwind.bakassh;

import java.nio.charset.StandardCharsets;

final class SelectionRegression {
    /**
     * 拖选越过一屏：滚动用的是只改 view_offset 的 scrollView，
     * 选区按绝对行号（0 = 屏幕首行，-1 = 屏幕上方一行）把历史行拼进来。
     */
    private static void selectionAcrossScrollback() {
        long h = NativeTerm.create(12, 3);
        try {
            byte[] text = "a\r\nb\r\nc\r\nd".getBytes(StandardCharsets.UTF_8);
            NativeTerm.pushBytes(h, text, text.length);
            if (NativeTerm.getHistorySize(h) < 1) throw new AssertionError("output did not scroll into history");

            NativeTerm.scrollView(h, 1);
            if (NativeTerm.getScrollOffset(h) != 1) throw new AssertionError("scrollView did not move the view");
            int[] historyRow = new int[12 * 5];
            NativeTerm.getRow(h, 0, historyRow);          // 绝对行 -1
            NativeTerm.scrollView(h, -1);
            int[] screenRow = new int[12 * 5];
            NativeTerm.getRow(h, 0, screenRow);           // 绝对行 0

            TerminalSelection across = new TerminalSelection(12);
            across.put(-1, historyRow);
            across.put(0, screenRow);
            across.moveAnchor(-1, 0);
            across.moveFocus(0, 3);   // 屏幕首行只有 "b"，其余是空格（尾部空格会被裁掉）
            equal("a\nb", across.text());
            System.out.println("PASS selection spans scrollback rows above the screen");

            NativeTerm.scrollView(h, 9999);
            if (NativeTerm.getScrollOffset(h) != NativeTerm.getHistorySize(h))
                throw new AssertionError("scrollView did not clamp to the oldest history line");
            NativeTerm.scrollView(h, -9999);
            if (NativeTerm.getScrollOffset(h) != 0) throw new AssertionError("scrollView did not clamp back to the bottom");
            System.out.println("PASS scrollView clamps at both ends of the scrollback");

            byte[] mouseOn = "\033[?1000h".getBytes(StandardCharsets.UTF_8);
            NativeTerm.pushBytes(h, mouseOn, mouseOn.length);
            byte[] sink = new byte[4096];
            while (NativeTerm.takeOutput(h, sink) > 0) { }
            NativeTerm.scrollView(h, 1);
            if (NativeTerm.takeOutput(h, sink) != 0)
                throw new AssertionError("scrollView leaked a remote wheel event in mouse mode");
            System.out.println("PASS scrollView never forwards to the remote application");
        } finally {
            NativeTerm.destroy(h);
        }
    }
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

            selectionAcrossScrollback();
        } finally { NativeTerm.destroy(h); }
    }
}

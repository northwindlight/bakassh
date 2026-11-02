package dev.northwind.bakassh;

import java.nio.charset.StandardCharsets;

/** Exercises actual libvterm modes, scrollback cells and the bytes sent to SSH. */
public final class InteractionRegression {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void feed(long handle, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        NativeTerm.pushBytes(handle, bytes, bytes.length);
    }
    private static String output(long handle) {
        StringBuilder text = new StringBuilder();
        NativeTerm.drainOutput(handle, bytes -> text.append(new String(bytes, StandardCharsets.UTF_8)));
        return text.toString();
    }
    private static String row(long handle, int row, int cols) {
        int[] cells = new int[cols * 5];
        NativeTerm.getRow(handle, row, cells);
        StringBuilder text = new StringBuilder();
        for (int col = 0; col < cols; col++) {
            int cp = cells[col * 5];
            if (cp == -1 || cells[col * 5 + 4] == 0) continue;
            text.appendCodePoint(cp == 0 ? ' ' : cp);
        }
        return text.toString().trim();
    }
    private static void lines(long h, int count) {
        for (int i = 0; i < count; i++) feed(h, (i == 0 ? "" : "\r\n") + "line-" + i);
    }
    private static void history() {
        long h = NativeTerm.create(20, 3);
        try {
            lines(h, 10);
            check(NativeTerm.getHistorySize(h) == 7, "shell history discarded");
            NativeTerm.scroll(h, 1, 2, 3);
            check(row(h, 0, 20).equals("line-4"), "wrong history viewport");
            check(output(h).isEmpty(), "shell scroll sent input");
            feed(h, "\r\nline-10\r\nline-11");
            check(NativeTerm.getScrollOffset(h) == 5 && row(h, 0, 20).equals("line-4"), "output moved reading position");
            int[] cursor = new int[4];
            NativeTerm.getCursor(h, cursor);
            check(cursor[2] == 0, "live cursor over history");
            NativeTerm.scroll(h, 0, 0, 100);
            check(row(h, 0, 20).equals("line-0"), "oldest boundary");
            NativeTerm.scroll(h, 0, 0, -100);
            check(row(h, 0, 20).equals("line-9"), "latest boundary");
            NativeTerm.getCursor(h, cursor);
            check(cursor[2] == 1, "cursor not restored");
            NativeTerm.scroll(h, 0, 0, 2);
            NativeTerm.followOutput(h);
            check(NativeTerm.getScrollOffset(h) == 0, "follow output");
        } finally { NativeTerm.destroy(h); }
    }
    private static void boundedHistory() {
        long h = NativeTerm.create(20, 3);
        try {
            lines(h, 4300);
            check(NativeTerm.getHistorySize(h) == 4096, "line bound");
            for (int i = 0; i < 9; i++) NativeTerm.scroll(h, 0, 0, 512);
            check(row(h, 0, 20).equals("line-201"), "history ring order");
            feed(h, "\r\nline-4300");
            check(row(h, 0, 20).equals("line-202"), "oldest eviction");
            feed(h, "\033[3J");
            check(NativeTerm.getHistorySize(h) == 0 && NativeTerm.getScrollOffset(h) == 0, "clear scrollback");
        } finally { NativeTerm.destroy(h); }
        h = NativeTerm.create(400, 3);
        try {
            lines(h, 4300);
            check(NativeTerm.getHistorySize(h) > 0 && NativeTerm.getHistorySize(h) < 4096, "byte bound");
        } finally { NativeTerm.destroy(h); }
    }
    private static void modes() {
        long h = NativeTerm.create(20, 3);
        try {
            check(!NativeTerm.click(h, 2, 6) && output(h).isEmpty(), "shell click emitted mouse");
            feed(h, "\033[?1000h\033[?1006h");
            check(NativeTerm.click(h, 2, 6), "mouse click not handled");
            check(output(h).equals("\033[<0;7;3M\033[<0;7;3m"), "SGR press/release coordinates");
            NativeTerm.scroll(h, 1, 4, 2);
            check(output(h).equals("\033[<64;5;2M\033[<64;5;2M"), "wheel position/count");
            NativeTerm.scroll(h, 1, 4, -1);
            check(output(h).equals("\033[<65;5;2M"), "wheel direction");
            feed(h, "\033[?1000l\033[?1049h");
            check(!NativeTerm.isLocalScroll(h), "alternate screen used local history");
            NativeTerm.scroll(h, 0, 0, 2);
            check(output(h).equals("\033[A\033[A"), "alternate screen scroll fallback");
            feed(h, "\033[?1h");
            NativeTerm.scroll(h, 0, 0, -1);
            check(output(h).equals("\033OB"), "application cursor mode ignored");
            feed(h, "\033[?1002h");
            check(NativeTerm.click(h, 0, 0), "drag tracking rejected click");
            check(output(h).equals("\033[<0;1;1M\033[<0;1;1m"), "tracking click bytes");
            feed(h, "\033[?1002l\033[?1049l");
            check(NativeTerm.isLocalScroll(h), "primary screen mode not restored");
        } finally { NativeTerm.destroy(h); }
    }
    private static void legacyMouse() {
        long h = NativeTerm.create(20, 3);
        try {
            feed(h, "\033[?1000h");
            NativeTerm.click(h, 1, 3);
            check(output(h).equals("\033[M $\"\033[M#$\""), "legacy mouse click");
        } finally { NativeTerm.destroy(h); }
    }
    private static void alternateHistory() {
        long h = NativeTerm.create(20, 3);
        try {
            lines(h, 10);
            NativeTerm.scroll(h, 0, 0, 3);
            feed(h, "\033[?1049h");
            check(NativeTerm.getScrollOffset(h) == 0, "history overlays alternate screen");
            lines(h, 20);
            check(NativeTerm.getHistorySize(h) == 7, "alternate screen leaked into history");
            feed(h, "\033[?1049l");
            check(row(h, 0, 20).equals("line-7"), "primary screen lost");
            NativeTerm.scroll(h, 0, 0, 3);
            feed(h, "\033[?1000h\033[?1006h");
            check(!NativeTerm.click(h, 0, 0), "clicked live app through history");
            NativeTerm.scroll(h, 0, 0, 1);
            check(output(h).isEmpty(), "history scrolling became remote wheel");
        } finally { NativeTerm.destroy(h); }
    }
    private static void resizeHistory() {
        long h = NativeTerm.create(20, 3);
        try {
            lines(h, 10);
            NativeTerm.scroll(h, 0, 0, 3);
            NativeTerm.resize(h, 20, 5);
            check(row(h, 0, 20).equals("line-4"), "resize moved anchored viewport");
            NativeTerm.followOutput(h);
            check(row(h, 0, 20).equals("line-5"), "resize failed to restore history");
            NativeTerm.resize(h, 20, 3);
            check(row(h, 0, 20).equals("line-7"), "shrinking lost history");
            for (int i = 0; i < 100; i++) {
                NativeTerm.resize(h, i % 2 == 0 ? 10 : 20, i % 3 + 3);
                NativeTerm.scroll(h, 0, 0, 2);
                NativeTerm.followOutput(h);
            }
        } finally { NativeTerm.destroy(h); }
    }
    private static void styledHistory() {
        long h = NativeTerm.create(8, 2);
        try {
            feed(h, "\033[31;1m中A\033[0m\r\nsecond\r\nthird");
            NativeTerm.scroll(h, 0, 0, 1);
            int[] cells = new int[40];
            NativeTerm.getRow(h, 0, cells);
            check(cells[0] == '中' && cells[4] == 2 && cells[5] == -1 && cells[10] == 'A', "wide history cells");
            check((cells[3] & 1) != 0 && cells[1] != cells[2], "history style/color");
            int[][] snapshotCells = new int[2][40];
            for (int r = 0; r < 2; r++) NativeTerm.getRow(h, r, snapshotCells[r]);
            TerminalSelection snapshot = new TerminalSelection(8, snapshotCells);
            snapshot.selectAll();
            String selected = snapshot.text();
            check(selected.contains("中A") && selected.contains("second"), "history selection read live screen");
            feed(h, "\r\nchanged");
            check(snapshot.text().equals(selected), "history snapshot changed");
            NativeTerm.resize(h, 2, 2);
            NativeTerm.getRow(h, 0, new int[10]);
        } finally { NativeTerm.destroy(h); }
    }
    private static void damage() {
        long h = NativeTerm.create(20, 3);
        try {
            lines(h, 10);
            NativeTerm.takeDamageRows(h, new int[3]);
            NativeTerm.scroll(h, 0, 0, 2);
            int[] moves = new int[256 * 8], rows = new int[3];
            check(NativeTerm.takeMoveOps(h, moves) == 0, "live moves reused for history");
            check(NativeTerm.takeDamageRows(h, rows) == 3, "history viewport did not invalidate cached rows");
            feed(h, "\r\nnew");
            check(NativeTerm.takeMoveOps(h, moves) == 0, "anchored history received live moves");
            check(NativeTerm.takeDamageRows(h, rows) == 3, "anchored output left stale rows");
            NativeTerm.followOutput(h);
            check(NativeTerm.takeDamageRows(h, rows) == 3, "follow output cache invalidation");
        } finally { NativeTerm.destroy(h); }
    }
    private static void paste() {
        long h = NativeTerm.create(20, 3);
        try {
            NativeTerm.startPaste(h); NativeTerm.endPaste(h);
            check(output(h).isEmpty(), "shell paste markers");
            feed(h, "\033[?2004h");
            NativeTerm.startPaste(h); NativeTerm.endPaste(h);
            check(output(h).equals("\033[200~\033[201~"), "bracketed paste markers");
            feed(h, "\033[?2004l");
            NativeTerm.startPaste(h); NativeTerm.endPaste(h);
            check(output(h).isEmpty(), "paste mode did not reset");
        } finally { NativeTerm.destroy(h); }
    }
    private static void independentHistory() {
        long first = NativeTerm.create(20, 3), second = NativeTerm.create(20, 3);
        try {
            lines(first, 10); lines(second, 6);
            NativeTerm.scroll(first, 0, 0, 2);
            check(NativeTerm.getScrollOffset(second) == 0 && row(second, 0, 20).equals("line-3"), "session history shared");
            NativeTerm.destroy(first); first = 0;
            NativeTerm.scroll(second, 0, 0, 3);
            check(row(second, 0, 20).equals("line-0"), "closing another session lost history");
        } finally { if (first != 0) NativeTerm.destroy(first); NativeTerm.destroy(second); }
    }
    public static void main(String[] args) {
        Runnable[] tests = {InteractionRegression::history, InteractionRegression::boundedHistory,
                InteractionRegression::modes, InteractionRegression::legacyMouse,
                InteractionRegression::alternateHistory, InteractionRegression::resizeHistory,
                InteractionRegression::styledHistory, InteractionRegression::damage,
                InteractionRegression::paste, InteractionRegression::independentHistory};
        String[] names = {"shell scrollback and anchored output", "bounded history and eviction/clear",
                "mouse click/wheel and alternate-screen routing", "legacy mouse press/release",
                "alternate screen isolation and history click suppression", "resize restores history and anchor",
                "styled CJK history and immutable selection", "viewport/cache invalidation",
                "negotiated bracketed paste", "independent session history"};
        for (int i = 0; i < tests.length; i++) { tests[i].run(); System.out.println("PASS " + names[i]); }
    }
}

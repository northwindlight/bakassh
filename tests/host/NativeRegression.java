package dev.northwind.bakassh;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/** Runs the real JNI bridge and vendored libvterm, without an Android device. */
public final class NativeRegression {
    private static void feed(long h, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        NativeTerm.pushBytes(h, bytes, bytes.length);
    }

    private static int[] row(long h, int r, int cols) {
        int[] cells = new int[cols * 5];
        NativeTerm.getRow(h, r, cells);
        return cells;
    }

    private static final class Cache {
        final int cols, rows;
        int[][] cells;
        Cache(long h, int cols, int rows) {
            this.cols = cols; this.rows = rows;
            cells = new int[rows][];
            update(h, 256);
        }
        void update(long h, int capacity) {
            int[] moves = new int[capacity * 8];
            int count = NativeTerm.takeMoveOps(h, moves);
            for (int i = 0; i < count; i++) {
                int p = i * 8;
                if (moves[p + 1] != 0 || moves[p + 3] != cols || moves[p + 5] != 0
                        || moves[p + 7] != cols) {
                    for (int d = moves[p]; d < moves[p + 2]; d++) cells[d] = row(h, d, cols);
                    continue;
                }
                int[][] old = cells.clone();
                for (int d = moves[p], s = moves[p + 4]; d < moves[p + 2]; d++, s++)
                    cells[d] = old[s];
            }
            int[] dirty = new int[rows];
            int n = NativeTerm.takeDamageRows(h, dirty);
            for (int i = 0; i < n; i++) cells[dirty[i]] = row(h, dirty[i], cols);
            for (int r = 0; r < rows; r++) {
                if (!Arrays.equals(cells[r], row(h, r, cols)))
                    throw new AssertionError("stale cached row " + r);
            }
        }
    }

    private static void scrolling(int count, int capacity) {
        long h = NativeTerm.create(20, 8);
        try {
            for (int i = 0; i < 8; i++) feed(h, "\033[" + (i + 1) + ";1Hrow-" + i);
            Cache cache = new Cache(h, 20, 8);
            // Damage a row, then scroll it before the next display frame.
            feed(h, "\033[4;1Hchanged\033[8;1H");
            for (int i = 0; i < count; i++) feed(h, "\r\nnew-" + i);
            cache.update(h, capacity);
        } finally { NativeTerm.destroy(h); }
    }

    private static void outputChunks() {
        long h = NativeTerm.create(20, 8);
        try {
            feed(h, "\033[6n");
            byte[] chunk = new byte[2];
            StringBuilder output = new StringBuilder();
            int n;
            while ((n = NativeTerm.takeOutput(h, chunk)) > 0)
                output.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
            if (!output.toString().equals("\033[1;1R"))
                throw new AssertionError("truncated DSR reply: " + output);
        } finally { NativeTerm.destroy(h); }
    }

    private static void automaticVersionReply() {
        long h = NativeTerm.create(20, 8);
        try {
            StringBuilder response = new StringBuilder();
            byte[] query = "\033[>0q".getBytes(StandardCharsets.UTF_8);
            NativeTerm.receive(h, query, query.length,
                    bytes -> response.append(new String(bytes, StandardCharsets.UTF_8)));
            if (!response.toString().equals("\033P>|libvterm(0.3)\033\\"))
                throw new AssertionError("XTVERSION not answered immediately: " + response);
            if (NativeTerm.takeOutput(h, new byte[100]) != 0)
                throw new AssertionError("reply left pending until next keypress");
        } finally { NativeTerm.destroy(h); }
    }

    private static void fixedTuiRegions() {
        long h = NativeTerm.create(20, 8);
        try {
            feed(h, "\033[1;1HFIXED HEADER\033[8;1HFIXED FOOTER\033[2;7r");
            Cache cache = new Cache(h, 20, 8);
            int[] header = row(h, 0, 20), footer = row(h, 7, 20);
            for (int frame = 0; frame < 200; frame++) {
                // Mix ordinary updates, forward/reverse scrolling and several
                // network packets before a single display frame.
                feed(h, "\033[4;1Hupdate-" + frame + "\033[7;1H");
                for (int i = 0; i < frame % 11 + 1; i++) feed(h, "\r\nbody-" + frame + "-" + i);
                if (frame % 3 == 0) feed(h, "\033[2;1H\033Mreverse-" + frame);
                cache.update(h, 128);
                if (!Arrays.equals(header, row(h, 0, 20)) || !Arrays.equals(footer, row(h, 7, 20)))
                    throw new AssertionError("fixed header/footer moved");
            }
        } finally { NativeTerm.destroy(h); }
    }

    private static void mixedTuiFrames() {
        long h = NativeTerm.create(20, 8);
        Random random = new Random(20261016);
        try {
            Cache cache = new Cache(h, 20, 8);
            for (int frame = 0; frame < 1000; frame++) {
                for (int i = 0; i < 12; i++) {
                    int r = 1 + random.nextInt(8), c = 1 + random.nextInt(15);
                    feed(h, "\033[" + r + ";" + c + "H");
                    switch (random.nextInt(9)) {
                        case 0: feed(h, "\033[2;7r\033[7;1H\r\nline"); break;
                        case 1: feed(h, "\033[2;7r\033[2;1H\033Mtop"); break;
                        case 2: feed(h, "\033[2P"); break; // partial row move
                        case 3: feed(h, "\033[2@"); break;
                        case 4: feed(h, "\033[L"); break;
                        case 5: feed(h, "\033[M"); break;
                        case 6: feed(h, "中文"); break;
                        case 7: feed(h, "\033[0;41mred\033[40mblack\033[0m"); break;
                        default: feed(h, "test-" + frame);
                    }
                }
                cache.update(h, 128);
            }
        } finally { NativeTerm.destroy(h); }
    }

    private static void outputBurst() {
        long h = NativeTerm.create(20, 8);
        try {
            String query = "\033[>0q", expected = "\033P>|libvterm(0.3)\033\\";
            feed(h, query.repeat(1000));
            StringBuilder result = new StringBuilder();
            NativeTerm.drainOutput(h, bytes -> result.append(new String(bytes, StandardCharsets.UTF_8)));
            if (!result.toString().equals(expected.repeat(1000)))
                throw new AssertionError("query replies lost in burst");
        } finally { NativeTerm.destroy(h); }
    }

    private static void overwriteWideLeft() {
        long h = NativeTerm.create(20, 8);
        try {
            feed(h, "中\033[1;1Hi");
            int[] cells = row(h, 0, 20);
            if (cells[0] != 'i' || cells[4] != 1 || cells[5] == -1)
                throw new AssertionError("ASCII inherited a CJK continuation: cp=" + cells[0] + " width=" + cells[4]);
        } finally { NativeTerm.destroy(h); }
    }

    private static void overwriteWideRight() {
        long h = NativeTerm.create(20, 8);
        try {
            feed(h, "中\033[1;2Hi");
            int[] cells = row(h, 0, 20);
            if ((cells[0] != 0 && cells[0] != ' ') || cells[5] != 'i' || cells[9] != 1)
                throw new AssertionError("overwritten CJK left a half glyph");
        } finally { NativeTerm.destroy(h); }
    }

    private static void overwriteWideOverlapDuringScroll() {
        long h = NativeTerm.create(20, 8);
        try {
            Cache cache = new Cache(h, 20, 8);
            for (int frame = 0; frame < 200; frame++) {
                feed(h, "\033[7;1H 中\033[7;1H文\033[8;1H\n");
                cache.update(h, 128);
                int[] cells = row(h, 5, 20);
                if (cells[0] != '文' || cells[4] != 2 || cells[5] != -1 || cells[10] == -1)
                    throw new AssertionError("overlap left a second trailing cell after scroll");
            }
        } finally { NativeTerm.destroy(h); }
    }

    private static void run(String name, Runnable test) {
        try { test.run(); System.out.println("PASS " + name); }
        catch (Throwable t) { System.out.println("FAIL " + name + ": " + t); failures++; }
    }
    private static int failures;
    public static void main(String[] args) {
        run("dirty row follows scrolling", () -> scrolling(1, 256));
        run("move consumer capacity", () -> scrolling(150, 128));
        run("move producer overflow", () -> scrolling(300, 256));
        run("partial output reads", NativeRegression::outputChunks);
        run("XTVERSION reply without a keypress", NativeRegression::automaticVersionReply);
        run("TUI fixed header/footer and scroll margins", NativeRegression::fixedTuiRegions);
        run("1000 mixed TUI display frames", NativeRegression::mixedTuiFrames);
        run("query response burst over 4 KiB", NativeRegression::outputBurst);
        run("ASCII overwrite of CJK lead keeps single-cell width", NativeRegression::overwriteWideLeft);
        run("ASCII overwrite of CJK tail clears the other half", NativeRegression::overwriteWideRight);
        run("CJK overlap stays coherent across 200 scroll frames", NativeRegression::overwriteWideOverlapDuringScroll);
        if (failures != 0) throw new AssertionError(failures + " regression(s)");
    }
}

package dev.northwind.bakassh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Synthetic terminal bytes shared by the host check and the Android CPU benchmark. */
final class TerminalWorkload {
    static final String VERSION = "terminal-workload-v1";
    static final String[] SCENARIOS = {"scroll", "rewrite", "unchanged", "region", "styles"};
    final int cols, rows;
    final String scenario;

    TerminalWorkload(String scenario, int cols, int rows) {
        if (cols < 16 || cols > 400 || rows < 4 || rows > NativeTerm.MAX_ROWS)
            throw new IllegalArgumentException("workload grid must be 16–400 columns, 4–512 rows");
        boolean found = false;
        for (String value : SCENARIOS) found |= value.equals(scenario);
        if (!found) throw new IllegalArgumentException("unknown workload: " + scenario);
        this.scenario = scenario; this.cols = cols; this.rows = rows;
    }

    byte[] setup() {
        StringBuilder text = new StringBuilder("\033c\033[?25l\033[2J");
        writeRows(text, 0);
        if (scenario.equals("region")) text.append("\033[2;").append(rows - 1).append('r');
        position(text, scenario.equals("region") ? rows - 1 : rows);
        return bytes(text);
    }

    byte[] frame(int index) {
        if (index < 0) throw new IllegalArgumentException("negative frame");
        StringBuilder text = new StringBuilder();
        if (scenario.equals("scroll") || scenario.equals("region")) {
            text.append("\r\n").append(rowText(index + 1, rows - 1));
        } else {
            if (scenario.equals("styles")) text.append((index & 1) == 0 ?
                    "\033[1;4;38;2;97;175;239;48;2;40;44;52m" : "\033[0;7;32;44m");
            writeRows(text, scenario.equals("rewrite") ? index + 1 : 0);
        }
        position(text, scenario.equals("region") ? rows - 1 : rows);
        return bytes(text);
    }

    String rowText(int frame, int row) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%04d:%03d ", frame, row));
        int width = text.length();
        while (width + 3 <= cols - 1) { text.append("中a"); width += 3; }
        while (width++ < cols - 1) text.append('x');
        return text.toString();
    }

    private void writeRows(StringBuilder text, int frame) {
        for (int row = 0; row < rows; row++) {
            position(text, row + 1);
            text.append(rowText(frame, row));
        }
    }
    private static void position(StringBuilder text, int row) { text.append("\033[").append(row).append(";1H"); }
    private static byte[] bytes(StringBuilder text) { return text.toString().getBytes(StandardCharsets.UTF_8); }

    static int[] row(long handle, int row, int cols) {
        int[] cells = new int[cols * 5];
        if (NativeTerm.getRow(handle, row, cells) != cols) throw new AssertionError("native grid mismatch");
        return cells;
    }

    static String cellsHash(long handle, int cols, int rows) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (int row = 0; row < rows; row++)
            for (int value : row(handle, row, cols)) updateInt(hash, value);
        return hex(hash.digest());
    }
    String inputHash(int frames) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(setup());
        for (int frame = 0; frame < frames; frame++) hash.update(frame(frame));
        return hex(hash.digest());
    }
    static void updateInt(MessageDigest hash, int value) {
        hash.update((byte) (value >>> 24)); hash.update((byte) (value >>> 16));
        hash.update((byte) (value >>> 8)); hash.update((byte) value);
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
}

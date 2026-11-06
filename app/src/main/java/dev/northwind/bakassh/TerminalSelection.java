package dev.northwind.bakassh;

import java.util.TreeMap;

/**
 * 线性、包含两端的文本选区。
 *
 * 行号是「绝对行」：0 是当前屏幕第一行，负数向上进入回滚历史（-1 = 屏幕上方一行）。
 * 拖选时视图会滚动，新露出来的行按绝对行号补进 lines，所以选区可以跨过一屏；
 * 已经取到的行是快照副本，远端后续输出不会改写它们。
 */
final class TerminalSelection {
    private final int cols;
    private final TreeMap<Integer, int[]> lines = new TreeMap<>();
    /** 绝对线性位置：绝对行 * cols + 列。 */
    private int anchor, focus;

    /** 固定网格（测试与整屏快照）：绝对行 0..n-1。 */
    TerminalSelection(int cols, int[][] source) {
        this.cols = cols;
        for (int r = 0; r < source.length; r++) lines.put(r, source[r].clone());
    }

    /** 滚动场景：用 {@link #put(int, int[])} 按绝对行号逐行填充。 */
    TerminalSelection(int cols) {
        this.cols = cols;
    }

    void put(int absRow, int[] cells) {
        int[] copy = new int[cols * 5];
        System.arraycopy(cells, 0, copy, 0, Math.min(cells.length, copy.length));
        lines.put(absRow, copy);
    }

    boolean has(int absRow) { return lines.containsKey(absRow); }
    int topRow() { return lines.isEmpty() ? 0 : lines.firstKey(); }
    int bottomRow() { return lines.isEmpty() ? 0 : lines.lastKey(); }

    private int clampRow(int row) {
        if (lines.isEmpty()) return 0;
        if (row < lines.firstKey()) return lines.firstKey();
        if (row > lines.lastKey()) return lines.lastKey();
        return row;
    }

    private int position(int row, int col) {
        row = clampRow(row);
        col = Math.max(0, Math.min(cols - 1, col));
        int[] cells = lines.get(row);
        // libvterm marks the second half of a wide glyph with UINT32_MAX.
        if (cells != null && col > 0 && (cells[col * 5] == -1 || cells[col * 5 + 4] == 0)) col--;
        return row * cols + col;
    }

    void selectWord(int row, int col) {
        int pos = position(row, col);
        int r = Math.floorDiv(pos, cols), c = Math.floorMod(pos, cols);
        int kind = wordKind(codePoint(r, c));
        int left = c, right = c;
        while (left > 0) {
            int prev = Math.floorMod(position(r, left - 1), cols);
            if (wordKind(codePoint(r, prev)) != kind) break;
            left = prev;
        }
        while (right + width(r, right) < cols) {
            int next = right + width(r, right);
            if (wordKind(codePoint(r, next)) != kind) break;
            right = next;
        }
        anchor = r * cols + left;
        focus = r * cols + right;
    }

    private static int wordKind(int cp) {
        if (Character.isWhitespace(cp)) return 0;
        if (Character.isLetterOrDigit(cp) || cp == '_') return 1;
        return 2;
    }

    void moveAnchor(int row, int col) { anchor = position(row, col); }
    void moveFocus(int row, int col) { focus = position(row, col); }
    void selectAll() {
        if (lines.isEmpty()) { anchor = focus = 0; return; }
        anchor = topRow() * cols;
        focus = bottomRow() * cols + cols - 1;
    }
    int start() { return Math.min(anchor, focus); }
    int end() {
        int pos = Math.max(anchor, focus);
        return pos + width(Math.floorDiv(pos, cols), Math.floorMod(pos, cols)) - 1;
    }
    int startRow() { return Math.floorDiv(start(), cols); }
    int endRow() { return Math.floorDiv(end(), cols); }
    int startHandle() { return anchor <= focus ? 1 : 2; }
    int firstColumn(int row) { return row == startRow() ? Math.floorMod(start(), cols) : 0; }
    int lastColumn(int row) { return row == endRow() ? Math.floorMod(end(), cols) : cols - 1; }
    private int width(int row, int col) {
        int[] cells = lines.get(row);
        return cells == null ? 1 : Math.max(1, cells[col * 5 + 4]);
    }
    private int codePoint(int row, int col) {
        int[] cells = lines.get(row);
        if (cells == null) return ' ';
        int cp = cells[col * 5];
        return cp < 32 || !Character.isValidCodePoint(cp) || (cp >= 0xd800 && cp <= 0xdfff) ? ' ' : cp;
    }

    String text() {
        StringBuilder result = new StringBuilder();
        int firstRow = startRow(), lastRow = endRow();
        for (int r = firstRow; r <= lastRow; r++) {
            int[] cells = lines.get(r);
            StringBuilder line = new StringBuilder();
            if (cells != null) {
                for (int c = firstColumn(r); c <= lastColumn(r); c += width(r, c)) {
                    if (cells[c * 5] != -1 && cells[c * 5 + 4] != 0)
                        line.appendCodePoint(codePoint(r, c));
                }
            }
            while (line.length() > 0 && line.charAt(line.length() - 1) == ' ') line.setLength(line.length() - 1);
            if (r > firstRow) result.append('\n');
            result.append(line);
        }
        return result.toString();
    }
}

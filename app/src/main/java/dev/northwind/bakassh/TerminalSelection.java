package dev.northwind.bakassh;

/** A linear, inclusive text range over an immutable snapshot of visible cells. */
final class TerminalSelection {
    private final int[][] cells;
    final int cols, rows;
    private int anchor, focus;

    TerminalSelection(int cols, int[][] source) {
        this.cols = cols;
        rows = source.length;
        cells = new int[rows][];
        for (int r = 0; r < rows; r++) cells[r] = source[r].clone();
    }

    private int position(int row, int col) {
        row = Math.max(0, Math.min(rows - 1, row));
        col = Math.max(0, Math.min(cols - 1, col));
        // libvterm marks the second half of a wide glyph with UINT32_MAX.
        if (col > 0 && (cells[row][col * 5] == -1 || cells[row][col * 5 + 4] == 0)) col--;
        return row * cols + col;
    }

    void selectWord(int row, int col) {
        int pos = position(row, col), r = pos / cols, c = pos % cols;
        int kind = wordKind(codePoint(r, c));
        int left = c, right = c;
        while (left > 0) {
            int prev = position(r, left - 1) % cols;
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
    void selectAll() { anchor = 0; focus = rows * cols - 1; }
    int start() { return Math.min(anchor, focus); }
    int end() {
        int pos = Math.max(anchor, focus);
        return Math.min(rows * cols - 1, pos + width(pos / cols, pos % cols) - 1);
    }
    int startHandle() { return anchor <= focus ? 1 : 2; }
    int firstColumn(int row) { return row == start() / cols ? start() % cols : 0; }
    int lastColumn(int row) { return row == end() / cols ? end() % cols : cols - 1; }
    private int width(int row, int col) { return Math.max(1, cells[row][col * 5 + 4]); }
    private int codePoint(int row, int col) {
        int cp = cells[row][col * 5];
        return cp < 32 || !Character.isValidCodePoint(cp) || (cp >= 0xd800 && cp <= 0xdfff) ? ' ' : cp;
    }

    String text() {
        StringBuilder result = new StringBuilder();
        int firstRow = start() / cols, lastRow = end() / cols;
        for (int r = firstRow; r <= lastRow; r++) {
            StringBuilder line = new StringBuilder();
            for (int c = firstColumn(r); c <= lastColumn(r); c += width(r, c)) {
                if (cells[r][c * 5] != -1 && cells[r][c * 5 + 4] != 0)
                    line.appendCodePoint(codePoint(r, c));
            }
            while (line.length() > 0 && line.charAt(line.length() - 1) == ' ') line.setLength(line.length() - 1);
            if (r > firstRow) result.append('\n');
            result.append(line);
        }
        return result.toString();
    }
}

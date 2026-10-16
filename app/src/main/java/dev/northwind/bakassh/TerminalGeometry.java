package dev.northwind.bakassh;

/** Font metrics at 100px produce continuous cell sizes and an integer PTY grid. */
final class TerminalGeometry {
    static final float MIN_COLUMNS = 20f, MAX_COLUMNS = 400f, MIN_FONT_PX = 6f;
    final float preferredColumns, fontPx, cellW, cellH;
    final int cols, rows;

    static float maximumColumns(int width, float advance100) {
        return Math.max(2f, Math.min(MAX_COLUMNS, width * 100f / (MIN_FONT_PX * advance100)));
    }

    TerminalGeometry(int width, int height, float preferred, float advance100, float spacing100, int maxRows) {
        if (width <= 0 || height <= 0 || !Float.isFinite(preferred) ||
                advance100 <= 0 || spacing100 <= 0) throw new IllegalArgumentException("invalid geometry");
        float max = maximumColumns(width, advance100);
        preferredColumns = Math.max(Math.min(MIN_COLUMNS, max), Math.min(max, preferred));
        cellW = width / preferredColumns;
        fontPx = cellW * 100f / advance100;
        cellH = fontPx * spacing100 / 100f;
        cols = Math.max(2, (int) Math.floor(preferredColumns + 0.0001f));
        rows = Math.max(2, Math.min(maxRows, (int) Math.floor(height / cellH)));
    }
}

package dev.northwind.bakassh;

/**
 * 字形位图的像素几何回归：相邻格子的位图必须真实重叠。
 *
 * 旧实现把位图宽度取整成 round(cellW)、落点也各自取整；1080px / 150 列时
 * cellW = 7.2，位图宽 7，每 5 格落点就会多跳 1px —— 屏幕上是一条条 1px 竖缝
 * （制表线和块元素被切成虚线）。本回归同时证明旧算法确实会留缝，
 * 并验证新算法在 20–400 列 × 5 种视口宽下都有至少 1px 的重叠。
 */
public final class GlyphGeometryRegression {
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) {
        // 旧算法（无留白）：位图宽 round(cellW)，落点 Math.round(col*cellW)
        float legacyCellW = 1080f / 150f;
        int legacyGaps = 0;
        for (int col = 1; col < 150; col++) {
            int previousRight = Math.round((col - 1) * legacyCellW) + Math.round(legacyCellW);
            if (Math.round(col * legacyCellW) > previousRight) legacyGaps++;
        }
        check(legacyGaps > 0, "legacy geometry should be shown to leave gaps");

        int layouts = 0;
        for (int width : new int[]{240, 720, 1080, 1440, 2160}) {
            for (int columns = 20; columns <= 400; columns++) {
                float cellW = width / (float) columns;
                // 与 TerminalView 相同：Cascadia Mono 的 advance 是 1200/2048 em
                float fontPx = cellW * 100f / 58.594f;
                int pad = Math.max(2, (int) Math.ceil(fontPx * 0.06f));
                check(GlyphGeometry.overlapsNeighbour(cellW, pad),
                        "glyph bitmaps may leave a gap at width=" + width + " columns=" + columns);
                for (int col = 0; col < columns - 1; col++) {
                    float right = GlyphGeometry.bitmapLeft(col, cellW, pad)
                            + GlyphGeometry.bitmapWidth(1, cellW, pad);
                    float next = GlyphGeometry.bitmapLeft(col + 1, cellW, pad);
                    check(right > next + 1f, "adjacent glyphs only touch at column " + col
                            + " (width=" + width + " columns=" + columns + ")");
                }
                layouts++;
            }
        }
        System.out.println("PASS glyph bitmaps overlap by >1px over " + layouts
                + " layouts; legacy geometry left " + legacyGaps + " gaps at 150 columns");
    }
}

package dev.northwind.bakassh;

/**
 * 方块元素（U+2580–U+259F）的像素几何回归。
 *
 * 0.4.6 真机截图：59 列（cellW ≈ 20.1px）时 Claude Code 的像素 logo 有 1px 竖缝。
 * 位图留白保证了位图重叠，但方块字形的墨迹只覆盖字体步进本身，落点取整后跳 21px 的格子之间
 * 最右一列只有约 10% 覆盖。本回归先证明「墨迹 = 步进」时确实会出现低覆盖列，
 * 再验证整数边界绘制在各种视口宽 × 列数下逐像素无缝、无重叠，并核对 32 个字符的形状。
 */
public final class BlockElementsRegression {
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    /** 像素 [x, x+1) 被墨迹区间 [a, b) 覆盖的比例。 */
    private static float coverage(int x, float a, float b) {
        return Math.max(0f, Math.min(x + 1f, b) - Math.max((float) x, a));
    }

    public static void main(String[] args) {
        // 旧路径：每格字形落在 round(col*cellW)，墨迹宽 = cellW（小数）。统计相邻两格交界处覆盖不足一半的像素。
        float cellW = 1186f / 59f;
        int seams = 0;
        for (int col = 1; col < 59; col++) {
            float prevLeft = Math.round((col - 1) * cellW), left = Math.round(col * cellW);
            for (int x = (int) prevLeft; x < (int) left; x++) {
                if (coverage(x, prevLeft, prevLeft + cellW) < .5f) seams++;
            }
        }
        check(seams > 0, "font-ink blocks should be shown to leave low-coverage seams");

        // 新路径：整块按格子边界铺满，逐像素恰好覆盖一次。
        float[] rects = new float[4 * BlockElements.STRIDE];
        int layouts = 0;
        for (int width : new int[]{720, 1080, 1186, 1256, 1440, 2160}) {
            for (int columns = 20; columns <= 400; columns += 3) {
                float w = width / (float) columns;
                int[] hits = new int[width + 2];
                for (int col = 0; col < columns; col++) {
                    int n = BlockElements.rects(0x2588, rects);
                    check(n == 1, "full block must be one rect");
                    int x0 = BlockElements.edge(0f, col, 1, rects[0], w), x1 = BlockElements.edge(0f, col, 1, rects[2], w);
                    for (int x = x0; x < x1; x++) hits[x]++;
                    // 半块拼接：▌ 与 ▐ 合起来必须正好等于整块
                    BlockElements.rects(0x258C, rects);
                    int leftEnd = BlockElements.edge(0f, col, 1, rects[2], w);
                    BlockElements.rects(0x2590, rects);
                    int rightStart = BlockElements.edge(0f, col, 1, rects[0], w);
                    check(leftEnd == rightStart, "left / right halves split differently at column " + col);
                }
                int end = BlockElements.edge(0f, columns, 0, 0f, w);
                for (int x = 0; x < end; x++) {
                    check(hits[x] == 1, "pixel " + x + " covered " + hits[x] + " times (width=" + width
                            + " columns=" + columns + ")");
                }
                // 双宽格子与两个单宽格子的外边界一致
                check(BlockElements.edge(0f, 4, 2, 8f, w) == BlockElements.edge(0f, 5, 1, 8f, w), "wide block edge mismatch");
                layouts++;
            }
        }

        // 行方向：相邻行共享边界（行高同样是小数）
        for (float h : new float[]{17.3f, 21.7f, 38.05f}) {
            for (int row = 0; row < 300; row++) {
                check(BlockElements.edge(0f, row, 1, 8f, h) == BlockElements.edge(0f, row + 1, 1, 0f, h),
                        "row " + row + " does not abut the next at height " + h);
            }
        }

        // 形状：32 个码点都有定义，坐标在格子内；面积与 Unicode 定义一致（八分之一为单位）
        float[] expectedArea = {
                32, 8, 16, 24, 32, 40, 48, 56, 64,      // ▀ ▁ ▂ ▃ ▄ ▅ ▆ ▇ █
                56, 48, 40, 32, 24, 16, 8,              // ▉ ▊ ▋ ▌ ▍ ▎ ▏
                32, 64, 64, 64, 8, 8,                   // ▐ ░ ▒ ▓ ▔ ▕（阴影按整格面积，透明度另算）
                16, 16, 16, 48, 32, 48, 48, 16, 32, 48  // ▖ ▗ ▘ ▙ ▚ ▛ ▜ ▝ ▞ ▟
        };
        for (int cp = 0x2580; cp <= 0x259F; cp++) {
            check(BlockElements.handles(cp), "unhandled block element U+" + Integer.toHexString(cp));
            int n = BlockElements.rects(cp, rects);
            check(n >= 1 && n <= 3, "bad rect count for U+" + Integer.toHexString(cp));
            float area = 0;
            for (int i = 0; i < n; i++) {
                int o = i * BlockElements.STRIDE;
                check(rects[o] >= 0 && rects[o + 2] <= 8 && rects[o] < rects[o + 2]
                        && rects[o + 1] >= 0 && rects[o + 3] <= 8 && rects[o + 1] < rects[o + 3],
                        "rect outside cell for U+" + Integer.toHexString(cp));
                area += (rects[o + 2] - rects[o]) * (rects[o + 3] - rects[o + 1]);
            }
            check(area == expectedArea[cp - 0x2580], "wrong area for U+" + Integer.toHexString(cp) + ": " + area);
        }
        BlockElements.rects(0x2592, rects);
        check(rects[4] == .5f, "medium shade alpha");
        check(!BlockElements.handles(0x2500) && !BlockElements.handles(0x25A0), "range leaked");

        System.out.println("PASS block elements: legacy seams reproduced (" + seams + " px), "
                + layouts + " layouts seamless, row edges shared, 32 shapes");
    }
}

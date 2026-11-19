package dev.northwind.bakassh;

/**
 * U+2580–U+259F 方块元素按整数格子边界直接画矩形，不走字体字形。纯算术，主机侧回归可以直接调用。
 *
 * 为什么不用字形：方块字形的墨迹正好等于字体步进（小数，例如 20.1px），而格子落点各自取整；
 * 相邻两格起点有时差 21px，前一格最右一列只有约 10% 覆盖，屏幕上就是一条 1px 暗缝
 * （Claude Code 的像素 logo 最明显）。字形留白只能救本来就画出界的制表符，救不了恰好铺满的方块。
 *
 * 做法与 fbcon 的像素字体一致：所有边界都用同一个取整函数从「列号 + 八分之一」算出，
 * 相邻格子共享同一条整数边界，缝在数学上不会出现。
 */
final class BlockElements {
    private BlockElements() {}

    /** 每个矩形 5 个 float：[x0, y0, x1, y1, alpha]，坐标以格子的八分之一为单位，y 向下。 */
    static final int STRIDE = 5;
    private static final float[] UL = {0, 0, 4, 4}, UR = {4, 0, 8, 4}, LL = {0, 4, 4, 8}, LR = {4, 4, 8, 8};

    static boolean handles(int cp) { return cp >= 0x2580 && cp <= 0x259F; }

    /** 写入 cp 的覆盖矩形，返回个数（out 至少 4 × STRIDE）。 */
    static int rects(int cp, float[] out) {
        if (cp >= 0x2581 && cp <= 0x2588) return put(out, 0, 0, 8 - (cp - 0x2580), 8, 8, 1f);   // 下 1/8 … 全块
        if (cp >= 0x2589 && cp <= 0x258F) return put(out, 0, 0, 0, 8 - (cp - 0x2588), 8, 1f);   // 左 7/8 … 1/8
        switch (cp) {
            case 0x2580: return put(out, 0, 0, 0, 8, 4, 1f);        // ▀
            case 0x2590: return put(out, 0, 4, 0, 8, 8, 1f);        // ▐
            case 0x2591: return put(out, 0, 0, 0, 8, 8, .25f);      // ░
            case 0x2592: return put(out, 0, 0, 0, 8, 8, .5f);       // ▒
            case 0x2593: return put(out, 0, 0, 0, 8, 8, .75f);      // ▓
            case 0x2594: return put(out, 0, 0, 0, 8, 1, 1f);        // ▔
            case 0x2595: return put(out, 0, 7, 0, 8, 8, 1f);        // ▕
            case 0x2596: return quads(out, LL);
            case 0x2597: return quads(out, LR);
            case 0x2598: return quads(out, UL);
            case 0x2599: return quads(out, UL, LL, LR);
            case 0x259A: return quads(out, UL, LR);
            case 0x259B: return quads(out, UL, UR, LL);
            case 0x259C: return quads(out, UL, UR, LR);
            case 0x259D: return quads(out, UR);
            case 0x259E: return quads(out, UR, LL);
            case 0x259F: return quads(out, UR, LL, LR);
            default: return 0;
        }
    }

    /**
     * 格子内第 eighths/8 处的整数像素坐标。所有边界都经由这里取整，
     * 因此第 col 格的右边界（eighths = 8）与第 col + width 格的左边界（eighths = 0）完全相同。
     */
    static int edge(float origin, int cell, int cells, float eighths, float cellSize) {
        return Math.round(origin + (cell + cells * eighths / 8f) * cellSize);
    }

    private static int put(float[] out, int index, float x0, float y0, float x1, float y1, float alpha) {
        int o = index * STRIDE;
        out[o] = x0; out[o + 1] = y0; out[o + 2] = x1; out[o + 3] = y1; out[o + 4] = alpha;
        return index + 1;
    }

    private static int quads(float[] out, float[]... parts) {
        int n = 0;
        for (float[] q : parts) n = put(out, n, q[0], q[1], q[2], q[3], 1f);
        return n;
    }
}

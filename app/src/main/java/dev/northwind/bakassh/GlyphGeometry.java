package dev.northwind.bakassh;

/**
 * 字形位图的落点与尺寸。纯算术，主机侧回归可以直接调它，而不是另抄一份。
 *
 * 为什么需要留白：单元格宽是连续值（视口宽 / 列数，例如 1080/150 = 7.2px），
 * 而位图只能有整数宽度、落点也只能落在整数像素上。位图宽度按 round(cellW) 取整、
 * 落点再各自取整时，两者的舍入方向不一致就会在相邻两格之间空出 1px ——
 * 屏幕上表现为制表线、块元素和背景块被切成虚线，也就是「网格拼接有缝」。
 *
 * 做法：位图上下左右各多留 pad 像素，绘制时整体外移 pad。相邻格子的位图因此
 * 真正重叠（重叠量远大于 1px 的取整误差），缝在数学上不可能出现；
 * 同时制表符/块元素本来就故意画出格子边界，靠这份重叠才能连成一条不断的线。
 */
final class GlyphGeometry {
    private GlyphGeometry() {}

    /** 位图宽度：cells 个格子的内容宽 + 两侧留白。 */
    static int bitmapWidth(int cells, float cellW, int pad) {
        return Math.max(1, Math.round(cells * cellW)) + pad * 2;
    }

    /** 位图落点：格子左边界外移留白。 */
    static float bitmapLeft(int col, float cellW, int pad) {
        return col * cellW - pad;
    }

    /**
     * 相邻两格的字形位图是否必然重叠。
     * 未取整时两格落点相差 cellW、位图宽为 round(cellW)+2*pad，重叠量就是二者之差；
     * 再扣掉两条边各自最多 0.5px 的取整误差，仍然必须为正。
     */
    static boolean overlapsNeighbour(float cellW, int pad) {
        float overlap = Math.round(cellW) + pad * 2f - cellW;
        return overlap - 1f > 0f;
    }
}

package dev.northwind.bakassh;

public final class ZoomRegression {
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }
    private static void near(float a, float b) {
        check(Math.abs(a - b) < 0.001f, a + " != " + b);
    }
    private static TerminalGeometry geometry(float columns) {
        return new TerminalGeometry(1080, 1920, columns, 60, 120, 512);
    }
    public static void main(String[] args) {
        TerminalZoom zoom = new TerminalZoom();
        zoom.begin(150, 20, 300);
        zoom.takeFrame();
        zoom.scale(1.0002f);
        TerminalGeometry before = geometry(150), fractional = geometry(zoom.takeFrame());
        check(fractional.fontPx > before.fontPx && zoom.isActive(), "font must change before release");
        near(fractional.fontPx / before.fontPx, 1.0002f);
        near(geometry(zoom.finish(false)).fontPx, fractional.fontPx);
        System.out.println("PASS fractional live font size before release, without a release snap");

        zoom.begin(150, 20, 300);
        zoom.scale(1.1f); zoom.scale(1.2f); zoom.scale(0.9f);
        near(zoom.takeFrame(), 150f / (1.1f * 1.2f * 0.9f));
        check(Float.isNaN(zoom.takeFrame()), "only one layout per frame");
        System.out.println("PASS touch samples coalesce into one display-frame layout");

        zoom.scale(Float.MAX_VALUE);
        near(zoom.takeFrame(), 20);
        zoom.scale(0.9f);
        near(zoom.takeFrame(), 20f / .9f);
        zoom.scale(Float.MIN_VALUE);
        near(zoom.takeFrame(), 300);
        zoom.scale(1.1f);
        near(zoom.takeFrame(), 300f / 1.1f);
        zoom.scale(Float.NaN); zoom.scale(0); zoom.scale(-1);
        check(Float.isNaN(zoom.takeFrame()), "invalid detector samples must be ignored");
        System.out.println("PASS bounded zoom reverses immediately and ignores invalid samples");

        float current = zoom.columns();
        zoom.begin(200, 20, 300);
        near(zoom.columns(), current);
        near(zoom.finish(true), 150);
        check(!zoom.isActive() && Float.isNaN(zoom.takeFrame()), "cancelled zoom has no pending update");
        System.out.println("PASS pointer-pair restart preserves zoom; cancellation restores original size");

        for (int width : new int[]{240, 1080, 2160}) for (int columns = 20; columns <= 400; columns++) {
            TerminalGeometry g = new TerminalGeometry(width, 2400, columns, 60, 120, 512);
            check(g.fontPx >= 5.999f && g.rows <= 512, "geometry exceeds font/native limits");
            check(g.cols * g.cellW <= width + 0.01f, "whole cells exceed viewport");
            if (columns <= TerminalGeometry.maximumColumns(width, 60)) check(g.cols == columns, "integer column drift");
        }
        System.out.println("PASS exact integer columns and physical limits over 1143 layouts");

        PendingResize pending = new PendingResize();
        int scheduled = 0;
        for (int i = 0; i < 240; i++) if (pending.offer(80 + i, 24 + i)) scheduled++;
        check(scheduled == 1, "obsolete resizes must not enqueue writer tasks");
        int[] size = pending.poll();
        check(size[0] == 319 && size[1] == 263, "latest dimensions lost");
        check(!pending.offer(90, 40), "in-flight writer already owns the task");
        size = pending.poll();
        check(size[0] == 90 && size[1] == 40 && pending.poll() == null, "resize during write lost");
        check(pending.offer(91, 41), "idle dispatcher must restart");
        System.out.println("PASS latest resize wins before/during IO without lost wakeups");

        long h = NativeTerm.create(150, 80);
        try {
            zoom.begin(150, 20, 300);
            for (int frame = 0; frame < 300; frame++) {
                zoom.scale(frame < 150 ? 1.005f : 1f / 1.005f);
                TerminalGeometry g = geometry(zoom.takeFrame());
                NativeTerm.resize(h, g.cols, g.rows);
                byte[] text = "\033[1;1Hlive 中文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                NativeTerm.receive(h, text, text.length, bytes -> {});
                check(NativeTerm.getRow(h, g.rows - 1, new int[g.cols * 5]) == g.cols,
                        "native grid must match each displayed frame before release");
            }
            near(zoom.finish(false), 150);
        } finally { NativeTerm.destroy(h); }
        System.out.println("PASS 300 live resize/font frames through the real JNI terminal");
    }
}

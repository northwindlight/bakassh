package dev.northwind.bakassh;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/** Real MotionEvents + JNI + Canvas. Must run on Android, not inferred from compilation. */
final class InteractionViewRegression {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static float metric(TerminalView view, String name) throws Exception {
        Field field = TerminalView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getFloat(view);
    }
    private static void feed(long handle, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        NativeTerm.pushBytes(handle, bytes, bytes.length);
    }
    private static String output(long handle) {
        StringBuilder out = new StringBuilder();
        NativeTerm.drainOutput(handle, bytes -> out.append(new String(bytes, StandardCharsets.UTF_8)));
        return out.toString();
    }
    private static void touch(TerminalView view, long down, int action, int elapsed, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, down + elapsed, action, x, y, 0);
        try { view.onTouchEvent(event); } finally { event.recycle(); }
    }
    static void verify(Context context) throws Exception {
        TerminalView view = new TerminalView(context);
        view.setPreferredColumns(40);
        view.setGridListener((cols, rows) -> {
            if (view.getHandle() == 0) view.attachNative(NativeTerm.create(cols, rows));
            else NativeTerm.resize(view.getHandle(), cols, rows);
        });
        int[] taps = {0};
        view.setInputSink(new TerminalView.InputSink() {
            public void mouse(int r, int c, int b, boolean p) {
                NativeTerm.mouseMove(view.getHandle(), r, c, 0);
                NativeTerm.mouseButton(view.getHandle(), b, p, 0);
            }
            public void tap() { taps[0]++; }
            public void tap(int r, int c) { if (!NativeTerm.click(view.getHandle(), r, c)) tap(); }
            public void scroll(int r, int c, int lines) { NativeTerm.scroll(view.getHandle(), r, c, lines); }
        });
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 800, 600);
        Bitmap bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
        Bitmap latest = null;
        try {
            float cw = metric(view, "cellW"), ch = metric(view, "cellH");
            long down = SystemClock.uptimeMillis();
            touch(view, down, MotionEvent.ACTION_DOWN, 0, 6.5f * cw, 2.5f * ch);
            touch(view, down, MotionEvent.ACTION_UP, 40, 6.5f * cw, 2.5f * ch);
            check(taps[0] == 1 && output(view.getHandle()).isEmpty(), "shell tap behavior");
            feed(view.getHandle(), "\033[?1000h\033[?1006h");
            touch(view, down + 100, MotionEvent.ACTION_DOWN, 0, 6.5f * cw, 2.5f * ch);
            touch(view, down + 100, MotionEvent.ACTION_UP, 40, 6.5f * cw, 2.5f * ch);
            check(output(view.getHandle()).equals("\033[<0;7;3M\033[<0;7;3m") && taps[0] == 1,
                    "TUI tap must click without opening keyboard");
            touch(view, down + 200, MotionEvent.ACTION_DOWN, 0, 6.5f * cw, 2.5f * ch);
            touch(view, down + 200, MotionEvent.ACTION_MOVE, 50, 6.5f * cw, 6.5f * ch);
            touch(view, down + 200, MotionEvent.ACTION_UP, 100, 6.5f * cw, 6.5f * ch);
            String wheel = output(view.getHandle());
            check(wheel.contains("\033[<64;7;3M") && !wheel.contains(";7;7M"), "wheel crossed panes");
            touch(view, down + 400, MotionEvent.ACTION_DOWN, 0, cw, ch);
            touch(view, down + 400, MotionEvent.ACTION_CANCEL, 20, cw, ch);
            touch(view, down + 400, MotionEvent.ACTION_UP, 40, cw, ch);
            check(output(view.getHandle()).isEmpty() && taps[0] == 1, "cancel became click");
            feed(view.getHandle(), "\033[?1000l");
            for (int i = 0; i < view.getRows() + 20; i++) feed(view.getHandle(), "row-" + i + "\r\n");
            view.draw(new Canvas(bitmap));
            latest = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            touch(view, down + 500, MotionEvent.ACTION_DOWN, 0, cw, 2 * ch);
            touch(view, down + 500, MotionEvent.ACTION_MOVE, 100, cw, 6 * ch);
            touch(view, down + 500, MotionEvent.ACTION_UP, 500, cw, 6 * ch);
            check(NativeTerm.getScrollOffset(view.getHandle()) > 0, "shell gesture did not scroll locally");
            check(output(view.getHandle()).isEmpty(), "shell gesture sent terminal input");
            view.draw(new Canvas(bitmap));
            check(!bitmap.sameAs(latest), "history gesture left cached live pixels");
            view.followOutput();
            check(NativeTerm.getScrollOffset(view.getHandle()) == 0, "follow output button action");
            view.draw(new Canvas(bitmap));
            check(bitmap.sameAs(latest), "follow output did not restore live pixels");
        } finally { if (latest != null) latest.recycle(); bitmap.recycle(); view.destroy(); }
    }
}

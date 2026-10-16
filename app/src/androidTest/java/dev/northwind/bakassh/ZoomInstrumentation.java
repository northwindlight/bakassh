package dev.northwind.bakassh;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android View/font/MotionEvent regression. No third-party test runner needed. */
public final class ZoomInstrumentation extends Instrumentation {
    private long eventTime = 1000;
    private int mouseEvents, taps, commits;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { verify(); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) {
            result.putString("stream", android.util.Log.getStackTraceString(failure.get()));
            finish(Activity.RESULT_CANCELED, result);
        } else {
            result.putString("stream", "PASS live font rendering, per-frame native grids, pointer transitions, cancellation\n");
            finish(Activity.RESULT_OK, result);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Object field(Object instance, String name) throws Exception {
        Field f = instance.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(instance);
    }
    private void touch(TerminalView view, int action, int count, float span) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = i == 0 ? 540 - span / 2 : 540 + span / 2;
            coords[i].y = 600;
            coords[i].pressure = coords[i].size = 1;
        }
        eventTime += 16;
        MotionEvent event = MotionEvent.obtain(1000, eventTime, action, count, props, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { view.onTouchEvent(event); } finally { event.recycle(); }
    }

    private void begin(TerminalView view) {
        touch(view, MotionEvent.ACTION_DOWN, 1, 200);
        touch(view, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 200);
    }
    private static void layout(TerminalView view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, 1200);
    }

    private void verify() throws Exception {
        TerminalView view = new TerminalView(getTargetContext());
        view.setGridListener((cols, rows) -> {
            if (view.getHandle() == 0) view.attachNative(NativeTerm.create(cols, rows));
            else NativeTerm.resize(view.getHandle(), cols, rows);
        });
        view.setInputSink(new TerminalView.InputSink() {
            public void mouse(int r, int c, int b, boolean p) { mouseEvents++; }
            public void tap() { taps++; }
        });
        view.setZoomListener(columns -> commits++);
        Bitmap bitmap = Bitmap.createBitmap(1080, 1200, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        try {
            layout(view, 1080);
            byte[] content = "\033[1;1Hlive 中文 frame".getBytes(StandardCharsets.UTF_8);
            NativeTerm.receive(view.getHandle(), content, content.length, bytes -> {});
            view.draw(canvas);
            float initialFont = (float) field(view, "fontPx");
            int initialCols = view.getCols();
            int glyphs = ((Map<?, ?>) field(view, "glyphCache")).size();
            begin(view);
            for (int frame = 0; frame < 20; frame++) {
                touch(view, MotionEvent.ACTION_MOVE, 2, 250 + frame * 10);
                view.draw(canvas);
                check(NativeTerm.getRow(view.getHandle(), view.getRows() - 1,
                        new int[view.getCols() * 5]) == view.getCols(), "native grid lags a frame");
            }
            float liveFont = (float) field(view, "fontPx");
            check(liveFont > initialFont && view.getCols() < initialCols, "font/grid only changed after release");
            check(((Map<?, ?>) field(view, "glyphCache")).size() == glyphs, "bitmap glyphs rebuilt during pinch");
            check(commits == 0, "preferences committed during pinch");
            touch(view, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 440);
            touch(view, MotionEvent.ACTION_MOVE, 1, 800);
            check(commits == 0, "first lifted finger prematurely committed zoom");
            touch(view, MotionEvent.ACTION_UP, 1, 800);
            view.draw(canvas);
            check(Math.abs((float) field(view, "fontPx") - liveFont) < .001f, "font snapped on release");
            check(commits == 1 && mouseEvents == 0 && taps == 0, "pinch leaked into mouse/tap or duplicate commit");

            begin(view);
            touch(view, MotionEvent.ACTION_MOVE, 2, 300);
            touch(view, MotionEvent.ACTION_MOVE, 2, 400);
            view.draw(canvas);
            touch(view, MotionEvent.ACTION_CANCEL, 2, 400);
            view.draw(canvas);
            check(Math.abs((float) field(view, "fontPx") - liveFont) < .001f && commits == 1,
                    "cancel committed a detector end callback");

            begin(view);
            touch(view, MotionEvent.ACTION_MOVE, 2, 300);
            touch(view, MotionEvent.ACTION_MOVE, 2, 400);
            view.draw(canvas);
            layout(view, 1000);
            touch(view, MotionEvent.ACTION_UP, 1, 400);
            view.draw(canvas);
            check(commits == 1 && mouseEvents == 0 && taps == 0, "viewport change committed a stale pinch");
        } finally { view.destroy(); bitmap.recycle(); }
    }
}

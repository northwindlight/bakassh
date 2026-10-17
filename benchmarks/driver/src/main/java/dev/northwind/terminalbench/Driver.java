package dev.northwind.terminalbench;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Injects identical on-device input across apps without changing their code. */
public final class Driver extends Instrumentation {
    private Bundle args;
    private long down;
    private android.app.UiAutomation automation;
    private java.lang.reflect.Method inject;
    private Object inputManager;
    @Override public void onCreate(Bundle arguments) { args = arguments; super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            automation = getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
            automation.adoptShellPermissionIdentity("android.permission.INJECT_EVENTS");
            Class<?> managerClass = Class.forName("android.hardware.input.InputManager");
            inputManager = managerClass.getMethod("getInstance").invoke(null);
            inject = managerClass.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            down = SystemClock.uptimeMillis();
            motion(MotionEvent.ACTION_CANCEL, 1, 628, 800, 0);
            SystemClock.sleep(300);
            android.os.Trace.beginSection("terminalbench:" + args.getString("label", args.getString("action", "pinch")));
            String action = args.getString("action", "pinch");
            if (action.equals("text")) {
                KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                        .getEvents(args.getString("text", "").toCharArray());
                if (events == null) throw new AssertionError("text cannot be encoded");
                for (KeyEvent event : events) automation.injectInputEvent(event, true);
            } else {
                float cx = Float.parseFloat(args.getString("x", "628"));
                float cy = Float.parseFloat(args.getString("y", "800"));
                float start = Float.parseFloat(args.getString("span", "650"));
                int duration = Integer.parseInt(args.getString("duration", "6000"));
                long begin = SystemClock.uptimeMillis();
                down = begin;
                if (action.equals("wait")) {
                    SystemClock.sleep(duration);
                } else if (action.equals("pinch")) {
                    float finalFactor = Float.parseFloat(args.getString("factor", "NaN"));
                    float lastSpan = start;
                    motion(MotionEvent.ACTION_DOWN, 1, cx - start / 2, cy, 0);
                    motion(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, cx, cy, start);
                    while (SystemClock.uptimeMillis() - begin < duration) {
                        double t = (SystemClock.uptimeMillis() - begin) / 1000.0;
                        float span = Float.isNaN(finalFactor)
                                ? start * (1f + .45f * (float) ((1 - Math.cos(t * Math.PI)) / 2))
                                : start * (float) Math.pow(finalFactor, Math.min(1.0, t * 1000 / duration));
                        lastSpan = span;
                        motion(MotionEvent.ACTION_MOVE, 2, cx, cy, span);
                        SystemClock.sleep(16);
                    }
                    motion(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, cx, cy, lastSpan);
                    motion(MotionEvent.ACTION_UP, 1, cx - lastSpan / 2, cy, 0);
                } else if (action.equals("swipe")) {
                    int stroke = 0;
                    while (SystemClock.uptimeMillis() - begin < duration) {
                        // Alternate to avoid measuring an unmoving scrollback boundary.
                        float direction = (stroke++ % 2 == 0) ? -1 : 1;
                        android.os.Trace.beginSection("terminalbench:stroke");
                        down = SystemClock.uptimeMillis();
                        motion(MotionEvent.ACTION_DOWN, 1, cx, cy - direction * 400, 0);
                        for (int step = 1; step <= 60; step++) {
                            motion(MotionEvent.ACTION_MOVE, 1, cx, cy - direction * 400 + direction * step * 800f / 60, 0);
                            SystemClock.sleep(8);
                        }
                        motion(MotionEvent.ACTION_UP, 1, cx, cy + direction * 400, 0);
                        android.os.Trace.endSection();
                        SystemClock.sleep(150);
                    }
                } else throw new AssertionError("unknown action");
            }
            android.os.Trace.endSection();
            result.putString("stream", "INPUT_OK\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable t) {
            result.putString("stream", android.util.Log.getStackTraceString(t));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void motion(int action, int count, float cx, float cy, float span) throws Exception {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties(); props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = count == 1 ? cx : cx + (i == 0 ? -span / 2 : span / 2);
            coords[i].y = cy; coords[i].pressure = coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            if (!(Boolean) inject.invoke(inputManager, event, 0) && action != MotionEvent.ACTION_CANCEL) throw new AssertionError("input injection failed");
        } finally { event.recycle(); }
    }
}

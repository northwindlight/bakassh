package dev.northwind.bakassh;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Widget gesture cancellation; repeat timing itself is covered with virtual time on the host. */
final class KeyRepeatViewRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void touch(RepeatKeyButton button, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try { button.onTouchEvent(event); } finally { event.recycle(); }
    }

    static void verify(Context base) {
        RepeatKeyButton button = new RepeatKeyButton(new android.view.ContextThemeWrapper(base, R.style.TermTheme));
        button.layout(0, 0, 160, 80);
        int[] clicks = {0};
        button.setOnClickListener(v -> clicks[0]++);
        try {
            touch(button, MotionEvent.ACTION_DOWN, 30, 30);
            check(button.isRepeating() && clicks[0] == 1, "touch down must send immediately");
            touch(button, MotionEvent.ACTION_UP, 30, 30);
            check(!button.isRepeating() && clicks[0] == 1, "release must not send another click");

            touch(button, MotionEvent.ACTION_DOWN, 30, 30);
            touch(button, MotionEvent.ACTION_MOVE, 200, 30);
            check(!button.isRepeating() && !button.isPressed(), "drag outside did not stop");
            touch(button, MotionEvent.ACTION_MOVE, 30, 30);
            touch(button, MotionEvent.ACTION_UP, 30, 30);
            check(clicks[0] == 2, "dragging back revived a cancelled press");

            touch(button, MotionEvent.ACTION_DOWN, 30, 30);
            touch(button, MotionEvent.ACTION_CANCEL, 30, 30);
            check(!button.isRepeating(), "cancel retained repeat");
            touch(button, MotionEvent.ACTION_DOWN, 30, 30);
            button.setVisibility(View.GONE);
            check(!button.isRepeating(), "hidden key retained repeat");
            button.setVisibility(View.VISIBLE);
            touch(button, MotionEvent.ACTION_DOWN, 30, 30);
            button.setEnabled(false);
            check(!button.isRepeating(), "disabled key retained repeat");
            button.setEnabled(true);
            touch(button, MotionEvent.ACTION_DOWN, 30, 30);
            button.onWindowFocusChanged(false);
            check(!button.isRepeating(), "window focus loss retained repeat");

            touch(button, MotionEvent.ACTION_DOWN, 30, 30);
            int before = clicks[0];
            button.stopRepeating(); // Same cancellation used by session switches and Activity.onPause.
            touch(button, MotionEvent.ACTION_UP, 30, 30);
            check(clicks[0] == before && !button.isRepeating(), "session cancellation leaked a release click");
            button.performClick();
            check(clicks[0] == before + 1 && !button.isRepeating(), "accessibility click started a held key");
        } finally { button.stopRepeating(); }
    }
}

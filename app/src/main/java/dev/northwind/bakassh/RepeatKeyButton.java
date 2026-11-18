package dev.northwind.bakassh;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.google.android.material.button.MaterialButton;

/** A direction key: touch-down sends once, release never sends an extra click. */
final class RepeatKeyButton extends MaterialButton {
    private final KeyRepeater repeater;
    private int pointerId = -1;

    RepeatKeyButton(Context context) {
        super(context);
        Handler handler = new Handler(Looper.getMainLooper());
        repeater = new KeyRepeater(new KeyRepeater.Scheduler() {
            @Override public void postDelayed(Runnable task, long delay) { handler.postDelayed(task, delay); }
            @Override public void removeCallbacks(Runnable task) { handler.removeCallbacks(task); }
        }, () -> {
            if (isEnabled() && getVisibility() == VISIBLE && isPressed()) performClick();
            else stopRepeating();
        }, ViewConfiguration.getKeyRepeatTimeout(), ViewConfiguration.getKeyRepeatDelay());
        setSoundEffectsEnabled(false);
    }

    void stopRepeating() {
        if (repeater != null) repeater.stop();
        pointerId = -1;
        setPressed(false);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    boolean isRepeating() { return repeater != null && repeater.isRepeating(); }

    // KeyRepeater routes both the initial touch and repeats through performClick().
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || !isClickable()) {
            stopRepeating();
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopRepeating();
                pointerId = event.getPointerId(0);
                drawableHotspotChanged(event.getX(), event.getY());
                setPressed(true);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                repeater.press();
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointerId);
                if (index < 0 || event.getX(index) < 0 || event.getX(index) >= getWidth() ||
                        event.getY(index) < 0 || event.getY(index) >= getHeight()) stopRepeating();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointerId) stopRepeating();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopRepeating();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public void setEnabled(boolean enabled) {
        if (!enabled) stopRepeating();
        super.setEnabled(enabled);
    }

    /** onVisibilityChanged 只在已附着窗口时分发；直接隐藏按键也要停止连发。 */
    @Override public void setVisibility(int visibility) {
        if (visibility != VISIBLE) stopRepeating();
        super.setVisibility(visibility);
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        if (!hasWindowFocus) stopRepeating();
        super.onWindowFocusChanged(hasWindowFocus);
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        if (visibility != VISIBLE) stopRepeating();
        super.onVisibilityChanged(changedView, visibility);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        if (visibility != VISIBLE) stopRepeating();
        super.onWindowVisibilityChanged(visibility);
    }

    @Override protected void onDetachedFromWindow() {
        stopRepeating();
        super.onDetachedFromWindow();
    }
}

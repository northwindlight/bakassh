package dev.northwind.bakassh;

/** Continuous font zoom. Input samples are combined until the next display frame. */
final class TerminalZoom {
    private boolean active, pending;
    private float columns, original, minimum, maximum;

    boolean isActive() { return active; }
    float columns() { return columns; }

    void begin(float current, float min, float max) {
        if (active) return; // another pointer pair in the same touch gesture
        minimum = min;
        maximum = max;
        columns = original = Math.max(min, Math.min(max, current));
        active = true;
        pending = true;
    }

    void scale(float factor) {
        if (!active || !Float.isFinite(factor) || factor <= 0) return;
        // Clamp each input sample so reversing direction at a limit is immediate.
        columns = Math.max(minimum, Math.min(maximum, columns / factor));
        pending = true;
    }

    float takeFrame() {
        if (!pending) return Float.NaN;
        pending = false;
        return columns;
    }

    float finish(boolean cancelled) {
        if (cancelled) columns = original;
        active = pending = false;
        return columns;
    }
}

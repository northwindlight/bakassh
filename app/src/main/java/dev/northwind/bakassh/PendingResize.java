package dev.northwind.bakassh;

/** At most one writer task; newer frame sizes replace unsent sizes. */
final class PendingResize {
    private int[] pending;
    private boolean scheduled;

    synchronized boolean offer(int cols, int rows) {
        pending = new int[]{cols, rows};
        if (scheduled) return false;
        scheduled = true;
        return true;
    }

    synchronized int[] poll() {
        int[] result = pending;
        pending = null;
        if (result == null) scheduled = false;
        return result;
    }
}

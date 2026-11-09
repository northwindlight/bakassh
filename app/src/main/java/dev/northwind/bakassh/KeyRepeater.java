package dev.northwind.bakassh;

/** One immediate key, followed by delayed repeats until the press is cancelled. */
final class KeyRepeater {
    interface Scheduler {
        void postDelayed(Runnable task, long delayMillis);
        void removeCallbacks(Runnable task);
    }

    private final Scheduler scheduler;
    private final Runnable action;
    private final long initialDelay, repeatDelay;
    private Runnable pending;

    KeyRepeater(Scheduler scheduler, Runnable action, long initialDelay, long repeatDelay) {
        if (initialDelay < 0 || repeatDelay <= 0) throw new IllegalArgumentException("Invalid repeat timing");
        this.scheduler = scheduler;
        this.action = action;
        this.initialDelay = initialDelay;
        this.repeatDelay = repeatDelay;
    }

    void press() {
        stop();
        Runnable task = new Runnable() {
            @Override public void run() {
                if (pending != this) return;
                action.run();
                if (pending == this) scheduler.postDelayed(this, repeatDelay);
            }
        };
        pending = task;
        action.run();
        if (pending == task) scheduler.postDelayed(task, initialDelay);
    }

    void stop() {
        Runnable task = pending;
        pending = null;
        if (task != null) scheduler.removeCallbacks(task);
    }

    boolean isRepeating() { return pending != null; }
}

package dev.northwind.bakassh;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.PriorityQueue;

/** Virtual time exercises the production repeater without sleeping or Android. */
public final class KeyRepeatRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class Clock implements KeyRepeater.Scheduler {
        private static final class Entry {
            final Runnable task;
            final long time;
            Entry(Runnable task, long time) { this.task = task; this.time = time; }
        }
        final PriorityQueue<Entry> tasks = new PriorityQueue<>(Comparator.comparingLong(e -> e.time));
        long now;
        public void postDelayed(Runnable task, long delay) { tasks.add(new Entry(task, now + delay)); }
        public void removeCallbacks(Runnable task) { tasks.removeIf(e -> e.task == task); }
        void advance(long millis) {
            long end = now + millis;
            while (!tasks.isEmpty() && tasks.peek().time <= end) {
                Entry next = tasks.remove(); now = next.time; next.task.run();
            }
            now = end;
        }
    }

    private static void hold() {
        Clock clock = new Clock(); int[] calls = {0};
        KeyRepeater repeat = new KeyRepeater(clock, () -> calls[0]++, 400, 50);
        repeat.press();
        check(calls[0] == 1, "press did not send immediately");
        clock.advance(399);
        check(calls[0] == 1, "tap began repeating too soon");
        clock.advance(1);
        check(calls[0] == 2, "first repeat timing");
        clock.advance(150);
        check(calls[0] == 5, "repeat interval");
        repeat.stop(); clock.advance(1000);
        check(calls[0] == 5 && clock.tasks.isEmpty(), "release left repeating work");
    }

    private static void shortTap() {
        Clock clock = new Clock(); int[] calls = {0};
        KeyRepeater repeat = new KeyRepeater(clock, () -> calls[0]++, 400, 50);
        repeat.press(); clock.advance(100); repeat.stop(); repeat.stop(); clock.advance(2000);
        check(calls[0] == 1 && !repeat.isRepeating(), "short tap or cancellation sent extra keys");
    }

    private static void restart() {
        Clock clock = new Clock(); int[] calls = {0};
        KeyRepeater repeat = new KeyRepeater(clock, () -> calls[0]++, 400, 50);
        repeat.press();
        Runnable stale = clock.tasks.peek().task;
        clock.advance(100); repeat.press(); stale.run();
        check(calls[0] == 2 && clock.tasks.size() == 1, "old press joined a new press");
        clock.advance(399); check(calls[0] == 2, "restart did not reset long-press delay");
        clock.advance(1); check(calls[0] == 3, "restarted key did not repeat");
        repeat.stop(); stale.run(); clock.advance(1000);
        check(calls[0] == 3, "stale callback revived stopped key");
    }

    private static void cancelDuringCallback() {
        Clock clock = new Clock(); int[] calls = {0}; KeyRepeater[] holder = new KeyRepeater[1];
        holder[0] = new KeyRepeater(clock, () -> {
            if (++calls[0] == 2) holder[0].stop();
        }, 400, 50);
        holder[0].press(); clock.advance(2000);
        check(calls[0] == 2 && clock.tasks.isEmpty(), "callback scheduled more keys after cancellation");
    }

    private static void restartDuringCallback() {
        Clock clock = new Clock(); int[] calls = {0}; KeyRepeater[] holder = new KeyRepeater[1];
        holder[0] = new KeyRepeater(clock, () -> {
            if (++calls[0] == 2) holder[0].press();
        }, 400, 50);
        holder[0].press(); clock.advance(400);
        check(calls[0] == 3 && clock.tasks.size() == 1, "reentrant press left duplicate repeat chains");
        clock.advance(399); check(calls[0] == 3, "old interval ran during replacement press");
        clock.advance(1); check(calls[0] == 4, "replacement press failed to repeat");
        holder[0].stop();
    }

    private static void directions() {
        String[] keys = {"UP", "DOWN", "LEFT", "RIGHT"}, suffix = {"A", "B", "D", "C"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            check(TerminalKeys.isDirection(key.toLowerCase(java.util.Locale.ROOT)), "direction not repeatable: " + key);
            long handle = NativeTerm.create(20, 4);
            try {
                byte[] mode = "\033[?1h".getBytes(StandardCharsets.US_ASCII);
                NativeTerm.pushBytes(handle, mode, mode.length);
                StringBuilder output = new StringBuilder(); Clock clock = new Clock();
                KeyRepeater repeat = new KeyRepeater(clock, () -> {
                    NativeTerm.keyboardKey(handle, TerminalKeys.code(key), 0);
                    NativeTerm.drainOutput(handle, bytes -> output.append(new String(bytes, StandardCharsets.US_ASCII)));
                }, 400, 50);
                repeat.press(); clock.advance(500); repeat.stop(); clock.advance(1000);
                String expected = "\033O" + suffix[i];
                check(output.toString().equals(expected + expected + expected + expected),
                        "repeat lost direction/application cursor encoding: " + key);
            } finally { NativeTerm.destroy(handle); }
        }
        for (String key : new String[]{"TAB", "ENTER", "BKSP", "CTRL", "PGUP", "u"})
            check(!TerminalKeys.isDirection(key), "non-direction repeats: " + key);
    }

    public static void main(String[] args) {
        hold(); System.out.println("PASS direction hold sends once immediately and repeats at the configured delay");
        shortTap(); System.out.println("PASS short tap and release/cancel never send an extra key");
        restart(); System.out.println("PASS rapid restart rejects old queued callbacks");
        cancelDuringCallback(); System.out.println("PASS cancellation during key delivery stops scheduling");
        restartDuringCallback(); System.out.println("PASS replacing a press during delivery keeps one repeat chain");
        directions(); System.out.println("PASS all four directions repeat with real JNI application cursor encoding");
    }
}

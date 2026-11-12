package dev.northwind.bakassh;

import java.util.Arrays;

/** Verifies fixture meaning and byte-fragmentation invariance on the real JNI engine. */
public final class ReplayRegression {
    private static void feed(long handle, byte[] data, boolean fragmented) {
        if (!fragmented) { NativeTerm.pushBytes(handle, data, data.length); return; }
        int[] lengths = {1, 2, 7, 31, 128, 4096};
        int at = 0, chunk = 0;
        while (at < data.length) {
            int end = Math.min(data.length, at + lengths[chunk++ % lengths.length]);
            byte[] bytes = Arrays.copyOfRange(data, at, end);
            NativeTerm.pushBytes(handle, bytes, bytes.length);
            at = end;
        }
    }

    private static String run(TerminalWorkload workload, int frames, boolean fragmented) throws Exception {
        long handle = NativeTerm.create(workload.cols, workload.rows);
        try {
            feed(handle, workload.setup(), fragmented);
            String initial = TerminalWorkload.cellsHash(handle, workload.cols, workload.rows);
            int[] header = TerminalWorkload.row(handle, 0, workload.cols);
            int[] footer = TerminalWorkload.row(handle, workload.rows - 1, workload.cols);
            for (int i = 0; i < frames; i++) {
                feed(handle, workload.frame(i), fragmented);
                // Same consumer limits as TerminalView: also exercise accumulated move/damage events.
                NativeTerm.takeMoveOps(handle, new int[128 * 8]);
                NativeTerm.takeDamageRows(handle, new int[NativeTerm.MAX_ROWS]);
            }
            String hash = TerminalWorkload.cellsHash(handle, workload.cols, workload.rows);
            if (workload.scenario.equals("unchanged") != hash.equals(initial))
                throw new AssertionError("workload did not produce the intended cell changes: " + workload.scenario);
            if (workload.scenario.equals("region") &&
                    (!Arrays.equals(header, TerminalWorkload.row(handle, 0, workload.cols)) ||
                     !Arrays.equals(footer, TerminalWorkload.row(handle, workload.rows - 1, workload.cols))))
                throw new AssertionError("fixed header/footer changed");
            if (workload.scenario.equals("scroll") && NativeTerm.getHistorySize(handle) != frames)
                throw new AssertionError("scroll frame did not advance history once");
            if (workload.scenario.equals("rewrite") || workload.scenario.equals("scroll") || workload.scenario.equals("region")) {
                String expected = String.format(java.util.Locale.ROOT, "%04d:", frames);
                int[] last = TerminalWorkload.row(handle, workload.scenario.equals("region") ? workload.rows - 2 : workload.rows - 1, workload.cols);
                for (int c = 0; c < expected.length(); c++)
                    if (last[c * 5] != expected.charAt(c)) {
                        StringBuilder actual = new StringBuilder();
                        for (int j = 0; j < 9; j++) actual.append((char) last[j * 5]);
                        int[] cursor = new int[4]; NativeTerm.getCursor(handle, cursor);
                        throw new AssertionError("missing final frame text: " + workload.scenario + " " +
                                workload.cols + "x" + workload.rows + " expected=" + expected + " actual=" + actual +
                                " first=" + last[0] + " cursor=" + Arrays.toString(cursor));
                    }
            }
            return hash;
        } finally { NativeTerm.destroy(handle); }
    }

    public static void main(String[] args) throws Exception {
        int frames = args.length == 0 ? 12 : Integer.parseInt(args[0]);
        if (frames < 1 || frames > 9999) throw new IllegalArgumentException("frames must be 1–9999");
        int[][] grids = {{80, 24}, {154, 134}, {335, 307}};
        for (int[] grid : grids) for (String scenario : TerminalWorkload.SCENARIOS) {
            TerminalWorkload workload = new TerminalWorkload(scenario, grid[0], grid[1]);
            String hash = run(workload, frames, false);
            if (!hash.equals(run(workload, frames, true))) throw new AssertionError("byte fragmentation changed final cells");
            System.out.println("{\"check\":\"PASS\",\"scenario\":\"" + scenario + "\",\"columns\":" + grid[0] +
                    ",\"rows\":" + grid[1] + ",\"frames\":" + frames + ",\"input_sha256\":\"" + workload.inputHash(frames) +
                    "\",\"final_cells_sha256\":\"" + hash + "\"}");
        }
    }
}

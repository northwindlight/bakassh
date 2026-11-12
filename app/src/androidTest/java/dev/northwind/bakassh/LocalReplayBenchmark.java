package dev.northwind.bakassh;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Trace;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Offscreen software-Canvas baseline. Timings are synchronous elapsed time, not presentation FPS. */
final class LocalReplayBenchmark {
    private final ZoomInstrumentation instrumentation;
    LocalReplayBenchmark(ZoomInstrumentation instrumentation) { this.instrumentation = instrumentation; }

    void run(Bundle args) {
        Bundle result = new Bundle();
        try {
            int cols = integer(args, "columns", 154, 16, 400), rows = integer(args, "rows", 134, 4, 512);
            int width = integer(args, "width", 1186, 128, 4096);
            int frames = integer(args, "frames", 60, 1, 2000), warmup = integer(args, "warmup", 30, 0, 500);
            String selected = args.getString("scenario", "all");
            String[] scenarios = selected.equals("all") ? TerminalWorkload.SCENARIOS : new String[]{selected};
            JSONArray reports = new JSONArray();
            for (String scenario : scenarios) reports.put(measure(new TerminalWorkload(scenario, cols, rows), width, warmup, frames));
            Context target = instrumentation.getTargetContext();
            android.content.pm.PackageInfo app = target.getPackageManager().getPackageInfo(target.getPackageName(), 0);
            JSONObject report = new JSONObject().put("format", "bakassh-local-replay-software-v1")
                    .put("presentation_measured", false).put("gpu_upload_measured", false).put("ssh_used", false)
                    .put("app_version", app.versionName).put("app_version_code", app.versionCode)
                    .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("build_fingerprint", android.os.Build.FINGERPRINT)
                    .put("results", reports);
            String json = report.toString(2);
            File file = new File(instrumentation.getTargetContext().getCacheDir(), "local-replay.json");
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(json.getBytes(StandardCharsets.UTF_8)); }
            result.putString("stream", json + "\nSoftware elapsed-time baseline only; no display FPS measured. Report: " + file + "\n");
            instrumentation.finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", android.util.Log.getStackTraceString(failure));
            instrumentation.finish(Activity.RESULT_CANCELED, result);
        }
    }

    private JSONObject measure(TerminalWorkload workload, int width, int warmup, int frames) throws Exception {
        State state = new State();
        long[] parse = new long[frames], draw = new long[frames];
        long[] counters = new long[2];
        try {
            onUi(() -> {
                SessionService service = SessionService.current();
                if (service != null) for (TerminalSession session : service.sessions())
                    if (session.isActive()) throw new IllegalStateException("Use a test process with no active SSH sessions for an isolated CPU baseline");
                Context theme = new android.view.ContextThemeWrapper(instrumentation.getTargetContext(), R.style.TermTheme);
                state.view = new TerminalView(theme);
                state.view.setPreferredColumns(workload.cols);
                state.view.setGridListener((c, r) -> {
                    if (state.view.getHandle() == 0) state.view.attachNative(NativeTerm.create(c, r));
                    else NativeTerm.resize(state.view.getHandle(), c, r);
                });
                layout(state.view, width, 1000);
                int height = (int) Math.ceil(((Number) field(state.view, "cellH")).floatValue() * workload.rows);
                layout(state.view, width, height);
                if (state.view.getCols() != workload.cols || state.view.getRows() != workload.rows)
                    throw new AssertionError("requested grid does not fit at this width: " + state.view.getGridInfo());
                // A layout matching the View's initial 80×24 grid need not notify the listener.
                if (state.view.getHandle() == 0)
                    state.view.attachNative(NativeTerm.create(workload.cols, workload.rows));
                state.bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                state.canvas = new Canvas(state.bitmap);
                byte[] bytes = workload.setup();
                NativeTerm.receive(state.view.getHandle(), bytes, bytes.length, ignored -> {});
                state.view.draw(state.canvas);
            });
            for (int frame = 0; frame < warmup + frames; frame++) {
                final int index = frame;
                byte[] bytes = workload.frame(frame); // Generate bytes outside the measured UI work.
                onUi(() -> {
                    if (index == warmup) {
                        counters[0] = counter(state.view, "rasterizedRows");
                        counters[1] = counter(state.view, "blittedRows");
                    }
                    Trace.beginSection("bakaSSH.replay.parse");
                    long started = System.nanoTime();
                    try { NativeTerm.receive(state.view.getHandle(), bytes, bytes.length, ignored -> {}); }
                    finally { Trace.endSection(); }
                    long parsed = System.nanoTime() - started;
                    Trace.beginSection("bakaSSH.replay.draw-software");
                    started = System.nanoTime();
                    try { state.view.draw(state.canvas); }
                    finally { Trace.endSection(); }
                    long drawn = System.nanoTime() - started;
                    if (index >= warmup) { parse[index - warmup] = parsed; draw[index - warmup] = drawn; }
                });
            }
            AtomicReference<JSONObject> result = new AtomicReference<>();
            String inputHash = workload.inputHash(warmup + frames);
            onUi(() -> {
                if (counter(state.view, "renderErrors") != 0) throw new AssertionError("renderer swallowed an exception");
                MessageDigest image = MessageDigest.getInstance("SHA-256");
                int[] pixels = new int[width];
                for (int row = 0; row < state.bitmap.getHeight(); row++) {
                    state.bitmap.getPixels(pixels, 0, width, 0, row, width, 1);
                    for (int pixel : pixels) TerminalWorkload.updateInt(image, pixel);
                }
                result.set(new JSONObject().put("scenario", workload.scenario)
                        .put("fixture", TerminalWorkload.VERSION).put("input_sha256", inputHash)
                        .put("columns", workload.cols).put("rows", workload.rows)
                        .put("width_px", width).put("height_px", state.bitmap.getHeight())
                        .put("font_px", ((Number) field(state.view, "fontPx")).doubleValue())
                        .put("cell_width_px", ((Number) field(state.view, "cellW")).doubleValue())
                        .put("cell_height_px", ((Number) field(state.view, "cellH")).doubleValue())
                        .put("warmup_frames", warmup).put("measured_frames", frames)
                        .put("total_workload_frames", warmup + frames)
                        .put("parse_elapsed_ms", timings(parse)).put("draw_software_elapsed_ms", timings(draw))
                        .put("rasterized_rows", counter(state.view, "rasterizedRows") - counters[0])
                        .put("blitted_rows", counter(state.view, "blittedRows") - counters[1])
                        .put("final_cells_sha256", TerminalWorkload.cellsHash(state.view.getHandle(), workload.cols, workload.rows))
                        .put("final_bitmap_argb_sha256", TerminalWorkload.hex(image.digest()))
                        .put("grid_info", state.view.getGridInfo()));
            });
            return result.get();
        } finally {
            onUi(() -> {
                if (state.view != null) state.view.destroy();
                if (state.bitmap != null) state.bitmap.recycle();
            });
        }
    }

    private static final class State { TerminalView view; Bitmap bitmap; Canvas canvas; }
    private interface UiTask { void run() throws Exception; }
    private void onUi(UiTask task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { task.run(); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError("local replay failed", failure.get());
    }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = TerminalView.class.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static long counter(TerminalView view, String name) throws Exception { return ((Number) field(view, name)).longValue(); }
    private static int integer(Bundle args, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(args.getString(key, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be " + min + "–" + max);
        return value;
    }
    private static JSONObject timings(long[] values) throws Exception {
        long[] sorted = values.clone(); Arrays.sort(sorted);
        double sum = 0; for (long value : sorted) sum += value;
        return new JSONObject().put("mean", sum / sorted.length / 1e6)
                .put("p50", sorted[(int) Math.ceil((sorted.length - 1) * .50)] / 1e6)
                .put("p95", sorted[(int) Math.ceil((sorted.length - 1) * .95)] / 1e6)
                .put("p99", sorted[(int) Math.ceil((sorted.length - 1) * .99)] / 1e6);
    }
}

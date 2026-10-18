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
    private Bundle arguments;
    private long eventTime = 1000;
    private int mouseEvents, taps, commits;

    @Override public void onCreate(Bundle args) { arguments = args; super.onCreate(args); start(); }

    @Override public void onStart() {
        if (arguments != null && "matched-grid".equals(arguments.getString("mode"))) {
            new MatchedGridBenchmark(this).run(arguments);
            return;
        }
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
    private void touch(TerminalView view, int action, int count, float multiplier) {
        float baseSpan = Math.max(view.getWidth() * .45f,
                android.view.ViewConfiguration.get(view.getContext()).getScaledMinimumScalingSpan() * 1.1f);
        float span = baseSpan * multiplier;
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
        touch(view, MotionEvent.ACTION_DOWN, 1, 1f);
        touch(view, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 1f);
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
                touch(view, MotionEvent.ACTION_MOVE, 2, 1.05f + frame * .02f);
                view.draw(canvas);
                check(NativeTerm.getRow(view.getHandle(), view.getRows() - 1,
                        new int[view.getCols() * 5]) == view.getCols(), "native grid lags a frame");
            }
            float liveFont = (float) field(view, "fontPx");
            check(liveFont > initialFont && view.getCols() < initialCols, "font/grid only changed after release");
            check(((Map<?, ?>) field(view, "glyphCache")).size() == glyphs, "bitmap glyphs rebuilt during pinch");
            check(commits == 0, "preferences committed during pinch");
            touch(view, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 1.43f);
            touch(view, MotionEvent.ACTION_MOVE, 1, 1.43f);
            check(commits == 0, "first lifted finger prematurely committed zoom");
            touch(view, MotionEvent.ACTION_UP, 1, 1.43f);
            view.draw(canvas);
            check(Math.abs((float) field(view, "fontPx") - liveFont) < .001f, "font snapped on release");
            check(commits == 1 && mouseEvents == 0 && taps == 0, "pinch leaked into mouse/tap or duplicate commit");

            begin(view);
            touch(view, MotionEvent.ACTION_MOVE, 2, 1.2f);
            touch(view, MotionEvent.ACTION_MOVE, 2, 1.4f);
            view.draw(canvas);
            touch(view, MotionEvent.ACTION_CANCEL, 2, 1.4f);
            view.draw(canvas);
            check(Math.abs((float) field(view, "fontPx") - liveFont) < .001f && commits == 1,
                    "cancel committed a detector end callback");

            begin(view);
            touch(view, MotionEvent.ACTION_MOVE, 2, 1.2f);
            touch(view, MotionEvent.ACTION_MOVE, 2, 1.4f);
            view.draw(canvas);
            layout(view, 1000);
            touch(view, MotionEvent.ACTION_UP, 1, 1.4f);
            view.draw(canvas);
            check(commits == 1 && mouseEvents == 0 && taps == 0, "viewport change committed a stale pinch");

            // Key and Host authentication regressions
            verifyKeys();
        } finally { view.destroy(); bitmap.recycle(); }
    }
    /** 私有测试目录和首选项；不能让缩放验收修改手机里的真实密钥。 */
    private void verifyKeys() throws Exception {
        String suffix = "key-regression-" + System.nanoTime();
        java.io.File directory = new java.io.File(getTargetContext().getCacheDir(), suffix);
        check(directory.mkdirs(), "create isolated key directory");
        android.content.Context context = new android.content.ContextWrapper(getTargetContext()) {
            @Override public java.io.File getFilesDir() { return directory; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(suffix + name, mode);
            }
        };
        try {
            check(KeyManager.getKeys(context).isEmpty(), "opening key list must not generate keys");
            check(KeyManager.getDefaultKey(context) == null, "fresh install has no identity");
            KeyItem generated = KeyManager.generateKey(context, "test-rsa", "RSA-2048", " pass phrase ", "test");
            check(generated.isDefault, "first explicit identity becomes default");
            check(KeyManager.getPrivFile(context, generated.id).exists(), "generated key exists");
            String pem;
            try (java.io.FileInputStream in = new java.io.FileInputStream(KeyManager.getPrivFile(context, generated.id))) {
                pem = new String(PrivateKeyImport.read(in), StandardCharsets.UTF_8);
            }
            int initialCount = KeyManager.getKeys(context).size();
            try {
                KeyManager.importKey(context, "bad", pem, "pass phrase");
                throw new AssertionError("trimmed password accepted");
            } catch (IllegalArgumentException expected) {
                check(KeyManager.getKeys(context).size() == initialCount, "failed import changes no metadata");
            }
            KeyItem imported = KeyManager.importKey(context, "imported", pem, " pass phrase ");
            check(imported.encrypted, "stored private key must remain marked encrypted");
            check(generated.publicKey.split(" ")[1].equals(imported.publicKey.split(" ")[1]), "public key matches");
            check(KeyManager.getKey(context, "missing-id") == null, "missing explicit key must not fall back silently");
            KeyManager.setDefaultKey(context, imported.id);
            check(imported.id.equals(KeyManager.getKey(context, "default").id), "follow default identity");
            Host host = new Host("test", "127.0.0.1", 22, "root", "", "publickey", imported.id, "");
            check(imported.id.equals(Host.fromJson(host.toJson()).keyId), "host key binding roundtrip");
            Host passwordHost = new Host("test", "127.0.0.1", 22, "root", "", "password", "default", " password ");
            check(" password ".equals(Host.fromJson(passwordHost.toJson()).password), "host password roundtrip");
            check(KeyManager.deleteKey(context, generated.id), "delete generated key");
            check(KeyManager.deleteKey(context, imported.id), "allow deleting last identity");
            check(KeyManager.getDefaultKey(context) == null, "empty list has no default");
            java.io.File legacy = new java.io.File(directory, "sshterm_id_rsa");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(legacy)) {
                out.write(pem.getBytes(StandardCharsets.UTF_8));
            }
            KeyItem migrated = KeyManager.getDefaultKey(context);
            check(migrated != null && migrated.encrypted, "migrate encrypted legacy key without public file");
            try (java.io.FileInputStream in = new java.io.FileInputStream(legacy)) {
                check(pem.equals(new String(PrivateKeyImport.read(in), StandardCharsets.UTF_8)), "migration must preserve existing private key");
            }
        } finally {
            getTargetContext().deleteSharedPreferences(suffix + "sshterm");
            deleteTestDirectory(directory);
        }
    }

    private static void deleteTestDirectory(java.io.File directory) {
        java.io.File[] files = directory.listFiles();
        if (files != null) for (java.io.File file : files) {
            if (file.isDirectory()) deleteTestDirectory(file);
            else file.delete();
        }
        directory.delete();
    }

}

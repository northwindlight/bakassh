package dev.northwind.bakassh;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Pixel assertions using real Android fonts and Canvas; compile alone is not a device pass. */
final class RenderingRegression {
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void feed(long handle, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        NativeTerm.receive(handle, bytes, bytes.length, reply -> { });
    }
    static void verify(Context context) throws Exception {
        TerminalView view = new TerminalView(context);
        view.setPreferredColumns(40);
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 800, 600);
        long first = NativeTerm.create(view.getCols(), view.getRows());
        long second = NativeTerm.create(view.getCols(), view.getRows());
        Bitmap frame = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
        try {
            view.attachSession(first);
            feed(first, "\033[2J\033[H中A\033[H");
            view.draw(new Canvas(frame));
            float cellW = (float) field(view, "cellW"), cellH = (float) field(view, "cellH");
            int sampleY = Math.max(1, (int) (cellH * .08f));
            check(frame.getPixel((int) (cellW * .5f), sampleY) == 0xffffffff, "cursor is hollow");
            check(frame.getPixel((int) (cellW * 1.5f), sampleY) == 0xffffffff, "CJK cursor is only one cell wide");
            check(frame.getPixel((int) (cellW * 2.5f), sampleY) != 0xffffffff, "CJK cursor exceeds two cells");
            feed(first, "\033[1;2H");
            view.draw(new Canvas(frame));
            check(frame.getPixel((int) (cellW * .5f), sampleY) == 0xffffffff &&
                    frame.getPixel((int) (cellW * 1.5f), sampleY) == 0xffffffff, "trailing-cell cursor splits CJK glyph");
            feed(first, "\033[1;3H");
            view.draw(new Canvas(frame));
            check(frame.getPixel((int) (cellW * 2.5f), sampleY) == 0xffffffff &&
                    frame.getPixel((int) (cellW * 3.5f), sampleY) != 0xffffffff, "ASCII cursor is not one cell wide");
            feed(first, "\033[2J\033[H中\033[Hi\033[H");
            view.draw(new Canvas(frame));
            check(frame.getPixel((int) (cellW * .5f), sampleY) == 0xffffffff &&
                    frame.getPixel((int) (cellW * 1.5f), sampleY) != 0xffffffff, "ASCII replacement inherited CJK cursor width");

            Method glyph = TerminalView.class.getDeclaredMethod("glyphFor", int.class, int.class, int.class, int.class);
            glyph.setAccessible(true);
            Paint paint = (Paint) field(view, "textPaint");
            for (int cp : new int[]{'i', 'W', 'f', 0xe9, 0x2192, 0x4e2d}) {
                for (int style = 0; style < 4; style++) {
                    int width = cp == 0x4e2d ? 2 : 1;
                    Bitmap expected = (Bitmap) glyph.invoke(view, cp, width, style, 0xaabbcc);
                    // Simulate state left by live zoom, a CJK cursor, or another style.
                    paint.setTextSize(100);
                    paint.setTextScaleX(3);
                    paint.setTextSkewX(.5f);
                    paint.setFakeBoldText(true);
                    Bitmap actual = (Bitmap) glyph.invoke(view, cp, width, style, 0x112233);
                    check(paint.getTextScaleX() <= 1f, "glyph stretched beyond natural width");
                    check(Math.abs(paint.getTextSize() - (float) field(view, "fontPx")) < .001f, "stale zoom font size");
                    for (int y = 0; y < actual.getHeight(); y++) {
                        for (int x = 0; x < actual.getWidth(); x++) {
                            check((expected.getPixel(x, y) >>> 24) == (actual.getPixel(x, y) >>> 24),
                                    "glyph depends on previous Paint state: " + cp + "/" + style);
                        }
                    }
                }
            }

            feed(first, "\033[2J\033[HAAA\033[?25l");
            feed(second, "\033[2J\033[HBBB\033[?25l");
            view.attachSession(first);
            view.draw(new Canvas(frame));
            int[] firstPixels = new int[800 * 600], returnedPixels = new int[800 * 600];
            frame.getPixels(firstPixels, 0, 800, 0, 0, 800, 600);
            view.attachSession(second);
            view.draw(new Canvas(frame));
            frame.getPixels(returnedPixels, 0, 800, 0, 0, 800, 600);
            check(!java.util.Arrays.equals(firstPixels, returnedPixels), "session switch reused stale row cache");
            view.attachSession(first);
            view.draw(new Canvas(frame));
            frame.getPixels(returnedPixels, 0, 800, 0, 0, 800, 600);
            check(java.util.Arrays.equals(firstPixels, returnedPixels), "switching back lost terminal content");
            view.destroy();
            check(NativeTerm.getRow(first, 0, new int[view.getCols() * 5]) == view.getCols(),
                    "destroying a View destroyed the service terminal");
        } finally {
            view.attachSession(0);
            view.destroy();
            NativeTerm.destroy(first);
            NativeTerm.destroy(second);
            frame.recycle();
        }
    }
}

package dev.northwind.bakassh;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

/**
 * 行位图缓存渲染器。
 *
 * 核心思想（也是我们实测出来的结论）：终端渲染的本质是「字符→像素」**一次**，
 * 之后滚动只是搬运已经光栅化好的像素。所以：
 *   - 每一行只在内容变化时光栅化一次，存成一张位图（行位图缓存）
 *   - libvterm 的 moverect 事件 → 只把「屏幕行 → 槽位」的映射搬一下（不碰像素）
 *   - 它的 damage 事件（SCROLL 档位下只覆盖新暴露的行）→ 只重光栅化那几行
 *   - 每帧绘制 = rows 次 drawBitmap（实测 76 次 blit ≈ 0.25ms），与格数无关
 *
 * 对照 Termux：它每帧无条件重画全部 11,704 格、逐非 ASCII 字符 measureText，
 * 实测整屏 33ms —— 那是「每帧重新排版整份文档」，不是终端渲染。
 */
public class TerminalView extends View {

    private static final String TAG = "sshterm-view";

    /** 位图槽位比屏幕行多留一些，滚动时进出的行可以复用槽位。 */
    private static final int SLOT_EXTRA = 64;
    /** 单个槽位位图相对 cellH 多留的高度余量（防止字形被裁）。 */
    private static final float ROW_BITMAP_SLACK = 1.15f;

    private long handle;
    private int cols, rows;

    private float cellW, cellH, baseline, textSize;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint cursorPaint = new Paint();
    private final Paint rowBgPaint = new Paint();

    private int defaultFg = 0xFFD0D0D0, defaultBg = 0xFF101014;

    // 行位图缓存
    private Bitmap[] slots;
    private boolean[] slotValid;
    private int[] screenToSlot;   // 长度 rows
    private int[] tmpMap;
    private int slotCount, rowBitmapH;
    private int[] freeStack;
    private int freeTop;

    // JNI 批量缓冲
    private int[] rowBuf;         // 每行 cols*5
    private int[] cellOne = new int[5];
    private int[] cursorOut = new int[4];
    private int[] moveOps = new int[8 * 128];
    private int[] damageRows = new int[512];

    // 统计（可复核：写进日志）
    private int frameCount;
    private long rasterizedRows, blittedRows;
    private long accDrawNs, accRasterNs;
    private long lastLogNs;
    private long framesSinceLog;
    private long droppedMoves;
    private int renderErrors;
    private boolean curBold, curUl, curItalic, curStrike;
    private int dbgRows;

    /** 网格由字号算出，而不是写死。 */
    public interface GridListener {
        void onGridChanged(int cols, int rows);
    }

    private GridListener gridListener;
    /** 默认字号（px）。手机上大致 27px 对应 ~50 列，和 Termux 观感接近。 */
    private float fontPx = 27f;
    private float latinAdvance100, fontSpacing100;
    private final TerminalZoom zoom = new TerminalZoom();
    private boolean geometryPending, bitmapGeometryDirty;

    public void setGridListener(GridListener l) {
        this.gridListener = l;
        if (l != null && slots != null) l.onGridChanged(cols, rows);
    }

    public void setFontPx(float px) {
        if (px > 0 && getWidth() > 0) setPreferredColumns(getWidth() * 100f / (px * latinAdvance100));
    }

    public TerminalView(Context context) {
        this(context, null);
    }

    /** XML 布局膨胀入口：缺这个构造函数会直接 InflateException 闪退。 */
    public TerminalView(Context context, android.util.AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TerminalView(Context context, android.util.AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        loadFonts(context);
        textPaint.setTypeface(latinTf);
        textPaint.setTextSize(100f);
        latinAdvance100 = Math.max(1f, textPaint.measureText("X"));
        fontSpacing100 = Math.max(1f, textPaint.getFontSpacing());
        bgPaint.setColor(defaultBg);
        rowBgPaint.setColor(defaultBg);
        cursorPaint.setColor(0xFFFFFFFF);

        // 占位值：真正的网格在 onSizeChanged 里由字号算出来
        cols = 80;
        rows = 24;
        slotCount = rows + SLOT_EXTRA;
        screenToSlot = new int[rows];
        tmpMap = new int[rows];
        Arrays.fill(screenToSlot, -1);

        selPaint.setColor(0x5533AAFF);

        scaleDetector = new android.view.ScaleGestureDetector(context,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                        clearSelection();
                        float max = TerminalGeometry.maximumColumns(getWidth(), latinAdvance100);
                        zoom.begin(targetColumns, Math.min(TerminalGeometry.MIN_COLUMNS, max), max);
                        postInvalidateOnAnimation();
                        return true;
                    }

                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector detector) {
                        zoom.scale(detector.getScaleFactor());
                        postInvalidateOnAnimation();
                        return true;
                    }

                    // Pointer-up may end one detector segment while fingers are
                    // still down. Commit only when the whole touch gesture ends.
                });
        scaleDetector.setQuickScaleEnabled(false);
        scaleDetector.setStylusScaleEnabled(false);
    }

    public void attachNative(long handle) {
        this.handle = handle;
        invalidateAllRows();
        postInvalidateOnAnimation();
    }

    public int getCols() { return cols; }
    public int getRows() { return rows; }
    public long getHandle() { return handle; }

    /** 输入回调：滚动/点击产生的鼠标事件、以及“轻点”交给会话层（转发远端 / 弹键盘）。 */
    public interface InputSink {
        void mouse(int row, int col, int button, boolean pressed);
        void tap();
    }

    private InputSink inputSink;

    public void setInputSink(InputSink s) { this.inputSink = s; }

    /**
     * 目标列数：列数定死 → 字号由列数反解 → 行数随可用高度自适应（真终端的行为）。
     * 捏合缩放直接改这个值（和 Termux 调字号是一回事），并持久化到 HostStore。
     */
    private float targetColumns = 150f;

    /** 选择与缩放的回调（主界面据此显示"复制/全选/粘贴/取消"条）。 */
    public interface SelectionListener { void onSelection(boolean active, boolean hasRange); }
    public interface ZoomListener { void onZoomed(float columns); }

    private SelectionListener selListener;
    private ZoomListener zoomListener;
    private TerminalSelection selection;
    private Bitmap selectionSnapshot;
    private boolean selActive;
    /** 正在拖哪个把手：0 无、1 起点、2 终点。 */
    private int handleDrag;
    /** 本次手势是否就是"启动选择"的那次：是的话抬手不能当成点击取消。 */
    private boolean gestureStartedSelection;
    private final Paint selPaint = new Paint();
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.view.ScaleGestureDetector scaleDetector;

    public void setSelectionListener(SelectionListener l) { this.selListener = l; }
    public void setZoomListener(ZoomListener l) { this.zoomListener = l; }

    public void setTargetCols(int cols) { setPreferredColumns(cols); }

    public void setPreferredColumns(float columns) {
        if (!Float.isFinite(columns)) return;
        cancelZoom();
        float next = Math.max(TerminalGeometry.MIN_COLUMNS, Math.min(TerminalGeometry.MAX_COLUMNS, columns));
        if (Math.abs(next - targetColumns) < 0.0001f) return;
        targetColumns = next;
        clearSelection();
        if (getWidth() > 0 && getHeight() > 0) updateGeometry(true);
        if (zoomListener != null) zoomListener.onZoomed(targetColumns);
        postInvalidateOnAnimation();
    }

    public void scaleFontBy(float factor) {
        if (Float.isFinite(factor) && factor > 0) setPreferredColumns(targetColumns / factor);
    }

    private void finishZoom(boolean cancelled) {
        if (!zoom.isActive()) return;
        targetColumns = zoom.finish(cancelled);
        geometryPending = true;
        if (!cancelled && zoomListener != null) zoomListener.onZoomed(targetColumns);
        postInvalidateOnAnimation();
    }

    private void cancelZoom() { finishZoom(true); }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            removeCallbacks(longPress);
            touchPressed = false;
            touchSuppressed = true;
            cancelZoom();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(longPress);
        touchPressed = false;
        touchSuppressed = true;
        cancelZoom();
        super.onDetachedFromWindow();
    }

    // ---------------- 位图字体（字形位图缓存）----------------
    /*
     * 思路取自 fbcon / WT 的 AtlasEngine：字形预渲染成位图块，绘制时只做 blit，
     * 位置只由列号算出（col*cellW），**永不累加字体步进**。
     * 这样"字宽度会变"在结构上不可能发生 —— 每格宽度就是指定的 width*cellW。
     * 拉丁用 WT 的 Cascadia Mono，CJK 用 Noto Sans Mono CJK（自带宽高关系，缩放接近 1）。
     */
    private Typeface latinTf, latinTfBold, cjkTf, cjkTfBold;
    private final java.util.HashMap<Long, Bitmap> glyphCache = new java.util.HashMap<>();
    private int glyphEvictions;
    private final char[] cpBuf = new char[2];
    private final Paint decoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 单元格宽 / 字体自然步进；=1 表示完全不用横向缩放。 */
    private float asciiScale = 1f;
    private float baseAdvance = 1f;
    private int glyphCount;
    private final java.util.HashMap<Long, Float> naturalAdvances = new java.util.HashMap<>();
    private char[] liveRun = new char[0];

    private void loadFonts(Context ctx) {
        try {
            latinTf = Typeface.createFromAsset(ctx.getAssets(), "fonts/CascadiaMono.ttf");
        } catch (Throwable t) {
            Log.e(TAG, "load CascadiaMono failed, fallback MONOSPACE", t);
            latinTf = Typeface.MONOSPACE;
        }
        try {
            cjkTf = Typeface.createFromAsset(ctx.getAssets(), "fonts/NotoSansMonoCJKsc-Regular.otf");
        } catch (Throwable t) {
            Log.e(TAG, "load NotoSansMonoCJK failed, fallback to latin", t);
            cjkTf = latinTf;
        }
        latinTfBold = Typeface.create(latinTf, Typeface.BOLD);
        cjkTfBold = Typeface.create(cjkTf, Typeface.BOLD);
        decoPaint.setStyle(Paint.Style.FILL);
    }

    private void clearGlyphCache() {
        for (Bitmap b : glyphCache.values()) if (b != null) b.recycle();
        glyphCache.clear();
    }

    /** 取（或生成）字形位图：宽度恰好 width*cellW、高度 rowBitmapH，已用 fg 画好。 */
    private Bitmap glyphFor(int cp, int width, int style, int fg) {
        /*
         * key 必须把颜色放到高位！之前写成
         *   (cp<<24) | (width<<21) | (style<<18) | (fg & 0xFFFFFF)
         * 颜色占 bit0-23、width/style 塞在 bit18-23 —— 直接重叠，不同格子会撞到同一个 key，
         * 于是屏幕上出现成片用错字形的杂点。
         */
        long key = ((long) cp << 40) | ((long) (width & 7) << 37) | ((long) (style & 7) << 34)
                | (fg & 0xFFFFFFL);
        Bitmap b = glyphCache.get(key);
        if (b != null) return b;
        if (glyphCache.size() > 6000) {
            glyphEvictions++;
            clearGlyphCache();
        }
        int gw = Math.max(1, Math.round(width * cellW));
        int gh = Math.max(1, rowBitmapH);
        b = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(b);
        boolean bold = (style & 1) != 0;
        textPaint.setTypeface(width >= 2 ? (bold ? cjkTfBold : cjkTf) : (bold ? latinTfBold : latinTf));
        textPaint.setFakeBoldText(false);          // 用真粗体字形，不靠 fake
        textPaint.setTextSkewX((style & 2) != 0 ? -0.25f : 0f);
        textPaint.setTextScaleX(1f);
        textPaint.setColor(fg | 0xFF000000);
        int nch = Character.toChars(cp, cpBuf, 0);
        float adv = textPaint.measureText(cpBuf, 0, nch);
        float target = width * cellW;
        if (adv > 0.05f) textPaint.setTextScaleX(target / adv);   // 每格宽度严格一致
        gc.drawText(cpBuf, 0, nch, 0f, baseline, textPaint);
        glyphCache.put(key, b);
        return b;
    }

    public String getGridInfo() {
        return "cols=" + targetColumns + " grid=" + cols + "x" + rows
                + " cell=" + String.format(Locale.US, "%.2fx%.2f", cellW, cellH)
                + " font=" + String.format(Locale.US, "%.1f", fontPx)
                + " cellW/advance=" + String.format(Locale.US, "%.3f", asciiScale)
                + " glyphs=" + glyphCache.size() + "/" + glyphCount
                + " evict=" + glyphEvictions;
    }

    // ---------------- 选择 ----------------

    public boolean isSelecting() { return selActive; }

    private void captureSelection() {
        finishZoom(false);
        if (geometryPending) updateGeometry(true);
        else if (bitmapGeometryDirty) rebuildRowCache();
        synchronized (NativeTerm.class) {
            int[][] cells = new int[rows][cols * 5];
            for (int r = 0; r < rows; r++) NativeTerm.getRow(handle, r, cells[r]);
            selection = new TerminalSelection(cols, cells);
            selectionSnapshot = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            drawFrame(new Canvas(selectionSnapshot));
        }
    }

    private void startSelection(int row, int col) {
        if (handle == 0 || getWidth() <= 0 || getHeight() <= 0) return;
        captureSelection();
        selection.selectWord(row, col);
        selActive = true;
        gestureStartedSelection = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        notifySelection();
        invalidate();
    }

    private void moveSelectionHandle(int row, int col) {
        if (selection == null) return;
        if (handleDrag == 1) selection.moveAnchor(row, col);
        else selection.moveFocus(row, col);
        notifySelection();
        invalidate();
    }

    public void selectAll() {
        if (handle == 0) return;
        if (!selActive) captureSelection();
        selection.selectAll();
        selActive = true;
        notifySelection();
        invalidate();
    }

    public void clearSelection() {
        if (!selActive) return;
        selActive = false;
        selection = null;
        if (selectionSnapshot != null) { selectionSnapshot.recycle(); selectionSnapshot = null; }
        notifySelection();
        invalidate();
    }

    private void notifySelection() {
        if (selListener != null) selListener.onSelection(selActive, selActive && selection.start() != selection.end());
    }

    public String getSelectionText() {
        return selActive && selection != null ? selection.text() : "";
    }

    public void getSelectionBounds(Rect rect) {
        if (!selActive || selection == null) { rect.set(0, 0, getWidth(), 1); return; }
        int first = selection.start() / cols, last = selection.end() / cols;
        rect.set((int) (selection.firstColumn(first) * cellW), (int) (first * cellH),
                (int) ((selection.lastColumn(first) + 1) * cellW),
                Math.min(getHeight(), (int) ((last + 1) * cellH)));
    }

    private float lastTouchY, scrollAcc;
    private boolean touchPressed, touchMoved, touchSuppressed, touchSelecting;
    private float touchDownX, touchDownY;
    private final Runnable longPress = () -> {
        if (touchPressed && !touchMoved && !touchSuppressed && !selActive) {
            int row = Math.max(0, Math.min(rows - 1, (int) (touchDownY / cellH)));
            int col = Math.max(0, Math.min(cols - 1, (int) (touchDownX / cellW)));
            startSelection(row, col);
            handleDrag = 2;
        }
    };

    /** Local selection consumes its entire gesture. Only a confirmed single
     * finger scroll produces remote wheel events; cancel/pinch never become taps. */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (inputSink == null || handle == 0) return super.onTouchEvent(e);
        int action = e.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPress);
            touchPressed = false;
            handleDrag = 0;
            scrollAcc = 0;
            touchSuppressed = true;
            cancelZoom();
            if (scaleDetector != null) scaleDetector.onTouchEvent(e);
            invalidate();
            return true;
        }
        if (action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            removeCallbacks(longPress);
            touchSuppressed = true;
            handleDrag = 0;
            scrollAcc = 0;
            clearSelection();
        }
        if (scaleDetector != null) scaleDetector.onTouchEvent(e);
        if (action == android.view.MotionEvent.ACTION_UP) finishZoom(false);
        if (e.getPointerCount() > 1 || (scaleDetector != null && scaleDetector.isInProgress())) {
            removeCallbacks(longPress);
            touchSuppressed = true;
            handleDrag = 0;
            scrollAcc = 0;
            clearSelection();
            return true;
        }
        final int row = Math.max(0, Math.min(rows - 1, (int) (e.getY() / cellH)));
        final int col = Math.max(0, Math.min(cols - 1, (int) (e.getX() / cellW)));
        final float slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        switch (action) {
            case android.view.MotionEvent.ACTION_DOWN:
                touchPressed = true;
                touchMoved = touchSuppressed = false;
                touchSelecting = selActive;
                lastTouchY = touchDownY = e.getY();
                touchDownX = e.getX();
                scrollAcc = 0;
                gestureStartedSelection = false;
                handleDrag = 0;
                if (selActive) {
                    float radius = 24f * getResources().getDisplayMetrics().density;
                    int start = selection.start(), end = selection.end();
                    float sx = (start % cols) * cellW, sy = (start / cols + 1) * cellH;
                    float ex = (end % cols + 1) * cellW, ey = (end / cols + 1) * cellH;
                    double ds = Math.hypot(e.getX() - sx, e.getY() - sy);
                    double de = Math.hypot(e.getX() - ex, e.getY() - ey);
                    if (Math.min(ds, de) <= radius)
                        handleDrag = ds <= de ? selection.startHandle() : 3 - selection.startHandle();
                } else postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
                return true;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (touchSuppressed) return true;
                boolean wasMoved = touchMoved;
                if (Math.hypot(e.getX() - touchDownX, e.getY() - touchDownY) > slop) {
                    touchMoved = true;
                    removeCallbacks(longPress);
                }
                if (selActive || touchSelecting || gestureStartedSelection) {
                    if (selActive && handleDrag != 0 && touchMoved) moveSelectionHandle(row, col);
                    return true;
                }
                float dy = e.getY() - lastTouchY;
                lastTouchY = e.getY();
                if (!touchMoved) return true;
                scrollAcc += wasMoved ? dy : e.getY() - touchDownY;
                while (scrollAcc >= cellH) { inputSink.mouse(row, col, 4, true); scrollAcc -= cellH; }
                while (scrollAcc <= -cellH) { inputSink.mouse(row, col, 5, true); scrollAcc += cellH; }
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                touchPressed = false;
                if (!touchSuppressed && !touchMoved && !gestureStartedSelection) {
                    if (selActive && handleDrag == 0) clearSelection();
                    else if (!selActive && !touchSelecting) inputSink.tap();
                }
                handleDrag = 0;
                scrollAcc = 0;
                gestureStartedSelection = false;
                return true;
            default:
                return true;
        }
    }

    /**
     * 数据到达后必须显式驱动渲染 —— 我第一版忘了这一步，结果喂了 3.75MB 只画了 2 帧。
     * 用 postInvalidateOnAnimation：合并到下一次 vsync，不用每个字节都要求重绘。
     * （这正是 Termux 的反面：它把重绘交给 IO 分片，我们把它交给 vsync。）
     */
    public void notifyNewData() {
        postInvalidateOnAnimation();
    }

    // ---------------- 几何 ----------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);

        /*
         * 高度/宽度为 0 时直接早退。
         * 实测踩到过：按键栏把终端挤成 0 高度 → 这里算出 1 行 → 建了个 73x1 的终端并把远端 PTY
         * 也设成 73x1，屏幕上只剩一片黑，还留下一个非法的原生句柄。
         */
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "onSizeChanged skipped: w=" + w + " h=" + h);
            return;
        }

        cancelZoom();
        touchSuppressed = true;
        updateGeometry(true);
    }

    private void updateGeometry(boolean rebuildBitmaps) {
        TerminalGeometry geometry = new TerminalGeometry(getWidth(), getHeight(), targetColumns,
                latinAdvance100, fontSpacing100, NativeTerm.MAX_ROWS);
        boolean gridChanged = geometry.cols != cols || geometry.rows != rows;
        targetColumns = geometry.preferredColumns;
        fontPx = geometry.fontPx;
        cellW = geometry.cellW;
        cellH = geometry.cellH;
        cols = geometry.cols;
        rows = geometry.rows;
        textPaint.setTypeface(latinTf);
        textPaint.setTextSize(fontPx);
        textPaint.setTextScaleX(1f);
        textPaint.setTextSkewX(0f);
        textPaint.setFakeBoldText(false);
        Paint.FontMetrics fm0 = textPaint.getFontMetrics();
        baseAdvance = fontPx * latinAdvance100 / 100f;
        asciiScale = 1f;
        baseline = (cellH - (-fm0.ascent + fm0.descent)) / 2f - fm0.ascent;
        geometryPending = false;
        bitmapGeometryDirty = true;
        if (rebuildBitmaps) rebuildRowCache();
        if (gridChanged && gridListener != null) gridListener.onGridChanged(cols, rows);
    }

    private void rebuildRowCache() {
        clearGlyphCache();        // 字号/网格变了，旧字形位图作废
        glyphCount = 0;
        slotCount = rows + SLOT_EXTRA;
        rowBitmapH = (int) Math.ceil(cellH);
        // Geometric changes end the frozen selection before rebuilding caches.
        clearSelection();

        // 槽位位图按新几何重建
        if (slots != null) {
            for (Bitmap b : slots) if (b != null) b.recycle();
        }
        slots = new Bitmap[slotCount];
        slotValid = new boolean[slotCount];
        /* freeStack 必须能装下全部槽位（slotCount = rows + SLOT_EXTRA）。
         * 之前按 SLOT_EXTRA+8 分配，行数一多就写出界 —— 崩在 onSizeChanged，表现为黑屏打不开。 */
        freeStack = new int[slotCount];
        freeTop = 0;
        for (int i = slotCount - 1; i >= 0; i--) freeStack[freeTop++] = i;
        screenToSlot = new int[rows];
        tmpMap = new int[rows];
        Arrays.fill(screenToSlot, -1);
        rowBuf = null;
        invalidateAllRows();

        bitmapGeometryDirty = false;
        String msg = "GEOM view=" + getWidth() + "x" + getHeight() + " " + getGridInfo()
                + " slots=" + slotCount
                + " cache_MB=" + (getWidth() * rowBitmapH * 4L * slotCount / 1024 / 1024);
        Log.i(TAG, msg);
        try { MainActivity.out((android.app.Activity) getContext(), msg); } catch (Throwable ignored) {}

    }

    private void invalidateAllRows() {
        if (screenToSlot == null) return;
        for (int r = 0; r < rows; r++) {
            if (screenToSlot[r] >= 0) slotValid[screenToSlot[r]] = false;
        }
    }

    // ---------------- 槽位管理 ----------------

    private int allocSlot() {
        if (freeTop == 0) return -1;
        return freeStack[--freeTop];
    }

    private void releaseSlot(int slot) {
        if (slot < 0) return;
        if (freeTop < freeStack.length) freeStack[freeTop++] = slot;
    }

    private boolean slotReferenced(int slot) {
        for (int r = 0; r < rows; r++) if (screenToSlot[r] == slot) return true;
        return false;
    }

    /**
     * 搬运事件：把 src 行的槽位交给 dest 行（像素一个字节都不动），
     * 然后释放不再被引用的槽位、给新暴露的行分配新槽位并标脏。
     */
    private void applyMove(int[] op, int off) {
        int dR0 = op[off], dC0 = op[off + 1], dR1 = op[off + 2], dC1 = op[off + 3];
        int sR0 = op[off + 4], sC0 = op[off + 5], sR1 = op[off + 6], sC1 = op[off + 7];
        boolean wholeRow = (dC0 == 0 && dC1 >= cols && sC0 == 0 && sC1 >= cols);

        if (!wholeRow) {
            // 带列的局部搬运：保守，把涉及的行标脏（仍然远好于整屏重画）
            for (int r = Math.max(0, dR0); r < dR1 && r < rows; r++) {
                int s = screenToSlot[r];
                if (s >= 0) slotValid[s] = false;
            }
            return;
        }

        System.arraycopy(screenToSlot, 0, tmpMap, 0, rows);

        /*
         * 关键：先把整个 dest 区间清成「无槽位」，再搬。
         * 之前我直接在旧映射上覆盖，导致两类错误：
         *   (a) dest 里没有被 src 覆盖的“新暴露行”仍指向旧槽位 → 显示旧内容；
         *   (b) 某些槽位被两行同时引用（别名）→ 整屏所有行显示同一行内容（实测就是这个症状：
         *       80 行全是“第 223 行”）。下面还会做一次去重兜底。
         */
        for (int r = Math.max(0, dR0); r < dR1 && r < rows; r++) tmpMap[r] = -1;

        int n = Math.min(dR1 - dR0, sR1 - sR0);
        for (int i = 0; i < n; i++) {
            int d = dR0 + i, s = sR0 + i;
            if (d >= 0 && d < rows && s >= 0 && s < rows) tmpMap[d] = screenToSlot[s];
        }

        // 释放不再被任何行引用的旧槽位
        for (int r = 0; r < rows; r++) {
            int old = screenToSlot[r];
            if (old >= 0 && old != tmpMap[r] && !referencedIn(tmpMap, old)) releaseSlot(old);
        }

        // 新暴露的行：分配新槽位并标脏（会从当前内容重新光栅化）
        for (int r = Math.max(0, dR0); r < dR1 && r < rows; r++) {
            if (tmpMap[r] < 0) {
                int s = allocSlot();
                if (s < 0) { Arrays.fill(tmpMap, -1); invalidateAllRows(); break; }
                tmpMap[r] = s;
                slotValid[s] = false;
            }
        }

        // 去重兜底：同一槽位被多行引用时，给后面的行换一个新槽位（避免别名）
        for (int r = 0; r < rows; r++) {
            int s = tmpMap[r];
            if (s < 0) continue;
            for (int q = 0; q < r; q++) {
                if (tmpMap[q] == s) {
                    int ns = allocSlot();
                    if (ns >= 0) { tmpMap[r] = ns; slotValid[ns] = false; }
                    else { tmpMap[r] = -1; }
                    break;
                }
            }
        }

        System.arraycopy(tmpMap, 0, screenToSlot, 0, rows);
    }

    private boolean referencedIn(int[] map, int slot) {
        for (int r = 0; r < rows; r++) if (map[r] == slot) return true;
        return false;
    }

    // ---------------- 绘制 ----------------

    private Typeface liveTypeface(int variant) {
        switch (variant) {
            case 1: return latinTfBold;
            case 2: return cjkTf;
            case 3: return cjkTfBold;
            default: return latinTf;
        }
    }

    private float naturalAdvance(int cp, int variant) {
        long key = ((long) cp << 2) | variant;
        Float cached = naturalAdvances.get(key);
        if (cached != null) return cached;
        textPaint.setTypeface(liveTypeface(variant));
        textPaint.setTextSize(100f);
        textPaint.setTextScaleX(1f);
        textPaint.setTextSkewX(0f);
        textPaint.setFakeBoldText(false);
        int n = Character.toChars(cp, cpBuf, 0);
        float advance = Math.max(0.1f, textPaint.measureText(cpBuf, 0, n));
        if (naturalAdvances.size() >= 6000) naturalAdvances.clear();
        naturalAdvances.put(key, advance);
        return advance;
    }

    /** During a pinch, draw text at the frame's real font size. Runs share
     * style and advance; no old glyph/row bitmap is enlarged or rebuilt. */
    private void drawLiveText(Canvas canvas) {
        long startNs = System.nanoTime();
        canvas.drawColor(defaultBg);
        ensureRowBuf();
        if (liveRun.length < cols * 2) liveRun = new char[cols * 2];
        textPaint.setUnderlineText(false);
        textPaint.setStrikeThruText(false);
        synchronized (NativeTerm.class) {
            // The bitmap cache will be rebuilt at the final geometry.
            NativeTerm.takeMoveOps(handle, moveOps);
            NativeTerm.takeDamageRows(handle, damageRows);
            for (int row = 0; row < rows; row++) {
                int n = NativeTerm.getRow(handle, row, rowBuf);
                float y = row * cellH;
                for (int col = 0; col < n; ) {
                    int bg = rowBuf[col * 5 + 2], end = col + 1;
                    while (end < n && rowBuf[end * 5 + 2] == bg) end++;
                    rowBgPaint.setColor(bg | 0xff000000);
                    canvas.drawRect(col * cellW, y, end * cellW, y + cellH, rowBgPaint);
                    col = end;
                }
                for (int col = 0; col < n; ) {
                    int base = col * 5, width = rowBuf[base + 4];
                    if (width <= 0 || rowBuf[base] == -1) { col++; continue; }
                    int cp = sanitizeCp(rowBuf[base]);
                    if (cp == ' ') { col += width; continue; }
                    int fg = rowBuf[base + 1], style = rowBuf[base + 3] & 5;
                    int variant = (width >= 2 ? 2 : 0) | (style & 1);
                    float advance = naturalAdvance(cp, variant);
                    int end = col, chars = 0;
                    while (end < n) {
                        int p = end * 5;
                        if (rowBuf[p + 4] != width || rowBuf[p + 1] != fg ||
                                (rowBuf[p + 3] & 5) != style || rowBuf[p] == -1) break;
                        int next = sanitizeCp(rowBuf[p]);
                        if (Math.abs(naturalAdvance(next, variant) - advance) > 0.001f) break;
                        chars += Character.toChars(next, liveRun, chars);
                        end += width;
                    }
                    textPaint.setTypeface(liveTypeface(variant));
                    textPaint.setTextSize(fontPx);
                    textPaint.setTextScaleX(width * cellW * 100f / (advance * fontPx));
                    textPaint.setTextSkewX((style & 4) != 0 ? -0.25f : 0f);
                    textPaint.setColor(fg | 0xff000000);
                    canvas.drawText(liveRun, 0, chars, col * cellW, y + baseline, textPaint);
                    col = end;
                }
                for (int col = 0; col < n; ) {
                    int p = col * 5, width = rowBuf[p + 4], attrs = rowBuf[p + 3];
                    if (width <= 0 || rowBuf[p] == -1) { col++; continue; }
                    decoPaint.setColor(rowBuf[p + 1] | 0xff000000);
                    float stroke = Math.max(1f, fontPx / 18f);
                    if ((attrs & 2) != 0) canvas.drawRect(col * cellW, y + baseline + stroke,
                            (col + width) * cellW, y + baseline + stroke * 2f, decoPaint);
                    if ((attrs & 32) != 0) canvas.drawRect(col * cellW, y + baseline - fontPx * 0.3f,
                            (col + width) * cellW, y + baseline - fontPx * 0.3f + stroke, decoPaint);
                    col += width;
                }
            }
            drawCursor(canvas);
        }
        frameCount++;
        framesSinceLog++;
        accDrawNs += System.nanoTime() - startNs;
        maybeLog();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (handle == 0 || slots == null || slotValid == null || screenToSlot == null) {
            canvas.drawColor(defaultBg);
            return;
        }
        try {
            if (selActive && selectionSnapshot != null) {
                canvas.drawBitmap(selectionSnapshot, 0f, 0f, null);
                drawSelectionOverlay(canvas);
                return;
            }
            if (zoom.isActive()) {
                float columns = zoom.takeFrame();
                if (Float.isFinite(columns)) {
                    targetColumns = columns;
                    updateGeometry(false);
                }
                drawLiveText(canvas);
            } else {
                if (geometryPending) updateGeometry(true);
                else if (bitmapGeometryDirty) rebuildRowCache();
                canvas.drawColor(defaultBg);
                drawFrame(canvas);
            }
        } catch (Throwable t) {
            // 渲染期异常不能把 app 打死：打日志、下一帧再试。
            // 注意只在首次异常时清空缓存：每帧都清会把行缓存彻底废掉（一次异常就退化成整屏重画）。
            renderErrors++;
            if (renderErrors < 6) Log.e(TAG, "onDraw exception #" + renderErrors, t);
            if (renderErrors == 1) {
                for (int i = 0; i < slotCount; i++) slotValid[i] = false;
            }
        }
    }

    private void drawFrame(Canvas canvas) {
        synchronized (NativeTerm.class) {
            drawFrameLocked(canvas);
        }
    }

    private void drawFrameLocked(Canvas canvas) {
        long t0 = System.nanoTime();
        ensureRowBuf();

        // 1) 搬运：只改映射
        if (moveOps.length < 8) moveOps = new int[8 * 128];
        int nm = NativeTerm.takeMoveOps(handle, moveOps);
        for (int i = 0; i < nm; i++) applyMove(moveOps, i * 8);

        // 2) 脏行 → 槽位失效
        if (damageRows.length < rows) damageRows = new int[rows * 2];
        int nd = NativeTerm.takeDamageRows(handle, damageRows);
        for (int i = 0; i < nd; i++) {
            int r = damageRows[i];
            if (r >= 0 && r < rows) {
                int s = screenToSlot[r];
                if (s < 0) { s = allocSlot(); screenToSlot[r] = s; }
                if (s >= 0) slotValid[s] = false;
            }
        }

        // 3) 只重光栅化失效的槽位
        long r0 = System.nanoTime();
        int rasterized = 0;
        for (int r = 0; r < rows; r++) {
            int s = screenToSlot[r];
            if (s < 0) {
                s = allocSlot();
                if (s < 0) continue;
                screenToSlot[r] = s;
                slotValid[s] = false;
            }
            if (!slotValid[s]) {
                rasterizeRow(r, s);
                slotValid[s] = true;
                rasterized++;
            }
        }
        long rNs = System.nanoTime() - r0;
        rasterizedRows += rasterized;

        // 4) 每行一次 blit
        long b0 = System.nanoTime();
        float y = 0f;
        for (int r = 0; r < rows; r++) {
            int s = screenToSlot[r];
            Bitmap b = (s >= 0) ? slots[s] : null;
            if (b != null) canvas.drawBitmap(b, 0f, y, null);
            y += cellH;
        }
        drawCursor(canvas);
        drawSelectionOverlay(canvas);
        long bNs = System.nanoTime() - b0;
        blittedRows += rows;

        long dt = System.nanoTime() - t0;
        accDrawNs += dt;
        accRasterNs += rNs;
        frameCount++;
        framesSinceLog++;
        maybeLog();
    }

    private void maybeLog() {
        long now = System.nanoTime();
        if (lastLogNs == 0) { lastLogNs = now; return; }
        if (now - lastLogNs < 1_000_000_000L) return;
        double secs = (now - lastLogNs) / 1e9;
        double fps = framesSinceLog / secs;
        double avg = framesSinceLog == 0 ? 0 : (accDrawNs / 1e6 / framesSinceLog);
        double rasterAvg = framesSinceLog == 0 ? 0 : (accRasterNs / 1e6 / framesSinceLog);
        Log.i(TAG, String.format(Locale.US,
                "RENDER fps=%.1f avg_frame_ms=%.2f avg_raster_ms=%.2f frames=%d rasterized_rows=%d blits=%d",
                fps, avg, rasterAvg, frameCount, rasterizedRows, blittedRows));
        lastLogNs = now;
        framesSinceLog = 0;
        accDrawNs = 0;
        accRasterNs = 0;
    }

    private void ensureRowBuf() {
        int need = cols * 5;
        if (rowBuf == null || rowBuf.length < need) rowBuf = new int[need];
    }

    /** 把一行光栅化进槽位位图：按 (样式,宽度) 分组，每组一次 drawText（不做逐格测量）。 */
    private void rasterizeRow(int row, int slot) {
        Bitmap b = slots[slot];
        if (b == null || b.getWidth() != getWidth()) {
            if (b != null) b.recycle();
            b = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, rowBitmapH), Bitmap.Config.ARGB_8888);
            slots[slot] = b;
        }
        Canvas c = new Canvas(b);
        int n = NativeTerm.getRow(handle, row, rowBuf);
        if (n <= 0) { c.drawColor(defaultBg); return; }

        // --- 临时埋点：搞清为什么整屏是黑的 ---
        if (dbgRows < 4) {
            dbgRows++;
            StringBuilder sb = new StringBuilder("ROWDBG row=" + row + " n=" + n
                    + " bmp=" + b.getWidth() + "x" + b.getHeight()
                    + " textSize=" + String.format(Locale.US, "%.2f", textSize)
                    + " baseline=" + String.format(Locale.US, "%.2f", baseline) + " cells:");
            for (int i = 0; i < Math.min(6, n); i++) {
                int q = i * 5;
                sb.append(" [cp=").append(rowBuf[q])
                  .append(" fg=").append(Integer.toHexString(rowBuf[q + 1]))
                  .append(" bg=").append(Integer.toHexString(rowBuf[q + 2]))
                  .append(" at=").append(rowBuf[q + 3])
                  .append(" w=").append(rowBuf[q + 4]).append("]");
            }
            try { MainActivity.out((android.app.Activity) getContext(), sb.toString()); } catch (Throwable ignored) {}
        }

        // 先用最左格子的背景刷底（M1a 简化；后续可改成按段刷背景色）
        c.drawColor(rowBuf[2] | 0xFF000000);

        int col = 0;
        while (col < n) {
            int base = col * 5;
            int fg = rowBuf[base + 1];
            int bg = rowBuf[base + 2];
            int attrs = rowBuf[base + 3];
            int width = rowBuf[base + 4];
            if (width <= 0) { col++; continue; }   // 全宽字符的尾随占位格，跳过
            int cp = sanitizeCp(rowBuf[base]);

            paintSegmentBg(c, col, col + width, bg);

            // 位图字体：非空格格子一次 blit，位置只由列号决定，宽度必然 = width*cellW
            if (cp != ' ') {
                // 属性位：bit0 粗体 / bit1 下划线 / bit2 斜体 / bit4 反显(已在 native 换成前景背景) / bit5 删除线
                int style = (attrs & 1) | ((attrs & 4) >> 1);
                Bitmap g = glyphFor(cp, width, style, fg);
                glyphCount++;
                c.drawBitmap(g, col * cellW, 0f, null);
            }

            // 下划线 / 删除线用矩形画，比让字体画更可控（颜色跟 fg 走）
            decoPaint.setColor(fg | 0xFF000000);
            if ((attrs & 2) != 0) {
                c.drawRect(col * cellW, baseline + 2f, (col + width) * cellW, baseline + 3.2f, decoPaint);
            }
            if ((attrs & 32) != 0) {
                c.drawRect(col * cellW, baseline - 4f, (col + width) * cellW, baseline - 3f, decoPaint);
            }
            col += width;
        }
    }

    /**
     * 把 libvterm 给的码点收敛成可安全绘制的值。
     * 空格子在 libvterm 里是 0，但也见过 (uint32_t)-1：直接丢给 Character.toChars 会抛
     * IllegalArgumentException: Not a valid Unicode code point: 0xFFFFFFFF（实测踩过）。
     * 另外控制字符、代理区也一并当空格处理。
     */
    private static int sanitizeCp(int cp) {
        if (cp == 0 || cp < 0x20 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return ' ';
        return cp;
    }

    private void paintSegmentBg(Canvas c, int col0, int col1, int bg) {
        rowBgPaint.setColor(bg | 0xFF000000);
        c.drawRect(col0 * cellW, 0f, col1 * cellW, cellH, rowBgPaint);
    }

    /** 样式只在变化时才设：改 Paint 属性会触发 Skia 内部重算，逐段设很贵。 */
    private void applyStyle(int attrs) {
        boolean bold = (attrs & 1) != 0, ul = (attrs & 2) != 0, it = (attrs & 4) != 0, st = (attrs & 32) != 0;
        if (bold != curBold) { textPaint.setFakeBoldText(bold); curBold = bold; }
        if (ul != curUl) { textPaint.setUnderlineText(ul); curUl = ul; }
        if (it != curItalic) { textPaint.setTextSkewX(it ? -0.25f : 0f); curItalic = it; }
        if (st != curStrike) { textPaint.setStrikeThruText(st); curStrike = st; }
    }

    /** Continuous text selection, with handles below the text instead of over it. */
    private void drawSelectionOverlay(Canvas canvas) {
        if (!selActive || selection == null) return;
        int start = selection.start(), end = selection.end();
        for (int r = start / cols; r <= end / cols; r++) {
            canvas.drawRect(selection.firstColumn(r) * cellW, r * cellH,
                    (selection.lastColumn(r) + 1) * cellW, (r + 1) * cellH, selPaint);
        }
        float radius = 6f * getResources().getDisplayMetrics().density;
        handlePaint.setColor(0xFF33AAFF);
        float sx = start % cols * cellW, sy = (start / cols + 1) * cellH;
        float ex = (end % cols + 1) * cellW, ey = (end / cols + 1) * cellH;
        drawSelectionHandle(canvas, sx, sy, radius, -1);
        drawSelectionHandle(canvas, ex, ey, radius, 1);
    }

    private void drawSelectionHandle(Canvas canvas, float x, float y, float radius, int side) {
        float cy = Math.min(getHeight() - radius, y + radius);
        float cx = Math.max(radius, Math.min(getWidth() - radius, x + side * radius));
        canvas.drawRect(x - 1f, y - cellH, x + 1f, cy, handlePaint);
        canvas.drawCircle(cx, cy, radius, handlePaint);
    }

    private void drawCursor(Canvas canvas) {
        NativeTerm.getCursor(handle, cursorOut);
        if (cursorOut[2] == 0) return;
        int r = cursorOut[0], ccol = cursorOut[1];
        if (r < 0 || r >= rows) return;
        float x = ccol * cellW;
        float y = r * cellH;
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(Math.max(1f, cellW * 0.15f));
        canvas.drawRect(x, y, x + cellW, y + cellH, cursorPaint);
        // 光标所在行也要重画（否则残影），交给下一帧；libvterm 的 movecursor 回调已把旧行标脏
    }

    public void destroy() {
        cancelZoom();
        removeCallbacks(longPress);
        touchPressed = false;
        clearSelection();
        synchronized (NativeTerm.class) {
            if (handle != 0) {
                NativeTerm.destroy(handle);
                handle = 0;
            }
        }
        if (slots != null) {
            for (Bitmap b : slots) if (b != null) b.recycle();
            slots = null;
        }
        clearGlyphCache();
    }
}

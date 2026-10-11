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

    public void setGridListener(GridListener l) { this.gridListener = l; }

    public void setFontPx(float px) {
        fontPx = px;
        if (getWidth() > 0 && getHeight() > 0) onSizeChanged(getWidth(), getHeight(), 0, 0);
    }

    public TerminalView(Context context) {
        super(context);
        loadFonts(context);
        textPaint.setTypeface(latinTf);
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

        // 捏合缩放：和 Termux 调字号一个意思 —— 直接改目标列数（列少则字大）
        scaleDetector = new android.view.ScaleGestureDetector(context,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector d) {
                        zoomAccum *= d.getScaleFactor();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(android.view.ScaleGestureDetector d) {
                        if (zoomAccum > 0.01f) {
                            int nc = Math.round(targetCols / zoomAccum);
                            zoomAccum = 1f;
                            setTargetCols(nc);
                        }
                    }
                });
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
    private int targetCols = 150;

    /** 选择与缩放的回调（主界面据此显示"复制/全选/粘贴/取消"条）。 */
    public interface SelectionListener { void onSelection(boolean active, boolean hasRange); }
    public interface ZoomListener { void onZoomed(int cols); }

    private SelectionListener selListener;
    private ZoomListener zoomListener;
    private final org.connectbot.bean.SelectionArea selArea = new org.connectbot.bean.SelectionArea();
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

    public void setTargetCols(int cols) {
        int c = Math.max(20, Math.min(400, cols));
        if (c == targetCols) return;
        targetCols = c;
        clearSelection();
        if (getWidth() > 0 && getHeight() > 0) onSizeChanged(getWidth(), getHeight(), 0, 0);
        if (zoomListener != null) zoomListener.onZoomed(targetCols);
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
        return "cols=" + targetCols + " grid=" + cols + "x" + rows
                + " cell=" + String.format(Locale.US, "%.2fx%.2f", cellW, cellH)
                + " font=" + String.format(Locale.US, "%.1f", fontPx)
                + " cellW/advance=" + String.format(Locale.US, "%.3f", asciiScale)
                + " glyphs=" + glyphCache.size() + "/" + glyphCount
                + " evict=" + glyphEvictions;
    }

    // ---------------- 选择 ----------------

    public boolean isSelecting() { return selActive; }

    private void startSelection(int row, int col) {
        // ConnectBot 的两段式：先定起点（selectingOrigin=true，此时 setRow/setColumn 同时设起止），
        // 之后第一次移动才 finishSelectingOrigin()，再拖动只动终点。
        selArea.setBounds(cols, rows);
        selArea.reset();
        selArea.setRow(row);
        selArea.setColumn(col);
        selActive = true;
        gestureStartedSelection = true;
        notifySelection();
        invalidate();
    }

    /** 拖动中：把终点移到 (row,col)。 */
    private void extendSelection(int row, int col) {
        if (selArea.isSelectingOrigin()) selArea.finishSelectingOrigin();
        selArea.setRow(row);
        selArea.setColumn(col);
        notifySelection();
        invalidate();
    }

    /** 拖动起点把手：重设起点、保留原终点。 */
    private void moveOriginHandle(int row, int col) {
        int b = selArea.getBottom(), r = selArea.getRight();
        selArea.reset();
        selArea.setRow(row);
        selArea.setColumn(col);
        selArea.finishSelectingOrigin();
        selArea.setRow(b);
        selArea.setColumn(r);
        notifySelection();
        invalidate();
    }

    public void selectAll() {
        selArea.setBounds(cols, rows);
        selArea.reset();
        selArea.setRow(0);
        selArea.setColumn(0);
        selArea.finishSelectingOrigin();
        selArea.setRow(Math.max(0, rows - 1));
        selArea.setColumn(Math.max(0, cols - 1));
        selActive = true;
        notifySelection();
        invalidate();
    }

    public void clearSelection() {
        if (!selActive) return;
        selActive = false;
        notifySelection();
        invalidate();
    }

    private void notifySelection() {
        if (selListener != null) selListener.onSelection(selActive, hasRange());
    }

    private boolean hasRange() {
        return selArea.getTop() != selArea.getBottom() || selArea.getLeft() != selArea.getRight();
    }

    /** 取选中区域的文本（按屏幕当前内容；行尾空格会去掉）。 */
    public String getSelectionText() {
        if (!selActive || handle == 0) return "";
        ensureRowBuf();
        int r0 = selArea.getTop(), r1 = selArea.getBottom();
        int cA = selArea.getLeft(), cB = selArea.getRight();
        StringBuilder out = new StringBuilder();
        for (int r = r0; r <= r1 && r < rows; r++) {
            int n = NativeTerm.getRow(handle, r, rowBuf);
            if (n <= 0) continue;
            int from = cA;          // SelectionArea 是矩形选择：每行都取 [left..right]
            int to = cB;
            StringBuilder line = new StringBuilder();
            for (int c = Math.max(0, from); c <= to && c < n; ) {
                int base = c * 5;
                int w = rowBuf[base + 4];
                if (w <= 0) { c++; continue; }
                line.appendCodePoint(sanitizeCp(rowBuf[base]));
                c += Math.max(1, w);
            }
            while (line.length() > 0 && line.charAt(line.length() - 1) == ' ') {
                line.setLength(line.length() - 1);
            }
            out.append(line);
            if (r < r1) out.append('\n');
        }
        return out.toString();
    }

    private float lastTouchY, scrollAcc, touchTravel;
    private float zoomAccum = 1f;
    private long touchDownNs;
    private float touchDownX, touchDownY;

    /**
     * 触摸滚动 → 鼠标滚轮事件。
     *
     * 走 libvterm 的鼠标 API 而不是手拼序列：它是按「应用是否开启鼠标上报」来决定要不要产生序列的
     * （vterm_mouse_button 里有 `if(!state->mouse_flags) return;`），所以普通 shell 里拖屏幕无害；
     * tmux（mouse on）会自己上报开启，滚轮事件就能被正确解析成滚动。
     * 约定：手指下移 = 内容下移 = 上滚（button 4），反之 button 5。
     */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (inputSink == null || handle == 0) return super.onTouchEvent(e);
        if (scaleDetector != null) scaleDetector.onTouchEvent(e);
        // 双指捏合期间不做选择/滚动
        if (e.getPointerCount() > 1 || (scaleDetector != null && scaleDetector.isInProgress())) {
            scrollAcc = 0;
            return true;
        }
        final int row = Math.max(0, Math.min(rows - 1, (int) (e.getY() / cellH)));
        final int col = Math.max(0, Math.min(cols - 1, (int) (e.getX() / cellW)));
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                lastTouchY = e.getY();
                scrollAcc = 0;
                touchTravel = 0;
                touchDownNs = System.nanoTime();
                touchDownX = e.getX();
                touchDownY = e.getY();
                gestureStartedSelection = false;
                handleDrag = 0;
                if (selActive) {
                    // 先看是不是按在把手上（把手画在选区左上/右下）
                    float rad = Math.max(cellW * 1.8f, cellH);
                    float ox = selArea.getLeft() * cellW;
                    float oy = selArea.getTop() * cellH + cellH / 2f;
                    float ex = (selArea.getRight() + 1) * cellW;
                    float ey = selArea.getBottom() * cellH + cellH / 2f;
                    if (Math.hypot(e.getX() - ox, e.getY() - oy) < rad) handleDrag = 1;
                    else if (Math.hypot(e.getX() - ex, e.getY() - ey) < rad) handleDrag = 2;
                }
                return true;
            case android.view.MotionEvent.ACTION_MOVE: {
                long heldMs = (System.nanoTime() - touchDownNs) / 1_000_000L;
                float dy = e.getY() - lastTouchY;
                lastTouchY = e.getY();
                touchTravel += Math.abs(dy);
                if (handleDrag == 1) { moveOriginHandle(row, col); return true; }
                if (handleDrag == 2) { extendSelection(row, col); return true; }
                if (selActive) { extendSelection(row, col); return true; }
                // 按住不放就进入选择（同一次手势里可以接着拖），比"抬手才选"顺手
                if (heldMs > 550 && touchTravel < cellH * 1.5f) {
                    startSelection(row, col);
                    return true;
                }
                scrollAcc += dy;
                while (scrollAcc >= cellH) { inputSink.mouse(row, col, 4, true); scrollAcc -= cellH; }
                while (scrollAcc <= -cellH) { inputSink.mouse(row, col, 5, true); scrollAcc += cellH; }
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                long heldMs = (System.nanoTime() - touchDownNs) / 1_000_000L;
                if (handleDrag != 0) {
                    handleDrag = 0;                       // 松开把手：保留选择
                } else if (selActive) {
                    /*
                     * 关键修复：启动选择的那次抬手不能被当成"点击取消"，
                     * 否则操作条一闪就没（实测日志 active=true 后立刻 false）。
                     * 只有"在没启动选择的手势里轻点一下"才取消。
                     */
                    if (!gestureStartedSelection && touchTravel < cellH * 0.4f) clearSelection();
                } else if (heldMs > 550 && touchTravel < cellH * 0.4f) {
                    startSelection(row, col);             // 没产生 MOVE 的慢按也算长按
                } else if (touchTravel < cellH * 0.4f) {
                    inputSink.tap();                      // 轻点：弹键盘
                }
                scrollAcc = 0;
                gestureStartedSelection = false;
                return true;
            }
            default:
                return super.onTouchEvent(e);
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

        /*
         * 网格与单元格。
         * 两种模式：预设固定网格（列/行由用户定，单元格 = 视图/格数），或自动（先定字号再算格数）。
         * 不管哪种，最后都要把 ASCII 步进用 textScaleX 强制到 cellW —— 这是"缩进和位置不对"的根治：
         * 之前靠字体自然步进累加，一旦某个字符来自 CJK/符号回退字体（步进不是 0.6em），整行就逐步跑偏。
         */
        int wantCols = targetCols;
        int newCols, newRows;
        // 几何一律按拉丁主字体的度量算（glyphFor 会临时换成别的字体，不能污染这里）
        textPaint.setTypeface(latinTf);
        textPaint.setTextSkewX(0f);
        textPaint.setFakeBoldText(false);
        textPaint.setTextScaleX(1f);
        if (wantCols > 0) {
            // 列数定死 → 反解字号。字体度量与字号成正比：先在 100px 上量一次，再按比例缩。
            textPaint.setTextSize(100f);
            float adv100 = textPaint.measureText("X");
            float sp100 = textPaint.getFontSpacing();
            if (adv100 < 1f) adv100 = 60f;
            fontPx = Math.max(6f, (w / (float) wantCols) * 100f / adv100);
            textPaint.setTextSize(fontPx);
            cellW = textPaint.measureText("X");
            cellH = textPaint.getFontSpacing();
            newCols = Math.max(2, (int) (w / cellW));
            newRows = Math.max(2, (int) (h / cellH));   // 至少 2 行：1 行的终端没有意义（还会把远端 PTY 设坏）
            cellW = (float) w / newCols;
            cellH = (float) h / newRows;
        } else {
            fontPx = 27f;
            textPaint.setTextSize(fontPx);
            float adv = textPaint.measureText("X");
            float sp = textPaint.getFontSpacing();
            newCols = Math.max(2, (int) (w / adv));
            newRows = Math.max(1, (int) (h / sp));
            cellW = (float) w / newCols;
            cellH = (float) h / newRows;
        }
        Paint.FontMetrics fm0 = textPaint.getFontMetrics();
        baseAdvance = textPaint.measureText("X");
        asciiScale = baseAdvance > 0.05f ? cellW / baseAdvance : 1f;
        clearGlyphCache();        // 字号/网格变了，旧字形位图作废
        glyphCount = 0;
        // 垂直居中：格子高常常大于字高，顶对齐会显得很空
        baseline = (cellH - (-fm0.ascent + fm0.descent)) / 2f - fm0.ascent;

        boolean gridChanged = (newCols != cols || newRows != rows);
        cols = newCols;
        rows = newRows;
        slotCount = rows + SLOT_EXTRA;
        rowBitmapH = (int) Math.ceil(cellH);
        // 几何变了，选区的行列索引就失效了
        if (selActive) { selActive = false; notifySelection(); }

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

        String msg = "GEOM view=" + w + "x" + h + " " + getGridInfo()
                + " slots=" + slotCount
                + " cache_MB=" + (w * rowBitmapH * 4L * slotCount / 1024 / 1024);
        Log.i(TAG, msg);
        try { MainActivity.out((android.app.Activity) getContext(), msg); } catch (Throwable ignored) {}

        if (gridChanged && gridListener != null) gridListener.onGridChanged(cols, rows);
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

    @Override
    protected void onDraw(Canvas canvas) {
        if (handle == 0 || slots == null || slotValid == null || screenToSlot == null) {
            canvas.drawColor(defaultBg);
            return;
        }
        try {
            drawFrame(canvas);
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

            if (bg != 0) paintSegmentBg(c, col, col + width, bg);

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
        if (bg == 0) return;
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

    /** 选区高亮 + 两个可拖动把手（学 ConnectBot：矩形选区，指针在起点/终点）。 */
    private void drawSelectionOverlay(Canvas canvas) {
        if (!selActive) return;
        int r0 = selArea.getTop(), r1 = selArea.getBottom();
        int c0 = selArea.getLeft(), c1 = selArea.getRight();
        for (int r = r0; r <= r1 && r < rows; r++) {
            float y = r * cellH;
            canvas.drawRect(c0 * cellW, y, (c1 + 1) * cellW, y + cellH, selPaint);
        }
        // 把手：起点在左上角，终点在右下角（和 ConnectBot 的指针位置一致）
        float rad = Math.max(6f, cellW * 0.8f);
        handlePaint.setColor(0xFF33AAFF);
        canvas.drawCircle(c0 * cellW, r0 * cellH + cellH / 2f, rad, handlePaint);
        canvas.drawCircle((c1 + 1) * cellW, r1 * cellH + cellH / 2f, rad, handlePaint);
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
        if (handle != 0) {
            NativeTerm.destroy(handle);
            handle = 0;
        }
        if (slots != null) {
            for (Bitmap b : slots) if (b != null) b.recycle();
            slots = null;
        }
        clearGlyphCache();
    }
}

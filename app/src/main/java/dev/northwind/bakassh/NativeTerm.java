package dev.northwind.bakassh;

/**
 * libvterm 的 JNI 接口（实现见 src/main/cpp/libvterm_jni.c）。
 *
 * 事件模型：C 侧累积，Java 侧每帧各取一次 —— 不做逐事件回调，
 * 逐帧一次 JNI 是现代价（这也是我们在 Termux 那边学到的教训：
 * 重绘/交互频率必须和 IO 分片解耦）。
 */
public final class NativeTerm {
    static {
        System.loadLibrary("bakasshterm");
    }

    /** 网格最小 2 列：libvterm 写全宽字符后要推进一列写尾随占位，1 列会越界 panic。 */
    public static final int MIN_COLS = 2;

    public static native long create(int cols, int rows);
    public static native void destroy(long h);

    /** 喂原始字节（SSH 通道收到的就是它）。返回后事件已可取。 */
    public static native void pushBytes(long h, byte[] data, int len);

    public static native void resize(long h, int cols, int rows);

    /** 搬运事件：每 8 个 int 一组 [destR0,destC0,destR1,destC1, srcR0,srcC0,srcR1,srcC1]，返回组数。 */
    public static native int takeMoveOps(long h, int[] out);

    /** 脏行行号，返回个数。 */
    public static native int takeDamageRows(long h, int[] out);

    /** out = [codepoint, fgRGB, bgRGB, attrs, width]，返回 1 表示有效。 */
    public static native int getCell(long h, int row, int col, int[] out);

    /**
     * 整行批量取格（必须批量：逐格 JNI 一整屏要 11,704 次调用，开销比渲染还大）。
     * out 每格 5 个 int [codepoint, fgRGB, bgRGB, attrs, width]，返回格数。
     */
    public static native int getRow(long h, int row, int[] out);

    /** out = [row, col, visible, shape] */
    public static native void getCursor(long h, int[] out);

    /** 终端回给宿主的数据（DSR/OSC 应答等），返回字节数。 */
    public static native int takeOutput(long h, byte[] out);

    /** 鼠标移动（0 基行列）。 */
    public static native void mouseMove(long h, int row, int col, int mod);

    /** 鼠标按键：4 = 滚轮上，5 = 滚轮下（libvterm 只在应用开启鼠标上报时才真的发序列）。 */
    public static native void mouseButton(long h, int button, boolean pressed, int mod);
}

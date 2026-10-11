/*
 * sshterm 的终端引擎桥：libvterm -> JNI。
 *
 * 设计要点（依据 libvterm src/screen.c 的实际行为，不是猜的）：
 *  - damage_merge 设为 VTERM_DAMAGE_SCROLL：滚动不再逐格发 damage，而是由
 *    vterm_screen_flush_damage() 一次性交出「搬运事件(moverect) + 只覆盖新暴露区域
 *    的 damage」。UI 侧因此可以「搬位图 + 只重光栅化新行」，而不是整屏重画。
 *  - 普通改格走 damage 回调，我们按「行」标记脏。
 *  - 每次 vterm_input_write 之后调用一次 vterm_screen_flush_damage()，把事件交出来。
 *
 * 所有事件都累积在 C 侧，Kotlin/Java 侧每帧各取一次（takeMoveOps / takeDamageRows），
 * 不做逐事件 JNI 回调 —— 逐帧一次 JNI 是现代价。
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "vterm.h"

#define TAG "sshterm-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_ROWS     512
#define MAX_MOVE_OPS 256
#define MAX_DAMAGE   512

typedef struct {
    VTerm *vt;
    VTermState *state;
    VTermScreen *screen;
    int cols, rows;

    /* 行脏标记（screen 行坐标） */
    unsigned char dirty[MAX_ROWS];
    int dirty_rows[MAX_DAMAGE];
    int dirty_count;
    int need_full;

    /* 搬运事件：每 8 个 int = dest(start_row,start_col,end_row,end_col) + src(...) */
    int move_ops[MAX_MOVE_OPS * 8];
    int move_count;

    /* 光标 */
    int cursor_row, cursor_col, cursor_visible;
    int cursor_shape;

    /* 终端回给宿主的数据（DSR/OSC 应答等），M1b 接到 SSH 通道 */
    char outbuf[4096];
    size_t outlen;

    /* 默认色 */
    int default_fg, default_bg;
} TermCtx;

/* ---------------- libvterm 回调 ---------------- */

static int cb_damage(VTermRect rect, void *user) {
    TermCtx *t = (TermCtx *) user;
    for (int r = rect.start_row; r < rect.end_row; r++) {
        if (r >= 0 && r < t->rows && !t->dirty[r]) t->dirty[r] = 1;
    }
    return 1;
}

static int cb_moverect(VTermRect dest, VTermRect src, void *user) {
    TermCtx *t = (TermCtx *) user;
    if (t->move_count < MAX_MOVE_OPS) {
        int *o = t->move_ops + t->move_count * 8;
        o[0] = dest.start_row; o[1] = dest.start_col; o[2] = dest.end_row; o[3] = dest.end_col;
        o[4] = src.start_row;  o[5] = src.start_col;  o[6] = src.end_row;  o[7] = src.end_col;
        t->move_count++;
    }
    return 1;
}

static int cb_movecursor(VTermPos pos, VTermPos oldpos, int visible, void *user) {
    TermCtx *t = (TermCtx *) user;
    if (oldpos.row >= 0 && oldpos.row < t->rows) t->dirty[oldpos.row] = 1; /* 光标残影：旧行也要重画 */
    if (pos.row >= 0 && pos.row < t->rows) t->dirty[pos.row] = 1;
    t->cursor_row = pos.row;
    t->cursor_col = pos.col;
    t->cursor_visible = visible;
    return 1;
}

static int cb_settermprop(VTermProp prop, VTermValue *val, void *user) {
    TermCtx *t = (TermCtx *) user;
    switch (prop) {
        case VTERM_PROP_CURSORVISIBLE:
            t->cursor_visible = val->boolean;
            if (t->cursor_row >= 0 && t->cursor_row < t->rows) t->dirty[t->cursor_row] = 1;
            break;
        case VTERM_PROP_CURSORSHAPE:
            t->cursor_shape = val->number;
            break;
        default:
            break;
    }
    return 1;
}

static int cb_bell(void *user) { (void) user; return 1; }

static int cb_resize(int rows, int cols, void *user) {
    TermCtx *t = (TermCtx *) user;
    t->rows = rows > MAX_ROWS ? MAX_ROWS : rows;
    t->cols = cols;
    t->need_full = 1;
    memset(t->dirty, 1, (size_t) t->rows);
    LOGI("libvterm resize -> %dx%d", cols, rows);
    return 1;
}

static int cb_sb_pushline(int cols, const VTermScreenCell *cells, void *user) {
    (void) cols; (void) cells; (void) user;
    return 1; /* M1a 先不接回滚历史；M1b 由这里把行喂给我们的行缓冲 */
}

static int cb_sb_popline(int cols, VTermScreenCell *cells, void *user) {
    (void) cols; (void) cells; (void) user;
    return 0; /* 没有历史可给 */
}

static int cb_sb_clear(void *user) { (void) user; return 1; }

static void cb_output(const char *s, size_t len, void *user) {
    TermCtx *t = (TermCtx *) user;
    if (t->outlen + len <= sizeof(t->outbuf)) {
        memcpy(t->outbuf + t->outlen, s, len);
        t->outlen += len;
    }
}

static VTermScreenCallbacks g_screen_cbs = {
    .damage = cb_damage,
    .moverect = cb_moverect,
    .movecursor = cb_movecursor,
    .settermprop = cb_settermprop,
    .bell = cb_bell,
    .resize = cb_resize,
    .sb_pushline = cb_sb_pushline,
    .sb_popline = cb_sb_popline,
    .sb_clear = cb_sb_clear,
};

/* ---------------- 工具 ---------------- */

/**
 * VTermColor -> 0xRRGGBB。
 *
 * 必须处理「默认前景/背景」：libvterm 用 VTERM_COLOR_DEFAULT_FG/BG 位表示"没设颜色"，
 * 这种色的 rgb 字段是空的（转换函数不会填），直接用就会得到 0x000000 ——
 * 于是黑字画在黑底上，整屏看起来是黑的（我们第一版就是这样，截图全黑）。
 */
static int color_to_rgb(TermCtx *t, VTermColor c) {
    if (VTERM_COLOR_IS_DEFAULT_FG(&c)) return t->default_fg;
    if (VTERM_COLOR_IS_DEFAULT_BG(&c)) return t->default_bg;
    vterm_state_convert_color_to_rgb(t->state, &c);
    return (c.rgb.red << 16) | (c.rgb.green << 8) | c.rgb.blue;
}

static void collect_dirty(TermCtx *t) {
    t->dirty_count = 0;
    if (t->need_full) {
        for (int r = 0; r < t->rows; r++) {
            if (t->dirty_count < MAX_DAMAGE) t->dirty_rows[t->dirty_count++] = r;
        }
        t->need_full = 0;
        memset(t->dirty, 0, (size_t) t->rows);
        return;
    }
    for (int r = 0; r < t->rows; r++) {
        if (t->dirty[r]) {
            if (t->dirty_count < MAX_DAMAGE) t->dirty_rows[t->dirty_count++] = r;
            t->dirty[r] = 0;
        }
    }
}

/* ---------------- JNI 导出 ---------------- */

JNIEXPORT jlong JNICALL
Java_dev_northwind_bakassh_NativeTerm_create(JNIEnv *env, jclass clazz, jint cols, jint rows) {
    (void) env; (void) clazz;
    if (cols < 2) cols = 2;          /* libvterm 全宽字符尾随占位要求 >=2 列 */
    if (rows < 1) rows = 1;
    if (rows > MAX_ROWS) rows = MAX_ROWS;

    TermCtx *t = (TermCtx *) calloc(1, sizeof(TermCtx));
    if (!t) return 0;
    t->cols = cols;
    t->rows = rows;
    t->default_fg = 0xD0D0D0;
    t->default_bg = 0x101014;
    t->cursor_visible = 1;

    t->vt = vterm_new(rows, cols);
    if (!t->vt) { free(t); return 0; }
    vterm_set_utf8(t->vt, 1);
    vterm_output_set_callback(t->vt, cb_output, t);

    t->state = vterm_obtain_state(t->vt);
    t->screen = vterm_obtain_screen(t->vt);
    vterm_screen_enable_altscreen(t->screen, 1);
    vterm_screen_set_callbacks(t->screen, &g_screen_cbs, t);
    /* 关键：SCROLL 档位下滚动以 moverect 事件交出，而不是逐格 damage */
    vterm_screen_set_damage_merge(t->screen, VTERM_DAMAGE_SCROLL);

    /*
     * 关键的一步，漏了会崩：
     * libvterm 的 encoding[0..3] 是在 state 的 reset 路径里初始化的，不调这个 reset
     * 它们就一直全是 NULL。之后 on_text 对「最高位为 0 的字节（ASCII）」会去取
     * encoding[gl_set].enc->decode，于是 [NULL+8] 段错误。
     * 症状很有迷惑性：纯中文流（高位为 1，走 encoding_utf8）完全不崩，
     * 一遇到 ASCII 文本（比如 shell 提示符）立刻崩 —— 我们的基准测试就是纯中文，所以没抓到。
     */
    vterm_screen_reset(t->screen, 1);

    VTermColor fg, bg;
    vterm_color_rgb(&fg, (uint8_t) (t->default_fg >> 16), (uint8_t) (t->default_fg >> 8), (uint8_t) t->default_fg);
    vterm_color_rgb(&bg, (uint8_t) (t->default_bg >> 16), (uint8_t) (t->default_bg >> 8), (uint8_t) t->default_bg);
    vterm_state_set_default_colors(t->state, &fg, &bg);
    vterm_screen_set_default_colors(t->screen, &fg, &bg);

    t->need_full = 1;
    LOGI("libvterm created %dx%d (vterm %s)", cols, rows, "0.3.x-neovim-fork");
    return (jlong) (intptr_t) t;
}

JNIEXPORT void JNICALL
Java_dev_northwind_bakassh_NativeTerm_destroy(JNIEnv *env, jclass clazz, jlong h) {
    (void) env; (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t) return;
    if (t->vt) vterm_free(t->vt);
    free(t);
}

JNIEXPORT void JNICALL
Java_dev_northwind_bakassh_NativeTerm_pushBytes(JNIEnv *env, jclass clazz, jlong h, jbyteArray data, jint len) {
    (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t || !data || len <= 0) return;
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return;
    vterm_input_write(t->vt, (const char *) buf, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    /* 把累积的搬运/脏区事件交出来 */
    vterm_screen_flush_damage(t->screen);
}

JNIEXPORT void JNICALL
Java_dev_northwind_bakassh_NativeTerm_resize(JNIEnv *env, jclass clazz, jlong h, jint cols, jint rows) {
    (void) env; (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t) return;
    if (cols < 2) cols = 2;
    if (rows < 1) rows = 1;
    if (rows > MAX_ROWS) rows = MAX_ROWS;
    t->cols = cols;
    t->rows = rows;
    vterm_set_size(t->vt, rows, cols);
    vterm_screen_flush_damage(t->screen);
}

/** 取出搬运事件：每 8 个 int 一组 [destR0,destC0,destR1,destC1, srcR0,srcC0,srcR1,srcC1] */
JNIEXPORT jint JNICALL
Java_dev_northwind_bakassh_NativeTerm_takeMoveOps(JNIEnv *env, jclass clazz, jlong h, jintArray out) {
    (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t) return 0;
    jint cap = (*env)->GetArrayLength(env, out) / 8;
    jint n = t->move_count < cap ? t->move_count : cap;
    if (n > 0) (*env)->SetIntArrayRegion(env, out, 0, n * 8, (const jint *) t->move_ops);
    t->move_count = 0;
    return n;
}

/** 取出脏行行号，返回个数 */
JNIEXPORT jint JNICALL
Java_dev_northwind_bakassh_NativeTerm_takeDamageRows(JNIEnv *env, jclass clazz, jlong h, jintArray out) {
    (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t) return 0;
    collect_dirty(t);
    jint cap = (*env)->GetArrayLength(env, out);
    jint n = t->dirty_count < cap ? t->dirty_count : cap;
    if (n > 0) (*env)->SetIntArrayRegion(env, out, 0, n, (const jint *) t->dirty_rows);
    return n;
}

/*
 * 归一化属性位（与 libvterm 的位域解耦：它的 underline 占 2 位、reverse 在 bit5，
 * 直接搬 bit 序号迟早画错）。Java 侧按这套位读：
 *   bit0 粗体 / bit1 下划线 / bit2 斜体 / bit3 闪烁 / bit4 反显 / bit5 删除线
 */
#define ATTR_BOLD      1
#define ATTR_UNDERLINE 2
#define ATTR_ITALIC    4
#define ATTR_BLINK     8
#define ATTR_REVERSE   16
#define ATTR_STRIKE    32

static int cell_attrs(const VTermScreenCell *cell) {
    int attrs = 0;
    if (cell->attrs.bold)      attrs |= ATTR_BOLD;
    if (cell->attrs.underline) attrs |= ATTR_UNDERLINE;
    if (cell->attrs.italic)    attrs |= ATTR_ITALIC;
    if (cell->attrs.blink)     attrs |= ATTR_BLINK;
    if (cell->attrs.reverse)   attrs |= ATTR_REVERSE;
    if (cell->attrs.strike)    attrs |= ATTR_STRIKE;
    if (cell->attrs.font == 1) attrs |= ATTR_BOLD;   /* 常见约定：font 1 就是粗体 */
    return attrs;
}

static void fill_cell(TermCtx *t, int row, int col, jint *p) {
    VTermPos pos = { .row = row, .col = col };
    VTermScreenCell cell;
    memset(&cell, 0, sizeof(cell));
    if (!vterm_screen_get_cell(t->screen, pos, &cell)) {
        p[0] = 32; p[1] = t->default_fg; p[2] = t->default_bg; p[3] = 0; p[4] = 1;
        return;
    }
    int fg = color_to_rgb(t, cell.fg);
    int bg = color_to_rgb(t, cell.bg);
    /* 反显 / 隐藏在这里就处理掉：Java 侧只管（已换好的）前景背景，少一层容易出错的逻辑 */
    if (cell.attrs.reverse) { int tmp = fg; fg = bg; bg = tmp; }
    if (cell.attrs.conceal) fg = bg;
    p[0] = (jint) cell.chars[0];
    p[1] = fg;
    p[2] = bg;
    p[3] = cell_attrs(&cell);
    p[4] = cell.width;
}

/**
 * 整行批量取格：out 每格 5 个 int [codepoint, fgRGB, bgRGB, attrs, width]。
 * 必须批量 —— 逐格调用 JNI 的话一整屏 11,704 次调用，光 JNI 开销就十几毫秒，
 * 我们费尽力气省下来的那点渲染时间会被它全吃掉。
 */
JNIEXPORT jint JNICALL
Java_dev_northwind_bakassh_NativeTerm_getRow(JNIEnv *env, jclass clazz, jlong h, jint row, jintArray out) {
    (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t) return 0;
    if (row < 0 || row >= t->rows) return 0;
    jint cap = (*env)->GetArrayLength(env, out) / 5;
    int n = cap < t->cols ? (int) cap : t->cols;
    jint *buf = (*env)->GetIntArrayElements(env, out, NULL);
    if (!buf) return 0;
    for (int c = 0; c < n; c++) fill_cell(t, row, c, buf + c * 5);
    /*
     * 必须是 0（拷回并释放），不能用 JNI_ABORT！
     * JNI_ABORT 的语义是"丢弃 native 对数组的修改" —— 我一度把它当"只读优化"改成了 JNI_ABORT，
     * 结果 770 个 int 全被丢弃，Java 侧拿到全零数组：所有格子 width==0 被跳过，整屏只剩背景色（黑屏）。
     */
    (*env)->ReleaseIntArrayElements(env, out, buf, 0);
    return n;
}

/** 取单元格：out = [codepoint, fgRGB, bgRGB, attrs, width] */
JNIEXPORT jint JNICALL
Java_dev_northwind_bakassh_NativeTerm_getCell(JNIEnv *env, jclass clazz, jlong h, jint row, jint col, jintArray out) {
    (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t) return 0;
    VTermPos pos = { .row = row, .col = col };
    VTermScreenCell cell;
    memset(&cell, 0, sizeof(cell));
    if (!vterm_screen_get_cell(t->screen, pos, &cell)) return 0;

    jint v[5];
    v[0] = (jint) cell.chars[0];
    v[1] = color_to_rgb(t, cell.fg);
    v[2] = color_to_rgb(t, cell.bg);
    v[3] = cell_attrs(&cell);
    v[4] = cell.width;
    (*env)->SetIntArrayRegion(env, out, 0, 5, v);
    return 1;
}

/** 取光标：out = [row, col, visible, shape] */
JNIEXPORT void JNICALL
Java_dev_northwind_bakassh_NativeTerm_getCursor(JNIEnv *env, jclass clazz, jlong h, jintArray out) {
    (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t) return;
    VTermPos pos;
    vterm_state_get_cursorpos(t->state, &pos);
    jint v[4];
    v[0] = pos.row;
    v[1] = pos.col;
    v[2] = t->cursor_visible;
    v[3] = t->cursor_shape;
    (*env)->SetIntArrayRegion(env, out, 0, 4, v);
}

/** 取终端回给宿主的数据（DSR/OSC 应答），返回字节数；M1b 接到 SSH 通道 */
JNIEXPORT jint JNICALL
Java_dev_northwind_bakassh_NativeTerm_takeOutput(JNIEnv *env, jclass clazz, jlong h, jbyteArray out) {
    (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t || t->outlen == 0) return 0;
    jint cap = (*env)->GetArrayLength(env, out);
    jint n = (jint) (t->outlen < (size_t) cap ? t->outlen : (size_t) cap);
    (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) t->outbuf);
    t->outlen = 0;
    return n;
}

/*
 * ---------------- 鼠标 / 滚轮 ----------------
 *
 * 直接用 libvterm 的鼠标 API，而不是自己在 Java 里拼 SGR 序列：
 * vterm_mouse_button 里有 `if(!state->mouse_flags) return;` —— 应用没开鼠标上报时一个字节都不发，
 * 所以普通 shell 下拖屏幕不会灌垃圾；tmux（mouse on）会自己上报开启，滚轮事件就能正确送到 tmux。
 * 滚轮约定：button 4 = 上滚，5 = 下滚（libvterm 内部编码成 0x40/0x41）。行列是 0 基。
 */
JNIEXPORT void JNICALL
Java_dev_northwind_bakassh_NativeTerm_mouseMove(JNIEnv *env, jclass clazz, jlong h, jint row, jint col, jint mod) {
    (void) env; (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t) return;
    vterm_mouse_move(t->vt, row, col, (VTermModifier) mod);
    vterm_screen_flush_damage(t->screen);
}

JNIEXPORT void JNICALL
Java_dev_northwind_bakassh_NativeTerm_mouseButton(JNIEnv *env, jclass clazz, jlong h, jint button, jboolean pressed, jint mod) {
    (void) env; (void) clazz;
    TermCtx *t = (TermCtx *) (intptr_t) h;
    if (!t) return;
    vterm_mouse_button(t->vt, button, pressed ? true : false, (VTermModifier) mod);
    vterm_screen_flush_damage(t->screen);
}

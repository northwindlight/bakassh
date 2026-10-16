package dev.northwind.bakassh;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;

/**
 * 终端界面：SSH + 位图字体渲染 + IME 输入 + 触摸滚动 + 长按选择复制 + 捏合缩放。
 *
 * 主机参数从 Intent 来（由 HostListActivity 传入）；startup 是连上后自动执行的命令。
 */
public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    /**
     * 终端四周留边（dp）。贴边对曲面屏/圆角屏不友好——内容会被边缘弯折或裁掉，
     * 所以留一点安全边距。改这个数即可（3 就够细，想更宽可以调大）。
     */
    private static final int EDGE_INSET_DP = 3;

    private static final String TAG = "sshterm";

    private TerminalView view;
    private EditText input;
    private Button ctrlButton;
    private Button altButton;
    private Button shiftButton;
    private android.view.ActionMode selectionActionMode;
    private long handle;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean sessionStarted;
    private volatile SshTransport transport;
    /** 粘滞修饰键：点一下点亮，作用于下一个输入的键。 */
    private boolean ctrlOn, altOn, shiftOn;

    // 连接参数（来自 Intent，缺省用一份保守默认值）
    private String hName = "";
    private String hHost = "";
    private int hPort = 22;
    private String hUser = "";
    private String hStartup = "";

    static void out(Activity a, String line) {
        Log.i(TAG, line);
        try {
            File f = new File(a.getExternalFilesDir(null), "m1a.txt");
            FileWriter w = new FileWriter(f, true);
            w.write(line + "\n");
            w.close();
        } catch (Throwable t) {
            Log.i(TAG, "file_write_fail=" + t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent it = getIntent();
        if (it != null) {
            hName = it.getStringExtra("name") != null ? it.getStringExtra("name") : hName;
            hHost = it.getStringExtra("host") != null ? it.getStringExtra("host") : hHost;
            hPort = it.getIntExtra("port", hPort);
            hUser = it.getStringExtra("user") != null ? it.getStringExtra("user") : hUser;
            hStartup = it.getStringExtra("startup") != null ? it.getStringExtra("startup") : hStartup;
        }

        try {
            File f = new File(getExternalFilesDir(null), "m1a.txt");
            if (f.exists() && !f.delete()) Log.i(TAG, "old m1a.txt delete failed");
        } catch (Throwable ignored) {
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101014);

        view = new TerminalView(this);
        view.setTargetCols(HostStore.getTargetCols(this, 150));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        int inset = dp(HostStore.getEdgeInset(this, EDGE_INSET_DP));
        vlp.setMargins(inset, inset, inset, inset);   // 四周留边，别贴屏幕边缘
        root.addView(view, vlp);

        root.addView(buildExtraKeys());

        /*
         * 输入框保存输入法的组词内容，只在 commitText / finishComposingText 时发给远端。
         * 空输入框里的退格和方向键另外转发为终端按键。
         */
        input = new androidx.appcompat.widget.AppCompatEditText(this) {
            @Override
            public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                InputConnection ic = super.onCreateInputConnection(outAttrs);
                if (ic == null) return null;
                return new InputConnectionWrapper(ic, true) {
                    @Override
                    public boolean commitText(CharSequence text, int newCursorPosition) {
                        if (text != null && text.length() > 0) sendTyped(text.toString());
                        // Keep composing text local; only committed text goes to SSH.
                        super.commitText("", 1);
                        input.getText().clear();
                        return true;
                    }

                    @Override
                    public boolean finishComposingText() {
                        Editable pending = input.getText();
                        String text = pending.toString();
                        boolean ok = super.finishComposingText();
                        if (!text.isEmpty()) sendTyped(text);
                        pending.clear();
                        return ok;
                    }

                    @Override
                    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                        if (input.length() > 0) return super.deleteSurroundingText(beforeLength, afterLength);
                        if (beforeLength > 0 && afterLength == 0) {
                            for (int i = 0; i < beforeLength; i++) sendTerminalKey(KeyEvent.KEYCODE_DEL);
                            return true;
                        }
                        return super.deleteSurroundingText(beforeLength, afterLength);
                    }

                    @Override
                    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                        if (input.length() > 0)
                            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
                        return deleteSurroundingText(beforeLength, afterLength);
                    }

                    @Override
                    public boolean sendKeyEvent(KeyEvent event) {
                        if (input.length() == 0 && isTerminalKey(event.getKeyCode())) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN) sendTerminalKey(event.getKeyCode());
                            return true;
                        }
                        return super.sendKeyEvent(event);
                    }
                };
            }
        };
        input.setBackgroundColor(0x00000000);
        input.setTextColor(0x00000000);
        input.setCursorVisible(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        root.addView(input, new LinearLayout.LayoutParams(1, 1));

        setContentView(root);
        out(this, "START device=" + android.os.Build.MODEL + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " target=" + hUser + "@" + hHost + ":" + hPort
                + " startup=\"" + hStartup + "\" cols=" + view.getCols());
        running = true;
        startIoThread();

    }

    private void updateSelectionToolbar(boolean active) {
        if (!active) {
            android.view.ActionMode mode = selectionActionMode;
            selectionActionMode = null;
            if (mode != null) mode.finish();
            return;
        }
        if (selectionActionMode != null) {
            selectionActionMode.invalidateContentRect();
            return;
        }
        selectionActionMode = view.startActionMode(new android.view.ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                menu.add(0, android.R.id.copy, 0, "复制").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(0, android.R.id.selectAll, 1, "全选").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(0, android.R.id.paste, 2, "粘贴").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
                return true;
            }
            @Override
            public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) { return false; }
            @Override
            public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                if (item.getItemId() == android.R.id.copy) copySelection();
                else if (item.getItemId() == android.R.id.selectAll) view.selectAll();
                else if (item.getItemId() == android.R.id.paste) pasteFromClipboard();
                else return false;
                return true;
            }
            @Override
            public void onDestroyActionMode(android.view.ActionMode mode) {
                selectionActionMode = null;
                view.clearSelection();
            }
            @Override
            public void onGetContentRect(android.view.ActionMode mode, View target, android.graphics.Rect rect) {
                view.getSelectionBounds(rect);
            }
        }, android.view.ActionMode.TYPE_FLOATING);
    }

    private void copySelection() {
        String t = view.getSelectionText();
        if (t.isEmpty()) {
            toast("没选中文本");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("bakaSSH", t));
        out(this, "COPIED chars=" + t.length());
        toast("已复制 " + t.length() + " 字符");
        view.clearSelection();
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            toast("剪贴板是空的");
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (cs == null) return;
        String t = cs.toString();
        sendBytes(t.getBytes(StandardCharsets.UTF_8));
        out(this, "PASTED chars=" + t.length());
        toast("已粘贴 " + t.length() + " 字符");
        view.clearSelection();
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把「不产生文本的按键」翻译成终端字节；返回 true 表示已处理。
     * 退格必须发 DEL(0x7f)，这是终端约定（发 0x08 在不少程序里是别的意思）。
     */
    private boolean sendTerminalKey(int keyCode) {
        String seq = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:           seq = "\u007f";     break;
            case KeyEvent.KEYCODE_FORWARD_DEL:   seq = "\u001b[3~";  break;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:  seq = "\r";         break;
            case KeyEvent.KEYCODE_TAB:           seq = "\t";         break;
            case KeyEvent.KEYCODE_ESCAPE:        seq = "\u001b";     break;
            case KeyEvent.KEYCODE_DPAD_UP:       seq = "\u001b[A";   break;
            case KeyEvent.KEYCODE_DPAD_DOWN:     seq = "\u001b[B";   break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:    seq = "\u001b[C";   break;
            case KeyEvent.KEYCODE_DPAD_LEFT:     seq = "\u001b[D";   break;
            case KeyEvent.KEYCODE_MOVE_HOME:     seq = "\u001b[H";   break;
            case KeyEvent.KEYCODE_MOVE_END:      seq = "\u001b[F";   break;
            case KeyEvent.KEYCODE_PAGE_UP:       seq = "\u001b[5~";  break;
            case KeyEvent.KEYCODE_PAGE_DOWN:     seq = "\u001b[6~";  break;
            default: break;
        }
        if (seq == null) return false;
        sendBytes(seq.getBytes(StandardCharsets.UTF_8));
        out(this, "KEY " + keyCode);
        return true;
    }

    private boolean isTerminalKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_TAB: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MOVE_HOME: case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_PAGE_DOWN:
                return true;
            default: return false;
        }
    }

    /** 硬键盘/系统按键：直接喂终端，别让输入框吃掉。 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (handle != 0 && input != null && BaseInputConnection.getComposingSpanStart(input.getText()) < 0) {
            if (isTerminalKey(e.getKeyCode())) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) sendTerminalKey(e.getKeyCode());
                return true;
            }
            // Physical keyboards produce KeyEvents instead of commitText.
            int cp = e.getUnicodeChar(e.getMetaState() & ~KeyEvent.META_CTRL_MASK);
            if (cp > 0 && Character.isValidCodePoint(cp)) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    ctrlOn |= e.isCtrlPressed();
                    altOn |= e.isAltPressed();
                    sendTyped(new String(Character.toChars(cp)));
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** 扩展按键栏：按配置渲染（默认两行，学 Termux 的 extra-keys）。 */
    private View buildExtraKeys() {
        java.util.List<String[]> rows = ExtraKeys.parse(ExtraKeys.get(this));
        /*
         * 关键：所有行都按「最大列数」切成等宽槽位，每键恰好 1 槽，不足的行用不可见占位补满。
         * 之前每行各自按 weight 均分 → 8 键的行和 10 键的行键宽不同，方向键对不齐、上下不一样大。
         */
        int slots = 1;
        for (String[] r : rows) slots = Math.max(slots, r.length);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF17171C);
        for (String[] rowKeys : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : rowKeys) {
                Button b = keyButton(labelFor(key));
                b.setTag(key);
                b.setOnClickListener(v -> onExtraKey((String) v.getTag()));
                String n = key.toUpperCase();
                if (n.equals("CTRL")) ctrlButton = b;
                else if (n.equals("ALT")) altButton = b;
                else if (n.equals("SHIFT")) shiftButton = b;
                row.addView(b, slotLp());
            }
            for (int i = rowKeys.length; i < slots; i++) row.addView(new View(this), slotLp());
            box.addView(row);
        }
        return box;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams slotLp() {
        /*
         * 固定高度：MaterialButton 的默认最小高度很大，两行会把终端挤成 0 高度
         * （实测：view=1256x0 → 终端塌成 1 行、远端 PTY 被设成 73x1）。
         */
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        int m = dp(2);
        p.setMargins(m, m, m, m);
        return p;
    }

    /** Material 3 圆角按键（Pixel 风格，带波纹）。 */
    private Button keyButton(String text) {
        com.google.android.material.button.MaterialButton b =
                new com.google.android.material.button.MaterialButton(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);   // 照 Termux：键大字也大
        b.setSingleLine(true);          // 防止 HOME 之类被折成两行
        b.setTextColor(0xFFE6E6EA);
        b.setPadding(0, dp(6), 0, dp(6));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setCornerRadius(dp(10));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A2A31));
        return b;
    }

    private String labelFor(String key) {
        switch (key.toUpperCase()) {
            case "UP":       return "↑";
            case "DOWN":     return "↓";
            case "LEFT":     return "←";
            case "RIGHT":    return "→";
            case "ENTER":    return "⏎";
            case "PGUP":     return "⇞";
            case "PGDN":     return "⇟";
            case "BKSP":     return "⌫";
            case "KEYBOARD": return "⌨";
            case "PASTE":    return "粘贴";
            default:         return key;
        }
    }

    private void onExtraKey(String key) {
        String n = key.toUpperCase();
        if (ExtraKeys.isModifier(n)) {
            if (n.equals("CTRL")) { ctrlOn = !ctrlOn; label(ctrlButton, "CTRL", ctrlOn); }
            else if (n.equals("ALT")) { altOn = !altOn; label(altButton, "ALT", altOn); }
            else { shiftOn = !shiftOn; label(shiftButton, "SHIFT", shiftOn); }
            return;
        }
        if (n.equals("FONT-")) { view.setTargetCols(view.getCols() + 15); return; }   // 列少 = 字大
        if (n.equals("FONT+")) { view.setTargetCols(view.getCols() - 15); return; }
        if (n.equals("KEYBOARD")) { showIme(); return; }
        if (n.equals("PASTE")) { pasteFromClipboard(); return; }
        byte[] b = ExtraKeys.bytesFor(key);
        if (b != null) sendBytes(b);
        showIme();
    }

    private void label(Button b, String name, boolean on) {
        if (b != null) b.setText(on ? name + "*" : name);
    }

    /** 普通文本：直接按 UTF-8 发给远端；CTRL 生效时把字母转成控制字符。 */
    private void sendTyped(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') { sb.append('\r'); continue; }
            if (ctrlOn) {
                if (cp >= 'a' && cp <= 'z') cp = cp - 'a' + 1;
                else if (cp >= 'A' && cp <= 'Z') cp = cp - 'A' + 1;
                else if (cp == ' ') cp = 0;
                else if (cp == '[') cp = 27;
                else if (cp == '\\') cp = 28;
                else if (cp == ']') cp = 29;
                else if (cp == '^') cp = 30;
                else if (cp == '_') cp = 31;
                ctrlOn = false;
                label(ctrlButton, "CTRL", false);
            }
            // ALT = ESC 前缀（终端里 meta 的通用约定）
            if (altOn) {
                sb.append('\u001b');
                altOn = false;
                label(altButton, "ALT", false);
            }
            if (shiftOn) {
                cp = Character.toUpperCase(cp);
                shiftOn = false;
                label(shiftButton, "SHIFT", false);
            }
            sb.appendCodePoint(cp);
        }
        if (sb.length() > 0) sendBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void sendBytes(byte[] b) {
        final SshTransport t = transport;
        if (t == null || !running) return;
        // Preserve protocol reply order before user input, including replies
        // generated by mouse events.
        pumpOutput();
        queueBytes(t, b);
    }

    private void queueBytes(SshTransport t, byte[] b) {
        // 关键：绝不能在这里直接写 socket —— UI 线程做网络 IO 会抛 NetworkOnMainThreadException。
        postIo(() -> {
            try {
                t.write(b);
            } catch (Throwable ex) {
                out(this, "io write failed: " + ex);
            }
        });
    }

    /** 把 libvterm 产生、需要回给宿主的数据（DSR 应答、鼠标序列等）投递给 IO 线程。 */
    private void pumpOutput() {
        synchronized (NativeTerm.class) {
            final SshTransport t = transport;
            if (handle == 0 || t == null || !running) return;
            NativeTerm.drainOutput(handle, bytes -> queueBytes(t, bytes));
        }
    }

    // ---------------- 专用 IO 线程 ----------------

    private final java.util.concurrent.LinkedBlockingQueue<Runnable> ioQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private Thread ioThread;

    private void startIoThread() {
        if (ioThread != null) return;
        ioThread = new Thread(() -> {
            while (running) {
                try {
                    Runnable r = ioQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (r != null) r.run();
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t) {
                    out(this, "io thread: " + t);
                }
            }
        }, "ssh-io");
        ioThread.start();
    }

    private void postIo(Runnable r) {
        if (running) ioQueue.offer(r);
    }

    private void showIme() {
        try {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;

        view.setGridListener((c, r) -> {
            if (handle == 0) {
                handle = NativeTerm.create(c, r);
                out(this, "NativeTerm.create -> handle=" + handle + " grid=" + c + "x" + r);
                if (handle == 0) {
                    out(this, "FATAL: libvterm create failed");
                    return;
                }
                view.attachNative(handle);
                startSessionOnce();
            } else {
                NativeTerm.resize(handle, c, r);
                final int nc = c, nr = r;
                final SshTransport t = transport;
                if (t != null) postIo(() -> t.resize(nc, nr));   // resize 也要走 IO 线程
                out(this, "GRID resize -> " + c + "x" + r);
            }
        });

        view.setInputSink(new TerminalView.InputSink() {
            @Override
            public void mouse(int row, int col, int button, boolean pressed) {
                if (handle == 0) return;
                NativeTerm.mouseMove(handle, row, col, 0);
                NativeTerm.mouseButton(handle, button, pressed, 0);
                pumpOutput();
            }

            @Override
            public void tap() {
                showIme();
            }
        });

        // 选择状态 → 显示/隐藏操作条
        view.setSelectionListener((active, hasRange) -> {
            updateSelectionToolbar(active);
            out(this, "SELECTION active=" + active + " hasRange=" + hasRange);
        });

        // 捏合缩放后持久化列数（下次进来还是这个字号）
        view.setZoomListener(cols -> {
            HostStore.setTargetCols(this, cols);
            out(this, "ZOOM targetCols=" + cols);
            toast("列数 " + cols);
        });

        if (handle != 0) startSessionOnce();
        input.postDelayed(this::showIme, 400);
    }

    private void startSessionOnce() {
        if (sessionStarted || handle == 0) return;
        sessionStarted = true;
        transport = new SshTransport();
        worker = new Thread(this::sessionRunner, "session");
        worker.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Pausing (Home, app switch, screen lock) keeps this session alive.
        // Activity destruction is the boundary at which we release it.
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (view != null) view.clearSelection();
        if (transport != null) {
            transport.disconnect();
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (ioThread != null) ioThread.interrupt();
        ioQueue.clear();
        synchronized (NativeTerm.class) {
            handle = 0;
            if (view != null) view.destroy();
        }
        super.onDestroy();
    }

    private void sessionRunner() {
        try {
            Thread.sleep(800);
            if (!running) return;

            try {
                KeyManager.ensureKey(this);
            } catch (Throwable t) {
                out(this, "keygen FAILED: " + t);
                return;
            }

            try {
                final SshTransport t = transport;
                if (!running) return;
                String status = t.connect(hHost, hPort, hUser,
                        KeyManager.privFile(this).getAbsolutePath(),
                        view.getCols(), view.getRows(),
                        new SshTransport.Listener() {
                            @Override
                            public void onBytes(byte[] buf, int len) {
                                synchronized (NativeTerm.class) {
                                    if (!running || handle == 0) return;
                                    NativeTerm.receive(handle, buf, len, bytes -> queueBytes(t, bytes));
                                }
                                view.notifyNewData();
                            }

                            @Override
                            public void onStatus(String msg) {
                                out(MainActivity.this, "SSH status: " + msg);
                                /*
                                 * 会话结束就回主界面：
                                 *  - "channel EOF"：远端 shell 退出（用户敲了 exit）
                                 *  - "reader stopped"：连接断了
                                 * 否则用户会停在一个再也不会更新的死终端页上。
                                 */
                                if (msg.startsWith("channel EOF") || msg.startsWith("reader stopped")) {
                                    runOnUiThread(() -> {
                                        if (running) { toast("会话结束"); finish(); }
                                    });
                                }
                            }
                        });
                out(this, "SSH " + status);

                Thread.sleep(1500);   // 等提示符
                if (hStartup != null && !hStartup.isEmpty() && running) {
                    String cmd = hStartup.endsWith("\n") ? hStartup : hStartup + "\n";
                    final byte[] bytes = cmd.getBytes(StandardCharsets.UTF_8);
                    postIo(() -> {
                        try {
                            t.write(bytes);
                        } catch (Throwable ex) {
                            out(this, "startup cmd failed: " + ex);
                        }
                    });
                    out(this, "STARTUP queued: " + hStartup.trim() + " rx_before=" + transport.getRxBytes());
                }
                for (int i = 0; i < 60 && running; i++) {
                    Thread.sleep(500);
                }
                out(this, "SESSION rx_bytes=" + transport.getRxBytes());
                out(this, "DONE");
            } catch (Throwable t) {
                if (!running) return;
                out(this, "SSH FAILED: " + t);
                if (transport != null) {
                    transport.disconnect();
                    transport = null;
                }
                // 连不上也要能回主界面（先给 2.5 秒让 toast 看得见）
                final String why = String.valueOf(t.getMessage());
                runOnUiThread(() -> toast("连接失败：" + why));
                new android.os.Handler(getMainLooper()).postDelayed(this::finish, 2500);
            }
        } catch (InterruptedException ie) {
            out(this, "worker interrupted");
        } catch (Throwable t) {
            out(this, "worker ERROR " + t);
        }
    }
}

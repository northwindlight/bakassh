package dev.northwind.bakassh;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 终端界面：紧凑工具栏、扩展按键、SSH、位图字体渲染、触摸滚动与真实缩放。
 */
public class MainActivity extends AppCompatActivity {

    private static final int EDGE_INSET_DP = 3;
    private static final String TAG = "sshterm";

    private TerminalView view;
    private EditText input;
    private MaterialButton ctrlButton;
    private MaterialButton altButton;
    private MaterialButton shiftButton;
    private TextView tvTermTitle;
    private TextView tvTermCols;
    private LinearLayout layoutExtraKeysBox;
    private android.view.ActionMode selectionActionMode;
    private long handle;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean sessionStarted;
    private volatile SshTransport transport;
    private final PendingResize pendingResize = new PendingResize();

    /** 粘滞修饰键：点一下点亮，作用于下一个输入的键。 */
    private boolean ctrlOn, altOn, shiftOn;

    // 连接参数
    private String hName = "";
    private String hHost = "";
    private int hPort = 22;
    private String hUser = "";
    private String hStartup = "";
    private String hAuthType = "publickey";
    private String hKeyId = "default";
    private String hPassword = "";

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
        setContentView(R.layout.activity_terminal);

        // 全屏终端：把内容让开刘海/挖孔和底部手势条，否则顶栏会被摄像头挖孔盖住。
        final View terminalRoot = findViewById(R.id.root_terminal);
        ViewCompat.setOnApplyWindowInsetsListener(terminalRoot, (v, insets) -> {
            androidx.core.graphics.Insets cutout =
                    insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets nav =
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(cutout.left, cutout.top, cutout.right, nav.bottom);
            return insets;
        });

        Intent it = getIntent();
        if (it != null) {
            hName = it.getStringExtra("name") != null ? it.getStringExtra("name") : hName;
            hHost = it.getStringExtra("host") != null ? it.getStringExtra("host") : hHost;
            hPort = it.getIntExtra("port", hPort);
            hUser = it.getStringExtra("user") != null ? it.getStringExtra("user") : hUser;
            hStartup = it.getStringExtra("startup") != null ? it.getStringExtra("startup") : hStartup;
            hAuthType = it.getStringExtra("auth_type") != null ? it.getStringExtra("auth_type") : hAuthType;
            hKeyId = it.getStringExtra("key_id") != null ? it.getStringExtra("key_id") : hKeyId;
            hPassword = it.getStringExtra("password") != null ? it.getStringExtra("password") : hPassword;
        }

        try {
            File f = new File(getExternalFilesDir(null), "m1a.txt");
            if (f.exists() && !f.delete()) Log.i(TAG, "old m1a.txt delete failed");
        } catch (Throwable ignored) {}

        view = findViewById(R.id.terminal_view);
        view.setPreferredColumns(HostStore.getZoomColumns(this, 150));

        FrameLayout container = findViewById(R.id.layout_terminal_container);
        int inset = dp(HostStore.getEdgeInset(this, EDGE_INSET_DP));
        FrameLayout.LayoutParams vlp = (FrameLayout.LayoutParams) view.getLayoutParams();
        vlp.setMargins(inset, inset, inset, inset);
        view.setLayoutParams(vlp);

        tvTermTitle = findViewById(R.id.tv_term_title);
        tvTermCols = findViewById(R.id.tv_term_cols);
        String displayTitle = (hName != null && !hName.trim().isEmpty()) ? hName : (hUser + "@" + hHost);
        tvTermTitle.setText(displayTitle);
        tvTermCols.setText(HostStore.getZoomColumns(this, 150) + "列");

        findViewById(R.id.btn_term_paste).setOnClickListener(v -> pasteFromClipboard());
        findViewById(R.id.btn_term_keyboard).setOnClickListener(v -> toggleIme());
        findViewById(R.id.btn_term_exit).setOnClickListener(v -> {
            androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, v);
            menu.getMenu().add(0, 1, 0, layoutExtraKeysBox.getVisibility() == View.VISIBLE ? "隐藏扩展按键" : "显示扩展按键");
            menu.getMenu().add(0, 2, 1, "断开连接");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    layoutExtraKeysBox.setVisibility(layoutExtraKeysBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                } else {
                    new MaterialAlertDialogBuilder(this).setTitle("断开连接？")
                            .setMessage("将关闭与 " + hHost + " 的 SSH 连接。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("断开", (d, w) -> finish()).show();
                }
                return true;
            });
            menu.show();
        });

        layoutExtraKeysBox = findViewById(R.id.layout_extra_keys_box);
        populateExtraKeys();

        /*
         * 透明输入框用于接收输入法文本和组词，不干扰整体界面布局。
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
                            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                sendTerminalKey(event.getKeyCode());
                            }
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
        container.addView(input, new FrameLayout.LayoutParams(1, 1));

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
            toast("未选中文本");
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
            toast("剪贴板为空");
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
        } catch (Throwable ignored) {}
    }

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

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (handle != 0 && input != null && BaseInputConnection.getComposingSpanStart(input.getText()) < 0) {
            if (isTerminalKey(e.getKeyCode())) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) sendTerminalKey(e.getKeyCode());
                return true;
            }
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

    /** 构造 Pixel Material 3 风格扩展按键栏 */
    private void toggleIme() {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(findViewById(R.id.root_terminal));
        if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } else {
            showIme();
        }
    }

    private void populateExtraKeys() {
        layoutExtraKeysBox.removeAllViews();
        List<String[]> rows = ExtraKeys.parse(ExtraKeys.get(this));
        int slots = 1;
        for (String[] r : rows) slots = Math.max(slots, r.length);

        for (String[] rowKeys : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : rowKeys) {
                MaterialButton b = keyButton(labelFor(key));
                b.setTag(key);
                b.setOnClickListener(v -> onExtraKey((String) v.getTag()));
                String n = key.toUpperCase();
                if (n.equals("CTRL")) ctrlButton = b;
                else if (n.equals("ALT")) altButton = b;
                else if (n.equals("SHIFT")) shiftButton = b;
                row.addView(b, slotLp());
            }
            for (int i = rowKeys.length; i < slots; i++) row.addView(new View(this), slotLp());
            layoutExtraKeysBox.addView(row);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams slotLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
        int m = dp(2);
        p.setMargins(m, m, m, m);
        return p;
    }

    /** Pixel Material 3 药丸圆角按键（高度与波纹适配） */
    private MaterialButton keyButton(String text) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setSingleLine(true);
        b.setTextColor(getResources().getColor(R.color.term_on_surface));
        b.setPadding(0, dp(4), 0, dp(4));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setCornerRadius(dp(12));
        b.setBackgroundTintList(ColorStateList.valueOf(getResources().getColor(R.color.terminal_extra_key_bg)));
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
            if (n.equals("CTRL")) {
                ctrlOn = !ctrlOn;
                updateModifierButtonState(ctrlButton, "CTRL", ctrlOn);
            } else if (n.equals("ALT")) {
                altOn = !altOn;
                updateModifierButtonState(altButton, "ALT", altOn);
            } else {
                shiftOn = !shiftOn;
                updateModifierButtonState(shiftButton, "SHIFT", shiftOn);
            }
            return;
        }
        if (n.equals("FONT-")) {
            view.scaleFontBy(1f / 1.1f);
            return;
        }
        if (n.equals("FONT+")) {
            view.scaleFontBy(1.1f);
            return;
        }
        if (n.equals("KEYBOARD")) {
            toggleIme();
            return;
        }
        if (n.equals("PASTE")) {
            pasteFromClipboard();
            return;
        }
        byte[] b = ExtraKeys.bytesFor(key);
        if (b != null) sendBytes(b);
        showIme();
    }

    /** 更新粘滞修饰键高亮状态（激活时点亮 Google Blue） */
    private void updateModifierButtonState(MaterialButton b, String name, boolean on) {
        if (b == null) return;
        b.setText(on ? name + "★" : name);
        if (on) {
            b.setBackgroundTintList(ColorStateList.valueOf(getResources().getColor(R.color.terminal_extra_key_active_bg)));
            b.setTextColor(getResources().getColor(R.color.terminal_extra_key_active_text));
        } else {
            b.setBackgroundTintList(ColorStateList.valueOf(getResources().getColor(R.color.terminal_extra_key_bg)));
            b.setTextColor(getResources().getColor(R.color.term_on_surface));
        }
    }

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
                updateModifierButtonState(ctrlButton, "CTRL", false);
            }
            if (altOn) {
                sb.append('\u001b');
                altOn = false;
                updateModifierButtonState(altButton, "ALT", false);
            }
            if (shiftOn) {
                cp = Character.toUpperCase(cp);
                shiftOn = false;
                updateModifierButtonState(shiftButton, "SHIFT", false);
            }
            sb.appendCodePoint(cp);
        }
        if (sb.length() > 0) sendBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void sendBytes(byte[] b) {
        final SshTransport t = transport;
        if (t == null || !running) return;
        pumpOutput();
        queueBytes(t, b);
    }

    private void queueBytes(SshTransport t, byte[] b) {
        postIo(() -> {
            try {
                t.write(b);
            } catch (Throwable ex) {
                out(this, "io write failed: " + ex);
            }
        });
    }

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
        } catch (Throwable ignored) {}
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
                final SshTransport t = transport;
                if (t != null && pendingResize.offer(c, r)) postIo(() -> {
                    int[] size;
                    while ((size = pendingResize.poll()) != null) t.resize(size[0], size[1]);
                });
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

        view.setSelectionListener((active, hasRange) -> {
            updateSelectionToolbar(active);
            out(this, "SELECTION active=" + active + " hasRange=" + hasRange);
        });

        view.setZoomListener(cols -> {
            HostStore.setZoomColumns(this, cols);
            if (tvTermCols != null) {
                tvTermCols.setText(cols + "列");
            }
            out(this, "ZOOM targetCols=" + cols);
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

            String keyPath = null;
            String keyPassphrase = null;

            if ("password".equalsIgnoreCase(hAuthType)) {
                if (hPassword == null || hPassword.isEmpty()) {
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final String[] pwdResult = new String[]{""};
                    final boolean[] cancelled = new boolean[]{false};
                    runOnUiThread(() -> {
                        LinearLayout box = new LinearLayout(this);
                        box.setOrientation(LinearLayout.VERTICAL);
                        box.setPadding(dp(24), dp(8), dp(24), dp(4));

                        TextInputLayout textInputLayout = new TextInputLayout(this);
                        textInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED);
                        textInputLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
                        textInputLayout.setEndIconTintList(ColorStateList.valueOf(getResources().getColor(R.color.term_on_surface_variant)));
                        textInputLayout.setHint("请输入主机登录密码");

                        TextInputEditText et = new TextInputEditText(this);
                        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        et.setTextColor(getResources().getColor(R.color.term_on_surface));
                        textInputLayout.addView(et, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                        box.addView(textInputLayout);

                        new MaterialAlertDialogBuilder(this)
                                .setTitle("SSH 密码认证")
                                .setMessage(hUser + "@" + hHost + ":" + hPort)
                                .setView(box)
                                .setCancelable(false)
                                .setPositiveButton("连接", (d, w) -> {
                                    pwdResult[0] = et.getText() != null ? et.getText().toString() : "";
                                    latch.countDown();
                                })
                                .setNegativeButton("取消", (d, w) -> {
                                    cancelled[0] = true;
                                    latch.countDown();
                                    finish();
                                })
                                .show();
                    });
                    latch.await();
                    if (cancelled[0] || !running) return;
                    hPassword = pwdResult[0];
                }
            } else {
                KeyItem key;
                try {
                    key = KeyManager.getKey(this, hKeyId);
                } catch (Throwable t) {
                    out(this, "getKey FAILED: " + t);
                    return;
                }
                if (key == null) {
                    runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                            .setTitle("没有可用的密钥")
                            .setMessage("请返回主机列表，生成或导入私钥，并在主机配置中选择它。")
                            .setPositiveButton("返回", (d, w) -> finish())
                            .setOnCancelListener(d -> finish()).show());
                    return;
                }
                keyPath = KeyManager.getPrivFile(this, key.id).getAbsolutePath();
                if (key.encrypted) {
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final String[] passResult = new String[]{""};
                    final boolean[] cancelled = new boolean[]{false};
                    runOnUiThread(() -> {
                        LinearLayout box = new LinearLayout(this);
                        box.setOrientation(LinearLayout.VERTICAL);
                        box.setPadding(dp(24), dp(8), dp(24), dp(4));

                        TextInputLayout textInputLayout = new TextInputLayout(this);
                        textInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED);
                        textInputLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
                        textInputLayout.setEndIconTintList(ColorStateList.valueOf(getResources().getColor(R.color.term_on_surface_variant)));
                        textInputLayout.setHint("私钥保护口令");

                        TextInputEditText et = new TextInputEditText(this);
                        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        et.setTextColor(getResources().getColor(R.color.term_on_surface));
                        textInputLayout.addView(et, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                        box.addView(textInputLayout);

                        new MaterialAlertDialogBuilder(this)
                                .setTitle("解锁密钥")
                                .setMessage("正在连接使用密钥: " + key.name)
                                .setView(box)
                                .setCancelable(false)
                                .setPositiveButton("确定", (d, w) -> {
                                    passResult[0] = et.getText() != null ? et.getText().toString() : "";
                                    latch.countDown();
                                })
                                .setNegativeButton("取消", (d, w) -> {
                                    cancelled[0] = true;
                                    latch.countDown();
                                    finish();
                                })
                                .show();
                    });
                    latch.await();
                    if (cancelled[0] || !running) return;
                    keyPassphrase = passResult[0];
                }
            }

            try {
                final SshTransport t = transport;
                if (!running) return;
                String status = t.connect(hHost, hPort, hUser, hAuthType,
                        keyPath, keyPassphrase, hPassword,
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
                                if (msg.startsWith("channel EOF") || msg.startsWith("reader stopped")) {
                                    runOnUiThread(() -> {
                                        if (running) { toast("会话结束"); finish(); }
                                    });
                                }
                            }
                        });
                out(this, "SSH " + status);

                Thread.sleep(1500);
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

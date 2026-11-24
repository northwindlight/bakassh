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
    private TextView tvTermStatus;
    private androidx.drawerlayout.widget.DrawerLayout drawer;
    private SessionDrawer sessionDrawer;
    private MaterialButton followOutputButton;
    private View sessionStateCard;
    /** 用户收起过状态卡片的会话与状态；状态变化后重新显示。 */
    private String hiddenSessionState;
    /** 重新连接时被接替的旧会话；新会话创建后关闭它并占据原位置。 */
    private String replacingSessionId;
    private LinearLayout layoutExtraKeysBox;
    private android.view.ActionMode selectionActionMode;
    private long handle;
    private SessionService service;
    private TerminalSession session;
    private String requestedSessionId;
    private boolean bound, resumed, preparingConnection, attachingSession, pendingHost;
    private android.app.Dialog credentialDialog;
    private android.app.Dialog hostKeyDialog;
    private HostKeyRequest displayedHostKey;
    private final java.util.Set<String> displayedKeyChanges = new java.util.HashSet<>();
    private final android.content.ServiceConnection connection = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
            service = ((SessionService.LocalBinder) binder).service();
            if (resumed) service.addListener(sessionListener);
            openRequestedSession();
        }
        @Override public void onServiceDisconnected(android.content.ComponentName name) {
            service = null;
            detachSession();
            toast("会话已在后台结束，请重新连接");
            finish();
        }
    };
    private final SessionService.Listener sessionListener = new SessionService.Listener() {
        @Override public void onSessionsChanged() {
            if (session != null && service.get(session.id) == null) {
                int closedIndex = sessionDrawer.indexOf(session.id);
                detachSession();
                List<TerminalSession> remaining = service.sessions();
                if (remaining.isEmpty()) { finish(); return; }
                attachSession(remaining.get(Math.max(0, Math.min(closedIndex, remaining.size() - 1))));
            }
            updateSessionTitle();
            updateSessionDrawer();
            updateHostKeyPrompt();
        }
        @Override public void onSessionData(TerminalSession changed) {
            if (session == changed) { changed.unread = false; view.notifyNewData(); }
            else sessionDrawer.data(changed);
        }
    };

    /** 粘滞修饰键：点一下点亮，作用于下一个输入的键。 */
    private boolean ctrlOn, altOn, shiftOn;
    private String renderedKeyLayout;
    private final java.util.List<RepeatKeyButton> repeatKeys = new java.util.ArrayList<>();

    // 连接参数
    private String hName = "";
    private String hHost = "";
    private int hPort = 22;
    private String hUser = "";
    private String hStartup = "";
    private String hAuthType = "publickey";
    private String hKeyId = "default";
    private String hPassword = "";
    private String hHostId;
    private boolean hPasswordUnavailable;

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
        // 终端外围（工具栏、快捷键、侧栏、弹窗）跟随壁纸取深色方案；终端画面本身保持固定深色。
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this,
                new com.google.android.material.color.DynamicColorsOptions.Builder()
                        .setThemeOverlay(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_Dark)
                        .build());
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_terminal);

        // 全屏终端：把内容让开刘海/挖孔和底部手势条，否则顶栏会被摄像头挖孔盖住。
        drawer = findViewById(R.id.terminal_drawer);
        drawer.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerSlide(View drawerView, float slideOffset) {
                if (slideOffset > 0) stopKeyRepeats();
            }
        });
        sessionDrawer = new SessionDrawer(findViewById(R.id.session_drawer));
        final View terminalRoot = drawer;
        ViewCompat.setOnApplyWindowInsetsListener(terminalRoot, (v, insets) -> {
            androidx.core.graphics.Insets cutout =
                    insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets nav =
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(cutout.left, cutout.top, cutout.right, nav.bottom);
            return insets;
        });

        Intent it = getIntent();
        requestedSessionId = savedInstanceState == null ? null : savedInstanceState.getString(SessionService.SESSION_ID);
        if (requestedSessionId == null && it != null) requestedSessionId = it.getStringExtra(SessionService.SESSION_ID);
        if (it != null) readHost(it);
        pendingHost = requestedSessionId == null && it != null && it.hasExtra("host");

        view = findViewById(R.id.terminal_view);
        view.setPreferredColumns(HostStore.getZoomColumns(this, 150));

        FrameLayout container = findViewById(R.id.layout_terminal_container);
        int inset = dp(HostStore.getEdgeInset(this, EDGE_INSET_DP));
        FrameLayout.LayoutParams vlp = (FrameLayout.LayoutParams) view.getLayoutParams();
        vlp.setMargins(inset, inset, inset, inset);
        view.setLayoutParams(vlp);

        tvTermTitle = findViewById(R.id.tv_term_title);
        tvTermCols = findViewById(R.id.tv_term_cols);
        tvTermStatus = findViewById(R.id.tv_term_status);
        followOutputButton = findViewById(R.id.btn_follow_output);
        followOutputButton.setOnClickListener(v -> view.followOutput());
        sessionStateCard = findViewById(R.id.card_session_state);
        findViewById(R.id.btn_session_state_reconnect).setOnClickListener(v -> reconnect());
        findViewById(R.id.btn_session_state_close).setOnClickListener(v -> {
            if (session == null || service == null) return;
            if (session.isActive()) SessionSwitcher.confirmClose(this, service, session);
            else service.close(session.id);
        });
        findViewById(R.id.btn_session_state_hide).setOnClickListener(v -> {
            if (session == null) return;
            hiddenSessionState = session.id + session.state();
            updateSessionState();
        });
        view.setScrollListener(this::updateScrollStatus);
        findViewById(R.id.btn_session_add).setOnClickListener(v -> newSession());
        findViewById(R.id.btn_close_session_drawer).setOnClickListener(v -> drawer.closeDrawers());
        findViewById(R.id.btn_edit_extra_keys).setOnClickListener(v -> {
            drawer.closeDrawers();
            ExtraKeysEditor.show(this, this::populateExtraKeys);
        });
        String displayTitle = (hName != null && !hName.trim().isEmpty()) ? hName : (hUser + "@" + hHost);
        tvTermTitle.setText(displayTitle);
        tvTermCols.setText(HostStore.getZoomColumns(this, 150) + "列");

        findViewById(R.id.btn_term_paste).setOnClickListener(v -> pasteFromClipboard());
        findViewById(R.id.btn_term_keyboard).setOnClickListener(v -> toggleIme());
        findViewById(R.id.btn_term_sessions).setOnClickListener(v -> showSessions());
        tvTermTitle.setOnClickListener(v -> showSessions());
        findViewById(R.id.btn_term_exit).setOnClickListener(v -> {
            androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, v);
            menu.getMenu().add(0, 3, 0, "新建会话");
            if (session != null && !session.isActive()) menu.getMenu().add(0, 7, 1, getString(R.string.reconnect));
            menu.getMenu().add(0, 5, 2, "重命名 / 管理当前会话");
            menu.getMenu().add(0, 1, 3, layoutExtraKeysBox.getVisibility() == View.VISIBLE ? "隐藏扩展按键" : "显示扩展按键");
            menu.getMenu().add(0, 6, 4, "编辑快捷键");
            menu.getMenu().add(0, 4, 5, "后台常驻");
            menu.getMenu().add(0, 2, 6, "关闭当前会话");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 7) {
                    reconnect();
                } else if (item.getItemId() == 1) {
                    stopKeyRepeats();
                    layoutExtraKeysBox.setVisibility(layoutExtraKeysBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    getPreferences(MODE_PRIVATE).edit().putBoolean("extra_keys_visible",
                            layoutExtraKeysBox.getVisibility() == View.VISIBLE).apply();
                } else if (item.getItemId() == 3) {
                    newSession();
                } else if (item.getItemId() == 4) {
                    BackgroundSettings.show(this);
                } else if (item.getItemId() == 6) {
                    ExtraKeysEditor.show(this, this::populateExtraKeys);
                } else if (item.getItemId() == 5 && session != null) {
                    SessionSwitcher.showActions(this, service, session);
                } else if (session != null) {
                    SessionSwitcher.confirmClose(this, service, session);
                }
                return true;
            });
            menu.show();
        });

        layoutExtraKeysBox = findViewById(R.id.layout_extra_keys_box);
        layoutExtraKeysBox.setVisibility(getPreferences(MODE_PRIVATE).getBoolean("extra_keys_visible", true) ? View.VISIBLE : View.GONE);
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
        bound = bindService(new Intent(this, SessionService.class), connection, Context.BIND_AUTO_CREATE);
        if (!bound) { toast("无法启动会话服务"); finish(); }
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
        if (session != null) { view.followOutput(); session.paste(t); }
        out(this, "PASTED chars=" + t.length());
        toast("已粘贴 " + t.length() + " 字符");
        view.clearSelection();
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private boolean sendTerminalKey(int keyCode) { return sendTerminalKey(keyCode, 0); }

    private boolean sendTerminalKey(int keyCode, int modifiers) {
        String name;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: name = "BKSP"; break;
            case KeyEvent.KEYCODE_FORWARD_DEL: name = "DEL"; break;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: name = "ENTER"; break;
            case KeyEvent.KEYCODE_TAB: name = "TAB"; break;
            case KeyEvent.KEYCODE_ESCAPE: name = "ESC"; break;
            case KeyEvent.KEYCODE_DPAD_UP: name = "UP"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: name = "DOWN"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: name = "RIGHT"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: name = "LEFT"; break;
            case KeyEvent.KEYCODE_MOVE_HOME: name = "HOME"; break;
            case KeyEvent.KEYCODE_MOVE_END: name = "END"; break;
            case KeyEvent.KEYCODE_PAGE_UP: name = "PGUP"; break;
            case KeyEvent.KEYCODE_PAGE_DOWN: name = "PGDN"; break;
            default: return false;
        }
        return sendNamedKey(name, modifiers);
    }

    private boolean sendNamedKey(String name, int modifiers) {
        int code = TerminalKeys.code(name);
        if (code == 0) return false;
        if (session != null) {
            view.followOutput();
            session.key(code, modifiers | (shiftOn ? 1 : 0) | (altOn ? 2 : 0) | (ctrlOn ? 4 : 0));
        }
        clearModifiers();
        return true;
    }

    private void clearModifiers() {
        ctrlOn = altOn = shiftOn = false;
        updateModifierButtonState(ctrlButton, "CTRL", false);
        updateModifierButtonState(altButton, "ALT", false);
        updateModifierButtonState(shiftButton, "SHIFT", false);
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
        if (handle != 0 && input != null && !drawer.isDrawerOpen(androidx.core.view.GravityCompat.START) &&
                BaseInputConnection.getComposingSpanStart(input.getText()) < 0) {
            if (isTerminalKey(e.getKeyCode())) {
                if (e.getAction() == KeyEvent.ACTION_DOWN)
                    sendTerminalKey(e.getKeyCode(), (e.isShiftPressed() ? 1 : 0) |
                            (e.isAltPressed() ? 2 : 0) | (e.isCtrlPressed() ? 4 : 0));
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
        KeyLayout layout = ExtraKeys.getLayout(this);
        String encoded = ExtraKeys.encode(layout);
        if (encoded.equals(renderedKeyLayout)) return;
        stopKeyRepeats();
        repeatKeys.clear();
        renderedKeyLayout = encoded;
        clearModifiers();
        ctrlButton = altButton = shiftButton = null;
        layoutExtraKeysBox.removeAllViews();
        List<String[]> rows = layout.snapshot();
        int slots = 1;
        for (String[] r : rows) slots = Math.max(slots, r.length);

        for (String[] rowKeys : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : rowKeys) {
                MaterialButton b = keyButton(key);
                b.setTag(key);
                b.setOnClickListener(v -> onExtraKey((String) v.getTag()));
                String n = key.toUpperCase(java.util.Locale.ROOT);
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
    private MaterialButton keyButton(String key) {
        MaterialButton b;
        if (TerminalKeys.isDirection(key)) {
            RepeatKeyButton repeat = new RepeatKeyButton(this);
            repeatKeys.add(repeat);
            b = repeat;
        } else b = new MaterialButton(this);
        b.setText(ExtraKeys.label(key));
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setSingleLine(true);
        b.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));
        b.setPadding(0, dp(4), 0, dp(4));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setCornerRadius(dp(12));
        b.setBackgroundTintList(ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)));
        return b;
    }

    private int themeColor(int attribute) { return SessionDrawer.color(this, attribute); }

    private void onExtraKey(String key) {
        String n = key.toUpperCase(java.util.Locale.ROOT);
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
        if (!sendNamedKey(key, 0)) sendTyped(TerminalKeys.text(key));
    }

    /** 粘滞修饰键高亮：激活时用主色填充，键名和宽度不变。 */
    private void updateModifierButtonState(MaterialButton b, String name, boolean on) {
        if (b == null) return;
        b.setBackgroundTintList(ColorStateList.valueOf(themeColor(on
                ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorSurfaceContainerHighest)));
        b.setTextColor(themeColor(on ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnSurface));
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

    private void sendBytes(byte[] bytes) {
        if (session != null) { view.followOutput(); session.send(bytes); }
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
        resumed = true;
        populateExtraKeys();
        if (service != null) service.addListener(sessionListener);
        view.setGridListener((cols, rows) -> {
            if (session != null) session.resize(cols, rows);
            else if (!attachingSession) openRequestedSession();
        });
        view.setInputSink(new TerminalView.InputSink() {
            @Override public void mouse(int row, int col, int button, boolean pressed) {
                if (session != null) session.mouse(row, col, button, pressed);
            }
            @Override public void tap() { showIme(); }
            @Override public void tap(int row, int col) {
                if (session == null || !session.click(row, col)) showIme();
            }
            @Override public void scroll(int row, int col, int lines) {
                if (session != null) session.scroll(row, col, lines);
            }
        });
        view.setSelectionListener((active, hasRange) -> updateSelectionToolbar(active));
        view.setZoomListener(columns -> {
            HostStore.setZoomColumns(this, columns);
            if (session != null) session.zoomColumns = columns;
            tvTermCols.setText(view.getCols() + "列");
        });
        if (service != null) {
            if (session != null) session.unread = false;
            sessionListener.onSessionsChanged();
            openRequestedSession();
        }
        view.notifyNewData();
        input.postDelayed(showKeyboard, 400);
    }

    private final Runnable showKeyboard = this::showIme;

    private void stopKeyRepeats() {
        for (RepeatKeyButton button : repeatKeys) button.stopRepeating();
    }

    @Override protected void onPause() {
        stopKeyRepeats();
        super.onPause();
    }

    @Override protected void onStop() {
        resumed = false;
        dismissHostKeyPrompt();
        if (service != null) service.removeListener(sessionListener);
        input.removeCallbacks(showKeyboard);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (session != null) state.putString(SessionService.SESSION_ID, session.id);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        requestedSessionId = intent.getStringExtra(SessionService.SESSION_ID);
        if (requestedSessionId != null) {
            if (credentialDialog != null) credentialDialog.dismiss();
            preparingConnection = false;
            pendingHost = false;
        }
        if (requestedSessionId == null && intent.hasExtra("host")) {
            if (credentialDialog != null) credentialDialog.dismiss();
            preparingConnection = false;
            replacingSessionId = null;
            readHost(intent);
            pendingHost = true;
        }
        openRequestedSession();
    }

    private void readHost(Intent intent) {
        hName = intent.getStringExtra("name") == null ? "" : intent.getStringExtra("name");
        hHost = intent.getStringExtra("host") == null ? "" : intent.getStringExtra("host");
        hPort = intent.getIntExtra("port", 22);
        hUser = intent.getStringExtra("user") == null ? "" : intent.getStringExtra("user");
        hStartup = intent.getStringExtra("startup") == null ? "" : intent.getStringExtra("startup");
        hAuthType = intent.getStringExtra("auth_type") == null ? "publickey" : intent.getStringExtra("auth_type");
        hKeyId = intent.getStringExtra("key_id") == null ? "default" : intent.getStringExtra("key_id");
        hPassword = "";
        hHostId = intent.getStringExtra("host_id");
        hPasswordUnavailable = false;
        intent.removeExtra("password");
    }

    private void openRequestedSession() {
        if (service == null || !resumed || preparingConnection || isFinishing()) return;
        if (requestedSessionId != null) {
            TerminalSession requested = service.get(requestedSessionId);
            requestedSessionId = null;
            if (requested != null) { attachSession(requested); return; }
            toast("原会话已结束，请重新连接");
            finish();
            return;
        }
        if (session != null && !pendingHost) return;
        if (hHost.isEmpty()) {
            TerminalSession selected = service.selected();
            if (selected == null) { finish(); return; }
            attachSession(selected);
        } else if (view.getWidth() > 0 && view.getHeight() > 0) {
            pendingHost = false;
            preparingConnection = true;
            prepareConnection();
        }
    }

    private void prepareConnection() {
        if ("password".equalsIgnoreCase(hAuthType)) {
            if (hHostId != null) {
                try {
                    for (Host host : HostStore.load(this)) if (hHostId.equals(host.id) && hHost.equals(host.host)
                            && hPort == host.port && hUser.equals(host.user)) {
                        hPassword = host.password;
                        hPasswordUnavailable = host.passwordUnavailable;
                        break;
                    }
                } catch (IllegalStateException error) { toast(error.getMessage()); cancelConnection(); return; }
            }
            if (hPassword.isEmpty()) promptSecret(hPasswordUnavailable ? "保存的密码无法解密，请重新输入" : "SSH 密码认证",
                    "主机登录密码", password -> createSession(null, null, password));
            else createSession(null, null, hPassword);
            return;
        }
        KeyItem key;
        try { key = KeyManager.getKey(this, hKeyId); }
        catch (Exception e) { toast("读取密钥失败：" + e.getMessage()); cancelConnection(); return; }
        if (key == null) {
            credentialDialog = new MaterialAlertDialogBuilder(this).setTitle("没有可用的密钥")
                    .setMessage("在主页「密钥」里生成或导入一个。")
                    .setPositiveButton("返回", (d, w) -> cancelConnection()).setOnCancelListener(d -> cancelConnection()).show();
            return;
        }
        String keyPath = KeyManager.getPrivFile(this, key.id).getAbsolutePath();
        if (key.encrypted) promptSecret("解锁 " + key.name, "密钥口令", passphrase -> createSession(keyPath, passphrase, null));
        else createSession(keyPath, null, null);
    }

    private void promptSecret(String title, String hint, java.util.function.Consumer<String> callback) {
        TextInputLayout field = new TextInputLayout(this);
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED);
        field.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        field.setHint(hint);
        TextInputEditText editor = new TextInputEditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editor.setSaveEnabled(false);
        field.addView(editor);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), dp(4));
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        credentialDialog = new MaterialAlertDialogBuilder(this).setTitle(title)
                .setMessage(hUser + "@" + hHost + ":" + hPort).setView(box)
                .setPositiveButton("连接", (d, w) -> {
                    String secret = editor.getText() == null ? "" : editor.getText().toString();
                    editor.setText("");
                    callback.accept(secret);
                }).setNegativeButton("取消", (d, w) -> cancelConnection()).setOnCancelListener(d -> cancelConnection()).show();
    }

    private void cancelConnection() {
        replacingSessionId = null;
        preparingConnection = false;
        pendingHost = false;
        hPassword = "";
        getIntent().removeExtra("password");
        if (session == null) finish();
    }

    private void createSession(String keyPath, String passphrase, String password) {
        if (isFinishing() || isDestroyed() || service == null) return;
        Host host = new Host(hName, hHost, hPort, hUser, hStartup, hAuthType, hKeyId, "");
        if (hHostId != null) host.id = hHostId;
        TerminalSession replaced = replacingSessionId == null ? null : service.get(replacingSessionId);
        replacingSessionId = null;
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, new Intent(this, SessionService.class));
            TerminalSession created = service.create(host, keyPath, passphrase, password,
                    view.getCols(), view.getRows(), replaced != null ? replaced.zoomColumns : HostStore.getZoomColumns(this, 150));
            attachSession(created);
            if (replaced != null) service.replace(replaced.id, created);
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    !getPreferences(MODE_PRIVATE).getBoolean("notification_asked", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notification_asked", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 51);
            }
        } catch (Exception e) { toast("创建会话失败：" + e.getMessage()); cancelConnection(); }
        hPassword = "";
        getIntent().removeExtra("password");
        preparingConnection = false;
    }

    private void attachSession(TerminalSession next) {
        stopKeyRepeats();
        if (next == session) {
            next.unread = false;
            updateSessionTitle();
            updateSessionDrawer();
            updateScrollStatus();
            updateHostKeyPrompt();
            return;
        }
        attachingSession = true;
        detachSession();
        // Set zoom before publishing the new session so geometry callbacks cannot overwrite its grid.
        view.setPreferredColumns(next.zoomColumns);
        session = next;
        service.select(next.id);
        next.unread = false;
        attachingSession = false;
        handle = next.handle();
        view.attachSession(handle);
        next.resize(view.getCols(), view.getRows());
        ctrlOn = altOn = shiftOn = false;
        updateModifierButtonState(ctrlButton, "CTRL", false);
        updateModifierButtonState(altButton, "ALT", false);
        updateModifierButtonState(shiftButton, "SHIFT", false);
        input.getText().clear();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.restartInput(input);
        updateSessionTitle();
        updateSessionDrawer();
        updateScrollStatus();
        updateHostKeyPrompt();
    }

    private void dismissHostKeyPrompt() {
        if (hostKeyDialog != null) hostKeyDialog.dismiss();
        hostKeyDialog = null;
        displayedHostKey = null;
    }

    private void updateHostKeyPrompt() {
        HostKeyRequest request = session == null ? null : session.hostKeyRequest;
        if (!resumed || session == null || isFinishing() || isDestroyed()) { dismissHostKeyPrompt(); return; }
        if (request != null && request.pending()) {
            if (displayedHostKey == request && hostKeyDialog != null && hostKeyDialog.isShowing()) return;
            dismissHostKeyPrompt();
            displayedHostKey = request;
            hostKeyDialog = HostKeyDialogs.confirm(this, request);
        } else {
            if (displayedHostKey != null) dismissHostKeyPrompt();
            if (session.hostKeyChange != null && displayedKeyChanges.add(session.id)) {
                dismissHostKeyPrompt();
                hostKeyDialog = HostKeyDialogs.changed(this, session.hostKeyChange);
            }
        }
    }

    private void detachSession() {
        dismissHostKeyPrompt();
        stopKeyRepeats();
        view.clearSelection();
        view.attachSession(0);
        session = null;
        handle = 0;
    }

    private void updateSessionTitle() {
        if (session == null) return;
        tvTermTitle.setText(session.title);
        // 状态点已表示连接状态：连接正常时只显示地址，默认 22 端口省略，避免地址被截成“n…site:22”。
        String endpoint = session.endpoint.endsWith(":22")
                ? session.endpoint.substring(0, session.endpoint.length() - 3) : session.endpoint;
        tvTermStatus.setText(session.state() == TerminalSession.State.CONNECTED
                ? endpoint : session.detail() + " · " + endpoint);
        android.graphics.drawable.Drawable dot = tvTermStatus.getCompoundDrawablesRelative()[0];
        if (dot != null) dot.mutate().setTint(SessionDrawer.stateColor(session));
        tvTermCols.setText(view.getCols() + "列");
        findViewById(R.id.btn_term_sessions).setContentDescription("会话 · " + service.sessions().size());
        updateSessionState();
    }

    /** 连接中 / 失败 / 已结束时在终端底部显示状态卡片，不改变终端行列。 */
    private void updateSessionState() {
        if (sessionStateCard == null) return;
        TerminalSession.State state = session == null ? null : session.state();
        boolean show = state != null && state != TerminalSession.State.CONNECTED
                && !(session.id + state).equals(hiddenSessionState);
        sessionStateCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        boolean connecting = state == TerminalSession.State.CONNECTING;
        boolean failed = state == TerminalSession.State.FAILED;
        String detail = session.detail();
        TextView title = findViewById(R.id.tv_session_state_title);
        TextView message = findViewById(R.id.tv_session_state_detail);
        if (connecting) {
            title.setText(detail.equals("正在连接") ? getString(R.string.state_connecting) + "…" : detail);
            message.setText(session.endpoint);
        } else if (failed) {
            title.setText(R.string.state_failed);
            message.setText(detail.startsWith("连接失败：") ? detail.substring(5) : detail);
        } else {
            title.setText(getString(R.string.state_kept_screen, detail));
            message.setText(session.endpoint);
        }
        findViewById(R.id.progress_session_state).setVisibility(connecting ? View.VISIBLE : View.GONE);
        android.widget.ImageView icon = findViewById(R.id.icon_session_state);
        icon.setVisibility(connecting ? View.GONE : View.VISIBLE);
        icon.setImageTintList(ColorStateList.valueOf(SessionDrawer.stateColor(session)));
        findViewById(R.id.btn_session_state_reconnect).setVisibility(connecting ? View.GONE : View.VISIBLE);
        ((MaterialButton) findViewById(R.id.btn_session_state_close))
                .setText(connecting ? R.string.cancel_connection : R.string.close_session);
    }

    private void useHost(Host host) {
        hName = host.name; hHost = host.host; hPort = host.port; hUser = host.user;
        hStartup = host.startup; hAuthType = host.authType; hKeyId = host.keyId; hPassword = host.password;
        hHostId = host.id; hPasswordUnavailable = host.passwordUnavailable;
    }

    /** 用同一主机重新连接当前会话；优先读取已保存主机的最新配置和密码。 */
    private void reconnect() {
        if (session == null || service == null || preparingConnection || session.isActive()) return;
        Host origin = service.origin(session.id);
        if (origin == null) { newSession(); return; }
        Host target = origin;
        try {
            for (Host saved : HostStore.load(this)) if (saved.id.equals(origin.id)) target = saved;
        } catch (IllegalStateException error) { toast(error.getMessage()); return; }
        useHost(target);
        replacingSessionId = session.id;
        pendingHost = true;
        openRequestedSession();
    }

    private void showSessions() {
        if (service == null) return;
        updateSessionDrawer();
        drawer.openDrawer(androidx.core.view.GravityCompat.START);
    }

    private void newSession() {
        drawer.closeDrawers();
        if (preparingConnection || service == null) return;
        List<Host> hosts;
        try { hosts = HostStore.load(this); }
        catch (IllegalStateException error) { toast(error.getMessage()); return; }
        if (hosts.isEmpty()) {
            startActivity(new Intent(this, HostListActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return;
        }
        String[] labels = new String[hosts.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = hosts.get(i).title() + "\n" + hosts.get(i).user + "@" + hosts.get(i).host;
        new MaterialAlertDialogBuilder(this).setTitle("新建连接")
                .setItems(labels, (d, index) -> {
                    useHost(hosts.get(index));
                    replacingSessionId = null;
                    pendingHost = true;
                    openRequestedSession();
                }).setNeutralButton("管理主机", (d, w) -> startActivity(new Intent(this, HostListActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)))
                .setNegativeButton("取消", null).show();
    }

    private void updateScrollStatus() {
        int offset = handle == 0 ? 0 : NativeTerm.getScrollOffset(handle);
        followOutputButton.setVisibility(offset == 0 ? View.GONE : View.VISIBLE);
        if (offset > 0) followOutputButton.setText("↓ " + getString(R.string.follow_output) + " · " + offset + " 行");
    }

    private void updateSessionDrawer() {
        if (service == null) return;
        sessionDrawer.refresh(service, session, selected -> {
            attachSession(selected);
            drawer.closeDrawers();
        });
    }

    @Override public void onBackPressed() {
        if (drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)) drawer.closeDrawers();
        else if (view.isSelecting()) view.clearSelection();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        dismissHostKeyPrompt();
        if (credentialDialog != null) credentialDialog.dismiss();
        if (service != null) service.removeListener(sessionListener);
        if (bound) unbindService(connection);
        detachSession();
        view.destroy();
        super.onDestroy();
    }
}

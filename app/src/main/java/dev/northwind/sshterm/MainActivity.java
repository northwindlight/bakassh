package dev.northwind.sshterm;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
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
public class MainActivity extends Activity {

    private static final String TAG = "sshterm";

    private TerminalView view;
    private EditText input;
    private Button ctrlButton;
    private LinearLayout selBar;
    private long handle;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean sessionStarted;
    private SshTransport transport;
    private boolean ctrlOn;
    private boolean clearingInput;
    private final byte[] outBuf = new byte[8192];

    // 连接参数（来自 Intent，缺省用一份保守默认值）
    private String hName = "rpi5";
    private String hHost = "rpi5.northwind.site";
    private int hPort = 22;
    private String hUser = "northwind";
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
        root.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        selBar = buildSelectionBar();
        selBar.setVisibility(View.GONE);
        root.addView(selBar);
        root.addView(buildExtraKeys());

        input = new EditText(this);
        input.setBackgroundColor(0x00000000);
        input.setTextColor(0x00000000);
        input.setCursorVisible(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (clearingInput || count <= 0) return;
                String added = s.subSequence(start, start + count).toString();
                sendTyped(added);
                clearingInput = true;
                input.setText("");
                clearingInput = false;
            }
        });
        root.addView(input, new LinearLayout.LayoutParams(1, 1));

        setContentView(root);
        out(this, "START device=" + android.os.Build.MODEL + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " target=" + hUser + "@" + hHost + ":" + hPort
                + " startup=\"" + hStartup + "\" cols=" + view.getCols());
        running = true;
        startIoThread();
    }

    /** 选中文本时出现的操作条（复制 / 全选 / 粘贴 / 取消）—— 对应"可以复制文字"。 */
    private LinearLayout buildSelectionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF23304A);
        bar.addView(smallButton("复制", v -> copySelection()), weight());
        bar.addView(smallButton("全选", v -> view.selectAll()), weight());
        bar.addView(smallButton("粘贴", v -> pasteFromClipboard()), weight());
        bar.addView(smallButton("取消", v -> view.clearSelection()), weight());
        return bar;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        return b;
    }

    private void copySelection() {
        String t = view.getSelectionText();
        if (t.isEmpty()) {
            toast("没选中文本");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("sshterm", t));
        out(this, "COPIED chars=" + t.length() + " head=" + t.substring(0, Math.min(40, t.length()))
                .replace("\n", "\\n"));
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

    /** ESC / TAB / CTRL / 方向键 / 回车 —— tmux copy-mode 导航离不开这些。 */
    private View buildExtraKeys() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(0xFF202024);
        String[][] keys = {
                {"ESC", "\u001b"}, {"TAB", "\t"}, {"CTRL", "#ctrl"},
                {"←", "\u001b[D"}, {"↑", "\u001b[A"}, {"↓", "\u001b[B"}, {"→", "\u001b[C"},
                {"⏎", "\r"}, {"A-", "#zoomout"}, {"A+", "#zoomin"},
        };
        for (String[] k : keys) {
            Button b = new Button(this);
            b.setText(k[0]);
            b.setAllCaps(false);
            b.setTextSize(11);
            b.setPadding(0, 0, 0, 0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setGravity(Gravity.CENTER);
            final String label = k[0], seq = k[1];
            b.setOnClickListener(v -> onExtraKey(label, seq));
            if ("CTRL".equals(label)) ctrlButton = b;
            row.addView(b, weight());
        }
        return row;
    }

    private void onExtraKey(String label, String seq) {
        if ("#ctrl".equals(seq)) {
            ctrlOn = !ctrlOn;
            if (ctrlButton != null) ctrlButton.setText(ctrlOn ? "CTRL*" : "CTRL");
            return;
        }
        // 字号：列数变少 = 字变大。捏合手势走同一套逻辑，这里给一个不依赖多点触控的入口。
        if ("#zoomout".equals(seq)) { view.setTargetCols(view.getCols() + 15); return; }
        if ("#zoomin".equals(seq)) { view.setTargetCols(view.getCols() - 15); return; }
        sendBytes(seq.getBytes(StandardCharsets.UTF_8));
        showIme();
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
                if (ctrlButton != null) ctrlButton.setText("CTRL");
            }
            sb.appendCodePoint(cp);
        }
        if (sb.length() > 0) sendBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void sendBytes(byte[] b) {
        final SshTransport t = transport;
        if (t == null) return;
        // 关键：绝不能在这里直接写 socket —— UI 线程做网络 IO 会抛 NetworkOnMainThreadException。
        postIo(() -> {
            try {
                t.write(b);
            } catch (Throwable ex) {
                out(this, "io write failed: " + ex);
            }
        });
        pumpOutput();
    }

    /** 把 libvterm 产生、需要回给宿主的数据（DSR 应答、鼠标序列等）投递给 IO 线程。 */
    private void pumpOutput() {
        if (handle == 0) return;
        final SshTransport t = transport;
        if (t == null) return;
        int n;
        while ((n = NativeTerm.takeOutput(handle, outBuf)) > 0) {
            final byte[] chunk = new byte[n];
            System.arraycopy(outBuf, 0, chunk, 0, n);
            postIo(() -> {
                try {
                    t.write(chunk);
                } catch (Throwable ex) {
                    out(this, "io pump failed: " + ex);
                }
            });
        }
    }

    // ---------------- 专用 IO 线程 ----------------

    private final java.util.concurrent.ArrayBlockingQueue<Runnable> ioQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(256);
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
        if (!ioQueue.offer(r)) out(this, "io queue full, dropped");
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
        view.setSelectionListener((active, hasRange) ->
                selBar.setVisibility(active ? View.VISIBLE : View.GONE));

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
        worker = new Thread(this::sessionRunner, "session");
        worker.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        sessionStarted = false;
        if (transport != null) {
            transport.disconnect();
            transport = null;
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (view != null) view.destroy();
        handle = 0;
    }

    private void sessionRunner() {
        try {
            Thread.sleep(800);
            if (handle == 0) return;

            String pub;
            try {
                pub = KeyManager.ensureKey(this);
                out(this, "PUBKEY " + pub);
            } catch (Throwable t) {
                out(this, "keygen FAILED: " + t);
                return;
            }

            try {
                transport = new SshTransport();
                String status = transport.connect(hHost, hPort, hUser,
                        KeyManager.privFile(this).getAbsolutePath(),
                        view.getCols(), view.getRows(),
                        new SshTransport.Listener() {
                            @Override
                            public void onBytes(byte[] buf, int len) {
                                NativeTerm.pushBytes(handle, buf, len);
                                view.notifyNewData();
                            }

                            @Override
                            public void onStatus(String msg) {
                                out(MainActivity.this, "SSH status: " + msg);
                            }
                        });
                out(this, "SSH " + status);

                Thread.sleep(1500);   // 等提示符
                if (hStartup != null && !hStartup.isEmpty() && running) {
                    final SshTransport t = transport;
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
                out(this, "SSH FAILED: " + t);
                if (transport != null) {
                    transport.disconnect();
                    transport = null;
                }
            }
        } catch (InterruptedException ie) {
            out(this, "worker interrupted");
        } catch (Throwable t) {
            out(this, "worker ERROR " + t);
        }
    }
}

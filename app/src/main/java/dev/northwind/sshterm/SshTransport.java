package dev.northwind.sshterm;

import android.util.Log;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * M1b：纯 Java 的 SSH 传输（JSch/mwiede 0.2.x）。
 *
 * 只做三件事：连上、开一个带 PTY 的 shell 通道、把远端字节原样交给终端引擎。
 * 输入方向先只提供 write()，界面/IME 在 M1c 接。
 */
public class SshTransport {

    public interface Listener {
        /** 远端来了字节（在 reader 线程回调，必须尽快返回）。 */
        void onBytes(byte[] buf, int len);
        void onStatus(String msg);
    }

    private static final String TAG = "sshterm-ssh";

    private Session session;
    private ChannelShell channel;
    private InputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile boolean running;
    private long rxBytes;
    private int cols, rows;

    public long getRxBytes() { return rxBytes; }
    public boolean isConnected() { return session != null && session.isConnected(); }

    public String connect(String host, int port, String user, String keyPath,
                          int cols, int rows, Listener listener) throws Exception {
        this.cols = cols;
        this.rows = rows;

        JSch jsch = new JSch();
        jsch.addIdentity(keyPath);

        session = jsch.getSession(user, host, port);
        session.setConfig("StrictHostKeyChecking", "no");   // M1b 先用 no，M1c 改成固定指纹
        session.setConfig("PreferredAuthentications", "publickey");
        session.connect(15000);

        String fp = "?";
        try {
            HostKey hk = session.getHostKey();
            if (hk != null) fp = hk.getFingerPrint(jsch);
        } catch (Throwable ignored) {
        }

        channel = (ChannelShell) session.openChannel("shell");
        channel.setPtyType("xterm-256color");
        channel.setPtySize(cols, rows, 0, 0);
        in = channel.getInputStream();
        out = channel.getOutputStream();
        channel.connect(15000);

        running = true;
        reader = new Thread(() -> {
            byte[] buf = new byte[65536];
            try {
                while (running) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n > 0) {
                        rxBytes += n;
                        listener.onBytes(buf, n);
                    }
                }
                listener.onStatus("channel EOF, rx=" + rxBytes);
            } catch (Throwable t) {
                if (running) listener.onStatus("reader stopped: " + t);
            }
        }, "ssh-reader");
        reader.start();

        return String.format(Locale.US, "connected %s@%s:%d pty=%dx%d hostkey=%s",
                user, host, port, cols, rows, fp);
    }

    public void write(byte[] data) throws Exception {
        if (out == null) return;
        out.write(data);
        out.flush();
    }

    public void write(byte[] data, int len) throws Exception {
        if (out == null) return;
        out.write(data, 0, len);
        out.flush();
    }

    public void write(String s) throws Exception {
        write(s.getBytes("UTF-8"));
    }

    public void resize(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        try {
            if (channel != null) channel.setPtySize(cols, rows, 0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "resize failed: " + t);
        }
    }

    public void disconnect() {
        running = false;
        try {
            if (channel != null) channel.disconnect();
        } catch (Throwable ignored) {
        }
        try {
            if (session != null) session.disconnect();
        } catch (Throwable ignored) {
        }
        channel = null;
        session = null;
    }
}

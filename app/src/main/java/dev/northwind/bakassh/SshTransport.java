package dev.northwind.bakassh;

import android.util.Log;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
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

    private volatile Session session;
    private volatile ChannelShell channel;
    private volatile OutputStream out;
    private Thread reader;
    private volatile boolean running;
    private volatile boolean closed;
    private volatile long rxBytes;
    private int cols, rows;

    public long getRxBytes() { return rxBytes; }
    public boolean isConnected() {
        Session current = session;
        return !closed && current != null && current.isConnected();
    }

    public String connect(String host, int port, String user, String keyPath,
                          int cols, int rows, Listener listener) throws Exception {
        synchronized (this) {
            if (closed) throw new IOException("connection cancelled");
            this.cols = cols;
            this.rows = rows;
        }

        JSch jsch = new JSch();
        jsch.addIdentity(keyPath);

        Session connectedSession = jsch.getSession(user, host, port);
        synchronized (this) {
            if (closed) throw new IOException("connection cancelled");
            session = connectedSession;
        }
        try {
            connectedSession.setConfig("StrictHostKeyChecking", "no");
            connectedSession.setConfig("PreferredAuthentications", "publickey");
            connectedSession.connect(15000);
            if (closed) throw new IOException("connection cancelled");

            String fp = "?";
            try {
                HostKey hk = connectedSession.getHostKey();
                if (hk != null) fp = hk.getFingerPrint(jsch);
            } catch (Throwable ignored) {
            }

            ChannelShell connectedChannel = (ChannelShell) connectedSession.openChannel("shell");
            connectedChannel.setPtyType("xterm-256color");
            InputStream in = connectedChannel.getInputStream();
            OutputStream output = connectedChannel.getOutputStream();
            synchronized (this) {
                if (closed) throw new IOException("connection cancelled");
                channel = connectedChannel;
                connectedChannel.setPtySize(this.cols, this.rows, 0, 0);
            }
            connectedChannel.connect(15000);
            synchronized (this) {
                if (closed) throw new IOException("connection cancelled");
                out = output;
                connectedChannel.setPtySize(this.cols, this.rows, 0, 0);
                running = true;
            }

            reader = new Thread(() -> {
                byte[] buf = new byte[65536];
                String status = null;
                try {
                    while (running) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        if (n > 0) {
                            rxBytes += n;
                            listener.onBytes(buf, n);
                        }
                    }
                    if (running) status = "channel EOF, rx=" + rxBytes;
                } catch (Exception t) {
                    if (running) status = "reader stopped: " + t;
                } finally {
                    disconnect();
                }
                if (status != null) listener.onStatus(status);
            }, "ssh-reader");
            reader.start();

            return String.format(Locale.US, "connected %s@%s:%d pty=%dx%d hostkey=%s",
                    user, host, port, cols, rows, fp);
        } catch (Exception e) {
            connectedSession.disconnect();
            disconnect();
            throw e;
        }
    }

    public void write(byte[] data) throws Exception {
        write(data, data.length);
    }

    public void write(byte[] data, int len) throws Exception {
        OutputStream output = out;
        if (closed || output == null) throw new IOException("SSH channel is not connected");
        output.write(data, 0, len);
        output.flush();
    }

    public void write(String s) throws Exception {
        write(s.getBytes("UTF-8"));
    }

    public synchronized void resize(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        try {
            if (channel != null) channel.setPtySize(cols, rows, 0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "resize failed: " + t);
        }
    }

    public void disconnect() {
        ChannelShell oldChannel;
        Session oldSession;
        synchronized (this) {
            closed = true;
            running = false;
            oldChannel = channel;
            oldSession = session;
            channel = null;
            session = null;
            out = null;
        }
        try {
            if (oldChannel != null) oldChannel.disconnect();
        } catch (Throwable ignored) {
        }
        try {
            if (oldSession != null) oldSession.disconnect();
        } catch (Throwable ignored) {
        }
    }
}

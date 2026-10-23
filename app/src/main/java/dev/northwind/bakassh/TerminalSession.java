package dev.northwind.bakassh;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One SSH connection and terminal, independent of any Activity or View. */
final class TerminalSession implements AutoCloseable {
    interface Listener {
        void onData(TerminalSession session);
        void onState(TerminalSession session);
    }

    enum State { CONNECTING, CONNECTED, ENDED, FAILED, CLOSED }

    final String id, title, endpoint;
    final AtomicBoolean frameQueued = new AtomicBoolean();
    private final Listener listener;
    private final SshTransport transport = new SshTransport();
    private final PendingResize pendingResize = new PendingResize();
    private final ScheduledExecutorService writer;
    private Thread connector;
    private long handle;
    private int cols, rows;
    private volatile State state = State.CONNECTING;
    private volatile String detail = "正在连接";
    float zoomColumns;

    TerminalSession(String id, String title, String endpoint, int cols, int rows,
                    float zoomColumns, Listener listener) {
        this.id = id;
        this.title = title;
        this.endpoint = endpoint;
        this.cols = cols;
        this.rows = rows;
        this.zoomColumns = zoomColumns;
        this.listener = listener;
        handle = NativeTerm.create(cols, rows);
        if (handle == 0) throw new IllegalStateException("无法创建终端");
        writer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ssh-writer-" + id);
            thread.setDaemon(true);
            return thread;
        });
    }

    long handle() { synchronized (NativeTerm.class) { return handle; } }
    State state() { return state; }
    String detail() { return detail; }
    boolean isActive() { return state == State.CONNECTING || state == State.CONNECTED; }

    void connect(String host, int port, String user, String authType, String keyPath,
                 String passphrase, String password, String startup) {
        connector = new Thread(() -> {
            try {
                int c, r;
                synchronized (NativeTerm.class) { c = cols; r = rows; }
                transport.connect(host, port, user, authType, keyPath, passphrase, password,
                        c, r, new SshTransport.Listener() {
                            @Override public void onBytes(byte[] bytes, int len) {
                                synchronized (NativeTerm.class) {
                                    if (handle == 0 || !isActive()) return;
                                    NativeTerm.receive(handle, bytes, len, TerminalSession.this::queueBytes);
                                }
                                listener.onData(TerminalSession.this);
                            }
                            @Override public void onStatus(String status) {
                                end(State.ENDED, status.startsWith("channel EOF") ? "会话已结束" : "连接已断开");
                            }
                        });
                synchronized (NativeTerm.class) {
                    if (state != State.CONNECTING) return;
                    state = State.CONNECTED;
                    detail = "已连接";
                    if (pendingResize.offer(cols, rows)) execute(this::flushResize);
                }
                listener.onState(this);
                if (startup != null && !startup.isEmpty()) {
                    byte[] command = (startup.endsWith("\n") ? startup : startup + "\n")
                            .getBytes(StandardCharsets.UTF_8);
                    try { writer.schedule(() -> write(command), 1500, TimeUnit.MILLISECONDS); }
                    catch (RejectedExecutionException ignored) { }
                }
            } catch (Exception e) {
                transport.disconnect();
                end(State.FAILED, "连接失败：" + e.getMessage());
            }
        }, "ssh-connect-" + id);
        connector.setDaemon(true);
        connector.start();
    }

    private void end(State next, String message) {
        synchronized (NativeTerm.class) {
            if (!isActive()) return;
            state = next;
            detail = message;
        }
        writer.shutdownNow();
        listener.onState(this);
    }

    void send(byte[] bytes) {
        synchronized (NativeTerm.class) {
            if (handle == 0 || state != State.CONNECTED) return;
            NativeTerm.drainOutput(handle, this::queueBytes);
            queueBytes(bytes);
        }
    }

    void mouse(int row, int col, int button, boolean pressed) {
        synchronized (NativeTerm.class) {
            if (handle == 0 || state != State.CONNECTED) return;
            NativeTerm.mouseMove(handle, row, col, 0);
            NativeTerm.mouseButton(handle, button, pressed, 0);
            NativeTerm.drainOutput(handle, this::queueBytes);
        }
    }

    void resize(int cols, int rows) {
        synchronized (NativeTerm.class) {
            if (handle == 0 || (this.cols == cols && this.rows == rows)) return;
            this.cols = cols;
            this.rows = rows;
            NativeTerm.resize(handle, cols, rows);
            if (isActive() && pendingResize.offer(cols, rows)) execute(this::flushResize);
        }
    }

    private void flushResize() {
        int[] size;
        while (isActive() && (size = pendingResize.poll()) != null) transport.resize(size[0], size[1]);
    }

    private void queueBytes(byte[] bytes) { execute(() -> write(bytes)); }
    private void execute(Runnable task) {
        try { writer.execute(task); } catch (RejectedExecutionException ignored) { }
    }
    private void write(byte[] bytes) {
        if (!isActive()) return;
        try { transport.write(bytes); }
        catch (Exception e) {
            transport.disconnect();
            end(State.FAILED, "发送失败：" + e.getMessage());
        }
    }

    @Override public void close() {
        synchronized (NativeTerm.class) {
            if (state == State.CLOSED) return;
            state = State.CLOSED;
            if (handle != 0) NativeTerm.destroy(handle);
            handle = 0;
        }
        writer.shutdownNow();
        if (connector != null) connector.interrupt();
        // JSch disconnect can wait for network IO; never block the main thread.
        Thread closer = new Thread(transport::disconnect, "ssh-close-" + id);
        closer.setDaemon(true);
        closer.start();
    }
}

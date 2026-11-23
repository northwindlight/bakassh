package dev.northwind.bakassh;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns an authenticated SSH -N connection and its -L/-R channels, independently of Activities. */
public final class SshTunnelService extends Service {
    static final String STOP = "dev.northwind.bakassh.STOP_SSH_TUNNEL";
    static final String CHANNEL = "ssh-forwarding";
    private static volatile SshTunnelService instance;
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor();
    private volatile SshTransport transport;
    private volatile boolean destroyed;
    private volatile int generation;
    volatile boolean uiVisible;
    volatile HostKeyRequest hostKeyRequest;
    volatile HostKeyTrust.Changed changedHostKey;
    private PowerManager.WakeLock wakeLock;

    static SshTunnelService current() { return instance; }
    synchronized boolean locksHeld() { return wakeLock != null && wakeLock.isHeld(); }
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("ssh_forwarding", Context.MODE_PRIVATE);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "SSH 转发", NotificationManager.IMPORTANCE_LOW));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bakaSSH:ssh-forwarding");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && STOP.equals(intent.getAction())) || !prefs(this).getBoolean("enabled", false)) {
            prefs(this).edit().putBoolean("enabled", false).putString("status", "转发已停止").apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        uiVisible = intent != null && intent.getBooleanExtra("interactive", false);
        changedHostKey = null;
        String keyPassphrase = intent == null ? null : intent.getStringExtra("key_passphrase");
        String password = intent == null ? null : intent.getStringExtra("password");
        if (intent != null) { intent.removeExtra("key_passphrase"); intent.removeExtra("password"); }
        final int activeGeneration = ++generation;
        HostKeyRequest waiting = hostKeyRequest;
        if (waiting != null) waiting.answer(false);
        SshTransport old = transport;
        if (old != null) new Thread(old::disconnect, "ssh-tunnel-replace").start();
        status("正在建立 SSH 转发…");
        control.execute(() -> {
            // Reconfiguration must release the old listening ports before binding them again.
            if (old != null) old.disconnect();
            connect(activeGeneration, keyPassphrase, password, 0);
        });
        return START_STICKY;
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private void connect(int activeGeneration, String passphrase, String transientPassword, int failures) {
        if (!active(activeGeneration)) return;
        SshTransport next = new SshTransport();
        transport = next;
        try {
            SshForwarding.Plan plan = SshForwarding.parse(prefs(this).getString("command", ""));
            String hostId = prefs(this).getString("host_id", "");
            Host host = null;
            for (Host item : HostStore.load(this)) if (item.id.equals(hostId)) { host = item; break; }
            if (host == null || !plan.host.equalsIgnoreCase(host.host) || !plan.user.equals(host.user) || plan.port != host.port)
                throw new IllegalArgumentException("SSH 主机配置已变化，请重新选择主机并保存转发");
            String keyPath = null;
            if (!"password".equals(host.authType)) {
                KeyItem key = KeyManager.getKey(this, host.keyId);
                if (key == null) throw new IllegalArgumentException("请先在密钥管理中选择可用的 SSH 密钥");
                if (key.encrypted && passphrase == null) throw new IllegalArgumentException("请打开转发页面输入密钥口令后恢复连接");
                keyPath = KeyManager.getPrivFile(this, key.id).getAbsolutePath();
            }
            String password = transientPassword != null ? transientPassword : host.password;
            if ("password".equals(host.authType) && password.isEmpty())
                throw new IllegalArgumentException("请打开转发页面输入 SSH 密码");
            synchronized (this) { if (!active(activeGeneration)) return; if (!wakeLock.isHeld()) wakeLock.acquire(); }
            status("正在连接 " + plan.user + "@" + plan.host + "…");
            next.connect(host.host, host.port, host.user, host.authType, keyPath, passphrase, password,
                    80, 24, new SshTransport.Listener() {
                        @Override public void onBytes(byte[] bytes, int count) { }
                        @Override public void onStatus(String message) {
                            if (active(activeGeneration)) try {
                                control.execute(() -> reconnect(activeGeneration, passphrase, transientPassword, 0));
                            } catch (java.util.concurrent.RejectedExecutionException ignored) { }
                        }
                    }, new HostKeyTrust(host.host, host.port, new KnownHostsStore(this), challenge -> {
                        if (!uiVisible || !active(activeGeneration)) return false;
                        HostKeyRequest request = new HostKeyRequest(challenge);
                        hostKeyRequest = request;
                        status("等待确认 SSH 主机指纹");
                        try { return request.await(); }
                        finally { if (hostKeyRequest == request) hostKeyRequest = null; }
                    }), plan);
            if (active(activeGeneration)) status("已连接 · " + plan.rules.size() + " 条 SSH 转发规则");
            else next.disconnect();
        } catch (Exception error) {
            next.disconnect();
            if (!active(activeGeneration)) return;
            if (error instanceof HostKeyTrust.Changed) changedHostKey = (HostKeyTrust.Changed) error;
            if (networkFailure(error)) reconnect(activeGeneration, passphrase, transientPassword, failures);
            else {
                prefs(this).edit().putBoolean("enabled", false).apply();
                status("转发未启动：" + error.getMessage());
                synchronized (this) { if (wakeLock.isHeld()) wakeLock.release(); }
            }
        }
    }

    private boolean active(int value) { return !destroyed && generation == value && prefs(this).getBoolean("enabled", false); }
    private void reconnect(int value, String passphrase, String password, int failures) {
        if (!active(value)) return;
        SshTransport old = transport;
        if (old != null) old.disconnect();
        int delay = Math.min(30, 3 + failures * 3);
        status("连接已断开，" + delay + " 秒后重试");
        control.schedule(() -> connect(value, passphrase, password, failures + 1), delay, TimeUnit.SECONDS);
    }

    private static boolean networkFailure(Exception error) {
        // Never retry passwords, changed host keys, denied forwards, or malformed settings automatically.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.NoRouteToHostException || cause instanceof java.net.UnknownHostException) return true;
        }
        String message = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return message.contains("timeout: socket is not established") || message.contains("connection is closed by foreign host");
    }

    private void status(String message) {
        if (destroyed) return;
        prefs(this).edit().putString("status", message).apply();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 20, new Intent(this, SshForwardingActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 21, new Intent(this, SshTunnelService.class).setAction(STOP), flags);
        startForeground(2, new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("bakaSSH · SSH 转发").setContentText(message).setContentIntent(open)
                .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).addAction(0, "停止转发", stop).build());
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true;
        ++generation;
        HostKeyRequest request = hostKeyRequest;
        if (request != null) request.answer(false);
        SshTransport old = transport;
        if (old != null) new Thread(old::disconnect, "ssh-tunnel-stop").start();
        control.shutdownNow();
        synchronized (this) { if (wakeLock.isHeld()) wakeLock.release(); }
        if (instance == this) instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}

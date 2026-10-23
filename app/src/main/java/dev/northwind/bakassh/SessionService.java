package dev.northwind.bakassh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** The service owns sessions; Activities borrow a terminal only while visible. */
public final class SessionService extends Service implements TerminalSession.Listener {
    static final String SESSION_ID = "session_id";
    private static final String CHANNEL = "ssh-sessions";
    private static final String TOGGLE_WAKE = "dev.northwind.bakassh.TOGGLE_WAKE";
    private static final String CLOSE_ALL = "dev.northwind.bakassh.CLOSE_ALL";
    private static final int NOTIFICATION_ID = 1;
    private static SessionService instance;
    private final LinkedHashMap<String, TerminalSession> sessions = new LinkedHashMap<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean foreground;

    interface Listener {
        void onSessionsChanged();
        void onSessionData(TerminalSession session);
    }
    public final class LocalBinder extends Binder {
        SessionService service() { return SessionService.this; }
    }
    static SessionService current() { return instance; }
    List<TerminalSession> sessions() { return new ArrayList<>(sessions.values()); }
    TerminalSession get(String id) { return sessions.get(id); }
    void addListener(Listener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "SSH 会话",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持 SSH 会话运行，点击返回终端");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService call must be promoted, including an empty first start.
        promote();
        if (intent != null && CLOSE_ALL.equals(intent.getAction())) {
            for (TerminalSession session : sessions()) session.close();
            sessions.clear();
            changed();
        } else if (intent != null && TOGGLE_WAKE.equals(intent.getAction())) {
            setKeepAwake(!keepAwake());
        }
        // TCP connections cannot survive process death. Do not create a misleading empty restart.
        if (sessions.isEmpty()) main.postDelayed(this::stopIfEmpty, 10000);
        return START_NOT_STICKY;
    }

    TerminalSession create(Host host, String keyPath, String passphrase, String password,
                           int cols, int rows, float zoomColumns) {
        TerminalSession session = new TerminalSession(UUID.randomUUID().toString(), host.title(),
                host.user + "@" + host.host + ":" + host.port, cols, rows, zoomColumns, this);
        sessions.put(session.id, session);
        changed();
        session.connect(host.host, host.port, host.user, host.authType, keyPath, passphrase, password, host.startup);
        return session;
    }

    void close(String id) {
        TerminalSession session = sessions.remove(id);
        if (session != null) session.close();
        changed();
    }

    @Override public void onData(TerminalSession session) {
        if (!session.frameQueued.compareAndSet(false, true)) return;
        main.post(() -> {
            session.frameQueued.set(false);
            if (sessions.get(session.id) != session) return;
            for (Listener listener : new ArrayList<>(listeners)) listener.onSessionData(session);
        });
    }

    @Override public void onState(TerminalSession session) {
        main.post(() -> { if (sessions.get(session.id) == session) changed(); });
    }

    private void changed() {
        updateLocks();
        if (!sessions.isEmpty()) promote();
        for (Listener listener : new ArrayList<>(listeners)) listener.onSessionsChanged();
        stopIfEmpty();
    }

    private void stopIfEmpty() {
        if (!sessions.isEmpty()) return;
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
    }

    boolean keepAwake() { return getSharedPreferences("sshterm", MODE_PRIVATE).getBoolean("session_keep_awake", false); }
    void setKeepAwake(boolean enabled) {
        getSharedPreferences("sshterm", MODE_PRIVATE).edit().putBoolean("session_keep_awake", enabled).apply();
        changed();
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    @SuppressWarnings("deprecation")
    private void updateLocks() {
        boolean active = false;
        for (TerminalSession session : sessions.values()) active |= session.isActive();
        if (active && keepAwake()) {
            if (wakeLock == null) {
                PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bakaSSH:sessions");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            if (wifiLock == null) {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifi != null) {
                    wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bakaSSH:sessions");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            }
        } else releaseLocks();
    }

    private void releaseLocks() {
        if (wakeLock != null) { if (wakeLock.isHeld()) wakeLock.release(); wakeLock = null; }
        if (wifiLock != null) { if (wifiLock.isHeld()) wifiLock.release(); wifiLock = null; }
    }

    private void promote() {
        startForeground(NOTIFICATION_ID, notification());
        foreground = true;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int active = 0;
        String lastId = null;
        for (TerminalSession session : sessions.values()) {
            if (session.isActive()) active++;
            lastId = session.id;
        }
        if (lastId != null) open.putExtra(SESSION_ID, lastId);
        int immutable = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, immutable);
        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, SessionService.class).setAction(TOGGLE_WAKE), immutable);
        PendingIntent close = PendingIntent.getService(this, 2,
                new Intent(this, SessionService.class).setAction(CLOSE_ALL), immutable);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_terminal).setContentTitle("bakaSSH · " + active + " 个活动会话")
                .setContentText(keepAwake() && active > 0 ? "休眠锁已开启 · 点击返回终端" : "点击返回终端")
                .setContentIntent(content).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, keepAwake() ? "释放休眠锁" : "保持唤醒", toggle)
                .addAction(0, "断开全部", close).build();
    }

    @Override public void onDestroy() {
        for (TerminalSession session : sessions()) session.close();
        sessions.clear();
        releaseLocks();
        listeners.clear();
        main.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        super.onDestroy();
    }
}

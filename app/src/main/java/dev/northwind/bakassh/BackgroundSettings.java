package dev.northwind.bakassh;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;
import java.util.Locale;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class BackgroundSettings {
    static void show(Activity activity) {
        PowerManager power = (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
        boolean exempt = power.isIgnoringBatteryOptimizations(activity.getPackageName());
        boolean notifications = androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled();
        String[] actions = {
                isColorOs() ? "设置后台权限 · ColorOS 耗电管理" : (exempt ? "后台权限：已申请 · 查看系统设置" : "设置后台权限"),
                notifications ? "通知：已允许 · 打开通知设置" : "允许显示会话通知",
                isColorOs() ? "系统电池优化设置" : "打开应用信息 / 厂商后台设置"
        };
        android.widget.TextView help = new android.widget.TextView(activity);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        help.setPadding(padding, padding / 2, padding, padding / 2);
        SessionService service = SessionService.current();
        SshTunnelService tunnel = SshTunnelService.current();
        help.setText("SSH 会话使用前台服务和 CPU / Wi-Fi 休眠锁。系统仍可能限制后台网络，"
                + "请在下方打开系统设置，由你允许后台运行。\n\n"
                + "前台服务：" + ((service != null && service.foregroundActive()) || tunnel != null ? "运行中" : "未运行")
                + " · 休眠锁：" + ((service != null && service.locksHeld()) || (tunnel != null && tunnel.locksHeld()) ? "已持有" : "未持有")
                + " · 电池优化：" + (exempt ? "已忽略" : "未忽略")
                + (isColorOs() ? "\n\nColorOS：进入「耗电管理」，选择「完全允许后台行为」。"
                + "仅有前台通知或休眠锁仍可能被系统冻结并断开网络。" : "\n\n请允许应用后台运行，并检查厂商的后台限制。")
                + "强行停止或进程被杀后，原 SSH 连接需要重新建立。");
        new MaterialAlertDialogBuilder(activity).setTitle("后台常驻").setView(help)
                        .setItems(actions, (d, index) -> {
                            if (index == 0) {
                                if (isColorOs()) openVendorSettings(activity); else openBackgroundPermission(activity);
                            } else if (index == 1) {
                                if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                        != PackageManager.PERMISSION_GRANTED) {
                                    activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 51);
                                } else if (Build.VERSION.SDK_INT >= 26) open(activity, new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
                                else open(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.parse("package:" + activity.getPackageName())));
                            } else if (isColorOs()) openBackgroundPermission(activity); else openVendorSettings(activity);
                        }).setNegativeButton("关闭", null).show();
    }

    static void openBackgroundPermission(Activity activity) {
        PowerManager power = (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
        Uri app = Uri.parse("package:" + activity.getPackageName());
        if (!power.isIgnoringBatteryOptimizations(activity.getPackageName()) &&
                open(activity, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(app))) return;
        // Public exported Settings entry; do not try to launch ColorOS's private PowerControlActivity.
        if (open(activity, new Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL").setData(app))) return;
        openVendorSettings(activity);
    }

    static void openVendorSettings(Activity activity) {
        if (isColorOs()) Toast.makeText(activity,
                "进入「耗电管理」，选择「完全允许后台行为」", Toast.LENGTH_LONG).show();
        if (!open(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + activity.getPackageName()))))
            Toast.makeText(activity, "请在系统应用设置中允许 bakaSSH 后台运行", Toast.LENGTH_LONG).show();
    }

    private static boolean isColorOs() {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme");
    }

    private static boolean open(Activity activity, Intent intent) {
        try { activity.startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException e) { return false; }
    }
}

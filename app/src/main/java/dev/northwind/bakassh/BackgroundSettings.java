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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class BackgroundSettings {
    static void show(Activity activity) {
        PowerManager power = (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
        boolean exempt = power.isIgnoringBatteryOptimizations(activity.getPackageName());
        boolean notifications = androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled();
        String[] actions = {
                exempt ? "电池优化：已允许后台运行" : "允许忽略电池优化",
                notifications ? "通知：已允许 · 打开通知设置" : "允许显示会话通知",
                "打开应用信息 / 厂商后台设置"
        };
        android.widget.TextView help = new android.widget.TextView(activity);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        help.setPadding(padding, padding / 2, padding, padding / 2);
        SessionService service = SessionService.current();
        help.setText("常驻后台已自动生效：只要还有活动会话，bakaSSH 就作为前台服务持有 CPU / Wi-Fi "
                + "休眠锁，切到后台、锁屏都会继续运行，没有需要手动打开的开关。\n\n"
                + "前台服务：" + (service != null && service.foregroundActive() ? "运行中" : "未运行")
                + " · 休眠锁：" + (service != null && service.locksHeld() ? "已持有" : "未持有")
                + " · 电池优化：" + (exempt ? "已忽略" : "未忽略")
                + "\n\nAndroid 16 会冻结处于缓存状态的应用；前台服务 + 休眠锁能避免这一点，"
                + "但厂商的后台清理仍需在系统里放行。强行停止或系统强杀后需要重新连接。");
        new MaterialAlertDialogBuilder(activity).setTitle("后台常驻").setView(help)
                        .setItems(actions, (d, index) -> {
                            if (index == 0) {
                                if (!exempt) open(activity, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:" + activity.getPackageName())));
                            } else if (index == 1) {
                                if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                        != PackageManager.PERMISSION_GRANTED) {
                                    activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 51);
                                } else if (Build.VERSION.SDK_INT >= 26) open(activity, new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
                                else open(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.parse("package:" + activity.getPackageName())));
                            } else open(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:" + activity.getPackageName())));
                        }).setNegativeButton("关闭", null).show();
    }

    private static void open(Activity activity, Intent intent) {
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException e) { Toast.makeText(activity, "系统未提供此设置入口", Toast.LENGTH_SHORT).show(); }
    }
}

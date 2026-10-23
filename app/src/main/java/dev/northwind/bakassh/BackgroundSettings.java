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
        boolean awake = activity.getSharedPreferences("sshterm", Activity.MODE_PRIVATE)
                .getBoolean("session_keep_awake", false);
        boolean notifications = androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled();
        String[] actions = {
                awake ? "释放 CPU / Wi-Fi 休眠锁" : "保持 CPU / Wi-Fi 唤醒（增加耗电）",
                exempt ? "电池优化：已允许后台运行" : "允许忽略电池优化",
                notifications ? "通知：已允许 · 打开通知设置" : "允许显示会话通知",
                "打开应用信息 / 厂商后台设置"
        };
        android.widget.TextView help = new android.widget.TextView(activity);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        help.setPadding(padding, padding / 2, padding, padding / 2);
        help.setText("返回主页后会话继续运行。开启休眠锁会增加耗电；厂商后台限制仍需在系统中放行。强行停止或系统强杀后，需要重新连接。");
        new MaterialAlertDialogBuilder(activity).setTitle("后台保活").setView(help)
                        .setItems(actions, (d, index) -> {
                            if (index == 0) {
                                SessionService service = SessionService.current();
                                if (service != null) service.setKeepAwake(!awake);
                                else activity.getSharedPreferences("sshterm", Activity.MODE_PRIVATE).edit()
                                        .putBoolean("session_keep_awake", !awake).apply();
                                show(activity);
                            } else if (index == 1) {
                                if (!exempt) open(activity, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:" + activity.getPackageName())));
                            } else if (index == 2) {
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

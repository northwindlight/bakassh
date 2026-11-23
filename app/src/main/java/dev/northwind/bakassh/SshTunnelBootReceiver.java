package dev.northwind.bakassh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.core.content.ContextCompat;

public final class SshTunnelBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        SharedPreferences p = SshTunnelService.prefs(context);
        if (!p.getBoolean("enabled", false) || !p.getBoolean("boot", false)) return;
        try { ContextCompat.startForegroundService(context, new Intent(context, SshTunnelService.class)); }
        catch (RuntimeException error) {
            p.edit().putString("status", "系统限制自动启动，请打开 SSH 转发页面恢复").apply();
        }
    }
}

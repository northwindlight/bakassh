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
            p.edit().putBoolean("enabled", false).putString("state", SshTunnelService.FAILED)
                    .putString("status", "系统不允许开机自启，请手动启动").apply();
        }
    }
}

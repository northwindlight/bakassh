package dev.northwind.bakassh;

import android.app.Activity;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class HostKeyDialogs {
    static AlertDialog confirm(Activity activity, HostKeyRequest request) {
        HostKeyTrust.Challenge key = request.challenge;
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("首次连接：确认主机指纹")
                .setMessage(key.endpoint + "\n" + key.algorithm + "\n\n" + key.fingerprint +
                        "\n\n请与服务器提供的指纹核对。确认后保存，后续连接必须匹配。")
                .setPositiveButton("信任并连接", (d, w) -> request.answer(true))
                .setNegativeButton("取消连接", (d, w) -> request.answer(false))
                .setOnCancelListener(d -> request.answer(false)).show();
        selectable(dialog);
        return dialog;
    }

    static AlertDialog changed(Activity activity, HostKeyTrust.Changed change) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("主机指纹已变化，连接已阻止")
                .setMessage(change.received.endpoint + "\n\n已保存：\n" + change.previous +
                        "\n\n本次收到（" + change.received.algorithm + "）：\n" + change.received.fingerprint +
                        "\n\n请先核实服务器身份。如确认更换了主机密钥，可从主机菜单的“主机指纹”移除旧记录，再重新连接。")
                .setPositiveButton("知道了", null)
                .setNeutralButton("管理已保存公钥", (d, w) -> manage(activity, change.received.endpoint)).show();
        selectable(dialog);
        return dialog;
    }

    static void manage(Activity activity, Host host) {
        manage(activity, HostKeyTrust.endpoint(host.host, host.port));
    }
    static void manage(Activity activity, String endpoint) {
        try {
            KnownHostsStore store = new KnownHostsStore(activity);
            String encoded = store.encoded(endpoint);
            if (encoded == null) {
                new MaterialAlertDialogBuilder(activity).setTitle("主机指纹")
                        .setMessage(endpoint + "\n\n尚未保存。首次连接时会显示指纹并请求确认。")
                        .setPositiveButton("知道了", null).show();
                return;
            }
            String description, publicKey = null;
            try {
                byte[] key = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
                HostKeyTrust.Challenge info = new HostKeyTrust.Challenge(endpoint, key);
                description = info.algorithm + "\n\n" + info.fingerprint;
                publicKey = info.algorithm + " " + encoded;
            } catch (Exception error) { description = "已保存的公钥记录无法解析。可移除后重新连接并核对指纹。"; }
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setTitle("已保存的主机公钥")
                    .setMessage(endpoint + "\n" + description + "\n\n同一地址和端口的所有账户共用此记录。")
                    .setNegativeButton("关闭", null)
                    .setNeutralButton("移除记录", (d, w) -> new MaterialAlertDialogBuilder(activity)
                            .setTitle("移除主机指纹？")
                            .setMessage("仅移除 " + endpoint + " 的已保存记录。下次连接仍需核对并确认新的指纹。")
                            .setNegativeButton("取消", null).setPositiveButton("移除", (confirm, which) -> {
                                try {
                                    if (!store.forget(endpoint, encoded)) throw new java.io.IOException();
                                    Toast.makeText(activity, "已移除主机指纹，下次连接需重新核对", Toast.LENGTH_LONG).show();
                                } catch (Exception error) { failed(activity); }
                            }).show());
            if (publicKey != null) {
                String copy = publicKey;
                builder.setPositiveButton("复制主机公钥", (d, w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SSH host public key", copy));
                    Toast.makeText(activity, "已复制主机公钥", Toast.LENGTH_SHORT).show();
                });
            }
            AlertDialog dialog = builder.show();
            selectable(dialog);
        } catch (Exception error) { failed(activity); }
    }
    private static void failed(Activity activity) {
        Toast.makeText(activity, "无法读取或修改主机指纹，记录未被自动替换", Toast.LENGTH_LONG).show();
    }
    private static void selectable(AlertDialog dialog) {
        android.widget.TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextIsSelectable(true);
    }
}

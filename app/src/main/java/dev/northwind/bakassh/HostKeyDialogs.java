package dev.northwind.bakassh;

import android.app.Activity;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class HostKeyDialogs {
    static AlertDialog confirm(Activity activity, HostKeyRequest request) {
        HostKeyTrust.Challenge key = request.challenge;
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("首次连接，核对指纹")
                .setMessage(text(key.endpoint + "\n" + key.algorithm + "\n\n", key.fingerprint,
                        "\n\n信任后会记住它，以后指纹不符将拒绝连接。"))
                .setPositiveButton("信任并连接", (d, w) -> request.answer(true))
                .setNegativeButton("取消连接", (d, w) -> request.answer(false))
                .setOnCancelListener(d -> request.answer(false)).show();
        selectable(dialog);
        return dialog;
    }

    static AlertDialog changed(Activity activity, HostKeyTrust.Changed change) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("主机指纹变了")
                .setMessage(text(change.received.endpoint + "\n\n已保存：\n", change.previous,
                        "\n\n本次收到（" + change.received.algorithm + "）：\n", change.received.fingerprint,
                        "\n\n可能是服务器重装过，也可能有人在中间拦截。确认是前者后，移除旧记录再连。"))
                .setPositiveButton("知道了", null)
                .setNeutralButton("管理记录", (d, w) -> manage(activity, change.received.endpoint)).show();
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
                        .setMessage(endpoint + "\n\n还没有记录，首次连接时确认。")
                        .setPositiveButton("知道了", null).show();
                return;
            }
            String description, publicKey = null;
            try {
                byte[] key = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
                HostKeyTrust.Challenge info = new HostKeyTrust.Challenge(endpoint, key);
                description = info.algorithm + "\n\n" + info.fingerprint;
                publicKey = info.algorithm + " " + encoded;
            } catch (Exception error) { description = "记录已损坏，可以移除后重新核对。"; }
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setTitle("主机指纹")
                    .setMessage(endpoint + "\n" + description + "\n\n同一地址和端口的所有用户共用这条记录。")
                    .setNegativeButton("关闭", null)
                    .setNeutralButton("移除记录", (d, w) -> new MaterialAlertDialogBuilder(activity)
                            .setTitle("移除主机指纹？")
                            .setMessage("下次连接 " + endpoint + " 时需要重新核对。")
                            .setNegativeButton("取消", null).setPositiveButton("移除", (confirm, which) -> {
                                try {
                                    if (!store.forget(endpoint, encoded)) throw new java.io.IOException();
                                    Toast.makeText(activity, "已移除", Toast.LENGTH_LONG).show();
                                } catch (Exception error) { failed(activity); }
                            }).show());
            if (publicKey != null) {
                String copy = publicKey;
                builder.setPositiveButton("复制公钥", (d, w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SSH host public key", copy));
                    Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
                });
            }
            AlertDialog dialog = builder.show();
            selectable(dialog);
        } catch (Exception error) { failed(activity); }
    }
    /** 交替拼接说明文字和等宽显示的指纹（奇数位为指纹），方便逐段核对。 */
    private static CharSequence text(String... parts) {
        android.text.SpannableStringBuilder result = new android.text.SpannableStringBuilder();
        for (int i = 0; i < parts.length; i++) {
            int start = result.length();
            result.append(parts[i]);
            if (i % 2 == 1) {
                result.setSpan(new android.text.style.TypefaceSpan("monospace"), start, result.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, result.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return result;
    }

    private static void failed(Activity activity) {
        Toast.makeText(activity, "读写指纹记录失败，原记录未改动", Toast.LENGTH_LONG).show();
    }
    private static void selectable(AlertDialog dialog) {
        android.widget.TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextIsSelectable(true);
    }
}

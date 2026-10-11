package dev.northwind.bakassh;

import android.content.Context;
import android.util.Log;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 应用自带的 SSH 密钥。
 *
 * rpi5 的 sshd 是 PasswordAuthentication no，所以必须有公钥；我们不借用 Termux 的密钥
 * （跨应用数据目录不可读），而是自己生成一对，把公钥交给用户在服务器端授权。
 * 公钥写到 files/ 目录并会被打进日志，方便用 adb 取出来。
 */
public final class KeyManager {

    private static final String TAG = "sshterm-key";
    private static final String PRIV = "sshterm_id_rsa";
    private static final String PUB = "sshterm_id_rsa.pub";

    private KeyManager() {}

    public static File privFile(Context c) {
        return new File(c.getFilesDir(), PRIV);
    }

    public static File pubFile(Context c) {
        return new File(c.getFilesDir(), PUB);
    }

    /** 没有就生成（RSA 3072，无口令），返回公钥字符串。 */
    public static String ensureKey(Context c) throws Exception {
        File priv = privFile(c);
        File pub = pubFile(c);
        if (priv.exists() && pub.exists()) {
            return readAll(pub);
        }
        JSch jsch = new JSch();
        KeyPair kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072);
        kp.writePrivateKey(priv.getAbsolutePath());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        kp.writePublicKey(bos, "sshterm-android");
        String pubStr = new String(bos.toByteArray(), "UTF-8").trim();
        FileOutputStream fo = new FileOutputStream(pub);
        fo.write((pubStr + "\n").getBytes("UTF-8"));
        fo.close();
        Log.i(TAG, "generated new keypair -> " + priv.getAbsolutePath());
        return pubStr;
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8").trim();
    }
}

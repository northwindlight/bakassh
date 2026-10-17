package dev.northwind.bakassh;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 集中管理应用内的所有 SSH 密钥对（生成、导入、绑定与检索）。
 */
public final class KeyManager {

    private static final String TAG = "sshterm-key";
    private static final String PREF = "sshterm";
    private static final String KEY_KEYS = "ssh_keys_list";
    private static final String LEGACY_PRIV = "sshterm_id_rsa";
    private static final String LEGACY_PUB = "sshterm_id_rsa.pub";

    private KeyManager() {}

    /** 密钥存储子目录 files/keys/ */
    public static File getKeysDir(Context c) {
        File dir = new File(c.getFilesDir(), "keys");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 兼容历史调用的默认私钥文件 */
    public static File privFile(Context c) {
        return getPrivFile(c, KeyItem.DEFAULT_ID);
    }

    /** 兼容历史调用的默认公钥文件 */
    public static File pubFile(Context c) {
        return getPubFile(c, KeyItem.DEFAULT_ID);
    }

    public static File getPrivFile(Context c, String id) {
        if (id == null || id.isEmpty() || KeyItem.DEFAULT_ID.equals(id)) {
            File legacy = new File(c.getFilesDir(), LEGACY_PRIV);
            if (legacy.exists()) return legacy;
            return new File(getKeysDir(c), "default.key");
        }
        return new File(getKeysDir(c), id + ".key");
    }

    public static File getPubFile(Context c, String id) {
        if (id == null || id.isEmpty() || KeyItem.DEFAULT_ID.equals(id)) {
            File legacy = new File(c.getFilesDir(), LEGACY_PUB);
            if (legacy.exists()) return legacy;
            return new File(getKeysDir(c), "default.pub");
        }
        return new File(getKeysDir(c), id + ".pub");
    }

    /** 确保存在默认密钥，并返回所有密钥列表 */
    public static synchronized List<KeyItem> getKeys(Context c) {
        List<KeyItem> list = new ArrayList<>();
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_KEYS, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) list.add(KeyItem.fromJson(o));
                }
            } catch (Throwable t) {
                Log.w(TAG, "failed to parse keys JSON: " + t);
            }
        }

        if (list.isEmpty()) {
            // 初始化或迁移历史默认密钥
            KeyItem def = ensureDefaultKey(c);
            list.add(def);
            saveKeys(c, list);
        } else {
            // 确保至少有一个默认密钥
            boolean hasDefault = false;
            for (KeyItem k : list) {
                if (k.isDefault) {
                    hasDefault = true;
                    break;
                }
            }
            if (!hasDefault) {
                list.get(0).isDefault = true;
                saveKeys(c, list);
            }
        }
        return list;
    }

    public static synchronized void saveKeys(Context c, List<KeyItem> keys) {
        try {
            JSONArray arr = new JSONArray();
            for (KeyItem k : keys) arr.put(k.toJson());
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY_KEYS, arr.toString()).apply();
        } catch (Throwable t) {
            Log.e(TAG, "saveKeys failed: " + t);
        }
    }

    public static synchronized KeyItem getKey(Context c, String id) {
        List<KeyItem> list = getKeys(c);
        if (id == null || id.isEmpty() || KeyItem.DEFAULT_ID.equals(id)) {
            return getDefaultKey(c);
        }
        for (KeyItem k : list) {
            if (id.equals(k.id)) return k;
        }
        return getDefaultKey(c);
    }

    public static synchronized KeyItem getDefaultKey(Context c) {
        List<KeyItem> list = getKeys(c);
        for (KeyItem k : list) {
            if (k.isDefault) return k;
        }
        return list.get(0);
    }

    public static synchronized void setDefaultKey(Context c, String id) {
        List<KeyItem> list = getKeys(c);
        for (KeyItem k : list) {
            k.isDefault = k.id.equals(id);
        }
        saveKeys(c, list);
    }

    /** 确保默认密钥生成或迁移 */
    private static KeyItem ensureDefaultKey(Context c) {
        File priv = new File(c.getFilesDir(), LEGACY_PRIV);
        File pub = new File(c.getFilesDir(), LEGACY_PUB);
        String pubStr = "";
        String fingerprint = "";
        try {
            if (priv.exists() && pub.exists()) {
                pubStr = readAll(pub);
                try {
                    JSch jsch = new JSch();
                    KeyPair kp = KeyPair.load(jsch, priv.getAbsolutePath());
                    fingerprint = kp.getFingerPrint();
                } catch (Throwable ignored) {}
            } else {
                JSch jsch = new JSch();
                KeyPair kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072);
                kp.writePrivateKey(priv.getAbsolutePath());
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                kp.writePublicKey(bos, "bakassh-default");
                pubStr = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
                writeFile(pub, pubStr + "\n");
                fingerprint = kp.getFingerPrint();
                Log.i(TAG, "generated new default RSA keypair");
            }
        } catch (Throwable t) {
            Log.e(TAG, "ensureDefaultKey error: " + t);
        }
        return new KeyItem(KeyItem.DEFAULT_ID, "默认密钥", "RSA-3072", fingerprint, pubStr, false, true, System.currentTimeMillis());
    }

    /** 兼容历史接口：没有就生成默认密钥，返回公钥字符串 */
    public static String ensureKey(Context c) throws Exception {
        return getDefaultKey(c).publicKey;
    }

    /** 生成新密钥对 */
    public static synchronized KeyItem generateKey(Context c, String name, String typeStr,
                                                   String passphrase, String comment) throws Exception {
        JSch jsch = new JSch();
        int typeCode;
        int keySize;
        String normalizedType;

        if ("RSA-2048".equalsIgnoreCase(typeStr)) {
            typeCode = KeyPair.RSA;
            keySize = 2048;
            normalizedType = "RSA-2048";
        } else if ("RSA-4096".equalsIgnoreCase(typeStr)) {
            typeCode = KeyPair.RSA;
            keySize = 4096;
            normalizedType = "RSA-4096";
        } else if ("ECDSA-256".equalsIgnoreCase(typeStr)) {
            typeCode = KeyPair.ECDSA;
            keySize = 256;
            normalizedType = "ECDSA-256";
        } else if ("ECDSA-384".equalsIgnoreCase(typeStr)) {
            typeCode = KeyPair.ECDSA;
            keySize = 384;
            normalizedType = "ECDSA-384";
        } else {
            typeCode = KeyPair.RSA;
            keySize = 3072;
            normalizedType = "RSA-3072";
        }

        KeyPair kp = KeyPair.genKeyPair(jsch, typeCode, keySize);
        boolean hasPass = passphrase != null && !passphrase.trim().isEmpty();
        if (hasPass) {
            kp.setPassphrase(passphrase.trim());
        }

        String id = "k_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        File privFile = getPrivFile(c, id);
        File pubFile = getPubFile(c, id);

        ByteArrayOutputStream privBos = new ByteArrayOutputStream();
        if (hasPass) {
            kp.writePrivateKey(privBos, passphrase.trim().getBytes(StandardCharsets.UTF_8));
        } else {
            kp.writePrivateKey(privBos);
        }
        writeFile(privFile, privBos.toByteArray());

        String keyComment = (comment != null && !comment.trim().isEmpty()) ? comment.trim() : ("bakassh-" + name.trim());
        ByteArrayOutputStream pubBos = new ByteArrayOutputStream();
        kp.writePublicKey(pubBos, keyComment);
        String pubStr = new String(pubBos.toByteArray(), StandardCharsets.UTF_8).trim();
        writeFile(pubFile, (pubStr + "\n").getBytes(StandardCharsets.UTF_8));

        String fp = kp.getFingerPrint();
        KeyItem item = new KeyItem(id, name.trim(), normalizedType, fp, pubStr, hasPass, false, System.currentTimeMillis());

        List<KeyItem> keys = getKeys(c);
        keys.add(item);
        saveKeys(c, keys);
        return item;
    }

    /** 导入已有私钥（OpenSSH、PEM、PKCS8 等格式） */
    public static synchronized KeyItem importKey(Context c, String name, String privateKeyContent,
                                                 String passphrase) throws Exception {
        if (privateKeyContent == null || privateKeyContent.trim().isEmpty()) {
            throw new IllegalArgumentException("私钥内容不能为空");
        }
        byte[] privBytes = privateKeyContent.trim().getBytes(StandardCharsets.UTF_8);
        JSch jsch = new JSch();
        KeyPair kp;
        try {
            kp = KeyPair.load(jsch, privBytes, null);
        } catch (Throwable t) {
            throw new IllegalArgumentException("无法解析私钥，请确认格式为 OpenSSH 或 PEM 文本：" + t.getMessage(), t);
        }

        boolean hasPass = passphrase != null && !passphrase.trim().isEmpty();
        // 记住「落盘的原文件是否加密」：decrypt() 会把 KeyPair 的 encrypted 标志清掉，
        // 但写进 files/keys 的仍是原始密文，连接时必须继续提示口令。
        boolean storedEncrypted = kp.isEncrypted();
        if (kp.isEncrypted()) {
            if (!hasPass) {
                throw new IllegalArgumentException("该私钥已被口令加密，请输入密码口令");
            }
            boolean ok = kp.decrypt(passphrase.trim());
            if (!ok) {
                throw new IllegalArgumentException("私钥口令错误，无法解密");
            }
        }

        String id = "k_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        File privFile = getPrivFile(c, id);
        File pubFile = getPubFile(c, id);

        writeFile(privFile, privBytes);

        String keyComment = "bakassh-" + name.trim();
        ByteArrayOutputStream pubBos = new ByteArrayOutputStream();
        kp.writePublicKey(pubBos, keyComment);
        String pubStr = new String(pubBos.toByteArray(), StandardCharsets.UTF_8).trim();
        writeFile(pubFile, (pubStr + "\n").getBytes(StandardCharsets.UTF_8));

        String type = kp.getKeyTypeString();
        String fp = kp.getFingerPrint();
        KeyItem item = new KeyItem(id, name.trim(), type, fp, pubStr, storedEncrypted, false, System.currentTimeMillis());

        List<KeyItem> keys = getKeys(c);
        keys.add(item);
        saveKeys(c, keys);
        return item;
    }

    public static synchronized boolean deleteKey(Context c, String id) {
        List<KeyItem> keys = getKeys(c);
        if (keys.size() <= 1) return false; // 至少保留一个密钥

        KeyItem target = null;
        for (KeyItem k : keys) {
            if (k.id.equals(id)) {
                target = k;
                break;
            }
        }
        if (target == null) return false;

        keys.remove(target);
        if (target.isDefault && !keys.isEmpty()) {
            keys.get(0).isDefault = true;
        }
        saveKeys(c, keys);

        // 删除物理文件
        try {
            File priv = getPrivFile(c, id);
            File pub = getPubFile(c, id);
            if (priv.exists()) priv.delete();
            if (pub.exists()) pub.delete();
        } catch (Throwable ignored) {}
        return true;
    }

    public static synchronized void renameKey(Context c, String id, String newName) {
        List<KeyItem> keys = getKeys(c);
        for (KeyItem k : keys) {
            if (k.id.equals(id)) {
                k.name = newName.trim();
                break;
            }
        }
        saveKeys(c, keys);
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(data);
        fo.close();
    }

    private static void writeFile(File f, String text) throws Exception {
        writeFile(f, text.getBytes(StandardCharsets.UTF_8));
    }
}

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

    /** 返回密钥列表；允许空列表，只迁移已有的历史身份。 */
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
            // 只迁移已有密钥；打开列表不应偷偷生成 RSA 并阻塞界面。
            if (new File(c.getFilesDir(), LEGACY_PRIV).exists()) {
                KeyItem def = ensureDefaultKey(c);
                list.add(def);
                saveKeys(c, list);
            }
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
        return null;
    }

    public static synchronized KeyItem getDefaultKey(Context c) {
        List<KeyItem> list = getKeys(c);
        for (KeyItem k : list) {
            if (k.isDefault) return k;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    public static synchronized void setDefaultKey(Context c, String id) {
        List<KeyItem> list = getKeys(c);
        for (KeyItem k : list) {
            k.isDefault = k.id.equals(id);
        }
        saveKeys(c, list);
    }

    /** 迁移历史密钥；缺少 .pub 时派生公钥，绝不覆盖已有私钥。 */
    private static KeyItem ensureDefaultKey(Context c) {
        File priv = new File(c.getFilesDir(), LEGACY_PRIV);
        File pub = new File(c.getFilesDir(), LEGACY_PUB);
        KeyPair kp = null;
        try {
            kp = KeyPair.load(new JSch(), priv.getAbsolutePath());
            String pubStr = pub.exists() ? readAll(pub) : "";
            if (pubStr.isEmpty() && kp.getPublicKeyBlob() != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                kp.writePublicKey(out, "bakassh-default");
                pubStr = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
                writeFile(pub, pubStr + "\n");
            }
            String fp = kp.getPublicKeyBlob() != null ? kp.getFingerPrint() : "";
            return new KeyItem(KeyItem.DEFAULT_ID, "默认密钥", kp.getKeyTypeString(), fp,
                    pubStr, kp.isEncrypted(), true, System.currentTimeMillis());
        } catch (Exception e) {
            throw new IllegalStateException("旧密钥无法读取，请检查原始私钥文件。", e);
        } finally {
            if (kp != null) kp.dispose();
        }
    }

    /** 兼容历史接口：返回当前默认身份的公钥。 */
    public static String ensureKey(Context c) throws Exception {
        KeyItem key = getDefaultKey(c);
        if (key == null) throw new IllegalStateException("请先生成或导入密钥");
        return key.publicKey;
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
        boolean hasPass = passphrase != null && !passphrase.isEmpty();
        if (hasPass) {
            kp.setPassphrase(passphrase);
        }

        String id = "k_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        File privFile = getPrivFile(c, id);
        File pubFile = getPubFile(c, id);

        ByteArrayOutputStream privBos = new ByteArrayOutputStream();
        if (hasPass) {
            kp.writePrivateKey(privBos, passphrase.getBytes(StandardCharsets.UTF_8));
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
        item.isDefault = keys.isEmpty();
        keys.add(item);
        saveKeys(c, keys);
        kp.dispose();
        return item;
    }

    /** 导入已有私钥（OpenSSH、PEM、PKCS8 等格式） */
    public static synchronized KeyItem importKey(Context c, String name, String privateKeyContent,
                                                 String passphrase) throws Exception {
        if (privateKeyContent == null || privateKeyContent.trim().isEmpty()) {
            throw new IllegalArgumentException("私钥内容不能为空");
        }
        byte[] privBytes = PrivateKeyImport.normalize(privateKeyContent);
        PrivateKeyImport.Loaded loaded;
        try { loaded = PrivateKeyImport.inspect(privBytes, passphrase); }
        catch (Exception e) {
            java.util.Arrays.fill(privBytes, (byte) 0);
            throw e;
        }
        KeyPair kp = loaded.pair;
        String id = "k_" + UUID.randomUUID().toString().replace("-", "");
        File privFile = getPrivFile(c, id);
        File pubFile = getPubFile(c, id);
        boolean stored = false;
        try {
            ByteArrayOutputStream pubBos = new ByteArrayOutputStream();
            kp.writePublicKey(pubBos, "bakassh-" + name.trim());
            String pubStr = new String(pubBos.toByteArray(), StandardCharsets.UTF_8).trim();
            KeyItem item = new KeyItem(id, name.trim(), kp.getKeyTypeString(), kp.getFingerPrint(),
                    pubStr, loaded.encrypted, false, System.currentTimeMillis());
            List<KeyItem> keys = getKeys(c);
            item.isDefault = keys.isEmpty();
            writeFile(privFile, privBytes);
            writeFile(pubFile, pubStr + "\n");
            keys.add(item);
            saveKeys(c, keys);
            stored = true;
            return item;
        } finally {
            kp.dispose();
            java.util.Arrays.fill(privBytes, (byte) 0);
            if (!stored) {
                if (privFile.exists()) privFile.delete();
                if (pubFile.exists()) pubFile.delete();
            }
        }
    }

    public static synchronized boolean deleteKey(Context c, String id) {
        List<KeyItem> keys = getKeys(c);

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

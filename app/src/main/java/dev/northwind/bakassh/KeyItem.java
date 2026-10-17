package dev.northwind.bakassh;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 代表一套 SSH 密钥对（公钥信息、元数据与本地私钥引用）。
 */
public class KeyItem {
    public static final String DEFAULT_ID = "default";

    public String id = "";
    public String name = "";
    public String type = ""; // "RSA-3072", "RSA-2048", "ECDSA-256", "IMPORTED", 等
    public String fingerprint = "";
    public String publicKey = "";
    public boolean encrypted = false;
    public boolean isDefault = false;
    public long createdAt = 0;

    public KeyItem() {}

    public KeyItem(String id, String name, String type, String fingerprint,
                   String publicKey, boolean encrypted, boolean isDefault, long createdAt) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.fingerprint = fingerprint;
        this.publicKey = publicKey;
        this.encrypted = encrypted;
        this.isDefault = isDefault;
        this.createdAt = createdAt;
    }

    public String title() {
        return name + (isDefault ? " 【默认】" : "");
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (type != null && !type.isEmpty()) sb.append(type);
        if (fingerprint != null && !fingerprint.isEmpty()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(fingerprint);
        }
        if (encrypted) sb.append(" [口令加密]");
        return sb.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("type", type);
        o.put("fingerprint", fingerprint);
        o.put("publicKey", publicKey);
        o.put("encrypted", encrypted);
        o.put("isDefault", isDefault);
        o.put("createdAt", createdAt);
        return o;
    }

    public static KeyItem fromJson(JSONObject o) {
        KeyItem k = new KeyItem();
        k.id = o.optString("id", "");
        k.name = o.optString("name", "");
        k.type = o.optString("type", "");
        k.fingerprint = o.optString("fingerprint", "");
        k.publicKey = o.optString("publicKey", "");
        k.encrypted = o.optBoolean("encrypted", false);
        k.isDefault = o.optBoolean("isDefault", false);
        k.createdAt = o.optLong("createdAt", 0);
        return k;
    }
}

package dev.northwind.bakassh;

import org.json.JSONException;
import org.json.JSONObject;

/** 一台保存下来的 SSH 主机。 */
public class Host {

    public String name = "";
    public String host = "";
    public int port = 22;
    public String user = "";
    /** 连上后自动执行的命令（可空），例如 tmux attach */
    public String startup = "";
    /** 认证方式："publickey"（默认）或 "password" */
    public String authType = "publickey";
    /** 绑定的密钥 ID；"default" 表示使用全局默认密钥 */
    public String keyId = "default";
    /** 保存的密码（可选；为空时若选择密码认证则连接时询问） */
    public String password = "";

    public Host() { }

    public Host(String name, String host, int port, String user, String startup) {
        this(name, host, port, user, startup, "publickey", "default", "");
    }

    public Host(String name, String host, int port, String user, String startup,
                String authType, String keyId, String password) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.user = user;
        this.startup = startup == null ? "" : startup;
        this.authType = (authType == null || authType.isEmpty()) ? "publickey" : authType;
        this.keyId = (keyId == null || keyId.isEmpty()) ? "default" : keyId;
        this.password = password == null ? "" : password;
    }

    public String title() {
        return (name == null || name.isEmpty()) ? (user + "@" + host) : name;
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        sb.append(user).append('@').append(host).append(':').append(port);
        if ("password".equalsIgnoreCase(authType)) {
            sb.append("  [密码]");
        } else {
            String kLabel = ("default".equals(keyId) || keyId == null || keyId.isEmpty()) ? "默认密钥" : keyId;
            sb.append("  [").append(kLabel).append("]");
        }
        if (startup != null && !startup.isEmpty()) sb.append("   ▸ ").append(startup);
        return sb.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("host", host);
        o.put("port", port);
        o.put("user", user);
        o.put("startup", startup);
        o.put("authType", authType);
        o.put("keyId", keyId);
        o.put("password", password);
        return o;
    }

    public static Host fromJson(JSONObject o) {
        Host h = new Host();
        h.name = o.optString("name", "");
        h.host = o.optString("host", "");
        h.port = o.optInt("port", 22);
        h.user = o.optString("user", "");
        h.startup = o.optString("startup", "");
        h.authType = o.optString("authType", "publickey");
        h.keyId = o.optString("keyId", "default");
        h.password = o.optString("password", "");
        return h;
    }
}

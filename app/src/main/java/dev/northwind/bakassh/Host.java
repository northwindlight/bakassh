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

    public Host() { }

    public Host(String name, String host, int port, String user, String startup) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.user = user;
        this.startup = startup == null ? "" : startup;
    }

    public String title() {
        return (name == null || name.isEmpty()) ? (user + "@" + host) : name;
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        sb.append(user).append('@').append(host).append(':').append(port);
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
        return o;
    }

    public static Host fromJson(JSONObject o) {
        Host h = new Host();
        h.name = o.optString("name", "");
        h.host = o.optString("host", "");
        h.port = o.optInt("port", 22);
        h.user = o.optString("user", "");
        h.startup = o.optString("startup", "");
        return h;
    }
}

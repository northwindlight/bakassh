package dev.northwind.bakassh;

import com.jcraft.jsch.Session;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Standard SSH -L / -R forwarding, parsed as arguments and never passed to a shell. */
final class SshForwarding {
    static final class Rule {
        final boolean remote;
        final String bind, target;
        final int port, targetPort;
        Rule(boolean remote, String bind, int port, String target, int targetPort) {
            this.remote = remote;
            this.bind = address(bind.isEmpty() ? "127.0.0.1" : bind);
            this.port = checkedPort(port);
            this.target = address(target);
            this.targetPort = checkedPort(targetPort);
        }
        String specification() { return bracket(bind) + ":" + port + ":" + bracket(target) + ":" + targetPort; }
    }

    static final class Plan {
        final String host, user;
        final int port, aliveSeconds, aliveCount;
        final List<Rule> rules;
        Plan(String host, String user, int port, List<Rule> rules, int aliveSeconds, int aliveCount) {
            this.host = address(host);
            this.user = address(user);
            this.port = checkedPort(port);
            if (rules.isEmpty() || rules.size() > 32) throw bad("请添加 1–32 条 -L / -R 规则");
            this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
            this.aliveSeconds = aliveSeconds;
            this.aliveCount = aliveCount;
        }
        String command() {
            StringBuilder out = new StringBuilder("ssh -N -o ExitOnForwardFailure=yes");
            out.append(" -o ServerAliveInterval=").append(aliveSeconds)
                    .append(" -o ServerAliveCountMax=").append(aliveCount).append(" -p ").append(port);
            for (Rule rule : rules) out.append(rule.remote ? " -R " : " -L ").append(quote(rule.specification()));
            return out.append(' ').append(quote(user + "@" + bracket(host))).toString();
        }
    }

    static Plan parse(String command) {
        List<String> args = words(command);
        if (args.isEmpty() || !"ssh".equals(args.get(0))) throw bad("请输入以 ssh 开头的转发命令");
        String endpoint = null, login = null;
        int sshPort = 22, aliveSeconds = 30, aliveCount = 3;
        boolean noShell = false, gateway = false;
        List<String> specifications = new ArrayList<>();
        List<Boolean> directions = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-N".equals(arg)) { noShell = true; continue; }
            if ("-T".equals(arg) || "-n".equals(arg)) continue;
            if ("-g".equals(arg)) { gateway = true; continue; }
            if (arg.startsWith("-L") || arg.startsWith("-R") || arg.startsWith("-p")
                    || arg.startsWith("-l") || arg.startsWith("-o")) {
                char option = arg.charAt(1);
                String value;
                if (arg.length() > 2) value = arg.substring(2);
                else {
                    if (++i >= args.size()) throw bad("选项缺少参数：" + arg);
                    value = args.get(i);
                }
                switch (option) {
                    case 'L': case 'R':
                        specifications.add(value); directions.add(option == 'R'); break;
                    case 'p': sshPort = parsePort(value); break;
                    case 'l': login = address(value); break;
                    case 'o': {
                        String[] pair = value.split("=", 2);
                        if (pair.length != 2) pair = value.trim().split("\\s+", 2);
                        if (pair.length != 2) throw bad("-o 参数请使用 名称=值");
                        if ("ExitOnForwardFailure".equalsIgnoreCase(pair[0])) {
                            if (!"yes".equalsIgnoreCase(pair[1])) throw bad("本页要求 ExitOnForwardFailure=yes，避免部分规则启动失败却显示已连接");
                        } else if ("ServerAliveInterval".equalsIgnoreCase(pair[0])) {
                            aliveSeconds = bounded(pair[1], 1, 3600, "ServerAliveInterval");
                        } else if ("ServerAliveCountMax".equalsIgnoreCase(pair[0])) {
                            aliveCount = bounded(pair[1], 1, 100, "ServerAliveCountMax");
                        } else throw bad("暂不支持 SSH 选项：" + pair[0]);
                        break;
                    }
                    default: throw new AssertionError();
                }
            } else if (arg.startsWith("-")) throw bad("暂不支持选项 " + arg + "；本页支持 -N、-L、-R、-p、-l、-g 和保活选项");
            else if (endpoint == null) endpoint = arg;
            else throw bad("转发命令不执行远程 shell 命令，请删除多余参数：" + arg);
        }
        if (!noShell) throw bad("请添加 -N，仅建立 SSH 转发");
        if (endpoint == null) throw bad("请填写 SSH 服务器，例如 user@server");
        int at = endpoint.lastIndexOf('@');
        String user = at >= 0 ? endpoint.substring(0, at) : login;
        String host = at >= 0 ? endpoint.substring(at + 1) : endpoint;
        if (user == null || user.isEmpty()) throw bad("请使用 user@server 或 -l 指定用户名");
        host = unbracket(host);
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < specifications.size(); i++) rules.add(parseRule(directions.get(i), specifications.get(i), gateway));
        return new Plan(host, user, sshPort, rules, aliveSeconds, aliveCount);
    }

    static Rule parseRule(boolean remote, String text, boolean gateway) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean bracket = false;
        for (char c : text.toCharArray()) {
            if (c == '[') { if (bracket || part.length() != 0) throw bad("IPv6 地址请使用 [地址] 格式"); bracket = true; }
            else if (c == ']') { if (!bracket) throw bad("地址括号不匹配"); bracket = false; }
            else if (c == ':' && !bracket) { parts.add(part.toString()); part.setLength(0); }
            else part.append(c);
        }
        if (bracket) throw bad("地址括号不匹配");
        parts.add(part.toString());
        if (parts.size() != 3 && parts.size() != 4) throw bad("规则格式：[监听地址:]监听端口:目标主机:目标端口");
        int offset = parts.size() == 4 ? 1 : 0;
        String bind = offset == 1 ? parts.get(0) : (!remote && gateway ? "0.0.0.0" : "127.0.0.1");
        if ("*".equals(bind) || bind.isEmpty()) bind = "0.0.0.0";
        return new Rule(remote, bind, parsePort(parts.get(offset)), parts.get(offset + 1), parsePort(parts.get(offset + 2)));
    }

    static void apply(Session session, Plan plan) throws Exception {
        for (Rule rule : plan.rules) {
            if (rule.remote) session.setPortForwardingR(rule.bind, rule.port, rule.target, rule.targetPort);
            else session.setPortForwardingL(rule.bind, rule.port, rule.target, rule.targetPort, (port, backlog, address) -> {
                java.net.ServerSocket socket = new java.net.ServerSocket();
                try {
                    socket.setReuseAddress(true);
                    socket.bind(new java.net.InetSocketAddress(address, port), backlog);
                    return socket;
                } catch (java.io.IOException error) { socket.close(); throw error; }
            });
        }
    }

    private static List<String> words(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\\' && quote != '\'') {
                if (++i == command.length()) throw bad("命令不能以反斜杠结束");
                c = command.charAt(i);
                if (c == '\r' && i + 1 < command.length() && command.charAt(i + 1) == '\n') { i++; continue; }
                if (c == '\n') continue;
                word.append(c); started = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0; else word.append(c);
            } else if (c == '\'' || c == '"') { quote = c; started = true; }
            else if (Character.isWhitespace(c)) {
                if (started) { result.add(word.toString()); word.setLength(0); started = false; }
            } else {
                if (";&|<>`$".indexOf(c) >= 0) throw bad("这里只解析 SSH 参数，不执行 shell 表达式");
                word.append(c); started = true;
            }
        }
        if (quote != 0) throw bad("命令引号未闭合");
        if (started) result.add(word.toString());
        return result;
    }

    private static String address(String value) {
        if (value == null || value.isEmpty() || value.startsWith("-") || value.indexOf('@') >= 0)
            throw bad("主机、用户和监听地址不能为空，也不能以 - 开头");
        for (char c : value.toCharArray()) if (Character.isWhitespace(c) || Character.isISOControl(c))
            throw bad("地址和用户名不能包含空白或控制字符");
        return value;
    }
    static int parsePort(String value) { return bounded(value, 1, 65535, "端口"); }
    private static int checkedPort(int value) {
        if (value < 1 || value > 65535) throw bad("端口范围是 1–65535");
        return value;
    }
    private static int bounded(String text, int min, int max, String name) {
        try { int value = Integer.parseInt(text); if (value >= min && value <= max) return value; }
        catch (NumberFormatException ignored) { }
        throw bad(name + "范围是 " + min + "–" + max);
    }
    private static String bracket(String value) { return value.indexOf(':') >= 0 ? "[" + value + "]" : value; }
    private static String unbracket(String value) {
        return value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
    }
    private static String quote(String value) {
        return value.matches("[A-Za-z0-9_@.:%\\[\\]-]+") ? value : "'" + value.replace("'", "'\\''") + "'";
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}

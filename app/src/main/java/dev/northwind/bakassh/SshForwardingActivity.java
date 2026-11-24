package dev.northwind.bakassh;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import com.google.android.material.R.attr;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Port forwarding page. The form and the command editor are two views of one standard ssh command;
 * edits are kept as a draft until the user starts or applies them, so leaving the page never loses work.
 */
public final class SshForwardingActivity extends AppCompatActivity {
    private final List<SshForwarding.Rule> rules = new ArrayList<>();
    private List<Host> hosts;
    private Host selected;
    private MaterialButtonToggleGroup modes;
    private LinearLayout ruleList;
    private TextInputEditText command;
    private TextInputLayout commandLayout;
    private TextView preview, status, statusTitle, rulesLabel, hostTitle, hostAddress, hostAvatar;
    private MaterialCardView statusCard;
    private ImageView statusIcon;
    private MaterialButton startButton, stopButton;
    private MaterialSwitch boot;
    private SharedPreferences prefs;
    private boolean manual, switching, resumed, hostsReadable = true;
    private int aliveSeconds = 30, aliveCount = 3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HostKeyRequest shownRequest;
    private HostKeyTrust.Changed shownChange;
    private AlertDialog keyDialog;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            renderState();
            SshTunnelService service = SshTunnelService.current();
            if (service != null) {
                service.uiVisible = true;
                if (service.hostKeyRequest != null && service.hostKeyRequest != shownRequest) {
                    shownRequest = service.hostKeyRequest;
                    if (keyDialog != null) keyDialog.dismiss();
                    keyDialog = HostKeyDialogs.confirm(SshForwardingActivity.this, shownRequest);
                }
                if (service.changedHostKey != null && service.changedHostKey != shownChange) {
                    shownChange = service.changedHostKey;
                    HostKeyDialogs.changed(SshForwardingActivity.this, shownChange);
                }
            }
            main.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_ssh_forwarding);
        ((MaterialToolbar) findViewById(R.id.tunnel_toolbar)).setNavigationOnClickListener(v -> finish());
        prefs = SshTunnelService.prefs(this);
        modes = findViewById(R.id.tunnel_modes);
        ruleList = findViewById(R.id.tunnel_rules);
        rulesLabel = findViewById(R.id.tunnel_rules_label);
        command = findViewById(R.id.tunnel_command);
        commandLayout = findViewById(R.id.tunnel_command_layout);
        preview = findViewById(R.id.tunnel_preview);
        status = findViewById(R.id.tunnel_status);
        statusTitle = findViewById(R.id.tunnel_status_title);
        statusCard = findViewById(R.id.tunnel_status_card);
        statusIcon = findViewById(R.id.tunnel_status_icon);
        hostTitle = findViewById(R.id.tunnel_host_title);
        hostAddress = findViewById(R.id.tunnel_host_address);
        hostAvatar = findViewById(R.id.tunnel_host_avatar);
        startButton = findViewById(R.id.tunnel_start);
        stopButton = findViewById(R.id.tunnel_stop);
        boot = findViewById(R.id.tunnel_boot);

        try { hosts = HostStore.load(this); }
        catch (RuntimeException error) { hosts = new ArrayList<>(); hostsReadable = false; }

        String requestedHost = getIntent().getStringExtra("host_id");
        String hostId = state != null ? state.getString("host_id") : requestedHost;
        if (hostId == null) hostId = prefs.getString("draft_host_id", prefs.getString("host_id", ""));
        for (Host host : hosts) if (host.id.equals(hostId)) selected = host;
        if (selected == null && !hosts.isEmpty()) selected = hosts.get(0);

        String saved = "";
        if (state != null) { saved = state.getString("command", ""); manual = state.getBoolean("manual"); }
        else if (selected != null && selected.id.equals(prefs.getString("draft_host_id", ""))) {
            saved = prefs.getString("draft_command", ""); manual = prefs.getBoolean("draft_manual", false);
        } else if (selected != null && selected.id.equals(prefs.getString("host_id", ""))) {
            saved = prefs.getString("command", ""); manual = prefs.getBoolean("manual", false);
        }
        if (!saved.isEmpty()) {
            try {
                SshForwarding.Plan plan = SshForwarding.parseDraft(saved);
                if (manual) adopt(plan); else load(plan);
            } catch (IllegalArgumentException error) { manual = true; }
        }
        command.setText(saved.isEmpty() || !manual ? draftCommand() : saved);

        boot.setChecked(prefs.getBoolean("boot", false));
        boot.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("boot", checked).apply());
        findViewById(R.id.tunnel_boot_row).setOnClickListener(v -> boot.toggle());
        findViewById(R.id.tunnel_background).setOnClickListener(v -> BackgroundSettings.show(this));
        findViewById(R.id.tunnel_host_row).setOnClickListener(v -> chooseHost());
        findViewById(R.id.tunnel_add).setOnClickListener(v -> editRule(-1));
        findViewById(R.id.tunnel_copy).setOnClickListener(v -> copy());
        commandLayout.setEndIconOnClickListener(v -> copy());
        command.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (!manual || switching) return;
                commandLayout.setError(null);
                try { adopt(SshForwarding.parseDraft(s.toString())); } catch (IllegalArgumentException ignored) { }
            }
        });
        modes.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || switching) return;
            boolean nextManual = id == R.id.tunnel_manual;
            if (nextManual == manual) return;
            try {
                if (nextManual) { switching = true; command.setText(draftCommand()); switching = false; }
                else load(SshForwarding.parseDraft(command.getText().toString()));
                manual = nextManual;
                commandLayout.setError(null);
            } catch (IllegalArgumentException error) { switching = false; commandLayout.setError(error.getMessage()); }
            updateMode();
        });
        startButton.setOnClickListener(v -> start());
        stopButton.setOnClickListener(v -> {
            prefs.edit().putBoolean("enabled", false).putString("state", SshTunnelService.IDLE).putString("status", "").apply();
            stopService(new Intent(this, SshTunnelService.class));
            renderState();
        });
        renderHost(); renderRules(); updateMode(); renderState();
    }

    // ---- host -------------------------------------------------------------------------------

    private void chooseHost() {
        if (hosts.isEmpty()) { finish(); return; }
        if (manual) { message("命令模式下，主机由命令里的 user@host 决定"); return; }
        String[] labels = new String[hosts.size()];
        for (int i = 0; i < hosts.size(); i++) labels[i] = hosts.get(i).title() + "\n" + address(hosts.get(i));
        new MaterialAlertDialogBuilder(this).setTitle("经由服务器")
                .setSingleChoiceItems(labels, hosts.indexOf(selected), (d, which) -> {
                    selected = hosts.get(which); renderHost(); updatePreview(); d.dismiss();
                }).setNegativeButton("取消", null).show();
    }
    private static String address(Host host) { return host.user + "@" + host.host + (host.port == 22 ? "" : ":" + host.port); }
    private void renderHost() {
        boolean known = selected != null;
        String title = !hostsReadable ? "无法读取主机" : hosts.isEmpty() ? "还没有主机" : known ? selected.title() : "没有匹配的主机";
        hostTitle.setText(title);
        hostAddress.setText(!hostsReadable ? "回到主页重试" : hosts.isEmpty() ? "先回主页添加一台" : known ? address(selected) : "");
        hostAvatar.setText(known && !title.isEmpty() ? title.substring(0, 1).toUpperCase(java.util.Locale.ROOT) : "?");
        findViewById(R.id.tunnel_host_chevron).setVisibility(manual || hosts.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private Host matching(SshForwarding.Plan plan) {
        if (selected != null && matches(selected, plan)) return selected;
        for (Host host : hosts) if (matches(host, plan)) return host;
        throw new IllegalArgumentException("主机列表里没有 " + plan.user + "@" + plan.host + ":" + plan.port + "，请先添加");
    }
    private static boolean matches(Host host, SshForwarding.Plan plan) {
        return host.host.equalsIgnoreCase(plan.host) && host.user.equals(plan.user) && host.port == plan.port;
    }
    /** Takes host and keep-alive settings from a command without touching the rule list (command mode). */
    private void adopt(SshForwarding.Plan plan) {
        selected = matching(plan); aliveSeconds = plan.aliveSeconds; aliveCount = plan.aliveCount;
        if (hostTitle != null) renderHost();
    }
    private void load(SshForwarding.Plan plan) {
        adopt(plan); rules.clear(); rules.addAll(plan.rules); renderRules();
    }

    // ---- command ----------------------------------------------------------------------------

    private SshForwarding.Plan draftPlan() {
        if (selected == null) throw new IllegalArgumentException("先选择服务器");
        return new SshForwarding.Plan(selected.host, selected.user, selected.port, rules, aliveSeconds, aliveCount, true);
    }
    private String draftCommand() { try { return draftPlan().pretty(); } catch (IllegalArgumentException error) { return ""; } }
    /** The plan to run: strict, so an empty rule list or a bad command is reported here. */
    private SshForwarding.Plan plan() {
        if (manual) return SshForwarding.parse(command.getText().toString());
        if (rules.isEmpty()) throw new IllegalArgumentException("先添加一条规则");
        draftPlan();
        return new SshForwarding.Plan(selected.host, selected.user, selected.port, rules, aliveSeconds, aliveCount);
    }
    private String currentCommand() {
        try { return plan().command(); } catch (IllegalArgumentException error) { return manual ? command.getText().toString() : ""; }
    }
    private void updatePreview() {
        String text = draftCommand();
        preview.setText(rules.isEmpty() || text.isEmpty() ? "添加规则后，这里显示等价的 ssh 命令" : text);
        findViewById(R.id.tunnel_copy).setVisibility(rules.isEmpty() || text.isEmpty() ? View.GONE : View.VISIBLE);
        renderState();
    }
    private void copy() {
        String text = manual ? command.getText().toString().trim() : draftCommand();
        if (text.isEmpty()) return;
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ssh", text));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }
    private void updateMode() {
        switching = true; modes.check(manual ? R.id.tunnel_manual : R.id.tunnel_graphical); switching = false;
        findViewById(R.id.tunnel_graph_group).setVisibility(manual ? View.GONE : View.VISIBLE);
        findViewById(R.id.tunnel_preview_block).setVisibility(manual ? View.GONE : View.VISIBLE);
        commandLayout.setVisibility(manual ? View.VISIBLE : View.GONE);
        renderHost(); updatePreview();
    }

    // ---- rules ------------------------------------------------------------------------------

    private void renderRules() {
        if (ruleList == null) return;
        ruleList.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < rules.size(); i++) {
            SshForwarding.Rule rule = rules.get(i);
            final int index = i;
            View card = inflater.inflate(R.layout.item_forward_rule, ruleList, false);
            TextView badge = card.findViewById(R.id.rule_badge);
            badge.setText(rule.remote ? "R" : "L");
            badge.setBackgroundTintList(ColorStateList.valueOf(color(rule.remote ? attr.colorTertiaryContainer : attr.colorPrimaryContainer)));
            badge.setTextColor(color(rule.remote ? attr.colorOnTertiaryContainer : attr.colorOnPrimaryContainer));
            ((TextView) card.findViewById(R.id.rule_route)).setText(route(rule));
            ((TextView) card.findViewById(R.id.rule_note)).setText(note(rule));
            card.setContentDescription((rule.remote ? "远程转发 " : "本地转发 ") + route(rule) + "，" + note(rule));
            card.setOnClickListener(v -> editRule(index));
            View menuButton = card.findViewById(R.id.rule_menu);
            menuButton.setOnClickListener(v -> {
                PopupMenu menu = new PopupMenu(this, v);
                menu.getMenu().add(0, 0, 0, "编辑");
                menu.getMenu().add(0, 1, 1, "上移").setEnabled(index > 0);
                menu.getMenu().add(0, 2, 2, "下移").setEnabled(index < rules.size() - 1);
                menu.getMenu().add(0, 3, 3, "删除");
                menu.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 0: editRule(index); break;
                        case 1: move(index, index - 1); break;
                        case 2: move(index, index + 1); break;
                        default: remove(index);
                    }
                    return true;
                });
                menu.show();
            });
            ruleList.addView(card);
        }
        findViewById(R.id.tunnel_rules_empty).setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
        rulesLabel.setText(rules.isEmpty() ? "规则" : "规则 · " + rules.size());
    }
    private static boolean loopback(String address) {
        return address.equals("127.0.0.1") || address.equals("localhost") || address.equals("::1");
    }
    private static String endpoint(String address, int port) {
        return (address.indexOf(':') >= 0 ? "[" + address + "]" : address) + ":" + port;
    }
    /** Listening side shown as just the port when it is loopback, which is the common case. */
    private static String route(SshForwarding.Rule rule) {
        String listen = loopback(rule.bind) ? String.valueOf(rule.port) : endpoint(rule.bind, rule.port);
        return listen + " → " + endpoint(rule.target, rule.targetPort);
    }
    private static String note(SshForwarding.Rule rule) {
        String side = rule.remote ? "服务器" : "手机";
        if (loopback(rule.bind)) return "在" + side + "上监听，仅" + side + "自己可连";
        if (rule.bind.equals("0.0.0.0") || rule.bind.equals("::")) return "在" + side + "上监听，同网络的设备都能连";
        return "在" + side + "的 " + rule.bind + " 上监听";
    }
    private void move(int from, int to) {
        if (to < 0 || to >= rules.size()) return;
        rules.add(to, rules.remove(from)); renderRules(); updatePreview();
    }
    private void remove(int index) {
        SshForwarding.Rule removed = rules.remove(index);
        renderRules(); updatePreview();
        Snackbar.make(findViewById(R.id.tunnel_scroll), "已删除规则", Snackbar.LENGTH_LONG)
                .setAnchorView(R.id.tunnel_bar)
                .setAction("撤销", v -> { rules.add(Math.min(index, rules.size()), removed); renderRules(); updatePreview(); }).show();
    }

    private void editRule(int index) {
        if (index < 0 && rules.size() >= 32) { message("最多 32 条规则"); return; }
        SshForwarding.Rule old = index < 0 ? null : rules.get(index);
        View content = getLayoutInflater().inflate(R.layout.dialog_forward_rule, null);
        MaterialButtonToggleGroup direction = content.findViewById(R.id.rule_direction);
        EditText bind = content.findViewById(R.id.rule_bind), port = content.findViewById(R.id.rule_port);
        EditText target = content.findViewById(R.id.rule_target), targetPort = content.findViewById(R.id.rule_target_port);
        TextView explain = content.findViewById(R.id.rule_explain), hint = content.findViewById(R.id.rule_hint);
        TextView listenLabel = content.findViewById(R.id.rule_listen_label), targetLabel = content.findViewById(R.id.rule_target_label);
        TextView error = content.findViewById(R.id.rule_error);
        bind.setText(old == null ? "127.0.0.1" : old.bind);
        port.setText(old == null ? "" : String.valueOf(old.port));
        target.setText(old == null ? "127.0.0.1" : old.target);
        targetPort.setText(old == null ? "" : String.valueOf(old.targetPort));
        Runnable describe = () -> {
            boolean remote = direction.getCheckedButtonId() == R.id.rule_remote;
            explain.setText(remote ? "服务器上开端口，流量经手机转给目标。"
                    : "手机上开端口，流量经服务器转给目标。");
            listenLabel.setText(remote ? "服务器上监听" : "手机上监听");
            targetLabel.setText(remote ? "目标（从手机看）" : "目标（从服务器看）");
            hint.setText(remote ? "监听 0.0.0.0 需要服务器开启 GatewayPorts。"
                    : "监听 0.0.0.0 时，同一网络的其他设备也能连。");
        };
        direction.check(old != null && old.remote ? R.id.rule_remote : R.id.rule_local);
        direction.addOnButtonCheckedListener((g, id, checked) -> { if (checked) describe.run(); });
        describe.run();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(old == null ? "添加规则" : "编辑规则")
                .setView(content).setNegativeButton("取消", null).setPositiveButton("保存", null);
        if (old != null) builder.setNeutralButton("删除", (d, w) -> remove(index));
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                SshForwarding.Rule rule = new SshForwarding.Rule(direction.getCheckedButtonId() == R.id.rule_remote,
                        bind.getText().toString().trim(), SshForwarding.parsePort(port.getText().toString().trim()),
                        target.getText().toString().trim(), SshForwarding.parsePort(targetPort.getText().toString().trim()));
                if (old == null) rules.add(rule); else rules.set(index, rule);
                renderRules(); updatePreview(); dialog.dismiss();
            } catch (IllegalArgumentException failure) {
                error.setText(failure.getMessage()); error.setVisibility(View.VISIBLE);
            }
        }));
        dialog.show();
        if (old == null) port.requestFocus();
    }

    // ---- state ------------------------------------------------------------------------------

    private void renderState() {
        if (statusCard == null) return;
        boolean enabled = prefs.getBoolean("enabled", false);
        String state = prefs.getString("state", SshTunnelService.IDLE);
        if (!enabled && !SshTunnelService.FAILED.equals(state)) state = SshTunnelService.IDLE;
        String detail = prefs.getString("status", "");
        int card = attr.colorSurfaceContainer, onCard = attr.colorOnSurface, badge = attr.colorSurfaceContainerHighest,
                onBadge = attr.colorOnSurfaceVariant, icon = R.drawable.ic_swap;
        switch (state) {
            case SshTunnelService.CONNECTED:
                card = attr.colorPrimaryContainer; onCard = attr.colorOnPrimaryContainer;
                badge = attr.colorPrimary; onBadge = attr.colorOnPrimary; break;
            case SshTunnelService.CONNECTING: case SshTunnelService.RETRYING:
                badge = attr.colorTertiaryContainer; onBadge = attr.colorOnTertiaryContainer; break;
            case SshTunnelService.FAILED:
                card = attr.colorErrorContainer; onCard = attr.colorOnErrorContainer;
                badge = attr.colorError; onBadge = attr.colorOnError; icon = R.drawable.ic_error; break;
            default:
                detail = idleDetail();
        }
        statusCard.setCardBackgroundColor(color(card));
        statusTitle.setText(SshTunnelService.title(state));
        statusTitle.setTextColor(color(onCard));
        status.setText(detail);
        status.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        status.setTextColor(color(onCard));
        statusIcon.setImageResource(icon);
        statusIcon.setBackgroundTintList(ColorStateList.valueOf(color(badge)));
        statusIcon.setImageTintList(ColorStateList.valueOf(color(onBadge)));

        boolean dirty = enabled && (selected == null || !selected.id.equals(prefs.getString("host_id", ""))
                || !currentCommand().equals(prefs.getString("command", "")));
        stopButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        startButton.setVisibility(!enabled || dirty ? View.VISIBLE : View.GONE);
        findViewById(R.id.tunnel_button_gap).setVisibility(enabled && dirty ? View.VISIBLE : View.GONE);
        startButton.setText(enabled ? "应用修改" : "启动");
        startButton.setEnabled(hostsReadable && selected != null);
    }
    private String idleDetail() {
        if (!hostsReadable) return "主机列表读取失败";
        if (hosts.isEmpty()) return "先在主页添加一台主机";
        if (selected == null) return "";
        if (manual) return "按命令里的规则转发";
        return rules.isEmpty() ? "添加一条规则后启动" : address(selected) + " · " + rules.size() + " 条规则";
    }

    private void start() {
        try {
            commandLayout.setError(null);
            SshForwarding.Plan plan = plan(); Host host = matching(plan); selected = host; renderHost();
            if ("password".equals(host.authType) && host.password.isEmpty()) secret("密码", value -> launch(host, plan, null, value));
            else if (!"password".equals(host.authType)) {
                KeyItem key = KeyManager.getKey(this, host.keyId);
                if (key == null) throw new IllegalArgumentException("这台主机没有可用的密钥");
                if (key.encrypted) secret("密钥口令", value -> launch(host, plan, value, null));
                else launch(host, plan, null, null);
            } else launch(host, plan, null, null);
        } catch (IllegalArgumentException error) {
            if (manual) commandLayout.setError(error.getMessage()); else message(error.getMessage());
        }
    }
    private void secret(String title, java.util.function.Consumer<String> callback) {
        View content = getLayoutInflater().inflate(R.layout.dialog_forward_secret, null);
        TextInputLayout layout = content.findViewById(R.id.secret_layout);
        TextInputEditText input = content.findViewById(R.id.secret_input);
        layout.setHint(title);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false);
        new MaterialAlertDialogBuilder(this).setTitle("输入" + title).setView(content).setNegativeButton("取消", null)
                .setPositiveButton("启动", (d, which) -> callback.accept(input.getText().toString())).show();
        input.requestFocus();
    }
    private void launch(Host host, SshForwarding.Plan plan, String passphrase, String password) {
        if (!prefs.edit().putString("host_id", host.id).putString("command", plan.command())
                .putBoolean("manual", manual).putBoolean("enabled", true)
                .putString("state", SshTunnelService.CONNECTING).putString("status", "").commit()) {
            message("配置没能保存，请重试"); return;
        }
        saveDraft();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 52);
        Intent intent = new Intent(this, SshTunnelService.class).putExtra("interactive", true);
        if (passphrase != null) intent.putExtra("key_passphrase", passphrase);
        if (password != null) intent.putExtra("password", password);
        try { ContextCompat.startForegroundService(this, intent); }
        catch (RuntimeException error) {
            prefs.edit().putBoolean("enabled", false).putString("state", SshTunnelService.FAILED)
                    .putString("status", "系统不允许在后台启动，请检查后台权限").apply();
        }
        renderState();
    }
    private void saveDraft() {
        prefs.edit().putString("draft_command", manual ? command.getText().toString() : draftCommand())
                .putBoolean("draft_manual", manual).putString("draft_host_id", selected == null ? "" : selected.id).apply();
    }
    private void message(String text) {
        Snackbar.make(findViewById(R.id.tunnel_scroll), text, Snackbar.LENGTH_LONG).setAnchorView(R.id.tunnel_bar).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("command", manual ? command.getText().toString() : draftCommand());
        state.putBoolean("manual", manual);
        if (selected != null) state.putString("host_id", selected.id);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; main.post(refresh); }
    @Override protected void onPause() {
        resumed = false; main.removeCallbacks(refresh);
        if (hostsReadable) saveDraft();
        SshTunnelService service = SshTunnelService.current(); if (service != null) service.uiVisible = false;
        if (shownRequest != null) shownRequest.answer(false);
        if (keyDialog != null) { keyDialog.dismiss(); keyDialog = null; } shownRequest = null;
        super.onPause();
    }
    private int color(int attribute) { return SessionDrawer.color(this, attribute); }
}

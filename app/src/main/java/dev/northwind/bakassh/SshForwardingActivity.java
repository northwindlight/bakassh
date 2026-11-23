package dev.northwind.bakassh;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.DragEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.ArrayList;
import java.util.List;

/** The graphical editor and manual mode share one standard SSH command. */
public final class SshForwardingActivity extends AppCompatActivity {
    private final List<SshForwarding.Rule> rules = new ArrayList<>();
    private List<Host> hosts;
    private Host selected;
    private MaterialAutoCompleteTextView hostView;
    private MaterialButtonToggleGroup modes;
    private LinearLayout ruleList;
    private TextInputEditText command;
    private TextInputLayout commandLayout;
    private TextView preview, status;
    private MaterialCheckBox boot;
    private boolean manual, switching, resumed, hostsReadable = true;
    private int aliveSeconds = 30, aliveCount = 3;
    private String initial;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HostKeyRequest shownRequest;
    private HostKeyTrust.Changed shownChange;
    private AlertDialog keyDialog;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (hostsReadable) status.setText(SshTunnelService.prefs(SshForwardingActivity.this).getString("status", "转发尚未启动"));
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
        ((MaterialToolbar) findViewById(R.id.tunnel_toolbar)).setNavigationOnClickListener(v -> onBackPressed());
        hostView = findViewById(R.id.tunnel_host);
        modes = findViewById(R.id.tunnel_modes);
        ruleList = findViewById(R.id.tunnel_rules);
        command = findViewById(R.id.tunnel_command);
        commandLayout = findViewById(R.id.tunnel_command_layout);
        preview = findViewById(R.id.tunnel_preview);
        status = findViewById(R.id.tunnel_status);
        boot = findViewById(R.id.tunnel_boot);
        try { hosts = HostStore.load(this); }
        catch (RuntimeException error) { hosts = new ArrayList<>(); hostsReadable = false; status.setText("无法读取 SSH 主机，请先处理主机列表的存储错误"); }
        String[] labels = new String[hosts.size()];
        for (int i = 0; i < hosts.size(); i++) labels[i] = hosts.get(i).title() + " · " + hosts.get(i).user + "@" + hosts.get(i).host;
        hostView.setFocusable(false);
        SharedPreferences p = SshTunnelService.prefs(this);
        String requestedHost = getIntent().getStringExtra("host_id");
        String hostId = state != null ? state.getString("host_id") : requestedHost;
        if (hostId == null) hostId = p.getString("host_id", "");
        for (Host host : hosts) if (host.id.equals(hostId)) selected = host;
        if (selected == null && !hosts.isEmpty()) selected = hosts.get(0);
        boot.setChecked(state != null ? state.getBoolean("boot") : p.getBoolean("boot", false));
        String saved = state != null ? state.getString("command", "") :
                (requestedHost == null || requestedHost.equals(p.getString("host_id", "")) ? p.getString("command", "") : "");
        try {
            if (!saved.isEmpty()) load(SshForwarding.parse(saved));
            else rules.add(new SshForwarding.Rule(false, "127.0.0.1", 2222, "127.0.0.1", 22));
        } catch (IllegalArgumentException error) { manual = true; }
        command.setText(saved.isEmpty() ? safeCommand() : saved);
        if (state != null && state.getBoolean("empty_rules")) rules.clear();
        if (state != null) manual = state.getBoolean("manual", manual);
        else manual |= p.getBoolean("manual", false);
        updateHostLabel();
        View.OnClickListener chooseHost = v -> {
            if (manual || hosts.isEmpty()) return;
            new MaterialAlertDialogBuilder(this).setTitle("选择 SSH 主机")
                    .setSingleChoiceItems(labels, hosts.indexOf(selected), (d, which) -> {
                        selected = hosts.get(which); updateHostLabel(); updatePreview(); d.dismiss();
                    }).setNegativeButton("取消", null).show();
        };
        hostView.setOnClickListener(chooseHost);
        ((TextInputLayout) findViewById(R.id.tunnel_host_layout)).setEndIconOnClickListener(chooseHost);
        modes.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || switching) return;
            boolean nextManual = id == R.id.tunnel_manual;
            try {
                if (nextManual && !manual) command.setText(graphPlan().command());
                else if (!nextManual && manual) load(SshForwarding.parse(command.getText().toString()));
                manual = nextManual;
                commandLayout.setError(null);
            } catch (IllegalArgumentException error) { showError(error); }
            updateMode();
        });
        findViewById(R.id.tunnel_add).setOnClickListener(v -> editRule(-1));
        findViewById(R.id.tunnel_copy).setOnClickListener(v -> {
            try {
                String text = plan().command();
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("SSH forwarding", text));
                Toast.makeText(this, "已复制标准 SSH 转发命令", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException error) { showError(error); }
        });
        findViewById(R.id.tunnel_background).setOnClickListener(v -> BackgroundSettings.show(this));
        findViewById(R.id.tunnel_start).setOnClickListener(v -> start());
        findViewById(R.id.tunnel_start).setEnabled(hostsReadable);
        findViewById(R.id.tunnel_stop).setOnClickListener(v -> {
            p.edit().putBoolean("enabled", false).putString("status", "转发已停止").apply();
            stopService(new Intent(this, SshTunnelService.class));
        });
        renderRules(); updateMode(); initial = snapshot();
        if (hosts.isEmpty()) new MaterialAlertDialogBuilder(this).setTitle(hostsReadable ? "先添加 SSH 主机" : "主机配置读取失败")
                .setMessage(hostsReadable ? "返回主机列表，保存服务器地址和认证方式，再建立 SSH 转发。" : "原主机配置未被覆盖。请返回主机列表处理存储错误后重试。")
                .setPositiveButton("知道了", null).show();
    }

    private Host matching(SshForwarding.Plan plan) {
        if (selected != null && matches(selected, plan)) return selected;
        for (Host host : hosts) if (matches(host, plan)) return host;
        throw new IllegalArgumentException("请先在主机列表保存 " + plan.user + "@" + plan.host + ":" + plan.port + "，以配置 SSH 认证");
    }
    private static boolean matches(Host host, SshForwarding.Plan plan) {
        return host.host.equalsIgnoreCase(plan.host) && host.user.equals(plan.user) && host.port == plan.port;
    }
    private void load(SshForwarding.Plan plan) {
        selected = matching(plan); rules.clear(); rules.addAll(plan.rules);
        aliveSeconds = plan.aliveSeconds; aliveCount = plan.aliveCount;
        updateHostLabel(); renderRules();
    }
    private void updateHostLabel() {
        if (selected != null) hostView.setText(selected.title() + " · " + selected.user + "@" + selected.host, false);
    }
    private SshForwarding.Plan graphPlan() {
        if (selected == null) throw new IllegalArgumentException("请先选择已保存的 SSH 主机");
        return new SshForwarding.Plan(selected.host, selected.user, selected.port, rules, aliveSeconds, aliveCount);
    }
    private SshForwarding.Plan plan() { return manual ? SshForwarding.parse(command.getText().toString()) : graphPlan(); }
    private String safeCommand() { try { return graphPlan().command(); } catch (IllegalArgumentException error) { return ""; } }
    private void updatePreview() { preview.setText(safeCommand()); }
    private void updateMode() {
        switching = true; modes.check(manual ? R.id.tunnel_manual : R.id.tunnel_graphical); switching = false;
        findViewById(R.id.tunnel_graph_group).setVisibility(manual ? View.GONE : View.VISIBLE);
        commandLayout.setVisibility(manual ? View.VISIBLE : View.GONE);
        preview.setVisibility(manual ? View.GONE : View.VISIBLE);
        hostView.setEnabled(!manual); updatePreview();
        ((TextInputLayout) findViewById(R.id.tunnel_host_layout)).setEndIconVisible(!manual);
    }

    private void renderRules() {
        if (ruleList == null) return;
        ruleList.removeAllViews();
        for (SshForwarding.Rule rule : rules) {
            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(20)); card.setCardElevation(0);
            card.setCardBackgroundColor(SessionDrawer.color(this, com.google.android.material.R.attr.colorSurfaceContainer));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(12); card.setLayoutParams(cardParams);
            LinearLayout row = new LinearLayout(this); row.setPadding(dp(16), dp(12), dp(4), dp(12)); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView text = new TextView(this);
            String direction = rule.remote ? "远程转发 · -R" : "本地转发 · -L";
            String route = rule.remote ? "SSH 服务器 → 手机可访问的目标" : "手机 → SSH 服务器可访问的目标";
            text.setText(direction + "\n" + rule.bind + ":" + rule.port + "  →  " + rule.target + ":" + rule.targetPort + "\n" + route);
            text.setTextSize(15); text.setLineSpacing(dp(5), 1);
            text.setTextColor(SessionDrawer.color(this, com.google.android.material.R.attr.colorOnSurface));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView handle = new TextView(this); handle.setText("↕"); handle.setTextSize(28);
            handle.setGravity(android.view.Gravity.CENTER); handle.setContentDescription("长按拖动规则；点击排序或删除");
            handle.setFocusable(true); row.addView(handle, new LinearLayout.LayoutParams(dp(56), dp(56)));
            card.addView(row); card.setClickable(true); card.setOnClickListener(v -> editRule(rules.indexOf(rule)));
            handle.setOnLongClickListener(v -> {
                v.startDragAndDrop(ClipData.newPlainText("SSH forwarding rule", "rule"), new View.DragShadowBuilder(card), rule, 0); return true;
            });
            handle.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle("转发规则")
                    .setItems(new String[]{"编辑", "上移", "下移", "删除"}, (d, choice) -> {
                        int at = rules.indexOf(rule);
                        if (choice == 0) editRule(at);
                        else if (choice == 3) { rules.remove(rule); renderRules(); updatePreview(); }
                        else move(rule, at + (choice == 1 ? -1 : 1));
                    }).show());
            card.setOnDragListener((view, event) -> {
                Object local = event.getLocalState();
                if (!(local instanceof SshForwarding.Rule) || !rules.contains(local)) return false;
                if (event.getAction() == DragEvent.ACTION_DRAG_ENTERED) { card.setStrokeWidth(dp(2)); card.setStrokeColor(SessionDrawer.color(this, com.google.android.material.R.attr.colorPrimary)); }
                if (event.getAction() == DragEvent.ACTION_DRAG_EXITED || event.getAction() == DragEvent.ACTION_DRAG_ENDED) card.setStrokeWidth(0);
                if (event.getAction() == DragEvent.ACTION_DROP) move((SshForwarding.Rule) local, rules.indexOf(rule));
                return true;
            });
            ruleList.addView(card);
        }
    }
    private void move(SshForwarding.Rule rule, int to) {
        if (to < 0 || to >= rules.size()) return;
        rules.remove(rule); rules.add(to, rule); renderRules(); updatePreview();
    }

    private void editRule(int index) {
        if (index < 0 && rules.size() >= 32) { showError(new IllegalArgumentException("最多支持 32 条转发规则")); return; }
        SshForwarding.Rule old = index < 0 ? new SshForwarding.Rule(false, "127.0.0.1", 2222, "127.0.0.1", 22) : rules.get(index);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(8), dp(24), 0);
        MaterialCheckBox remote = new MaterialCheckBox(this); remote.setText("远程转发（-R，在 SSH 服务器监听）"); remote.setChecked(old.remote); content.addView(remote);
        EditText bind = input(content, "监听地址", old.bind, false);
        TextView hint = new TextView(this); hint.setText("127.0.0.1 仅本机；0.0.0.0 允许其他设备连接。-R 的地址还受服务器 GatewayPorts 配置限制。"); hint.setTextSize(12); content.addView(hint);
        EditText port = input(content, "监听端口", String.valueOf(old.port), true);
        EditText target = input(content, "目标主机", old.target, false);
        EditText targetPort = input(content, "目标端口", String.valueOf(old.targetPort), true);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(index < 0 ? "添加转发规则" : "编辑转发规则")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                SshForwarding.Rule rule = new SshForwarding.Rule(remote.isChecked(), bind.getText().toString().trim(),
                        SshForwarding.parsePort(port.getText().toString()), target.getText().toString().trim(), SshForwarding.parsePort(targetPort.getText().toString()));
                if (index < 0) rules.add(rule); else rules.set(index, rule);
                renderRules(); updatePreview(); dialog.dismiss();
            } catch (IllegalArgumentException error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        }));
        dialog.show();
    }
    private EditText input(LinearLayout content, String label, String value, boolean number) {
        TextInputLayout layout = new TextInputLayout(this); layout.setHint(label); layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText field = new TextInputEditText(layout.getContext()); field.setSingleLine(true); field.setText(value);
        field.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        layout.addView(field); content.addView(layout, new LinearLayout.LayoutParams(-1, -2)); return field;
    }

    private void start() {
        try {
            commandLayout.setError(null);
            SshForwarding.Plan plan = plan(); Host host = matching(plan); selected = host; updateHostLabel();
            if ("password".equals(host.authType) && host.password.isEmpty()) secret("SSH 密码", value -> launch(host, plan, null, value));
            else if (!"password".equals(host.authType)) {
                KeyItem key = KeyManager.getKey(this, host.keyId);
                if (key == null) throw new IllegalArgumentException("请选择可用的 SSH 密钥");
                if (key.encrypted) secret("密钥口令", value -> launch(host, plan, value, null));
                else launch(host, plan, null, null);
            } else launch(host, plan, null, null);
        } catch (IllegalArgumentException error) { showError(error); }
    }
    private void secret(String title, java.util.function.Consumer<String> callback) {
        LinearLayout content = new LinearLayout(this); content.setPadding(dp(24), 0, dp(24), 0);
        EditText input = input(content, title, "", false); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); input.setSaveEnabled(false);
        new MaterialAlertDialogBuilder(this).setTitle(title).setView(content).setNegativeButton("取消", null)
                .setPositiveButton("连接", (d, which) -> callback.accept(input.getText().toString())).show();
    }
    private void launch(Host host, SshForwarding.Plan plan, String passphrase, String password) {
        if (!SshTunnelService.prefs(this).edit().putString("host_id", host.id).putString("command", plan.command())
                .putBoolean("manual", manual).putBoolean("boot", boot.isChecked()).putBoolean("enabled", true).commit()) {
            Toast.makeText(this, "无法保存转发配置，请重试", Toast.LENGTH_LONG).show(); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 52);
        Intent intent = new Intent(this, SshTunnelService.class).putExtra("interactive", true);
        if (passphrase != null) intent.putExtra("key_passphrase", passphrase);
        if (password != null) intent.putExtra("password", password);
        try { ContextCompat.startForegroundService(this, intent); initial = snapshot(); }
        catch (RuntimeException error) {
            SshTunnelService.prefs(this).edit().putBoolean("enabled", false).putString("status", "系统未允许启动，请检查后台设置").apply();
            Toast.makeText(this, "无法启动转发，请检查系统后台限制", Toast.LENGTH_LONG).show();
        }
    }
    private void showError(IllegalArgumentException error) {
        if (manual) commandLayout.setError(error.getMessage()); else Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
    }
    private String snapshot() { return (manual ? command.getText().toString() : safeCommand()) + "\n" + boot.isChecked(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("command", manual ? command.getText().toString() : safeCommand()); state.putBoolean("manual", manual); state.putBoolean("boot", boot.isChecked());
        if (selected != null) state.putString("host_id", selected.id);
        state.putBoolean("empty_rules", rules.isEmpty());
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {
        if (initial != null && !initial.equals(snapshot())) new MaterialAlertDialogBuilder(this).setTitle("放弃未保存的转发配置？")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃", (d, w) -> finish()).show();
        else super.onBackPressed();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; main.post(refresh); }
    @Override protected void onPause() {
        resumed = false; main.removeCallbacks(refresh);
        SshTunnelService service = SshTunnelService.current(); if (service != null) service.uiVisible = false;
        if (shownRequest != null) shownRequest.answer(false);
        if (keyDialog != null) { keyDialog.dismiss(); keyDialog = null; } shownRequest = null;
        super.onPause();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

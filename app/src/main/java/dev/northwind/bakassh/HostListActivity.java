package dev.northwind.bakassh;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 主机、密钥与设置：动态配色、分组表面和全屏编辑页。 */
public class HostListActivity extends AppCompatActivity {

    private List<Host> hosts;
    private BaseAdapter adapter;
    private ListView list;
    private View emptyStateView;
    private TextView subtitleView, forwardingLabel;
    private View activeSessions;
    private TextView activeSessionsLabel;
    private final SessionService.Listener sessionListener = new SessionService.Listener() {
        @Override public void onSessionsChanged() { updateActiveSessions(); }
        @Override public void onSessionData(TerminalSession session) { }
    };
    private SessionService observedService;
    private boolean hostsReadable = true;

    private View importRoot;
    private byte[] importBytes;
    private final ActivityResultLauncher<String[]> keyFilePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::readKeyFile);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 与 DynamicColors.applyToActivitiesIfAvailable 相同的时机：在 Activity 创建视图前叠加壁纸配色。
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host_list);

        try { hosts = HostStore.load(this); }
        catch (IllegalStateException error) { hosts = new ArrayList<>(); hostsReadable = false; }

        list = findViewById(R.id.list_hosts);
        View header = getLayoutInflater().inflate(R.layout.header_host_list, list, false);
        list.addHeaderView(header, null, false);
        emptyStateView = header.findViewById(R.id.layout_empty_state);
        subtitleView = header.findViewById(R.id.tv_host_subtitle);
        activeSessions = header.findViewById(R.id.card_active_sessions);
        activeSessionsLabel = header.findViewById(R.id.tv_active_sessions);
        activeSessions.setOnClickListener(v -> showActiveSessions());

        ExtendedFloatingActionButton fabAdd = findViewById(R.id.fab_add_host);
        fabAdd.setOnClickListener(v -> editDialog(-1));
        // 向下滚动时收起成圆形按钮，回到顶部再展开，不挡住最后一张卡片的菜单。
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView view, int state) { }
            @Override public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
                boolean top = first == 0 && (view.getChildCount() == 0 || view.getChildAt(0).getTop() >= -dp(24));
                if (top && !fabAdd.isExtended()) fabAdd.extend();
                else if (!top && fabAdd.isExtended()) fabAdd.shrink();
            }
        });

        header.findViewById(R.id.chip_manage_keys).setOnClickListener(v -> manageKeysDialog());
        forwardingLabel = header.findViewById(R.id.tv_home_forwarding);
        header.findViewById(R.id.chip_forwarding).setOnClickListener(v -> startActivity(new Intent(this, SshForwardingActivity.class)));
        findViewById(R.id.chip_settings).setOnClickListener(v -> settingsDialog());

        adapter = new BaseAdapter() {
            @Override public int getCount() { return hosts.size(); }
            @Override public Object getItem(int i) { return hosts.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View convertView, ViewGroup parent) {
                View row = convertView;
                if (row == null) {
                    row = LayoutInflater.from(HostListActivity.this)
                            .inflate(R.layout.item_host_card, parent, false);
                }

                Host h = hosts.get(i);
                TextView tvTitle = row.findViewById(R.id.tv_host_title);
                TextView tvEndpoint = row.findViewById(R.id.tv_host_endpoint);
                TextView tvSessions = row.findViewById(R.id.tv_host_sessions);
                ImageButton btnMore = row.findViewById(R.id.btn_host_more);

                tvTitle.setText(h.title());
                tvEndpoint.setText(h.user + "@" + h.host + (h.port != 22 ? ":" + h.port : ""));
                bindAvatar(row.findViewById(R.id.tv_host_avatar), h);
                SessionService service = SessionService.current();
                int running = service == null ? 0 : service.activeCount(h.id);
                tvSessions.setVisibility(running > 0 ? View.VISIBLE : View.GONE);
                if (running > 0) {
                    tvSessions.setText(getString(R.string.host_sessions_running, running));
                    android.graphics.drawable.Drawable dot = tvSessions.getCompoundDrawablesRelative()[0];
                    if (dot != null) dot.mutate().setTint(SessionDrawer.CONNECTED);
                }

                btnMore.setOnClickListener(v -> showHostActionMenu(btnMore, i));
                // ListView 的 OnItemClick 会被 clickable 的卡片吃掉，这里显式绑定。
                row.findViewById(R.id.card_host).setOnClickListener(v -> connect(h));
                row.findViewById(R.id.card_host).setOnLongClickListener(v -> {
                    showHostActionMenu(btnMore, i);
                    return true;
                });

                return row;
            }
        };

        list.setAdapter(adapter);

        updateListState();

        MainActivity.out(this, "HOSTLIST shown, hosts=" + hosts.size()
                + " cols=" + HostStore.getTargetCols(this, 150));
        if (!hostsReadable) showHostStorageError();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** 首字头像：按主机 ID 在三种容器色之间轮换，壁纸取色时一起变化。 */
    private void bindAvatar(TextView avatar, Host h) {
        String title = h.title().trim();
        int first = title.isEmpty() ? '?' : title.codePointAt(0);
        avatar.setText(new String(Character.toChars(Character.toUpperCase(first))));
        int[][] palette = {
                {com.google.android.material.R.attr.colorPrimaryContainer, com.google.android.material.R.attr.colorOnPrimaryContainer},
                {com.google.android.material.R.attr.colorSecondaryContainer, com.google.android.material.R.attr.colorOnSecondaryContainer},
                {com.google.android.material.R.attr.colorTertiaryContainer, com.google.android.material.R.attr.colorOnTertiaryContainer}};
        int[] pair = palette[Math.floorMod(h.id == null ? 0 : h.id.hashCode(), palette.length)];
        avatar.setBackgroundTintList(android.content.res.ColorStateList.valueOf(SessionDrawer.color(this, pair[0])));
        avatar.setTextColor(SessionDrawer.color(this, pair[1]));
    }

    private void showHostStorageError() {
        new MaterialAlertDialogBuilder(this).setTitle("暂时无法读取主机配置")
                .setMessage("原数据没有改动，可以重试。")
                .setPositiveButton("重试", (d, w) -> recreate()).setNegativeButton("关闭", null).show();
    }

    private void updateListState() {
        if (hosts.isEmpty()) {
            emptyStateView.setVisibility(View.VISIBLE);
            subtitleView.setVisibility(View.GONE);
        } else {
            emptyStateView.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            subtitleView.setVisibility(View.VISIBLE);
            subtitleView.setText(hosts.size() + " 台");
        }
    }

    private void connect(Host h) {
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        it.putExtra("name", h.name);
        it.putExtra("host", h.host);
        it.putExtra("port", h.port);
        it.putExtra("user", h.user);
        it.putExtra("startup", h.startup);
        it.putExtra("auth_type", h.authType);
        it.putExtra("key_id", h.keyId);
        it.putExtra("host_id", h.id);
        MainActivity.out(this, "CONNECT " + h.user + "@" + h.host + ":" + h.port + " [" + h.authType + "]");
        startActivity(it);
    }

    @Override protected void onResume() {
        super.onResume();
        observedService = SessionService.current();
        if (observedService != null) observedService.addListener(sessionListener);
        updateActiveSessions();
        updateForwarding();
    }

    /** 主页转发入口的副标题：未开启 / 转发中 · N 条 / 正在连接 / 启动失败。 */
    private void updateForwarding() {
        android.content.SharedPreferences p = SshTunnelService.prefs(this);
        String state = p.getString("state", SshTunnelService.IDLE);
        boolean enabled = p.getBoolean("enabled", false);
        if (!enabled && !SshTunnelService.FAILED.equals(state)) state = SshTunnelService.IDLE;
        String label = SshTunnelService.title(state);
        if (SshTunnelService.CONNECTED.equals(state)) {
            try { label += " · " + SshForwarding.parse(p.getString("command", "")).rules.size() + " 条"; }
            catch (IllegalArgumentException ignored) { }
        }
        forwardingLabel.setText(label);
    }

    @Override protected void onStop() {
        if (observedService != null) observedService.removeListener(sessionListener);
        observedService = null;
        super.onStop();
    }

    private void updateActiveSessions() {
        SessionService service = SessionService.current();
        int count = service == null ? 0 : service.sessions().size();
        int running = 0;
        if (service != null) for (TerminalSession session : service.sessions()) if (session.isActive()) running++;
        activeSessions.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        // 已结束的会话仍保留最后画面，但不能算作“正在运行”。
        String label = running > 0 ? getString(R.string.sessions_running, running) : getString(R.string.sessions_ended, count);
        if (running > 0 && running < count) label += getString(R.string.sessions_ended_suffix, count - running);
        activeSessionsLabel.setText(label);
        adapter.notifyDataSetChanged();
    }

    private void showActiveSessions() {
        SessionService service = SessionService.current();
        if (service == null) return;
        SessionSwitcher.show(this, service, null,
                selected -> startActivity(new Intent(this, MainActivity.class)
                        .putExtra(SessionService.SESSION_ID, selected.id)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)),
                () -> editDialog(-1));
    }

    /** 编辑页独占窗口；表单可滚动，键盘弹出时保留顶栏操作。 */
    private Dialog newPage(View root) {
        Dialog dialog = new EditorDialog(this, root);
        dialog.setContentView(root);
        return dialog;
    }

    private void showPage(Dialog dialog) {
        dialog.show();
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    private void showHostActionMenu(View anchor, int pos) {
        Host h = hosts.get(pos);
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, anchor);
        String[] actions = {"连接", "编辑", "复制 SSH 命令", "删除", "主机指纹", "端口转发"};
        int[] order = {0, 1, 3, 5, 4, 2}; // 删除放最后
        for (int i = 0; i < actions.length; i++) menu.getMenu().add(0, i, order[i], actions[i]);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 5: startActivity(new Intent(this, SshForwardingActivity.class).putExtra("host_id", h.id)); break;
                case 0: connect(h); break;
                case 1: editDialog(pos); break;
                case 2: copySshCommand(h); break;
                case 3: confirmDelete(pos); break;
                case 4: HostKeyDialogs.manage(this, h); break;
            }
            return true;
        });
        menu.show();
    }

    private void copySshCommand(Host h) {
        String cmd = "ssh " + (h.port != 22 ? "-p " + h.port + " " : "") + h.user + "@" + h.host;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("SSH Command", cmd));
            Toast.makeText(this, "已复制：" + cmd, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(int pos) {
        Host h = hosts.get(pos);
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除「" + h.title() + "」？")
                .setMessage("删除后无法恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    List<Host> updated = new ArrayList<>(hosts);
                    updated.remove(pos);
                    if (!HostStore.save(this, updated)) return;
                    hosts = updated;
                    adapter.notifyDataSetChanged();
                    updateListState();
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 新建与编辑主机。 */
    private void editDialog(int pos) {
        if (!hostsReadable) { showHostStorageError(); return; }
        Host h = pos >= 0 ? hosts.get(pos) : new Host("", "", 22, "", "", "publickey", "default", "");
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_edit_host, null);
        Dialog dialog = newPage(root);

        TextView tvTitle = root.findViewById(R.id.tv_dialog_title);
        TextInputEditText etName = root.findViewById(R.id.et_host_name);
        TextInputEditText etIp = root.findViewById(R.id.et_host_ip);
        TextInputEditText etPort = root.findViewById(R.id.et_host_port);
        TextInputEditText etUser = root.findViewById(R.id.et_host_user);
        TextInputEditText etStartup = root.findViewById(R.id.et_host_startup);
        TextInputEditText etPassword = root.findViewById(R.id.et_host_password);

        MaterialButtonToggleGroup toggleAuth = root.findViewById(R.id.toggle_auth_type);
        View layoutSelectKey = root.findViewById(R.id.layout_select_key);
        View layoutPassword = root.findViewById(R.id.layout_password_input);
        TextView tvSelectedKeyName = root.findViewById(R.id.tv_selected_key_name);

        tvTitle.setText(pos >= 0 ? "编辑主机" : "添加主机");
        etName.setText(h.name);
        etIp.setText(h.host);
        etPort.setText(String.valueOf(h.port));
        etUser.setText(h.user);
        etStartup.setText(h.startup);
        etPassword.setText(h.password);
        etPassword.setSaveEnabled(false);
        if (h.passwordUnavailable) ((TextInputLayout) layoutPassword).setHelperText(
                "原密码无法解密。输入新密码替换，留空则清除。");

        final String[] currentAuth = new String[]{h.authType != null ? h.authType : "publickey"};
        final String[] selectedKeyId = new String[]{h.keyId != null ? h.keyId : "default"};

        Runnable updateKeyDisplay = () -> {
            KeyItem k = KeyManager.getKey(this, selectedKeyId[0]);
            tvSelectedKeyName.setText(KeyItem.DEFAULT_ID.equals(selectedKeyId[0])
                    ? "跟随默认密钥" + (k != null ? " · " + k.name : "")
                    : (k != null ? k.name : "密钥不可用"));
        };
        updateKeyDisplay.run();

        Runnable updateAuthView = () -> {
            boolean isPassword = "password".equalsIgnoreCase(currentAuth[0]);
            layoutSelectKey.setVisibility(isPassword ? View.GONE : View.VISIBLE);
            layoutPassword.setVisibility(isPassword ? View.VISIBLE : View.GONE);
            if (isPassword) {
                toggleAuth.check(R.id.btn_toggle_password);
            } else {
                toggleAuth.check(R.id.btn_toggle_pubkey);
            }
        };
        updateAuthView.run();

        toggleAuth.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == R.id.btn_toggle_password) {
                    currentAuth[0] = "password";
                } else {
                    currentAuth[0] = "publickey";
                }
                updateAuthView.run();
            }
        });

        layoutSelectKey.setOnClickListener(v -> {
            List<KeyItem> keyList = KeyManager.getKeys(this);
            String[] names = new String[keyList.size() + 1];
            names[0] = "跟随默认密钥";
            int checked = 0;
            for (int i = 0; i < keyList.size(); i++) {
                KeyItem k = keyList.get(i);
                names[i + 1] = k.name + (k.isDefault ? " · 默认" : "") + " · " + k.type;
                if (k.id.equals(selectedKeyId[0])) checked = i + 1;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle("选择密钥")
                    .setSingleChoiceItems(names, checked, (d, which) -> {
                        selectedKeyId[0] = which == 0 ? KeyItem.DEFAULT_ID : keyList.get(which - 1).id;
                        updateKeyDisplay.run();
                        d.dismiss();
                    })
                    .show();
        });

        root.findViewById(R.id.btn_cancel_host).setOnClickListener(v -> dialog.cancel());
        root.findViewById(R.id.btn_save_host).setOnClickListener(v -> {
            String host = etIp.getText() != null ? etIp.getText().toString().trim() : "";
            if (host.isEmpty()) {
                EditorDialog.fieldError(etIp, "请输入主机名或 IP 地址");
                return;
            }

            Host n = new Host();
            n.id = h.id;
            n.name = etName.getText() != null ? etName.getText().toString().trim() : "";
            n.host = host;
            try {
                n.port = Integer.parseInt(etPort.getText() != null ? etPort.getText().toString().trim() : "22");
            } catch (NumberFormatException t) {
                EditorDialog.fieldError(etPort, "1–65535");
                return;
            }
            if (n.port < 1 || n.port > 65535) {
                EditorDialog.fieldError(etPort, "1–65535");
                return;
            }
            if (etUser.getText() == null || etUser.getText().toString().trim().isEmpty()) {
                EditorDialog.fieldError(etUser, "请输入用户名");
                return;
            }
            n.user = etUser.getText() != null ? etUser.getText().toString().trim() : "";
            n.startup = etStartup.getText() != null ? etStartup.getText().toString().trim() : "";
            n.authType = currentAuth[0];
            n.keyId = selectedKeyId[0];
            n.password = etPassword.getText() != null ? etPassword.getText().toString() : "";

            List<Host> updated = new ArrayList<>(hosts);
            if (pos >= 0) updated.set(pos, n); else updated.add(n);
            if (!HostStore.save(this, updated)) return;
            hosts = updated;
            adapter.notifyDataSetChanged();
            updateListState();
            dialog.dismiss();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });

        showPage(dialog);
    }

    /** 密钥管理：展示所有密钥、生成新密钥、导入私钥、查看公钥与设置默认 */
    private void manageKeysDialog() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_key_manager, null);
        Dialog dialog = newPage(root);

        ListView lv = root.findViewById(R.id.list_keys);
        List<KeyItem> keyList = new ArrayList<>(KeyManager.getKeys(this));

        BaseAdapter keyAdapter = new BaseAdapter() {
            @Override public int getCount() { return keyList.size(); }
            @Override public Object getItem(int i) { return keyList.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View convertView, ViewGroup parent) {
                View row = convertView;
                if (row == null) {
                    row = LayoutInflater.from(HostListActivity.this)
                            .inflate(R.layout.item_key_card, parent, false);
                }
                KeyItem k = keyList.get(i);
                TextView tvName = row.findViewById(R.id.tv_key_name);
                TextView tvSubtitle = row.findViewById(R.id.tv_key_subtitle);
                TextView tvBadge = row.findViewById(R.id.tv_key_default_badge);
                ImageButton btnMore = row.findViewById(R.id.btn_key_more);

                tvName.setText(k.name);
                tvSubtitle.setText(k.type + " · " + (k.fingerprint.length() > 22 ? k.fingerprint.substring(0, 22) + "…" : k.fingerprint));
                tvBadge.setVisibility(k.isDefault ? View.VISIBLE : View.GONE);
                row.findViewById(R.id.tv_key_encrypted_badge).setVisibility(k.encrypted ? View.VISIBLE : View.GONE);
                row.findViewById(R.id.layout_key_badges).setVisibility(k.isDefault || k.encrypted ? View.VISIBLE : View.GONE);

                Runnable showActions = () -> keyActionMenu(btnMore, k, () -> {
                    keyList.clear();
                    keyList.addAll(KeyManager.getKeys(HostListActivity.this));
                    notifyDataSetChanged();
                    adapter.notifyDataSetChanged();
                });

                row.findViewById(R.id.card_key).setOnClickListener(v -> showKeyDetailDialog(k));
                btnMore.setOnClickListener(v -> showActions.run());

                return row;
            }
        };

        lv.setAdapter(keyAdapter);
        TextView noKeys = new TextView(this);
        noKeys.setText("还没有密钥\n生成新的，或导入私钥文件");
        noKeys.setGravity(android.view.Gravity.CENTER);
        noKeys.setTextSize(15);
        noKeys.setLineSpacing(dp(4), 1f);
        noKeys.setTextColor(SessionDrawer.color(this, com.google.android.material.R.attr.colorOnSurfaceVariant));
        noKeys.setCompoundDrawablesRelativeWithIntrinsicBounds(0, R.drawable.ic_key, 0, 0);
        noKeys.setCompoundDrawablePadding(dp(16));
        noKeys.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(
                SessionDrawer.color(this, com.google.android.material.R.attr.colorOnSurfaceVariant)));
        noKeys.setPadding(dp(24), dp(48), dp(24), dp(48));
        ((ViewGroup) lv.getParent()).addView(noKeys);
        lv.setEmptyView(noKeys);

        Runnable refreshList = () -> {
            keyList.clear();
            keyList.addAll(KeyManager.getKeys(HostListActivity.this));
            keyAdapter.notifyDataSetChanged();
            adapter.notifyDataSetChanged();
        };

        root.findViewById(R.id.btn_generate_key).setOnClickListener(v -> generateKeyDialog(refreshList));
        root.findViewById(R.id.btn_import_key).setOnClickListener(v -> importKeyDialog(refreshList));
        root.findViewById(R.id.btn_close_key_mgr).setOnClickListener(v -> dialog.dismiss());

        showPage(dialog);
    }

    private void keyActionMenu(View anchor, KeyItem k, Runnable onRefresh) {
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, anchor);
        String[] actions = {"查看公钥", "设为默认", "重命名", "删除"};
        for (int i = 0; i < actions.length; i++) menu.getMenu().add(0, i, i, actions[i]);
        menu.getMenu().findItem(1).setEnabled(!k.isDefault);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0: showKeyDetailDialog(k); break;
                case 1:
                    KeyManager.setDefaultKey(this, k.id);
                    onRefresh.run();
                    break;
                case 2: renameKeyDialog(k, onRefresh); break;
                case 3: deleteKeyDialog(k, onRefresh); break;
            }
            return true;
        });
        menu.show();
    }

    private void showKeyDetailDialog(KeyItem k) {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_key_detail, null);
        Dialog dialog = newPage(root);

        TextView tvName = root.findViewById(R.id.tv_detail_key_name);
        TextView tvInfo = root.findViewById(R.id.tv_detail_key_info);
        TextView tvPub = root.findViewById(R.id.tv_detail_pubkey);

        tvName.setText(k.name + " 公钥");
        tvInfo.setText("算法: " + k.type + "\n指纹: " + k.fingerprint);
        tvPub.setText(k.publicKey);

        root.findViewById(R.id.btn_copy_pubkey).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("SSH 公钥", k.publicKey));
            }
            Toast.makeText(this, "已复制公钥", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        root.findViewById(R.id.btn_close_detail).setOnClickListener(v -> dialog.cancel());
        showPage(dialog);
    }

    private void generateKeyDialog(Runnable onRefresh) {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_generate_key, null);
        Dialog dialog = newPage(root);

        TextInputEditText etName = root.findViewById(R.id.et_gen_key_name);
        TextInputEditText etPass = root.findViewById(R.id.et_gen_key_pass);
        TextInputEditText etComment = root.findViewById(R.id.et_gen_key_comment);
        TextView tvType = root.findViewById(R.id.tv_gen_key_type);

        final String[] types = new String[]{"RSA-3072（推荐）", "RSA-2048", "RSA-4096", "ECDSA-256", "ECDSA-384"};
        final String[] typeVals = new String[]{"RSA-3072", "RSA-2048", "RSA-4096", "ECDSA-256", "ECDSA-384"};
        final int[] selectedTypeIdx = new int[]{0};

        root.findViewById(R.id.layout_select_gen_type).setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("选择算法与位长")
                    .setSingleChoiceItems(types, selectedTypeIdx[0], (d, which) -> {
                        selectedTypeIdx[0] = which;
                        tvType.setText("算法类型：" + types[which]);
                        d.dismiss();
                    })
                    .show();
        });

        root.findViewById(R.id.btn_cancel_gen).setOnClickListener(v -> dialog.cancel());
        root.findViewById(R.id.btn_confirm_gen).setOnClickListener(v -> {
            String name = etName.getText() != null ? etName.getText().toString().trim() : "";
            if (name.isEmpty()) name = "新建密钥";
            String finalName = name;
            String type = typeVals[selectedTypeIdx[0]];
            String pass = etPass.getText() != null ? etPass.getText().toString() : "";
            String comment = etComment.getText() != null ? etComment.getText().toString() : "";

            MaterialButton generate = root.findViewById(R.id.btn_confirm_gen);
            generate.setEnabled(false);
            generate.setText("生成中…");
            ((EditorDialog) dialog).setBusy(true);

            new Thread(() -> {
                try {
                    KeyItem item = KeyManager.generateKey(getApplicationContext(), finalName, type, pass, comment);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        ((EditorDialog) dialog).setBusy(false);
                        dialog.dismiss();
                        etPass.setText("");
                        Toast.makeText(HostListActivity.this, "已生成：" + item.name, Toast.LENGTH_SHORT).show();
                        onRefresh.run();
                        showKeyDetailDialog(item);
                    });
                } catch (Exception t) {
                    runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        ((EditorDialog) dialog).setBusy(false);
                        generate.setEnabled(true);
                        generate.setText("生成");
                        new MaterialAlertDialogBuilder(HostListActivity.this)
                                .setTitle("生成失败").setMessage(t.getMessage())
                                .setPositiveButton("确定", null).show();
                    });
                }
            }, "key-generate").start();
        });

        showPage(dialog);
    }

    private void importKeyDialog(Runnable onRefresh) {
        View root = getLayoutInflater().inflate(R.layout.dialog_import_key, null);
        Dialog dialog = newPage(root);
        importRoot = root;
        importBytes = null;
        TextInputEditText name = root.findViewById(R.id.et_import_key_name);
        TextInputEditText content = root.findViewById(R.id.et_import_key_content);
        TextInputEditText pass = root.findViewById(R.id.et_import_key_pass);
        TextView error = root.findViewById(R.id.tv_import_error);
        MaterialButton confirm = root.findViewById(R.id.btn_confirm_import);
        root.findViewById(R.id.btn_choose_key_file).setOnClickListener(v -> keyFilePicker.launch(new String[]{"*/*"}));
        root.findViewById(R.id.btn_paste_key).setOnClickListener(v -> {
            root.findViewById(R.id.layout_import_content).setVisibility(View.VISIBLE);
            root.findViewById(R.id.layout_import_pass).setVisibility(View.VISIBLE);
            importBytes = null;
            ((TextView) root.findViewById(R.id.tv_import_file)).setText("使用粘贴的私钥文本");
            content.requestFocus();
        });
        root.findViewById(R.id.btn_cancel_import).setOnClickListener(v -> dialog.cancel());
        dialog.setOnDismissListener(d -> {
            if (importRoot == root) {
                importRoot = null;
                if (importBytes != null) java.util.Arrays.fill(importBytes, (byte) 0);
                importBytes = null;
            }
            content.setText("");
            pass.setText("");
        });
        confirm.setOnClickListener(v -> {
            byte[] data = importBytes != null ? importBytes.clone()
                    : content.getText().toString().getBytes(StandardCharsets.UTF_8);
            if (data.length == 0) { error.setText("选择私钥文件，或粘贴私钥文本"); return; }
            String nickname = name.getText().toString().trim();
            if (nickname.isEmpty()) { EditorDialog.fieldError(name, "请输入密钥名称"); return; }
            String password = pass.getText().toString();
            error.setText("");
            confirm.setEnabled(false);
            confirm.setText("正在导入…");
            ((EditorDialog) dialog).setBusy(true);
            root.findViewById(R.id.btn_choose_key_file).setEnabled(false);
            root.findViewById(R.id.btn_paste_key).setEnabled(false);
            name.setEnabled(false);
            content.setEnabled(false);
            pass.setEnabled(false);
            new Thread(() -> {
                try {
                    KeyItem item = KeyManager.importKey(getApplicationContext(), nickname,
                            new String(data, StandardCharsets.UTF_8), password);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        ((EditorDialog) dialog).setBusy(false);
                        dialog.dismiss();
                        onRefresh.run();
                        showKeyDetailDialog(item);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        ((EditorDialog) dialog).setBusy(false);
                        confirm.setEnabled(true);
                        root.findViewById(R.id.btn_choose_key_file).setEnabled(true);
                        root.findViewById(R.id.btn_paste_key).setEnabled(true);
                        name.setEnabled(true);
                        content.setEnabled(true);
                        pass.setEnabled(true);
                        confirm.setText("导入");
                        error.setText(e.getMessage());
                    });
                } finally { java.util.Arrays.fill(data, (byte) 0); }
            }, "key-import").start();
        });
        showPage(dialog);
    }

    private void readKeyFile(Uri uri) {
        if (uri == null) return;
        if (importRoot == null) importKeyDialog(() -> adapter.notifyDataSetChanged());
        View root = importRoot;
        TextView status = root.findViewById(R.id.tv_import_file);
        status.setText("正在读取文件…");
        importBytes = null;
        ((TextInputEditText) root.findViewById(R.id.et_import_key_content)).setText("");
        root.findViewById(R.id.btn_choose_key_file).setEnabled(false);
        root.findViewById(R.id.btn_paste_key).setEnabled(false);
        root.findViewById(R.id.btn_confirm_import).setEnabled(false);
        new Thread(() -> {
            try {
                String filename = "导入密钥";
                try (Cursor cursor = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) filename = cursor.getString(0);
                }
                byte[] data;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    byte[] raw = PrivateKeyImport.read(in);
                    try { data = PrivateKeyImport.normalize(new String(raw, StandardCharsets.UTF_8)); }
                    finally { java.util.Arrays.fill(raw, (byte) 0); }
                }
                PrivateKeyImport.Info info = PrivateKeyImport.inspectHeader(data);
                String finalFilename = filename;
                runOnUiThread(() -> {
                    if (importRoot != root || isFinishing() || isDestroyed()) {
                        java.util.Arrays.fill(data, (byte) 0);
                        return;
                    }
                    importBytes = data;
                    status.setText(finalFilename + " · " + info.type
                            + (info.encrypted ? " · 已加密" : " · 无口令"));
                    root.findViewById(R.id.layout_import_pass).setVisibility(info.encrypted ? View.VISIBLE : View.GONE);
                    ((TextInputEditText) root.findViewById(R.id.et_import_key_pass)).setText("");
                    ((TextInputEditText) root.findViewById(R.id.et_import_key_name)).setText(finalFilename);
                    ((TextInputEditText) root.findViewById(R.id.et_import_key_content)).setText("");
                    root.findViewById(R.id.layout_import_content).setVisibility(View.GONE);
                    ((TextView) root.findViewById(R.id.tv_import_error)).setText("");
                    root.findViewById(R.id.btn_confirm_import).setEnabled(true);
                    root.findViewById(R.id.btn_choose_key_file).setEnabled(true);
                    root.findViewById(R.id.btn_paste_key).setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (importRoot != root) return;
                    status.setText("读不了这个文件，换一个试试");
                    ((TextView) root.findViewById(R.id.tv_import_error)).setText(e.getMessage());
                    root.findViewById(R.id.btn_confirm_import).setEnabled(true);
                    root.findViewById(R.id.btn_choose_key_file).setEnabled(true);
                    root.findViewById(R.id.btn_paste_key).setEnabled(true);
                });
            }
        }, "key-file-read").start();
    }

    private void renameKeyDialog(KeyItem k, Runnable onRefresh) {
        TextInputLayout inputLayout = new TextInputLayout(this);
        inputLayout.setHint("新密钥名称");
        TextInputEditText et = new TextInputEditText(this);
        et.setText(k.name);
        inputLayout.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(inputLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
                .setTitle("重命名密钥")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String newName = et.getText() != null ? et.getText().toString().trim() : "";
                    if (!newName.isEmpty()) {
                        KeyManager.renameKey(this, k.id, newName);
                        onRefresh.run();
                        Toast.makeText(this, "已重命名", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteKeyDialog(KeyItem k, Runnable onRefresh) {
        if (!hostsReadable) { showHostStorageError(); return; }
        List<KeyItem> keys = KeyManager.getKeys(this);
        List<Host> inUse;
        try {
            inUse = HostStore.getHostsUsingKey(this, k.id);
            if (k.isDefault && !KeyItem.DEFAULT_ID.equals(k.id))
                inUse.addAll(HostStore.getHostsUsingKey(this, KeyItem.DEFAULT_ID));
        } catch (IllegalStateException error) { hostsReadable = false; showHostStorageError(); return; }
        String msg = inUse.isEmpty() ? "删除后无法恢复。" : inUse.size() + " 台主机在用它，删除后"
                + (keys.size() > 1 ? "改用默认密钥。" : "这些主机要重新配置密钥才能连接。");

        new MaterialAlertDialogBuilder(this)
                .setTitle("删除密钥「" + k.name + "」？")
                .setMessage(msg)
                .setPositiveButton("删除", (d, w) -> {
                    boolean ok = KeyManager.deleteKey(this, k.id);
                    if (ok) {
                        boolean hostChanged = false;
                        for (Host h : hosts) {
                            if (k.id.equals(h.keyId)) {
                                h.keyId = "default";
                                hostChanged = true;
                            }
                        }
                        if (hostChanged) {
                            if (!HostStore.save(this, hosts)) { onRefresh.run(); return; }
                        }
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                        onRefresh.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 终端设置。 */
    private void settingsDialog() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        Dialog dialog = newPage(root);

        TextInputEditText etInset = root.findViewById(R.id.et_settings_inset);
        TextInputEditText etCols = root.findViewById(R.id.et_settings_cols);

        etInset.setText(String.valueOf(HostStore.getEdgeInset(this, 3)));
        etCols.setText(String.valueOf(HostStore.getTargetCols(this, 150)));

        root.findViewById(R.id.btn_cancel_settings).setOnClickListener(v -> dialog.cancel());
        root.findViewById(R.id.settings_extra_keys).setOnClickListener(v -> extraKeysDialog());
        root.findViewById(R.id.settings_background).setOnClickListener(v -> BackgroundSettings.show(this));
        root.findViewById(R.id.settings_about).setOnClickListener(v -> aboutDialog());
        ((TextView) root.findViewById(R.id.tv_settings_version)).setText(getString(R.string.about_version, versionName()));
        SessionService service = SessionService.current();
        ((TextView) root.findViewById(R.id.tv_settings_background)).setText(service != null && service.foregroundActive()
                ? "后台运行中 · " + service.sessions().size() + " 个会话" : getString(R.string.background_settings_help));
        root.findViewById(R.id.btn_save_settings).setOnClickListener(v -> {
            int inset, cols;
            try { inset = Integer.parseInt(etInset.getText().toString().trim()); }
            catch (Exception e) { inset = -1; }
            try { cols = Integer.parseInt(etCols.getText().toString().trim()); }
            catch (Exception e) { cols = -1; }
            if (inset < 0 || inset > 48) { EditorDialog.fieldError(etInset, "请输入 0–48"); return; }
            if (cols < 10 || cols > 500) { EditorDialog.fieldError(etCols, "请输入 10–500"); return; }
            HostStore.setEdgeInset(this, inset);
            HostStore.setTargetCols(this, cols);
            dialog.dismiss();
            Toast.makeText(this, "已保存终端设置", Toast.LENGTH_SHORT).show();
        });

        showPage(dialog);
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** 关于：版本、作者与项目链接。 */
    private void aboutDialog() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_about, null);
        Dialog dialog = newPage(root);
        ((TextView) root.findViewById(R.id.tv_about_version)).setText(getString(R.string.about_version, versionName()));
        root.findViewById(R.id.btn_close_about).setOnClickListener(v -> dialog.cancel());
        root.findViewById(R.id.about_author).setOnClickListener(v -> openLink(getString(R.string.about_author_url)));
        root.findViewById(R.id.about_source).setOnClickListener(v -> openLink(getString(R.string.about_source_url)));
        root.findViewById(R.id.about_releases).setOnClickListener(v -> openLink(getString(R.string.about_releases_url)));
        root.findViewById(R.id.about_licenses).setOnClickListener(v -> openLink(getString(R.string.about_licenses_url)));
        showPage(dialog);
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("bakaSSH", url));
            Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
        }
    }

    /** Shared graphical shortcut editor. */
    private void extraKeysDialog() {
        ExtraKeysEditor.show(this, null);
    }
}

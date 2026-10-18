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
    private TextView subtitleView;

    private View importRoot;
    private byte[] importBytes;
    private final ActivityResultLauncher<String[]> keyFilePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::readKeyFile);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 只给主机列表启用壁纸取色；终端界面固定深色，不参与 DynamicColors。
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_host_list);

        hosts = HostStore.load(this);

        list = findViewById(R.id.list_hosts);
        View header = getLayoutInflater().inflate(R.layout.header_host_list, list, false);
        list.addHeaderView(header, null, false);
        emptyStateView = header.findViewById(R.id.layout_empty_state);
        subtitleView = header.findViewById(R.id.tv_host_subtitle);

        ExtendedFloatingActionButton fabAdd = findViewById(R.id.fab_add_host);
        fabAdd.setOnClickListener(v -> editDialog(-1));

        header.findViewById(R.id.chip_manage_keys).setOnClickListener(v -> manageKeysDialog());
        header.findViewById(R.id.chip_extra_keys).setOnClickListener(v -> extraKeysDialog());
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
                ImageButton btnMore = row.findViewById(R.id.btn_host_more);

                tvTitle.setText(h.title());
                tvEndpoint.setText(h.user + "@" + h.host + ":" + h.port);

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
    }

    private void updateListState() {
        if (hosts.isEmpty()) {
            emptyStateView.setVisibility(View.VISIBLE);
            subtitleView.setText("随时连接你的设备");
        } else {
            emptyStateView.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            subtitleView.setText(hosts.size() + " 台设备，随时连接");
        }
    }

    private void connect(Host h) {
        Intent it = new Intent(this, MainActivity.class);
        it.putExtra("name", h.name);
        it.putExtra("host", h.host);
        it.putExtra("port", h.port);
        it.putExtra("user", h.user);
        it.putExtra("startup", h.startup);
        it.putExtra("auth_type", h.authType);
        it.putExtra("key_id", h.keyId);
        it.putExtra("password", h.password);
        MainActivity.out(this, "CONNECT " + h.user + "@" + h.host + ":" + h.port + " [" + h.authType + "]");
        startActivity(it);
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
        String[] actions = {"连接", "编辑", "复制 SSH 命令", "删除"};
        for (int i = 0; i < actions.length; i++) menu.getMenu().add(0, i, i, actions[i]);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0: connect(h); break;
                case 1: editDialog(pos); break;
                case 2: copySshCommand(h); break;
                case 3: confirmDelete(pos); break;
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
            Toast.makeText(this, "已复制连接命令: " + cmd, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(int pos) {
        Host h = hosts.get(pos);
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除主机")
                .setMessage("确认删除主机 \"" + h.title() + "\"？此操作无法撤销。")
                .setPositiveButton("删除", (d, w) -> {
                    hosts.remove(pos);
                    HostStore.save(this, hosts);
                    adapter.notifyDataSetChanged();
                    updateListState();
                    Toast.makeText(this, "已删除主机", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 新建与编辑主机。 */
    private void editDialog(int pos) {
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
                etIp.setError("请输入主机名或 IP 地址");
                etIp.requestFocus();
                return;
            }

            Host n = new Host();
            n.name = etName.getText() != null ? etName.getText().toString().trim() : "";
            n.host = host;
            try {
                n.port = Integer.parseInt(etPort.getText() != null ? etPort.getText().toString().trim() : "22");
            } catch (NumberFormatException t) {
                etPort.setError("请输入 1–65535 之间的端口");
                etPort.requestFocus();
                return;
            }
            if (n.port < 1 || n.port > 65535) {
                etPort.setError("请输入 1–65535 之间的端口");
                etPort.requestFocus();
                return;
            }
            if (etUser.getText() == null || etUser.getText().toString().trim().isEmpty()) {
                etUser.setError("请输入用户名");
                etUser.requestFocus();
                return;
            }
            n.user = etUser.getText() != null ? etUser.getText().toString().trim() : "";
            n.startup = etStartup.getText() != null ? etStartup.getText().toString().trim() : "";
            n.authType = currentAuth[0];
            n.keyId = selectedKeyId[0];
            n.password = etPassword.getText() != null ? etPassword.getText().toString() : "";

            if (pos >= 0) {
                hosts.set(pos, n);
            } else {
                hosts.add(n);
            }

            HostStore.save(this, hosts);
            adapter.notifyDataSetChanged();
            updateListState();
            dialog.dismiss();
            Toast.makeText(this, "已保存主机配置", Toast.LENGTH_SHORT).show();
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
                tvSubtitle.setText(k.type + " · " + (k.fingerprint.length() > 22 ? k.fingerprint.substring(0, 22) + "..." : k.fingerprint));
                tvBadge.setVisibility(k.isDefault ? View.VISIBLE : View.GONE);

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
        noKeys.setText("还没有密钥，生成一个或导入已有私钥。");
        noKeys.setGravity(android.view.Gravity.CENTER);
        noKeys.setPadding(24, 48, 24, 48);
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
            Toast.makeText(this, "已复制公钥到剪贴板", Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(HostListActivity.this, "已生成密钥: " + item.name, Toast.LENGTH_SHORT).show();
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
            if (data.length == 0) { error.setText("请选择私钥文件，或粘贴私钥文本。"); return; }
            String nickname = name.getText().toString().trim();
            if (nickname.isEmpty()) { name.setError("请输入密钥名称"); name.requestFocus(); return; }
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
                    status.setText("无法读取文件，请重新选择");
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
        List<KeyItem> keys = KeyManager.getKeys(this);
        List<Host> inUse = HostStore.getHostsUsingKey(this, k.id);
        if (k.isDefault && !KeyItem.DEFAULT_ID.equals(k.id)) {
            inUse.addAll(HostStore.getHostsUsingKey(this, KeyItem.DEFAULT_ID));
        }
        String msg = "确认删除密钥 \"" + k.name + "\"？";
        if (!inUse.isEmpty()) {
            msg += "\n\n有 " + inUse.size() + " 台主机使用此密钥。"
                    + (keys.size() > 1 ? "删除后将改用默认密钥。" : "再次连接前需要生成或导入密钥。");
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("删除密钥")
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
                            HostStore.save(this, hosts);
                        }
                        Toast.makeText(this, "已删除密钥", Toast.LENGTH_SHORT).show();
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
        root.findViewById(R.id.btn_save_settings).setOnClickListener(v -> {
            int inset, cols;
            try { inset = Integer.parseInt(etInset.getText().toString().trim()); }
            catch (Exception e) { inset = -1; }
            try { cols = Integer.parseInt(etCols.getText().toString().trim()); }
            catch (Exception e) { cols = -1; }
            if (inset < 0 || inset > 48) { etInset.setError("请输入 0–48"); return; }
            if (cols < 10 || cols > 500) { etCols.setError("请输入 10–500"); return; }
            HostStore.setEdgeInset(this, inset);
            HostStore.setTargetCols(this, cols);
            dialog.dismiss();
            Toast.makeText(this, "已保存终端设置", Toast.LENGTH_SHORT).show();
        });

        showPage(dialog);
    }

    /** 扩展按键布局编辑。 */
    private void extraKeysDialog() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_extra_keys, null);
        Dialog dialog = newPage(root);

        TextInputEditText et = root.findViewById(R.id.et_extra_keys_config);
        et.setText(ExtraKeys.get(this));

        root.findViewById(R.id.btn_cancel_extra_keys).setOnClickListener(v -> dialog.cancel());
        root.findViewById(R.id.btn_reset_extra_keys).setOnClickListener(v -> {
            et.setText(ExtraKeys.DEFAULT);
            Toast.makeText(this, "已恢复默认扩展按键", Toast.LENGTH_SHORT).show();
        });

        root.findViewById(R.id.btn_save_extra_keys).setOnClickListener(v -> {
            String val = et.getText() != null ? et.getText().toString().trim() : "";
            ExtraKeys.set(this, val.isEmpty() ? ExtraKeys.DEFAULT : val);
            dialog.dismiss();
            Toast.makeText(this, "已保存扩展按键", Toast.LENGTH_SHORT).show();
        });

        showPage(dialog);
    }
}

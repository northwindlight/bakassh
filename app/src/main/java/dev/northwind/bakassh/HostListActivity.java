package dev.northwind.bakassh;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：Pixel / Material 3 风格的主机列表与密钥管理。
 * 遵循 Material You 规范：大标题顶栏、大圆角卡片、药丸操作按钮与底部抽屉（BottomSheet）。
 */
public class HostListActivity extends AppCompatActivity {

    private List<Host> hosts;
    private BaseAdapter adapter;
    private ListView list;
    private View emptyStateView;
    private TextView subtitleView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host_list);

        hosts = HostStore.load(this);

        list = findViewById(R.id.list_hosts);
        emptyStateView = findViewById(R.id.layout_empty_state);
        subtitleView = findViewById(R.id.tv_host_subtitle);

        ExtendedFloatingActionButton fabAdd = findViewById(R.id.fab_add_host);
        fabAdd.setOnClickListener(v -> editDialog(-1));

        findViewById(R.id.chip_manage_keys).setOnClickListener(v -> manageKeysDialog());
        findViewById(R.id.chip_extra_keys).setOnClickListener(v -> extraKeysDialog());
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
                TextView tvChipAuth = row.findViewById(R.id.tv_chip_auth);
                TextView tvChipStartup = row.findViewById(R.id.tv_chip_startup);
                ImageButton btnMore = row.findViewById(R.id.btn_host_more);

                tvTitle.setText(h.title());
                tvEndpoint.setText(h.user + "@" + h.host + ":" + h.port);

                if ("password".equalsIgnoreCase(h.authType)) {
                    tvChipAuth.setText("🔒 密码认证");
                } else {
                    KeyItem boundKey = KeyManager.getKey(HostListActivity.this, h.keyId);
                    String keyName = boundKey != null ? boundKey.name : "默认密钥";
                    tvChipAuth.setText("🔑 " + keyName);
                }

                if (h.startup != null && !h.startup.trim().isEmpty()) {
                    tvChipStartup.setVisibility(View.VISIBLE);
                    tvChipStartup.setText("▸ " + h.startup.trim());
                } else {
                    tvChipStartup.setVisibility(View.GONE);
                }

                btnMore.setOnClickListener(v -> showHostActionSheet(i));

                return row;
            }
        };

        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> connect(hosts.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showHostActionSheet(position);
            return true;
        });

        updateListState();

        MainActivity.out(this, "HOSTLIST shown, hosts=" + hosts.size()
                + " cols=" + HostStore.getTargetCols(this, 150));
    }

    private void updateListState() {
        if (hosts.isEmpty()) {
            emptyStateView.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
            subtitleView.setText("还没有配置远程主机 · 点击下方新建");
        } else {
            emptyStateView.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            subtitleView.setText("已保存 " + hosts.size() + " 台主机 · 点按卡片快速连接");
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

    /** 弹出主机操作抽屉 (Pixel 底部操作表) */
    private void showHostActionSheet(int pos) {
        Host h = hosts.get(pos);
        String[] actions = new String[]{"立即连接", "编辑配置", "复制 SSH 命令", "删除主机"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(h.title())
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            connect(h);
                            break;
                        case 1:
                            editDialog(pos);
                            break;
                        case 2:
                            copySshCommand(h);
                            break;
                        case 3:
                            confirmDelete(pos);
                            break;
                    }
                })
                .show();
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

    /** 新建 / 编辑主机：Pixel 风格 BottomSheet 抽屉 */
    private void editDialog(int pos) {
        Host h = pos >= 0 ? hosts.get(pos) : new Host("", "", 22, "", "", "publickey", "default", "");
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_edit_host, null);
        dialog.setContentView(root);

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

        tvTitle.setText(pos >= 0 ? "编辑主机" : "新建主机");
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
            tvSelectedKeyName.setText("绑定密钥：" + (k != null ? k.name : "默认密钥"));
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
            String[] names = new String[keyList.size()];
            int checked = 0;
            for (int i = 0; i < keyList.size(); i++) {
                KeyItem k = keyList.get(i);
                names[i] = k.name + (k.isDefault ? " [全局默认]" : "") + " (" + k.type + ")";
                if (k.id.equals(selectedKeyId[0])) checked = i;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle("选择绑定的公钥密钥")
                    .setSingleChoiceItems(names, checked, (d, which) -> {
                        selectedKeyId[0] = keyList.get(which).id;
                        updateKeyDisplay.run();
                        d.dismiss();
                    })
                    .show();
        });

        root.findViewById(R.id.btn_cancel_host).setOnClickListener(v -> dialog.dismiss());
        root.findViewById(R.id.btn_save_host).setOnClickListener(v -> {
            String host = etIp.getText() != null ? etIp.getText().toString().trim() : "";
            if (host.isEmpty()) {
                Toast.makeText(this, "主机名或 IP 地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            Host n = new Host();
            n.name = etName.getText() != null ? etName.getText().toString().trim() : "";
            n.host = host;
            try {
                n.port = Integer.parseInt(etPort.getText() != null ? etPort.getText().toString().trim() : "22");
            } catch (Throwable t) {
                n.port = 22;
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

        dialog.show();
    }

    /** 密钥管理：展示所有密钥、生成新密钥、导入私钥、查看公钥与设置默认 */
    private void manageKeysDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_key_manager, null);
        dialog.setContentView(root);

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

                Runnable showActions = () -> keyActionMenu(k, () -> {
                    keyList.clear();
                    keyList.addAll(KeyManager.getKeys(HostListActivity.this));
                    notifyDataSetChanged();
                    adapter.notifyDataSetChanged();
                });

                row.setOnClickListener(v -> showActions.run());
                btnMore.setOnClickListener(v -> showActions.run());

                return row;
            }
        };

        lv.setAdapter(keyAdapter);

        Runnable refreshList = () -> {
            keyList.clear();
            keyList.addAll(KeyManager.getKeys(HostListActivity.this));
            keyAdapter.notifyDataSetChanged();
            adapter.notifyDataSetChanged();
        };

        root.findViewById(R.id.btn_generate_key).setOnClickListener(v -> generateKeyDialog(refreshList));
        root.findViewById(R.id.btn_import_key).setOnClickListener(v -> importKeyDialog(refreshList));
        root.findViewById(R.id.btn_close_key_mgr).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void keyActionMenu(KeyItem k, Runnable onRefresh) {
        String[] actions = new String[]{"查看 / 复制公钥", "设为默认密钥", "重命名", "删除密钥"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(k.name + (k.isDefault ? " 【默认密钥】" : ""))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            showKeyDetailDialog(k);
                            break;
                        case 1:
                            KeyManager.setDefaultKey(this, k.id);
                            Toast.makeText(this, "已将 " + k.name + " 设为默认密钥", Toast.LENGTH_SHORT).show();
                            onRefresh.run();
                            break;
                        case 2:
                            renameKeyDialog(k, onRefresh);
                            break;
                        case 3:
                            deleteKeyDialog(k, onRefresh);
                            break;
                    }
                })
                .show();
    }

    private void showKeyDetailDialog(KeyItem k) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_key_detail, null);
        dialog.setContentView(root);

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

        root.findViewById(R.id.btn_close_detail).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void generateKeyDialog(Runnable onRefresh) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_generate_key, null);
        dialog.setContentView(root);

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

        root.findViewById(R.id.btn_cancel_gen).setOnClickListener(v -> dialog.dismiss());
        root.findViewById(R.id.btn_confirm_gen).setOnClickListener(v -> {
            String name = etName.getText() != null ? etName.getText().toString().trim() : "";
            if (name.isEmpty()) name = "新建密钥";
            String finalName = name;
            String type = typeVals[selectedTypeIdx[0]];
            String pass = etPass.getText() != null ? etPass.getText().toString() : "";
            String comment = etComment.getText() != null ? etComment.getText().toString() : "";

            Toast.makeText(this, "正在生成密钥对，请稍候...", Toast.LENGTH_SHORT).show();
            dialog.dismiss();

            new Thread(() -> {
                try {
                    KeyItem item = KeyManager.generateKey(HostListActivity.this, finalName, type, pass, comment);
                    runOnUiThread(() -> {
                        Toast.makeText(HostListActivity.this, "已生成密钥: " + item.name, Toast.LENGTH_SHORT).show();
                        onRefresh.run();
                        showKeyDetailDialog(item);
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> new MaterialAlertDialogBuilder(HostListActivity.this)
                            .setTitle("生成失败")
                            .setMessage(t.getMessage())
                            .setPositiveButton("确定", null)
                            .show());
                }
            }).start();
        });

        dialog.show();
    }

    private void importKeyDialog(Runnable onRefresh) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_import_key, null);
        dialog.setContentView(root);

        TextInputEditText etName = root.findViewById(R.id.et_import_key_name);
        TextInputEditText etContent = root.findViewById(R.id.et_import_key_content);
        TextInputEditText etPass = root.findViewById(R.id.et_import_key_pass);

        root.findViewById(R.id.btn_cancel_import).setOnClickListener(v -> dialog.dismiss());
        root.findViewById(R.id.btn_confirm_import).setOnClickListener(v -> {
            String name = etName.getText() != null ? etName.getText().toString().trim() : "";
            if (name.isEmpty()) name = "导入密钥";
            String keyText = etContent.getText() != null ? etContent.getText().toString().trim() : "";
            String pass = etPass.getText() != null ? etPass.getText().toString() : "";

            if (keyText.isEmpty()) {
                Toast.makeText(this, "请粘贴私钥内容", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                KeyItem item = KeyManager.importKey(this, name, keyText, pass);
                dialog.dismiss();
                Toast.makeText(this, "已成功导入密钥: " + item.name, Toast.LENGTH_SHORT).show();
                onRefresh.run();
                showKeyDetailDialog(item);
            } catch (Throwable t) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("导入失败")
                        .setMessage(t.getMessage())
                        .setPositiveButton("确定", null)
                        .show();
            }
        });

        dialog.show();
    }

    private void renameKeyDialog(KeyItem k, Runnable onRefresh) {
        TextInputEditText et = new TextInputEditText(this);
        et.setText(k.name);
        et.setHint("新密钥名称");

        new MaterialAlertDialogBuilder(this)
                .setTitle("重命名密钥")
                .setView(et)
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
        if (keys.size() <= 1) {
            Toast.makeText(this, "至少保留一个密钥，无法删除", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Host> inUse = HostStore.getHostsUsingKey(this, k.id);
        String msg = "确认删除密钥 \"" + k.name + "\"？";
        if (!inUse.isEmpty()) {
            msg += "\n\n注意：有 " + inUse.size() + " 台主机当前绑定了此密钥，删除后将自动改用默认密钥。";
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

    /** 终端设置：Pixel 风格 BottomSheet 抽屉 */
    private void settingsDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        dialog.setContentView(root);

        TextInputEditText etInset = root.findViewById(R.id.et_settings_inset);
        TextInputEditText etCols = root.findViewById(R.id.et_settings_cols);

        etInset.setText(String.valueOf(HostStore.getEdgeInset(this, 3)));
        etCols.setText(String.valueOf(HostStore.getTargetCols(this, 150)));

        root.findViewById(R.id.btn_cancel_settings).setOnClickListener(v -> dialog.dismiss());
        root.findViewById(R.id.btn_save_settings).setOnClickListener(v -> {
            try {
                if (etInset.getText() != null) {
                    HostStore.setEdgeInset(this, Integer.parseInt(etInset.getText().toString().trim()));
                }
            } catch (Throwable ignored) {}
            try {
                if (etCols.getText() != null) {
                    HostStore.setTargetCols(this, Integer.parseInt(etCols.getText().toString().trim()));
                }
            } catch (Throwable ignored) {}
            dialog.dismiss();
            Toast.makeText(this, "已保存终端设置", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    /** 扩展按键定制：Pixel 风格 BottomSheet 抽屉 */
    private void extraKeysDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_extra_keys, null);
        dialog.setContentView(root);

        TextInputEditText et = root.findViewById(R.id.et_extra_keys_config);
        et.setText(ExtraKeys.get(this));

        root.findViewById(R.id.btn_reset_extra_keys).setOnClickListener(v -> {
            et.setText(ExtraKeys.DEFAULT);
            ExtraKeys.set(this, ExtraKeys.DEFAULT);
            Toast.makeText(this, "已恢复默认扩展按键", Toast.LENGTH_SHORT).show();
        });

        root.findViewById(R.id.btn_save_extra_keys).setOnClickListener(v -> {
            String val = et.getText() != null ? et.getText().toString().trim() : "";
            ExtraKeys.set(this, val.isEmpty() ? ExtraKeys.DEFAULT : val);
            dialog.dismiss();
            Toast.makeText(this, "已保存扩展按键", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }
}

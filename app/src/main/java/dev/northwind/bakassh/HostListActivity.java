package dev.northwind.bakassh;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：主机列表与密钥管理。
 * 点一台主机进终端；长按可编辑/删除；底部能新建主机、管理与绑定密钥。
 */
public class HostListActivity extends AppCompatActivity {

    private List<Host> hosts;
    private BaseAdapter adapter;
    private ListView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hosts = HostStore.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121216);

        root.addView(title("bakaSSH"), lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        TextView hint = new TextView(this);
        hint.setText("点一台主机连接 · 长按编辑/删除");
        hint.setTextColor(0xFF8A8A94);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(hint);

        list = new ListView(this);
        list.setDividerHeight(1);
        list.setBackgroundColor(0xFF121216);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return hosts.size(); }
            @Override public Object getItem(int i) { return hosts.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout row = new LinearLayout(HostListActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));
                row.setBackgroundColor(0xFF17171C);
                Host h = hosts.get(i);
                TextView t1 = new TextView(HostListActivity.this);
                t1.setText(h.title());
                t1.setTextColor(0xFFE8E8EC);
                t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

                TextView t2 = new TextView(HostListActivity.this);
                String authDesc;
                if ("password".equalsIgnoreCase(h.authType)) {
                    authDesc = "[密码]";
                } else {
                    KeyItem boundKey = KeyManager.getKey(HostListActivity.this, h.keyId);
                    authDesc = "[公钥: " + (boundKey != null ? boundKey.name : "默认") + "]";
                }
                String sub = h.user + "@" + h.host + ":" + h.port + "  " + authDesc;
                if (h.startup != null && !h.startup.isEmpty()) sub += "   ▸ " + h.startup;
                t2.setText(sub);
                t2.setTextColor(0xFF8A8A94);
                t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

                row.addView(t1);
                row.addView(t2);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> connect(hosts.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            editMenu(pos);
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF1C1C22);
        bar.addView(button("＋ 新建主机", v -> editDialog(-1)), weight());
        bar.addView(button("密钥管理", v -> manageKeysDialog()), weight());
        bar.addView(button("扩展按键", v -> extraKeysDialog()), weight());
        bar.addView(button("设置", v -> settingsDialog()), weight());
        root.addView(bar, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        setContentView(root);
        MainActivity.out(this, "HOSTLIST shown, hosts=" + hosts.size()
                + " cols=" + HostStore.getTargetCols(this, 150));
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFF0F0F4);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setPadding(dp(16), dp(16), dp(16), dp(4));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        com.google.android.material.button.MaterialButton b =
                new com.google.android.material.button.MaterialButton(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setCornerRadius(dp(12));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A2A31));
        b.setTextColor(0xFFE6E6EA);
        b.setOnClickListener(l);
        return b;
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

    private void editMenu(int pos) {
        Host h = hosts.get(pos);
        new AlertDialog.Builder(this)
                .setTitle(h.title())
                .setItems(new String[]{"编辑", "删除"}, (d, which) -> {
                    if (which == 0) editDialog(pos);
                    else confirmDelete(pos);
                })
                .show();
    }

    private void confirmDelete(int pos) {
        Host h = hosts.get(pos);
        new AlertDialog.Builder(this)
                .setTitle("删除 " + h.title() + "？")
                .setPositiveButton("删除", (d, w) -> {
                    hosts.remove(pos);
                    HostStore.save(this, hosts);
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(String value, String hint, int inputType, LinearLayout parent) {
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setHint(hint);
        e.setInputType(inputType);
        parent.addView(e, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        return e;
    }

    private void editDialog(int pos) {
        Host h = pos >= 0 ? hosts.get(pos) : new Host("", "", 22, "", "", "publickey", "default", "");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText eName = field(h.name, "名称（随意，例如 rpi5）", InputType.TYPE_CLASS_TEXT, box);
        EditText eHost = field(h.host, "主机名或 IP", InputType.TYPE_CLASS_TEXT, box);
        EditText ePort = field(String.valueOf(h.port), "端口", InputType.TYPE_CLASS_NUMBER, box);
        EditText eUser = field(h.user, "用户名", InputType.TYPE_CLASS_TEXT, box);
        EditText eCmd = field(h.startup, "连上后自动执行（可空）", InputType.TYPE_CLASS_TEXT, box);

        final String[] currentAuth = new String[]{h.authType != null ? h.authType : "publickey"};
        final String[] selectedKeyId = new String[]{h.keyId != null ? h.keyId : "default"};

        Button bAuth = button("认证方式：" + ("password".equalsIgnoreCase(currentAuth[0]) ? "密码认证" : "公钥认证"), null);
        bAuth.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        box.addView(bAuth, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        KeyItem currentKey = KeyManager.getKey(this, selectedKeyId[0]);
        String keyBtnText = "绑定密钥：" + (currentKey != null ? currentKey.name : "默认密钥");
        Button bKey = button(keyBtnText, null);
        bKey.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        box.addView(bKey, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        EditText ePwd = field(h.password, "密码（留空则连接时询问）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, box);

        Runnable updateAuthUi = () -> {
            boolean isPassword = "password".equalsIgnoreCase(currentAuth[0]);
            bAuth.setText("认证方式：" + (isPassword ? "密码认证" : "公钥认证"));
            bKey.setVisibility(isPassword ? View.GONE : View.VISIBLE);
            ePwd.setVisibility(isPassword ? View.VISIBLE : View.GONE);
        };
        updateAuthUi.run();

        bAuth.setOnClickListener(v -> {
            String[] options = new String[]{"公钥认证（推荐）", "密码认证"};
            int checked = "password".equalsIgnoreCase(currentAuth[0]) ? 1 : 0;
            new AlertDialog.Builder(this)
                    .setTitle("选择认证方式")
                    .setSingleChoiceItems(options, checked, (d, which) -> {
                        currentAuth[0] = (which == 1) ? "password" : "publickey";
                        updateAuthUi.run();
                        d.dismiss();
                    })
                    .show();
        });

        bKey.setOnClickListener(v -> {
            List<KeyItem> keyList = KeyManager.getKeys(this);
            String[] names = new String[keyList.size()];
            int checked = 0;
            for (int i = 0; i < keyList.size(); i++) {
                KeyItem k = keyList.get(i);
                names[i] = k.name + (k.isDefault ? " [全局默认]" : "") + " (" + k.type + ")";
                if (k.id.equals(selectedKeyId[0])) checked = i;
            }
            new AlertDialog.Builder(this)
                    .setTitle("选择绑定的公钥密钥")
                    .setSingleChoiceItems(names, checked, (d, which) -> {
                        selectedKeyId[0] = keyList.get(which).id;
                        bKey.setText("绑定密钥：" + keyList.get(which).name);
                        d.dismiss();
                    })
                    .show();
        });

        new AlertDialog.Builder(this)
                .setTitle(pos >= 0 ? "编辑主机" : "新建主机")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    Host n = new Host();
                    n.name = eName.getText().toString().trim();
                    n.host = eHost.getText().toString().trim();
                    try { n.port = Integer.parseInt(ePort.getText().toString().trim()); }
                    catch (Throwable t) { n.port = 22; }
                    n.user = eUser.getText().toString().trim();
                    n.startup = eCmd.getText().toString().trim();
                    n.authType = currentAuth[0];
                    n.keyId = selectedKeyId[0];
                    n.password = ePwd.getText().toString();
                    if (n.host.isEmpty()) {
                        Toast.makeText(this, "主机名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (pos >= 0) hosts.set(pos, n); else hosts.add(n);
                    HostStore.save(this, hosts);
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 密钥管理：展示所有密钥、生成新密钥、导入私钥、查看公钥与设置默认 */
    private void manageKeysDialog() {
        List<KeyItem> keyList = new ArrayList<>(KeyManager.getKeys(this));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        ListView lv = new ListView(this);
        lv.setDividerHeight(1);

        BaseAdapter keyAdapter = new BaseAdapter() {
            @Override public int getCount() { return keyList.size(); }
            @Override public Object getItem(int i) { return keyList.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout row = new LinearLayout(HostListActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                KeyItem k = keyList.get(i);
                TextView t1 = new TextView(HostListActivity.this);
                t1.setText(k.title());
                t1.setTextColor(0xFFE8E8EC);
                t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                TextView t2 = new TextView(HostListActivity.this);
                t2.setText(k.subtitle());
                t2.setTextColor(0xFF8A8A94);
                t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                row.addView(t1);
                row.addView(t2);
                return row;
            }
        };
        lv.setAdapter(keyAdapter);

        box.addView(lv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260), 0));

        Runnable refreshList = () -> {
            keyList.clear();
            keyList.addAll(KeyManager.getKeys(HostListActivity.this));
            keyAdapter.notifyDataSetChanged();
            adapter.notifyDataSetChanged();
        };

        lv.setOnItemClickListener((p, v, pos, id) -> {
            KeyItem selected = keyList.get(pos);
            keyActionMenu(selected, refreshList);
        });

        new AlertDialog.Builder(this)
                .setTitle("密钥管理（点击管理单个密钥）")
                .setView(box)
                .setPositiveButton("＋ 生成密钥", (d, w) -> generateKeyDialog(refreshList))
                .setNeutralButton("导入私钥", (d, w) -> importKeyDialog(refreshList))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void keyActionMenu(KeyItem k, Runnable onRefresh) {
        String[] actions = new String[]{"查看 / 复制公钥", "设为默认密钥", "重命名", "删除密钥"};
        new AlertDialog.Builder(this)
                .setTitle(k.name + (k.isDefault ? " 【默认】" : ""))
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
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(10));

        TextView info = new TextView(this);
        info.setText("类型: " + k.type + "\n指纹: " + k.fingerprint);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setTextColor(0xFF8A8A94);
        box.addView(info);

        TextView pubText = new TextView(this);
        pubText.setText(k.publicKey);
        pubText.setTextIsSelectable(true);
        pubText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pubText.setTextColor(0xFFE6E6EA);
        pubText.setPadding(0, dp(10), 0, dp(10));
        box.addView(pubText);

        new AlertDialog.Builder(this)
                .setTitle(k.name + " 公钥")
                .setView(box)
                .setPositiveButton("复制公钥", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("SSH 公钥", k.publicKey));
                    Toast.makeText(this, "已复制公钥到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void generateKeyDialog(Runnable onRefresh) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText eName = field("新建密钥", "密钥名称（例如：个人服务器）", InputType.TYPE_CLASS_TEXT, box);

        final String[] types = new String[]{"RSA-3072（推荐）", "RSA-2048", "RSA-4096", "ECDSA-256", "ECDSA-384"};
        final String[] typeVals = new String[]{"RSA-3072", "RSA-2048", "RSA-4096", "ECDSA-256", "ECDSA-384"};
        final int[] selectedTypeIdx = new int[]{0};

        Button bType = button("密钥类型：" + types[0], null);
        bType.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        box.addView(bType, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        bType.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("选择密钥类型")
                    .setSingleChoiceItems(types, selectedTypeIdx[0], (d, which) -> {
                        selectedTypeIdx[0] = which;
                        bType.setText("密钥类型：" + types[which]);
                        d.dismiss();
                    })
                    .show();
        });

        EditText ePass = field("", "私钥保护口令（可选，留空则免密码）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, box);
        EditText eComment = field("", "公钥注释（可选，留空则自动生成）", InputType.TYPE_CLASS_TEXT, box);

        new AlertDialog.Builder(this)
                .setTitle("生成新 SSH 密钥")
                .setView(box)
                .setPositiveButton("生成", (d, w) -> {
                    String name = eName.getText().toString().trim();
                    if (name.isEmpty()) name = "新建密钥";
                    String finalName = name;
                    String type = typeVals[selectedTypeIdx[0]];
                    String pass = ePass.getText().toString();
                    String comment = eComment.getText().toString();

                    Toast.makeText(this, "正在生成密钥对，请稍候...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        try {
                            KeyItem item = KeyManager.generateKey(HostListActivity.this, finalName, type, pass, comment);
                            runOnUiThread(() -> {
                                Toast.makeText(HostListActivity.this, "已生成密钥：" + item.name, Toast.LENGTH_SHORT).show();
                                onRefresh.run();
                                showKeyDetailDialog(item);
                            });
                        } catch (Throwable t) {
                            runOnUiThread(() -> {
                                new AlertDialog.Builder(HostListActivity.this)
                                        .setTitle("生成失败")
                                        .setMessage(t.getMessage())
                                        .setPositiveButton("确定", null)
                                        .show();
                            });
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importKeyDialog(Runnable onRefresh) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText eName = field("", "密钥名称（例如：导入的 GitHub 密钥）", InputType.TYPE_CLASS_TEXT, box);

        EditText eKey = new EditText(this);
        eKey.setHint("粘贴 OpenSSH / PEM 格式私钥内容\n-----BEGIN ... PRIVATE KEY-----");
        eKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        eKey.setMinLines(5);
        eKey.setMaxLines(10);
        eKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(eKey, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        EditText ePass = field("", "私钥加密口令（若未加密可留空）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, box);

        new AlertDialog.Builder(this)
                .setTitle("导入已有私钥")
                .setView(box)
                .setPositiveButton("导入", (d, w) -> {
                    String name = eName.getText().toString().trim();
                    if (name.isEmpty()) name = "导入密钥";
                    String keyText = eKey.getText().toString().trim();
                    String pass = ePass.getText().toString();

                    try {
                        KeyItem item = KeyManager.importKey(this, name, keyText, pass);
                        Toast.makeText(this, "已成功导入密钥：" + item.name, Toast.LENGTH_SHORT).show();
                        onRefresh.run();
                        showKeyDetailDialog(item);
                    } catch (Throwable t) {
                        new AlertDialog.Builder(this)
                                .setTitle("导入失败")
                                .setMessage(t.getMessage())
                                .setPositiveButton("确定", null)
                                .show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameKeyDialog(KeyItem k, Runnable onRefresh) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText e = field(k.name, "新名称", InputType.TYPE_CLASS_TEXT, box);

        new AlertDialog.Builder(this)
                .setTitle("重命名密钥")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String newName = e.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        KeyManager.renameKey(this, k.id, newName);
                        onRefresh.run();
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
            msg += "\n\n注意：有 " + inUse.size() + " 台主机当前绑定了此密钥，删除后这些主机将自动改用默认密钥。";
        }

        new AlertDialog.Builder(this)
                .setTitle("删除密钥")
                .setMessage(msg)
                .setPositiveButton("删除", (d, w) -> {
                    boolean ok = KeyManager.deleteKey(this, k.id);
                    if (ok) {
                        // 重置受影响的主机绑定的密钥为默认
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

    /** 设置：终端边距、目标列数（都可自己调）。 */
    private void settingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        final EditText eInset = field(String.valueOf(HostStore.getEdgeInset(this, 3)),
                "终端四周留边（dp，0=贴边；曲面屏建议 3~8）", InputType.TYPE_CLASS_NUMBER, box);
        final EditText eCols = field(String.valueOf(HostStore.getTargetCols(this, 150)),
                "目标列数（越小字越大；进终端后也能捏合缩放）", InputType.TYPE_CLASS_NUMBER, box);
        new AlertDialog.Builder(this)
                .setTitle("设置（保存后下次进终端生效）")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        HostStore.setEdgeInset(this,
                                Integer.parseInt(eInset.getText().toString().trim()));
                    } catch (Throwable ignored) {
                    }
                    try {
                        HostStore.setTargetCols(this,
                                Integer.parseInt(eCols.getText().toString().trim()));
                    } catch (Throwable ignored) {
                    }
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 扩展按键栏定制（学 Termux 的 extra-keys，但用一行文本配置）。 */
    private void extraKeysDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView legend = new TextView(this);
        legend.setText(ExtraKeys.legend());
        legend.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(legend);
        final EditText e = new EditText(this);
        e.setText(ExtraKeys.get(this));
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(e, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        new AlertDialog.Builder(this)
                .setTitle("扩展按键（下次进终端生效）")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    ExtraKeys.set(this, e.getText().toString());
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("恢复默认", (d, w) -> ExtraKeys.set(this, ExtraKeys.DEFAULT))
                .setNegativeButton("取消", null)
                .show();
    }
}

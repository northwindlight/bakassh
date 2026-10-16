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

import java.util.List;

/**
 * 主界面：主机列表（照着 ConnectBot 的样子做）。
 * 点一台就进终端；长按可编辑/删除；底部能新建主机、查看本机公钥。
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
                t2.setText(h.subtitle());
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
        bar.addView(button("公钥", v -> showPubKey()), weight());
        bar.addView(button("扩展按键", v -> extraKeysDialog()), weight());
        bar.addView(button("设置", v -> settingsDialog()), weight());
        root.addView(bar, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        setContentView(root);
        MainActivity.out(this, "HOSTLIST shown, hosts=" + hosts.size()
                + " cols=" + HostStore.getTargetCols(this, 150));
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        return p;
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
        MainActivity.out(this, "CONNECT " + h.user + "@" + h.host + ":" + h.port);
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
        // 不设文字颜色：跟随对话框主题（硬编码近白色会在浅色对话框上变成白底白字）
        parent.addView(e, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        return e;
    }

    private void editDialog(int pos) {
        Host h = pos >= 0 ? hosts.get(pos) : new Host("", "", 22, "", "");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        /*
         * 注意：这里不要给输入框硬编码文字颜色。
         * 之前设成近白色（0xFFE8E8EC）而对话框背景是浅色 → 白底白字，编辑界面等于看不见。
         * 让文字/提示色跟随主题即可；应用主题已定为深色 Material。
         */
        EditText eName = field(h.name, "名称（随意，例如 rpi5）", InputType.TYPE_CLASS_TEXT, box);
        EditText eHost = field(h.host, "主机名或 IP", InputType.TYPE_CLASS_TEXT, box);
        EditText ePort = field(String.valueOf(h.port), "端口", InputType.TYPE_CLASS_NUMBER, box);
        EditText eUser = field(h.user, "用户名", InputType.TYPE_CLASS_TEXT, box);
        EditText eCmd = field(h.startup, "连上后自动执行（可空）",
                InputType.TYPE_CLASS_TEXT, box);

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

    private void showPubKey() {
        try {
            String pub = KeyManager.ensureKey(this);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("bakaSSH 公钥", pub));
            new AlertDialog.Builder(this)
                    .setTitle("本机公钥（已复制到剪贴板）")
                    .setMessage(pub)
                    .setPositiveButton("好", null)
                    .show();
        } catch (Throwable t) {
            Toast.makeText(this, "生成失败：" + t, Toast.LENGTH_LONG).show();
        }
    }
}

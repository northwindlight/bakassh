package dev.northwind.bakassh;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Shared session overview for the terminal and the host list. */
final class SessionSwitcher {
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static void show(Context context, SessionService service, String current,
                     Consumer<TerminalSession> select, Runnable create) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 20));
        View handle = new View(context);
        handle.setBackgroundResource(R.drawable.bg_drag_handle);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(context, 36), dp(context, 4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(context, 16);
        box.addView(handle, handleLp);
        TextView heading = new TextView(context);
        heading.setTextSize(22);
        heading.setTextColor(SessionDrawer.color(context, com.google.android.material.R.attr.colorOnSurface));
        box.addView(heading);
        TextView hint = new TextView(context);
        hint.setText("点按切换，长按或 ⋮ 重命名、关闭");
        hint.setTextSize(14);
        hint.setTextColor(SessionDrawer.color(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(0, dp(context, 4), 0, dp(context, 16));
        box.addView(hint);
        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        MaterialButton add = new MaterialButton(context);
        add.setText("新建连接");
        add.setIconResource(R.drawable.ic_add);
        add.setOnClickListener(v -> { dialog.dismiss(); create.run(); });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 56));
        addLp.topMargin = dp(context, 12);
        box.addView(add, addLp);
        Map<String, TextView> labels = new HashMap<>();
        Runnable refresh = () -> {
            List<TerminalSession> sessions = service.sessions();
            heading.setText("会话 · " + sessions.size());
            list.removeAllViews();
            labels.clear();
            for (int i = 0; i < sessions.size(); i++) {
                TerminalSession session = sessions.get(i);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(context, 6);
                list.addView(SessionDrawer.card(context, service, session, i + 1, session.id.equals(current), labels, () -> {
                    dialog.dismiss();
                    select.accept(session);
                }), lp);
            }
            // 会话少时面板贴合内容，多时最多占半屏并滚动。
            int rowHeight = dp(context, 82);
            int maxHeight = Math.min(dp(context, 420), context.getResources().getDisplayMetrics().heightPixels / 2);
            scroll.getLayoutParams().height = Math.min(maxHeight, Math.max(rowHeight, sessions.size() * rowHeight));
            scroll.requestLayout();
        };
        SessionService.Listener listener = new SessionService.Listener() {
            public void onSessionsChanged() {
                if (service.sessions().isEmpty()) dialog.dismiss();
                else refresh.run();
            }
            public void onSessionData(TerminalSession changed) {
                TextView label = labels.get(changed.id);
                if (label != null) SessionDrawer.bindStatus(label, changed, changed.id.equals(current));
            }
        };
        refresh.run();
        service.addListener(listener);
        androidx.lifecycle.LifecycleEventObserver lifecycle = (owner, event) -> {
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) dialog.dismiss();
        };
        if (context instanceof androidx.lifecycle.LifecycleOwner)
            ((androidx.lifecycle.LifecycleOwner) context).getLifecycle().addObserver(lifecycle);
        dialog.setOnDismissListener(d -> {
            service.removeListener(listener);
            if (context instanceof androidx.lifecycle.LifecycleOwner)
                ((androidx.lifecycle.LifecycleOwner) context).getLifecycle().removeObserver(lifecycle);
        });
        dialog.setContentView(box);
        dialog.show();
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    static void showActions(Context context, SessionService service, TerminalSession session) {
        new MaterialAlertDialogBuilder(context).setTitle(session.title)
                .setItems(new String[]{"重命名会话", "关闭会话"}, (d, which) -> {
                    if (which == 0) rename(context, service, session);
                    else confirmClose(context, service, session);
                }).show();
    }

    static void confirmClose(Context context, SessionService service, TerminalSession session) {
        if (service.get(session.id) == null) return;
        if (!session.isActive()) { service.close(session.id); return; }
        new MaterialAlertDialogBuilder(context).setTitle("关闭「" + session.title + "」？")
                .setMessage("将断开此 SSH 连接。\n" + session.endpoint)
                .setNegativeButton("取消", null)
                .setPositiveButton("关闭会话", (d, w) -> service.close(session.id)).show();
    }

    private static void rename(Context context, SessionService service, TerminalSession session) {
        TextInputLayout field = new TextInputLayout(context);
        field.setHint("会话名称");
        TextInputEditText edit = new TextInputEditText(context);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setSingleLine(true);
        edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        field.addView(edit);
        edit.setText(session.title);
        edit.selectAll();
        LinearLayout padding = new LinearLayout(context);
        padding.setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0);
        padding.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle("重命名会话").setView(padding).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = edit.getText() == null ? "" : edit.getText().toString().trim();
            if (name.isEmpty()) { field.setError("请输入会话名称"); return; }
            service.rename(session.id, name);
            dialog.dismiss();
        }));
        dialog.show();
    }
}

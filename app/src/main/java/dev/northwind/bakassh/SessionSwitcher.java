package dev.northwind.bakassh;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
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
        box.setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20));
        TextView heading = new TextView(context);
        heading.setTextSize(22);
        heading.setTypeface(null, Typeface.BOLD);
        box.addView(heading);
        TextView hint = new TextView(context);
        hint.setText("点按切换 · 更多操作可重命名或关闭");
        hint.setPadding(0, dp(context, 8), 0, dp(context, 12));
        box.addView(hint);
        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(dp(context, 360), context.getResources().getDisplayMetrics().heightPixels / 2)));
        MaterialButton add = new MaterialButton(context);
        add.setText("＋ 新建连接");
        add.setOnClickListener(v -> { dialog.dismiss(); create.run(); });
        box.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52)));
        Map<String, TextView> labels = new HashMap<>();
        Runnable refresh = () -> {
            List<TerminalSession> sessions = service.sessions();
            heading.setText("会话 · " + sessions.size());
            list.removeAllViews();
            labels.clear();
            for (int i = 0; i < sessions.size(); i++) {
                TerminalSession session = sessions.get(i);
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(context, 4), dp(context, 6), 0, dp(context, 6));
                LinearLayout text = new LinearLayout(context);
                text.setOrientation(LinearLayout.VERTICAL);
                text.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
                TextView title = new TextView(context);
                title.setText(label(session, i + 1, session.id.equals(current)));
                title.setTextSize(16);
                title.setTypeface(null, Typeface.BOLD);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                labels.put(session.id, title);
                TextView detail = new TextView(context);
                detail.setText(session.endpoint + "\n" + session.detail());
                detail.setTextSize(12);
                text.addView(title);
                text.addView(detail);
                text.setOnClickListener(v -> {
                    if (service.get(session.id) == null) return;
                    dialog.dismiss();
                    select.accept(session);
                });
                text.setBackgroundResource(android.R.drawable.list_selector_background);
                row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                ImageButton more = new ImageButton(context);
                more.setImageResource(R.drawable.ic_more_vert);
                android.util.TypedValue color = new android.util.TypedValue();
                context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, color, true);
                more.setColorFilter(color.data);
                more.setBackgroundColor(Color.TRANSPARENT);
                more.setContentDescription(session.title + " 的会话操作");
                more.setOnClickListener(v -> showActions(context, service, session));
                row.addView(more, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
                list.addView(row);
            }
        };
        SessionService.Listener listener = new SessionService.Listener() {
            public void onSessionsChanged() {
                if (service.sessions().isEmpty()) dialog.dismiss();
                else refresh.run();
            }
            public void onSessionData(TerminalSession changed) {
                TextView label = labels.get(changed.id);
                if (label != null && changed.unread && !changed.id.equals(current))
                    label.setText(SessionSwitcher.label(changed, service.sessions().indexOf(changed) + 1, false));
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

    static String label(TerminalSession session, int index, boolean current) {
        String status = session.state() == TerminalSession.State.CONNECTING ? " · 连接中" :
                session.state() == TerminalSession.State.FAILED ? " · 失败" :
                session.isActive() ? "" : " · 已结束";
        return (current ? "● " : session.unread ? "• " : "") + index + " · " + session.title + status;
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

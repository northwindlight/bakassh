package dev.northwind.bakassh;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Session list drawn over the terminal; opening it never resizes the PTY. */
final class SessionDrawer {
    /** 状态点颜色：与终端顶栏一致，不随壁纸变化，保证一眼能分辨。 */
    static final int CONNECTED = 0xFF6DD58C, CONNECTING = 0xFFFFB960, FAILED = 0xFFF2B8B5, ENDED = 0xFF8E9099;

    private final Context context;
    private final LinearLayout list;
    private final TextView title;
    private final Map<String, TextView> labels = new HashMap<>();
    private List<TerminalSession> sessions = java.util.Collections.emptyList();
    private String selected;

    SessionDrawer(View drawer) {
        context = drawer.getContext();
        list = drawer.findViewById(R.id.session_drawer_list);
        title = drawer.findViewById(R.id.session_drawer_title);
    }

    int indexOf(String id) {
        for (int i = 0; i < sessions.size(); i++) if (id.equals(sessions.get(i).id)) return i;
        return 0;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    static int color(Context context, int attribute) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    static int stateColor(TerminalSession session) {
        switch (session.state()) {
            case CONNECTED: return CONNECTED;
            case CONNECTING: return CONNECTING;
            case FAILED: return FAILED;
            default: return ENDED;
        }
    }

    /** 状态行：连接状态，后台有新输出时追加提示。 */
    static String status(TerminalSession session, boolean current) {
        String text = session.detail();
        return !current && session.unread && session.isActive() ? text + " · 有新输出" : text;
    }

    static void bindStatus(TextView view, TerminalSession session, boolean current) {
        String text = status(session, current);
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
        android.graphics.drawable.Drawable dot = view.getCompoundDrawablesRelative()[0];
        if (dot != null) dot.mutate().setTint(stateColor(session));
        boolean unread = !current && session.unread && session.isActive();
        view.setTextColor(unread ? color(view.getContext(), androidx.appcompat.R.attr.colorPrimary)
                : color(view.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
    }

    /**
     * 一张会话卡片：编号、名称、地址与状态，点按切换，长按或更多按钮管理。
     * 抽屉和主页会话面板共用，保证两处样式一致。
     */
    static MaterialCardView card(Context context, SessionService service, TerminalSession item, int number,
                                 boolean current, Map<String, TextView> statusLabels, Runnable select) {
        MaterialCardView card = new MaterialCardView(context);
        card.setRadius(dp(context, 20));
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(color(context, current ? com.google.android.material.R.attr.colorSecondaryContainer :
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 12), 0, 0, 0);

        TextView badge = new TextView(context);
        badge.setText(String.valueOf(number));
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(14);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color(context, current ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorSurfaceContainerHighest));
        badge.setBackground(circle);
        badge.setTextColor(color(context, current ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(badge, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(context, 12), dp(context, 12), dp(context, 4), dp(context, 12));
        text.setMinimumHeight(dp(context, 76));
        text.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(context);
        name.setText(item.title);
        name.setTextSize(15);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(color(context, com.google.android.material.R.attr.colorOnSurface));
        TextView endpoint = new TextView(context);
        endpoint.setText(item.endpoint);
        endpoint.setTextSize(12);
        endpoint.setTypeface(Typeface.MONOSPACE);
        endpoint.setSingleLine(true);
        endpoint.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        endpoint.setTextColor(color(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
        TextView status = new TextView(context);
        status.setTextSize(12);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.bg_status_dot, 0, 0, 0);
        status.setCompoundDrawablePadding(dp(context, 6));
        status.setPadding(0, dp(context, 4), 0, 0);
        bindStatus(status, item, current);
        if (statusLabels != null) statusLabels.put(item.id, status);
        text.addView(name);
        text.addView(endpoint);
        text.addView(status);
        card.setOnClickListener(v -> { if (service.get(item.id) == item) select.run(); });
        card.setOnLongClickListener(v -> { SessionSwitcher.showActions(context, service, item); return true; });
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton more = new ImageButton(context);
        more.setImageResource(R.drawable.ic_more_vert);
        more.setImageTintList(ColorStateList.valueOf(color(context, com.google.android.material.R.attr.colorOnSurfaceVariant)));
        TypedValue borderless = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, borderless, true);
        more.setBackgroundResource(borderless.resourceId);
        more.setContentDescription("管理 " + item.title);
        more.setOnClickListener(v -> SessionSwitcher.showActions(context, service, item));
        row.addView(more, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        card.addView(row);
        return card;
    }

    void refresh(SessionService service, TerminalSession current, Consumer<TerminalSession> select) {
        sessions = service.sessions();
        selected = current == null ? null : current.id;
        title.setText("会话 · " + sessions.size());
        list.removeAllViews();
        labels.clear();
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession item = sessions.get(i);
            MaterialCardView card = card(context, service, item, i + 1, item == current, labels,
                    () -> select.accept(item));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(context, 6);
            list.addView(card, lp);
        }
    }

    void data(TerminalSession item) {
        TextView label = labels.get(item.id);
        if (label != null) bindStatus(label, item, item.id.equals(selected));
    }
}

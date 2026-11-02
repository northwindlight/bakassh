package dev.northwind.bakassh;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
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

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private int color(int attribute) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    void refresh(SessionService service, TerminalSession current, Consumer<TerminalSession> select) {
        sessions = service.sessions();
        selected = current == null ? null : current.id;
        title.setText("会话 · " + sessions.size());
        list.removeAllViews();
        labels.clear();
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession item = sessions.get(i);
            boolean active = item == current;
            MaterialCardView card = new MaterialCardView(context);
            card.setRadius(dp(16));
            card.setCardElevation(0);
            card.setStrokeWidth(0);
            card.setCardBackgroundColor(color(active ? com.google.android.material.R.attr.colorSecondaryContainer :
                    com.google.android.material.R.attr.colorSurfaceContainer));
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(12), dp(14), dp(4), dp(14));
            text.setMinimumHeight(dp(80));
            TextView name = new TextView(context);
            name.setText(SessionSwitcher.label(item, i + 1, active));
            name.setTextSize(15);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
            labels.put(item.id, name);
            TextView detail = new TextView(context);
            detail.setText(item.endpoint + "\n" + item.detail());
            detail.setTextSize(12);
            detail.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            text.addView(name);
            text.addView(detail);
            TypedValue ripple = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            text.setBackgroundResource(ripple.resourceId);
            text.setOnClickListener(v -> { if (service.get(item.id) == item) select.accept(item); });
            text.setOnLongClickListener(v -> { SessionSwitcher.showActions(context, service, item); return true; });
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageButton more = new ImageButton(context);
            more.setImageResource(R.drawable.ic_more_vert);
            more.setImageTintList(ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant)));
            more.setBackgroundResource(ripple.resourceId);
            more.setContentDescription("管理 " + item.title);
            more.setOnClickListener(v -> SessionSwitcher.showActions(context, service, item));
            row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
            card.addView(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            list.addView(card, lp);
        }
    }

    void data(TerminalSession item) {
        TextView label = labels.get(item.id);
        if (label != null) {
            String text = SessionSwitcher.label(item, indexOf(item.id) + 1, item.id.equals(selected));
            if (!TextUtils.equals(label.getText(), text)) label.setText(text);
        }
    }
}

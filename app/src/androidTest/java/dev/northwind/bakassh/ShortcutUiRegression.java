package dev.northwind.bakassh;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;

import androidx.drawerlayout.widget.DrawerLayout;

/** Android JSON/storage and drawer geometry, isolated from real saved settings. */
final class ShortcutUiRegression {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void verify(Context base) throws Exception {
        String prefix = "shortcut-test-" + System.nanoTime() + "-";
        Context context = new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
        try {
            SharedPreferences preferences = context.getSharedPreferences("bakassh", Context.MODE_PRIVATE);
            preferences.edit().putString("extra_keys", "ESC,BKSP,ALT,UP,KEYBOARD|CTRL,SHIFT,LEFT,DOWN,RIGHT").commit();
            check(ExtraKeys.getLayout(context).key(0, 1).equals("TAB"), "old default did not gain Tab");
            preferences.edit().putString("extra_keys", "ESC,TAB,/|CTRL,c,RIGHT").commit();
            KeyLayout custom = ExtraKeys.getLayout(context);
            custom.replace(0, 2, "|");
            custom.replace(1, 1, ",");
            check(ExtraKeys.getLayout(context).key(0, 2).equals("/"), "draft changed saved layout");
            ExtraKeys.saveLayout(context, custom);
            KeyLayout roundtrip = ExtraKeys.getLayout(context);
            check(roundtrip.key(0, 2).equals("|") && roundtrip.key(1, 1).equals(","), "JSON lost literal delimiters");
            check(preferences.getString("extra_keys", "").equals("ESC,TAB,/|CTRL,c,RIGHT"), "legacy backup overwritten");
            preferences.edit().putString("extra_keys_layout_v2", "broken").commit();
            check(ExtraKeys.getLayout(context).key(1, 1).equals("c"), "bad structured layout discarded old custom keys");
        } finally { base.deleteSharedPreferences(prefix + "bakassh"); }

        Context theme = new android.view.ContextThemeWrapper(base, R.style.TermTheme);
        DrawerLayout drawer = (DrawerLayout) LayoutInflater.from(theme).inflate(R.layout.activity_terminal, null);
        TerminalView terminal = drawer.findViewById(R.id.terminal_view);
        int width = Math.max(1080, base.getResources().getDisplayMetrics().widthPixels), height = 1600;
        try {
            measure(drawer, width, height);
            int before = terminal.getHeight();
            int toolbar = drawer.findViewById(R.id.layout_terminal_toolbar).getHeight();
            check(before == height - toolbar, "permanent session chrome reduced terminal height");
            drawer.openDrawer(androidx.core.view.GravityCompat.START, false);
            measure(drawer, width, height);
            check(terminal.getHeight() == before, "opening session drawer resized terminal");
            drawer.closeDrawers();
            measure(drawer, width, height);
            check(terminal.getHeight() == before, "closing drawer changed terminal geometry");
        } finally { terminal.destroy(); }
    }
    private static void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
}

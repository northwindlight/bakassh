package dev.northwind.bakassh;

import android.app.Activity;
import android.content.ClipData;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Shared graphical editor. Only Save changes the persistent layout. */
final class ExtraKeysEditor {
    private static final String[] PALETTE = {"ESC", "TAB", "CTRL", "ALT", "SHIFT", "ENTER", "BKSP", "DEL",
            "UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN", "INS", "SPACE", "/", "-", "|", ",",
            "KEYBOARD", "PASTE", "FONT-", "FONT+"};
    private final Activity activity;
    private final View root;
    private final EditorDialog dialog;
    private final Runnable onSaved;
    private KeyLayout layout;
    private int selectedRow, selectedCol;
    private android.app.Dialog childDialog;

    static EditorDialog show(Activity activity, Runnable onSaved) {
        ExtraKeysEditor editor = new ExtraKeysEditor(activity, onSaved);
        androidx.lifecycle.LifecycleEventObserver lifecycle = (owner, event) -> {
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) editor.dialog.dismiss();
        };
        if (activity instanceof androidx.lifecycle.LifecycleOwner)
            ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().addObserver(lifecycle);
        editor.dialog.setOnDismissListener(d -> {
            if (editor.childDialog != null) editor.childDialog.dismiss();
            if (activity instanceof androidx.lifecycle.LifecycleOwner)
                ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().removeObserver(lifecycle);
        });
        editor.dialog.show();
        if (editor.dialog.getWindow() != null) {
            editor.dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            editor.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        return editor.dialog;
    }

    private ExtraKeysEditor(Activity activity, Runnable onSaved) {
        this.activity = activity;
        this.onSaved = onSaved;
        layout = ExtraKeys.getLayout(activity);
        root = activity.getLayoutInflater().inflate(R.layout.dialog_extra_keys, new android.widget.FrameLayout(activity), false);
        dialog = new EditorDialog(activity, root, () -> ExtraKeys.encode(layout));
        dialog.setContentView(root);
        root.findViewById(R.id.btn_cancel_extra_keys).setOnClickListener(v -> dialog.cancel());
        root.findViewById(R.id.btn_save_extra_keys).setOnClickListener(v -> {
            ExtraKeys.saveLayout(activity, layout);
            dialog.dismiss();
            if (onSaved != null) onSaved.run();
        });
        root.findViewById(R.id.btn_reset_extra_keys).setOnClickListener(v -> {
            layout = KeyLayout.defaults();
            selectedRow = selectedCol = 0;
            render();
        });
        root.findViewById(R.id.btn_add_key_row).setOnClickListener(v -> mutate(() -> {
            selectedRow = layout.addRow(); selectedCol = 0;
        }));
        root.findViewById(R.id.btn_custom_key_text).setOnClickListener(v -> customText());
        ChipGroup palette = root.findViewById(R.id.extra_keys_palette);
        for (String key : PALETTE) {
            Chip chip = new Chip(activity);
            chip.setText(ExtraKeys.label(key));
            chip.setTag(key);
            chip.setCheckable(true);
            chip.setContentDescription("替换为 " + key);
            chip.setOnClickListener(v -> mutate(() -> layout.replace(selectedRow, selectedCol, key)));
            palette.addView(chip);
        }
        render();
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private int color(int attribute) {
        TypedValue value = new TypedValue();
        activity.getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }
    private MaterialButton button(String text) {
        MaterialButton button = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setTextSize(12);
        button.setMaxLines(1);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return button;
    }
    private LinearLayout.LayoutParams slot() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private void render() {
        selectedRow = Math.max(0, Math.min(selectedRow, layout.rowCount() - 1));
        selectedCol = Math.max(0, Math.min(selectedCol, layout.size(selectedRow) - 1));
        LinearLayout preview = root.findViewById(R.id.extra_keys_preview);
        preview.removeAllViews();
        for (int r = 0; r < layout.rowCount(); r++) {
            final int rowIndex = r;
            LinearLayout header = new LinearLayout(activity);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(activity);
            title.setText("第 " + (r + 1) + " 行");
            title.setTypeface(null, Typeface.BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
            title.setGravity(Gravity.CENTER_VERTICAL);
            MaterialButton add = button("＋ 按键");
            add.setEnabled(layout.size(r) < KeyLayout.MAX_KEYS);
            add.setContentDescription("第 " + (r + 1) + " 行添加按键");
            add.setOnClickListener(v -> mutate(() -> {
                selectedRow = rowIndex; selectedCol = layout.addKey(rowIndex, "TAB");
            }));
            add.setOnDragListener((v, event) -> drag(event, rowIndex, -1, v));
            header.addView(add, new LinearLayout.LayoutParams(dp(88), dp(48)));
            ImageButton more = new ImageButton(activity);
            more.setImageResource(R.drawable.ic_more_vert);
            more.setColorFilter(color(com.google.android.material.R.attr.colorOnSurface));
            more.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            more.setContentDescription("第 " + (r + 1) + " 行操作");
            more.setOnClickListener(v -> rowActions(rowIndex));
            header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
            preview.addView(header);
            LinearLayout row = new LinearLayout(activity);
            for (int c = 0; c < layout.size(r); c++) {
                final int colIndex = c;
                String key = layout.key(r, c);
                MaterialButton keyButton = button(ExtraKeys.label(key));
                keyButton.setContentDescription("第 " + (r + 1) + " 行第 " + (c + 1) + " 键：" + key);
                boolean selected = r == selectedRow && c == selectedCol;
                keyButton.setStrokeWidth(selected ? dp(2) : dp(1));
                keyButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(selected ?
                        com.google.android.material.R.attr.colorPrimaryContainer : com.google.android.material.R.attr.colorSurfaceContainer)));
                keyButton.setTextColor(color(selected ? com.google.android.material.R.attr.colorOnPrimaryContainer :
                        com.google.android.material.R.attr.colorOnSurface));
                keyButton.setSelected(selected);
                keyButton.setOnClickListener(v -> { selectedRow = rowIndex; selectedCol = colIndex; render(); });
                keyButton.setOnLongClickListener(v -> {
                    return v.startDragAndDrop(ClipData.newPlainText("shortcut", ""), new View.DragShadowBuilder(v),
                            new DragKey(this, rowIndex, colIndex), 0);
                });
                keyButton.setOnDragListener((v, event) -> drag(event, rowIndex, colIndex, v));
                row.addView(keyButton, slot());
            }
            preview.addView(row);
        }
        root.findViewById(R.id.btn_add_key_row).setEnabled(layout.rowCount() < KeyLayout.MAX_ROWS);
        ChipGroup palette = root.findViewById(R.id.extra_keys_palette);
        for (int i = 0; i < palette.getChildCount(); i++) {
            Chip key = (Chip) palette.getChildAt(i);
            key.setChecked(layout.key(selectedRow, selectedCol).equals(key.getTag()));
        }
        ((TextView) root.findViewById(R.id.extra_keys_selection)).setText("正在编辑：第 " + (selectedRow + 1) +
                " 行 · " + ExtraKeys.label(layout.key(selectedRow, selectedCol)));
        LinearLayout actions = root.findViewById(R.id.extra_keys_move_actions);
        actions.removeAllViews();
        action(actions, "←", "向左移动", selectedCol > 0,
                () -> move(selectedRow, selectedCol - 1));
        action(actions, "→", "向右移动", selectedCol + 1 < layout.size(selectedRow),
                () -> move(selectedRow, selectedCol + 2));
        action(actions, "↑", "移到上一行", selectedRow > 0,
                () -> move(selectedRow - 1, layout.size(selectedRow - 1)));
        action(actions, "↓", "移到下一行", selectedRow + 1 < layout.rowCount(),
                () -> move(selectedRow + 1, layout.size(selectedRow + 1)));
        action(actions, "删除", "删除当前按键", layout.rowCount() > 1 || layout.size(0) > 1,
                () -> layout.remove(selectedRow, selectedCol));
    }

    private void action(LinearLayout row, String text, String description, boolean enabled, Runnable action) {
        MaterialButton button = button(text);
        button.setContentDescription(description);
        button.setEnabled(enabled);
        button.setOnClickListener(v -> mutate(action));
        row.addView(button, slot());
    }
    private void move(int row, int col) {
        int[] selected = layout.move(selectedRow, selectedCol, row, col);
        selectedRow = selected[0]; selectedCol = selected[1];
    }
    private void mutate(Runnable action) {
        try { action.run(); render(); }
        catch (IllegalArgumentException e) { Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }
    private boolean drag(DragEvent event, int row, int col, View target) {
        if (!(event.getLocalState() instanceof DragKey)) return false;
        DragKey key = (DragKey) event.getLocalState();
        if (key.editor != this) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED: target.setAlpha(.55f); break;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED: target.setAlpha(1f); break;
            case DragEvent.ACTION_DROP:
                target.setAlpha(1f);
                mutate(() -> {
                    selectedRow = key.row; selectedCol = key.col;
                    int destination = col < 0 ? layout.size(row) : col;
                    if (col >= 0 && row == key.row && key.col < col) destination++;
                    move(row, destination);
                });
                break;
            default: break;
        }
        return true;
    }
    private static final class DragKey {
        final ExtraKeysEditor editor;
        final int row, col;
        DragKey(ExtraKeysEditor editor, int row, int col) { this.editor = editor; this.row = row; this.col = col; }
    }
    private void rowActions(int row) {
        childDialog = new MaterialAlertDialogBuilder(activity).setTitle("第 " + (row + 1) + " 行")
                .setItems(new String[]{"上移一行", "下移一行", "删除此行"}, (d, which) -> mutate(() -> {
                    if (which == 2) layout.removeRow(row);
                    else {
                        int next = row + (which == 0 ? -1 : 1);
                        if (next < 0 || next >= layout.rowCount()) throw new IllegalArgumentException("已经到达边界");
                        layout.swapRows(row, next);
                        selectedRow = next;
                    }
                })).show();
    }
    private void customText() {
        TextInputLayout field = new TextInputLayout(activity);
        field.setHint("发送的文字或符号");
        TextInputEditText text = new TextInputEditText(activity);
        text.setSingleLine(true);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(KeyLayout.MAX_TEXT * 2)});
        field.addView(text);
        LinearLayout padding = new LinearLayout(activity);
        padding.setPadding(dp(24), dp(8), dp(24), 0);
        padding.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        androidx.appcompat.app.AlertDialog edit = new MaterialAlertDialogBuilder(activity).setTitle("自定义按键")
                .setView(padding).setNegativeButton("取消", null).setPositiveButton("替换", null).create();
        edit.setOnShowListener(d -> edit.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                layout.replace(selectedRow, selectedCol, text.getText() == null ? "" : text.getText().toString());
                edit.dismiss(); render();
            } catch (IllegalArgumentException e) { field.setError(e.getMessage()); }
        }));
        edit.show();
        childDialog = edit;
    }
}

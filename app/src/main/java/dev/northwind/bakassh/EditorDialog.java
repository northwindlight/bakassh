package dev.northwind.bakassh;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** 全屏编辑页；返回时保护未保存的表单，提交期间阻止重复操作。 */
final class EditorDialog extends Dialog {
    private final View content;
    private String initialState;
    private boolean busy;
    private final java.util.function.Supplier<String> state;

    EditorDialog(Context context, View content) {
        this(context, content, null);
    }

    EditorDialog(Context context, View content, java.util.function.Supplier<String> state) {
        super(context, R.style.EditorOverlay);
        this.content = content;
        this.state = state;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        initialState = currentState();
    }

    void setBusy(boolean busy) {
        this.busy = busy;
    }

    @Override public void cancel() {
        if (busy) return;
        if (initialState != null && !initialState.equals(currentState())) {
            new MaterialAlertDialogBuilder(getContext())
                    .setTitle("放弃未保存的更改？")
                    .setNegativeButton("继续编辑", null)
                    .setPositiveButton("放弃", (d, w) -> dismiss())
                    .show();
        } else {
            super.cancel();
        }
    }

    @Override public void onBackPressed() { cancel(); }

    /** 在输入框所属的 TextInputLayout 下方显示错误，修改内容后自动清除。 */
    static void fieldError(EditText field, String message) {
        android.view.ViewParent parent = field.getParent();
        while (parent != null && !(parent instanceof com.google.android.material.textfield.TextInputLayout))
            parent = parent.getParent();
        if (parent == null) { field.setError(message); field.requestFocus(); return; }
        com.google.android.material.textfield.TextInputLayout layout =
                (com.google.android.material.textfield.TextInputLayout) parent;
        layout.setError(message);
        field.requestFocus();
        if (field.getTag(R.id.tag_error_watcher) == null) {
            field.setTag(R.id.tag_error_watcher, Boolean.TRUE);
            field.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (layout.getError() != null) layout.setError(null);
                }
                @Override public void afterTextChanged(android.text.Editable s) { }
            });
        }
    }

    private String currentState() { return state == null ? snapshot(content) : state.get(); }

    private static String snapshot(View view) {
        if (view instanceof EditText) return ((EditText) view).getText().toString() + '\u0000';
        if (view instanceof MaterialButtonToggleGroup)
            return "auth:" + ((MaterialButtonToggleGroup) view).getCheckedButtonId();
        int id = view.getId();
        if (id == R.id.tv_selected_key_name || id == R.id.tv_gen_key_type || id == R.id.tv_import_file)
            return ((TextView) view).getText().toString();
        StringBuilder result = new StringBuilder();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result.append(snapshot(group.getChildAt(i)));
        }
        return result.toString();
    }
}

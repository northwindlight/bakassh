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

    EditorDialog(Context context, View content) {
        super(context, R.style.EditorOverlay);
        this.content = content;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        initialState = snapshot(content);
    }

    void setBusy(boolean busy) {
        this.busy = busy;
    }

    @Override public void cancel() {
        if (busy) return;
        if (initialState != null && !initialState.equals(snapshot(content))) {
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

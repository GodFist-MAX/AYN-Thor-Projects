package androidx.preference;

import android.content.Context;
import android.widget.EditText;

public class EditTextPreference extends DialogPreference {
    public interface OnBindEditTextListener {
        void onBindEditText(EditText editText);
    }

    private String text;
    private OnBindEditTextListener bindListener;

    public EditTextPreference(Context context) {
        super(context);
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
        if (store != null && hasKey()) store.edit().putString(getKey(), text).apply();
    }

    public void setOnBindEditTextListener(OnBindEditTextListener listener) {
        this.bindListener = listener;
    }

    public OnBindEditTextListener getOnBindEditTextListener() {
        return bindListener;
    }

    @Override void onAttached() {
        Object def = getDefaultValue();
        text = store != null && hasKey() ? store.getString(getKey(), def == null ? null : def.toString()) : (def == null ? null : def.toString());
    }

    @Override void apply(Object v) {
        setText(v == null ? null : v.toString());
    }
}

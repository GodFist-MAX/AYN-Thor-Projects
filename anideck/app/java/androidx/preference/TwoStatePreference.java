package androidx.preference;

import android.content.Context;

public abstract class TwoStatePreference extends Preference {
    private boolean checked;
    private CharSequence summaryOn;
    private CharSequence summaryOff;

    public TwoStatePreference(Context context) {
        super(context);
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
        if (store != null && hasKey()) store.edit().putBoolean(getKey(), checked).apply();
    }

    public void setSummaryOn(CharSequence s) {
        summaryOn = s;
    }

    public void setSummaryOff(CharSequence s) {
        summaryOff = s;
    }

    @Override public CharSequence getSummary() {
        if (checked && summaryOn != null) return summaryOn;
        if (!checked && summaryOff != null) return summaryOff;
        return super.getSummary();
    }

    @Override void onAttached() {
        Object def = getDefaultValue();
        boolean fallback = def instanceof Boolean && (Boolean) def;
        checked = store != null && hasKey() ? store.getBoolean(getKey(), fallback) : fallback;
    }

    @Override void apply(Object v) {
        setChecked(Boolean.TRUE.equals(v));
    }
}

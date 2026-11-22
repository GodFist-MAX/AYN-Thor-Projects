package androidx.preference;

import android.content.Context;

public class ListPreference extends DialogPreference {
    private CharSequence[] entries = new CharSequence[0];
    private CharSequence[] entryValues = new CharSequence[0];
    private String value;

    public ListPreference(Context context) {
        super(context);
    }

    public CharSequence[] getEntries() {
        return entries;
    }

    public void setEntries(CharSequence[] entries) {
        this.entries = entries == null ? new CharSequence[0] : entries;
    }

    public CharSequence[] getEntryValues() {
        return entryValues;
    }

    public void setEntryValues(CharSequence[] values) {
        this.entryValues = values == null ? new CharSequence[0] : values;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
        if (store != null && hasKey()) store.edit().putString(getKey(), value).apply();
    }

    public int findIndexOfValue(String value) {
        if (value == null) return -1;
        for (int i = entryValues.length - 1; i >= 0; i--) {
            if (entryValues[i] != null && entryValues[i].toString().equals(value)) return i;
        }
        return -1;
    }

    public CharSequence getEntry() {
        int i = findIndexOfValue(value);
        return i >= 0 && i < entries.length ? entries[i] : null;
    }

    @Override public CharSequence getSummary() {
        CharSequence s = super.getSummary();
        if (s == null) return null;
        CharSequence entry = getEntry();
        return s.toString().contains("%s") ? String.format(s.toString(), entry == null ? "" : entry) : s;
    }

    @Override void onAttached() {
        Object def = getDefaultValue();
        value = store != null && hasKey() ? store.getString(getKey(), def == null ? null : def.toString()) : (def == null ? null : def.toString());
    }

    @Override void apply(Object v) {
        setValue(v == null ? null : v.toString());
    }
}

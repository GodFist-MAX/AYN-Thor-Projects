package androidx.preference;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

public class MultiSelectListPreference extends DialogPreference {
    private CharSequence[] entries = new CharSequence[0];
    private CharSequence[] entryValues = new CharSequence[0];
    private Set<String> values = new HashSet<>();

    public MultiSelectListPreference(Context context) {
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

    public Set<String> getValues() {
        return values;
    }

    public void setValues(Set<String> values) {
        this.values = values == null ? new HashSet<>() : new HashSet<>(values);
        if (store != null && hasKey()) store.edit().putStringSet(getKey(), this.values).apply();
    }

    public int findIndexOfValue(String value) {
        if (value == null) return -1;
        for (int i = entryValues.length - 1; i >= 0; i--) {
            if (entryValues[i] != null && entryValues[i].toString().equals(value)) return i;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    @Override void onAttached() {
        Object def = getDefaultValue();
        Set<String> fallback = def instanceof Set ? (Set<String>) def : new HashSet<>();
        Set<String> saved = store != null && hasKey() ? store.getStringSet(getKey(), fallback) : fallback;
        values = saved == null ? new HashSet<>() : new HashSet<>(saved);
    }

    @SuppressWarnings("unchecked")
    @Override void apply(Object v) {
        setValues(v instanceof Set ? (Set<String>) v : new HashSet<>());
    }
}

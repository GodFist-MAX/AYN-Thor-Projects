package androidx.preference;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The part of AndroidX Preference that extension settings screens use. AniDeck draws the screen
 * itself; these objects only hold what the extension set up and save the values it chooses.
 */
public class Preference {
    public interface OnPreferenceChangeListener {
        boolean onPreferenceChange(Preference preference, Object newValue);
    }

    private final Context context;
    private String key;
    private CharSequence title;
    private CharSequence summary;
    private Object defaultValue;
    private boolean enabled = true;
    private boolean visible = true;
    private OnPreferenceChangeListener changeListener;
    SharedPreferences store;

    public Preference(Context context) {
        this.context = context;
    }

    public Context getContext() {
        return context;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public CharSequence getTitle() {
        return title;
    }

    public void setTitle(CharSequence title) {
        this.title = title;
    }

    public void setTitle(int resId) {
        this.title = context.getString(resId);
    }

    public CharSequence getSummary() {
        return summary;
    }

    public void setSummary(CharSequence summary) {
        this.summary = summary;
    }

    public void setSummary(int resId) {
        this.summary = context.getString(resId);
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object value) {
        this.defaultValue = value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setOnPreferenceChangeListener(OnPreferenceChangeListener listener) {
        this.changeListener = listener;
    }

    public OnPreferenceChangeListener getOnPreferenceChangeListener() {
        return changeListener;
    }

    public boolean callChangeListener(Object newValue) {
        return changeListener == null || changeListener.onPreferenceChange(this, newValue);
    }

    public SharedPreferences getSharedPreferences() {
        return store;
    }

    public boolean hasKey() {
        return key != null && !key.isEmpty();
    }

    /** AniDeck's settings screen picked a value: the extension's listener can refuse it, else it's saved. */
    public final boolean choose(Object value) {
        if (!callChangeListener(value)) return false;
        apply(value);
        return true;
    }

    public final void reload() {
        if (store != null) onAttached();
    }

    void attach(SharedPreferences store) {
        this.store = store;
        onAttached();
    }

    /** Reads the saved value, or the default when nothing is saved yet. */
    void onAttached() {
    }

    /** Applies a value the user picked, after the extension's listener accepted it. */
    void apply(Object value) {
    }
}

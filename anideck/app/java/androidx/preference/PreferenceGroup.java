package androidx.preference;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public abstract class PreferenceGroup extends Preference {
    private final List<Preference> children = new ArrayList<>();

    public PreferenceGroup(Context context) {
        super(context);
    }

    public boolean addPreference(Preference p) {
        if (children.contains(p)) return true;
        children.add(p);
        SharedPreferences s = getSharedPreferences();
        if (s != null) p.attach(s);
        return true;
    }

    public boolean removePreference(Preference p) {
        return children.remove(p);
    }

    public void removeAll() {
        children.clear();
    }

    public int getPreferenceCount() {
        return children.size();
    }

    public Preference getPreference(int index) {
        return children.get(index);
    }

    @SuppressWarnings("unchecked")
    public <T extends Preference> T findPreference(CharSequence key) {
        if (key == null) return null;
        for (Preference p : children) {
            if (key.toString().equals(p.getKey())) return (T) p;
            if (p instanceof PreferenceGroup) {
                Preference found = ((PreferenceGroup) p).findPreference(key);
                if (found != null) return (T) found;
            }
        }
        return null;
    }

    @Override void onAttached() {
        for (Preference p : children) p.attach(store);
    }
}

package androidx.preference;

import android.content.Context;
import android.content.SharedPreferences;

public final class PreferenceScreen extends PreferenceGroup {
    public PreferenceScreen(Context context, SharedPreferences store) {
        super(context);
        attach(store);
    }
}

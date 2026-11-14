package dev.anideck.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Whether the device has working internet (a validated default network), with change listeners. */
final class Net extends ConnectivityManager.NetworkCallback {
    interface Listener {
        void onNet(boolean online);
    }

    /** A drop has to last this long before anything pauses; Wi-Fi roaming blips are ignored. */
    private static final long DROP_MS = 4000;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Runnable goOffline = () -> set(false);
    private volatile boolean online;

    Net(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        online = usable(cm.getNetworkCapabilities(cm.getActiveNetwork()));
        cm.registerDefaultNetworkCallback(this, App.I.main);
    }

    boolean online() {
        return online;
    }

    void listen(Listener l) {
        listeners.add(l);
    }

    void unlisten(Listener l) {
        listeners.remove(l);
    }

    private static boolean usable(NetworkCapabilities c) {
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
        update(usable(caps));
    }

    @Override public void onLost(Network n) {
        update(false);
    }

    private void update(boolean usable) {
        App.I.main.removeCallbacks(goOffline);
        if (usable) set(true);
        else if (online) App.I.main.postDelayed(goOffline, DROP_MS);
    }

    private void set(boolean on) {
        if (on == online) return;
        online = on;
        Log.i(App.TAG, on ? "internet is back" : "internet lost");
        for (Listener l : listeners) l.onNet(on);
    }
}

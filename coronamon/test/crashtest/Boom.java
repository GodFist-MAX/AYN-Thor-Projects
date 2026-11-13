package dev.grokbuild.cototest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Crashes on purpose so Coronamon's crash watch can be tested without taking over a screen. */
public class Boom extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        throw new IllegalStateException("Coronamon test crash: the oven exploded");
    }
}

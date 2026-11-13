package dev.grokbuild.coronamon;

final class Log {
    static final String TAG = "Coronamon";

    private Log() {
    }

    static void i(String s) {
        android.util.Log.i(TAG, s);
    }

    static void w(String s) {
        android.util.Log.w(TAG, s);
    }

    static void w(String s, Throwable t) {
        android.util.Log.w(TAG, s, t);
    }
}

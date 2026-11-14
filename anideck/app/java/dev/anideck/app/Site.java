package dev.anideck.app;

import android.net.Uri;

import java.util.Locale;

/** The streaming site: which hosts belong to it and how its pages are addressed. */
final class Site {
    /**
     * Miruro's mirrors share one app but each has its own AniList login client and storage,
     * so the app stays on this one.
     */
    private static final String BASE = "https://www.miruro.cx";
    private static final String[] HOSTS = {"miruro.cx"};
    /** The site's login goes through AniList's OAuth pages. */
    private static final String[] LOGIN_HOSTS = {"anilist.co"};

    private Site() {
    }

    static String base() {
        return BASE;
    }

    static String url(String path) {
        return base() + (path.startsWith("/") ? path : "/" + path);
    }

    static String search(String query) {
        return url("/search?query=" + Uri.encode(query));
    }

    static boolean isSite(String host) {
        return matches(host, HOSTS);
    }

    static boolean isLogin(String host) {
        return matches(host, LOGIN_HOSTS);
    }

    /** Pages the top screen may open. Everything else (ads, redirects, app links) is blocked. */
    static boolean allowed(Uri u) {
        if ("about:blank".equals(u.toString())) return true;
        String scheme = u.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
        return isSite(u.getHost()) || matches(u.getHost(), LOGIN_HOSTS);
    }

    private static boolean matches(String host, String[] list) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String h : list) {
            if (h != null && (host.equals(h) || host.endsWith("." + h))) return true;
        }
        return false;
    }
}

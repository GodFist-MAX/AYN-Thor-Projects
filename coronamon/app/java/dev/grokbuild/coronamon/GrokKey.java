package dev.grokbuild.coronamon;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The xAI API key for his Grok brain. It lives only here: in app-private storage (not backed up), encrypted
 * with an AES key that never leaves the Thor's keystore. Never log it, return it or write it anywhere else;
 * the app's source is public on GitHub.
 */
final class GrokKey {
    private static final String ALIAS = "coronamon-grok-key";
    private static final String FILE = "grok_key.bin";
    private static volatile String cached;

    private GrokKey() {
    }

    static boolean looksValid(String k) {
        return k != null && k.matches("xai-[A-Za-z0-9_-]{20,300}");
    }

    static synchronized void set(Context ctx, String key) throws Exception {
        key = key == null ? "" : key.trim();
        if (!looksValid(key)) throw new IllegalArgumentException("That doesn't look like an xAI API key (they start with xai-)");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, secret());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(key.getBytes(StandardCharsets.UTF_8));
        ByteBuffer b = ByteBuffer.allocate(1 + iv.length + ct.length);
        b.put((byte) iv.length).put(iv).put(ct);
        File f = file(ctx), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(b.array());
        }
        if (!tmp.renameTo(f)) throw new IOException("couldn't save the key");
        cached = key;
    }

    static synchronized String get(Context ctx) {
        if (cached != null) return cached;
        File f = file(ctx);
        if (!f.isFile()) return null;
        try {
            byte[] all = Files.readAllBytes(f.toPath());
            int n = all[0];
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, all, 1, n));
            cached = new String(c.doFinal(all, 1 + n, all.length - 1 - n), StandardCharsets.UTF_8);
            return cached;
        } catch (Exception e) {
            Log.w("grok key unreadable: " + e.getClass().getSimpleName());
            return null;
        }
    }

    static synchronized void clear(Context ctx) {
        cached = null;
        file(ctx).delete();
    }

    static boolean has(Context ctx) {
        return get(ctx) != null;
    }

    /** "xai-…AbCd": enough to tell keys apart without showing one. */
    static String hint(Context ctx) {
        String k = get(ctx);
        return k == null ? null : "xai-…" + k.substring(k.length() - 4);
    }

    /** Takes anything shaped like an xAI key out of text before it's logged, saved or shown. */
    static String scrub(String s) {
        return s == null ? null : s.replaceAll("xai-[A-Za-z0-9_-]{8,}", "xai-…");
    }

    private static SecretKey secret() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }
}

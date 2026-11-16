package dev.grokbuild.coronamon;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import rikka.shizuku.Shizuku;

/**
 * Keeps Coronamon alive: a foreground service that owns his overlay windows on both screens, runs
 * the animation loop, and turns jobs, crashes, updates and app launches into reactions.
 */
public final class PetService extends Service implements Brain.Host, PetView.Touch, Choreographer.FrameCallback,
        DisplayManager.DisplayListener {
    static volatile PetService instance;

    static final String CH_PET = "pet", CH_EVENTS = "events";
    static final int NOTIF = 1;
    static final String ACTION_SWITCH = "dev.grokbuild.coronamon.SWITCH";
    static final String ACTION_TOGGLE = "dev.grokbuild.coronamon.TOGGLE";
    static final String ACTION_UPDATE = "dev.grokbuild.coronamon.UPDATE";

    Settings settings;
    Lines lines;
    Registry registry;
    History history;
    Bugs bugs;
    Brain brain;
    CrashWatch crashWatch;
    Foreground foreground;
    Updates updates;
    Mac mac;
    Diagnostics diag;
    final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();

    private final List<Brain.Stage> stages = new ArrayList<>();
    private final List<Win> wins = new ArrayList<>();
    private boolean visible = true, ghost, looping;
    private long hideUntil;
    private long lastFrameNs;
    private Map<Integer, String> topApps;
    private int notifStage = -1;
    private boolean notifVisible = true;

    private Lines.Line speaking;
    private long speakUntil;
    private Brain.Prompt prompt;
    private int bubbleStage = -1;
    private long lastStatusWrite;
    private boolean shizukuNagged;
    /** The package an install job is running for; its package broadcasts are the job's, not news. */
    private String jobPkg;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri d = i.getData();
            String pkg = d == null ? null : d.getSchemeSpecificPart();
            String a = i.getAction();
            boolean replacing = i.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            if (pkg == null || Registry.SELF.equals(pkg)) return;
            if (Intent.ACTION_PACKAGE_REMOVED.equals(a)) {
                if (!replacing && registry.has(pkg)) history.add("uninstall", pkg, registry.label(pkg), true, null, null);
                return;
            }
            if (Intent.ACTION_PACKAGE_ADDED.equals(a) && replacing) return;
            boolean fresh = Intent.ACTION_PACKAGE_ADDED.equals(a);
            main.postDelayed(() -> outsideInstall(pkg, fresh), 1500);
        }
    };

    private final class Win {
        final Brain.Stage stage;
        final Context ctx;
        final WindowManager wm;
        final PetView view;
        final WindowManager.LayoutParams lp;
        boolean attached;
        Bubble bubble;
        WindowManager.LayoutParams blp;
        boolean bubbleAttached;

        Win(Brain.Stage stage, Context ctx) {
            this.stage = stage;
            this.ctx = ctx;
            wm = ctx.getSystemService(WindowManager.class);
            view = new PetView(ctx);
            view.actor = brain.actor;
            view.touch = PetService.this;
            lp = overlayParams(10, 10);
        }
    }

    private static WindowManager.LayoutParams overlayParams(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("Coronamon");
        return lp;
    }

    // ---- lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        settings = new Settings(this);
        lines = new Lines();
        lines.load(this);
        registry = new Registry(this);
        history = new History(this);
        for (File d : new File[]{Paths.ROOT, Paths.INBOX, Paths.APKS, Paths.REPORTS, Paths.DOWNLOADS, Bugs.FILES}) d.mkdirs();
        bugs = new Bugs(this);
        mac = new Mac(this);
        diag = new Diagnostics(this);
        channels();
        startForeground(NOTIF, petNotification());
        brain = new Brain(this);
        visible = settings.visible();
        buildStages();
        int first = startStage();
        brain.place(first, stages.get(first).w * (0.3f + rnd.nextFloat() * 0.4f));
        getSystemService(DisplayManager.class).registerDisplayListener(this, main);
        foreground = new Foreground(this, registry, main, this::onTopApps);
        foreground.start();
        crashWatch = new CrashWatch(this, registry, bugs, new CrashWatch.OnScreen() {
            @Override
            public JSONObject now() {
                return screensNow();
            }

            @Override
            public boolean recently(String pkg, long ms) {
                return foreground.recentlyOnScreen(pkg, ms);
            }
        }, main, this::onCrash);
        crashWatch.start();
        updates = new Updates(this, registry, main, updateListener);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf, RECEIVER_EXPORTED);
        main.postDelayed(updateTick, 3 * 60_000L);
        main.postDelayed(this::checkShizuku, 2 * 60_000L);
        try {
            Shizuku.addRequestPermissionResultListener((code, result) -> {
                if (code == Shell.REQUEST) {
                    history.add("shizuku", null, "Shizuku", result == PackageManager.PERMISSION_GRANTED, null, null);
                    say(new Lines.Line("", result == PackageManager.PERMISSION_GRANTED
                            ? "Shizuku said yes! Now I can install and fix things without bugging you."
                            : "Shizuku said no... That's okay, Grok can still help over USB."), null, null);
                }
            }, main);
        } catch (Throwable t) {
            Log.w("shizuku listener: " + t);
        }
        if (!selfUpdated()) brain.greet();
        history.add("start", Registry.SELF, "Coronamon", true, null, version());
        startLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_SWITCH.equals(a)) {
            if (!visible) setVisible(true);
            summon(brain.st == 0 ? 1 : 0);
        } else if (ACTION_TOGGLE.equals(a)) {
            setVisible(!visible);
        } else if (ACTION_UPDATE.equals(a)) {
            String pkg = intent.getStringExtra("pkg");
            if (pkg != null) updates.fetch(pkg);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopLoop();
        for (Win w : wins) {
            detach(w);
            detachBubble(w);
        }
        getSystemService(DisplayManager.class).unregisterDisplayListener(this);
        unregisterReceiver(packages);
        crashWatch.stop();
        foreground.quit();
        updates.quit();
        main.removeCallbacksAndMessages(null);
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, PetService.class));
    }

    String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // ---- stages and windows ----

    private void buildStages() {
        for (Win w : wins) {
            detach(w);
            detachBubble(w);
        }
        stages.clear();
        wins.clear();
        Display top = Screens.top(this), bottom = Screens.bottom(this);
        for (Display d : new Display[]{top, bottom}) {
            if (d == null) continue;
            Brain.Stage s = new Brain.Stage();
            s.displayId = d.getDisplayId();
            s.name = Screens.name(s.displayId);
            Context wctx = createWindowContext(d, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            Win w = new Win(s, wctx);
            measure(w, d);
            stages.add(s);
            wins.add(w);
        }
    }

    private void measure(Win w, Display d) {
        Rect b = w.wm.getMaximumWindowMetrics().getBounds();
        w.stage.w = b.width();
        w.stage.h = b.height();
        int st = d.getState();
        w.stage.on = st == Display.STATE_ON || st == Display.STATE_ON_SUSPEND || st == Display.STATE_VR;
        float dpi = w.ctx.getResources().getDisplayMetrics().densityDpi;
        w.stage.k = settings.size() * dpi / 369f * 0.95f;
    }

    private int startStage() {
        String pref = settings.screenPref();
        int want = pref.equals("bottom") && stages.size() > 1 ? 1 : 0;
        if (pref.equals("auto") && stages.size() > 1 && rnd.nextFloat() < 0.35f) want = 1;
        if (!stages.get(want).on && stages.size() > 1) want = 1 - want;
        return want;
    }

    private boolean showing() {
        if (!visible || System.currentTimeMillis() < hideUntil) return false;
        if (ghost && "hide".equals(settings.games())) return false;
        return brain.stage().on;
    }

    private void render() {
        boolean show = showing();
        for (int i = 0; i < wins.size(); i++) {
            Win w = wins.get(i);
            if (show && i == brain.st) {
                if (!w.attached) attach(w);
                position(w);
                w.view.invalidate();
            } else if (w.attached) {
                detach(w);
            }
        }
        updateBubble(show);
        long now = System.currentTimeMillis();
        if (now - lastStatusWrite > 30_000) {
            lastStatusWrite = now;
            try {
                Util.writeText(Paths.STATUS_MIRROR, status().toString(2));
            } catch (Exception ignored) {
            }
        }
        if (brain.st != notifStage || visible != notifVisible) refreshNotification();
    }

    private void attach(Win w) {
        float k = w.stage.k;
        w.lp.width = (int) (PetView.WIN_W * k);
        w.lp.height = (int) (PetView.WIN_H * k);
        w.lp.x = Math.round(brain.x - PetView.ROOT_X * k);
        w.lp.y = Math.round(brain.y - PetView.ROOT_Y * k);
        applyGhost(w.lp);
        w.view.scale = k;
        try {
            w.wm.addView(w.view, w.lp);
            w.attached = true;
        } catch (Exception e) {
            Log.w("addView on " + w.stage.name + ": " + e);
        }
    }

    private void detach(Win w) {
        if (!w.attached) return;
        try {
            w.wm.removeViewImmediate(w.view);
        } catch (Exception ignored) {
        }
        w.attached = false;
    }

    private void position(Win w) {
        float k = w.stage.k;
        int px = Math.round(brain.x - PetView.ROOT_X * k), py = Math.round(brain.y - PetView.ROOT_Y * k);
        int ww = (int) (PetView.WIN_W * k), hh = (int) (PetView.WIN_H * k);
        w.view.scale = k;
        if (px != w.lp.x || py != w.lp.y || ww != w.lp.width || hh != w.lp.height) {
            w.lp.x = px;
            w.lp.y = py;
            w.lp.width = ww;
            w.lp.height = hh;
            try {
                w.wm.updateViewLayout(w.view, w.lp);
            } catch (Exception e) {
                w.attached = false;
            }
        }
    }

    /**
     * Ghost windows let touches through to the app below only while the combined opacity of his
     * windows at a point stays at or under 0.8: 1 - (1 - 0.45) * (1 - 0.62) = 0.79.
     */
    private void applyGhost(WindowManager.LayoutParams lp) {
        if (ghost) {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.alpha = lp.getTitle().toString().endsWith("speech") ? 0.62f : 0.45f;
        } else {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.alpha = 1f;
        }
    }

    // ---- frame loop ----

    private void startLoop() {
        if (looping) return;
        looping = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void stopLoop() {
        looping = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long ns) {
        if (!looping) return;
        if (lastFrameNs == 0) {
            lastFrameNs = ns;
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        float dt = (ns - lastFrameNs) / 1e9f;
        lastFrameNs = ns;
        try {
            brain.tick(Math.min(dt, 0.06f));
            render();
        } catch (Exception e) {
            Log.w("frame", e);
        }
        boolean fast = brain.fast() || speaking != null;
        boolean slow = brain.asleep() && speaking == null;
        if (fast) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, slow ? 55 : 22);
    }

    // ---- displays ----

    @Override
    public void onDisplayAdded(int id) {
        if (Screens.bottom(this) != null && stages.size() < 2) rebuild();
    }

    @Override
    public void onDisplayRemoved(int id) {
        for (Brain.Stage s : stages) if (s.displayId == id) rebuild();
    }

    @Override
    public void onDisplayChanged(int id) {
        DisplayManager dm = getSystemService(DisplayManager.class);
        for (Win w : wins) {
            if (w.stage.displayId != id) continue;
            Display d = dm.getDisplay(id);
            if (d == null) continue;
            boolean wasOn = w.stage.on;
            measure(w, d);
            if (w.stage == brain.stage()) brain.clampToStage();
            if (wasOn != w.stage.on) displayPower();
        }
    }

    private void rebuild() {
        int oldDisplay = brain.stage().displayId;
        buildStages();
        int idx = 0;
        for (int i = 0; i < stages.size(); i++) if (stages.get(i).displayId == oldDisplay) idx = i;
        brain.place(idx, brain.x);
    }

    private void displayPower() {
        boolean any = false;
        for (Brain.Stage s : stages) any |= s.on;
        if (!any) {
            stopLoop();
            foreground.stop();
            return;
        }
        if (!brain.stage().on) {
            for (int i = 0; i < stages.size(); i++) if (stages.get(i).on) brain.place(i, stages.get(i).w / 2);
        }
        foreground.start();
        startLoop();
    }

    void sizeChanged() {
        for (Win w : wins) {
            Display d = getSystemService(DisplayManager.class).getDisplay(w.stage.displayId);
            if (d != null) measure(w, d);
        }
        brain.clampToStage();
    }

    // ---- Brain.Host ----

    @Override
    public int stageCount() {
        return stages.size();
    }

    @Override
    public Brain.Stage stage(int i) {
        return stages.get(Math.max(0, Math.min(stages.size() - 1, i)));
    }

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public Lines lines() {
        lines.refresh(this);
        return lines;
    }

    @Override
    public boolean speaking() {
        return speaking != null;
    }

    @Override
    public void stageChanged(int from, int to) {
        if (from >= 0 && from < wins.size()) detach(wins.get(from));
    }

    @Override
    public void ghostChanged(boolean g) {
        ghost = g;
        for (Win w : wins) {
            applyGhost(w.lp);
            if (w.attached) {
                try {
                    w.wm.updateViewLayout(w.view, w.lp);
                } catch (Exception ignored) {
                }
            }
            if (w.blp != null) {
                applyGhost(w.blp);
                if (w.bubbleAttached) {
                    try {
                        w.wm.updateViewLayout(w.bubble, w.blp);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    @Override
    public void say(Lines.Line line, String[] buttons, Brain.Prompt p) {
        if (line == null) return;
        if (speaking != null && prompt != null && p == null) return;
        if (prompt != null) {
            Brain.Prompt old = prompt;
            prompt = null;
            old.chose(-1);
        }
        speaking = line;
        prompt = p;
        speakUntil = 0;
        String src = settings.showSources() && "ts".equals(line.src) ? "Digimon Story: Time Stranger" : null;
        Win w = wins.get(brain.st);
        ensureBubble(w);
        if (bubbleStage >= 0 && bubbleStage != brain.st) detachBubble(wins.get(bubbleStage));
        w.bubble.show(line.text, src, buttons);
        bubbleStage = brain.st;
        Log.i("says: " + line.text);
    }

    private void ensureBubble(Win w) {
        if (w.bubble != null) return;
        w.bubble = new Bubble(w.ctx);
        w.bubble.listener = new Bubble.Listener() {
            @Override
            public void chose(int index) {
                endSpeech(index);
            }

            @Override
            public void dismissed() {
                endSpeech(-1);
            }
        };
        w.blp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        w.blp.setTitle("Coronamon speech");
    }

    private void updateBubble(boolean show) {
        if (speaking == null) {
            if (bubbleStage >= 0) detachBubble(wins.get(bubbleStage));
            bubbleStage = -1;
            brain.actor.talking = false;
            return;
        }
        if (bubbleStage != brain.st) {
            if (bubbleStage >= 0) {
                Win old = wins.get(bubbleStage);
                detachBubble(old);
                Win w = wins.get(brain.st);
                ensureBubble(w);
                if (old.bubble != null && prompt == null) {
                    endSpeech(-1);
                    return;
                }
                w.bubble.show(speaking.text, null, null);
            }
            bubbleStage = brain.st;
        }
        Win w = wins.get(bubbleStage);
        Bubble b = w.bubble;
        brain.actor.talking = b.typing();
        long now = System.currentTimeMillis();
        if (!b.typing() && speakUntil == 0) speakUntil = now + (b.hasButtons() ? 30_000 : b.readTime());
        if (speakUntil > 0 && now > speakUntil) {
            endSpeech(-1);
            return;
        }
        if (!show) {
            detachBubble(w);
            return;
        }
        b.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = b.getMeasuredWidth(), bh = b.getMeasuredHeight();
        Brain.Stage s = w.stage;
        float k = s.k;
        int bx = (int) Math.max(6, Math.min(s.w - bw - 6, brain.x - bw / 2f));
        float headTop = brain.y - (232 + brain.actor.pose.v[Pose.LIFT]) * k;
        int by = (int) Math.max(6, headTop - bh + 8 * k);
        float tail = brain.x - bx;
        if (Math.abs(tail - b.tailX) > 1) {
            b.tailX = tail;
            b.invalidate();
        }
        if (!w.bubbleAttached) {
            w.blp.x = bx;
            w.blp.y = by;
            applyGhost(w.blp);
            try {
                w.wm.addView(b, w.blp);
                w.bubbleAttached = true;
            } catch (Exception e) {
                Log.w("bubble: " + e);
            }
        } else if (bx != w.blp.x || by != w.blp.y) {
            w.blp.x = bx;
            w.blp.y = by;
            try {
                w.wm.updateViewLayout(b, w.blp);
            } catch (Exception ignored) {
            }
        }
    }

    private void detachBubble(Win w) {
        if (w.bubble == null || !w.bubbleAttached) return;
        try {
            w.wm.removeViewImmediate(w.bubble);
        } catch (Exception ignored) {
        }
        w.bubbleAttached = false;
    }

    private void endSpeech(int choice) {
        speaking = null;
        speakUntil = 0;
        brain.actor.talking = false;
        if (bubbleStage >= 0) detachBubble(wins.get(bubbleStage));
        bubbleStage = -1;
        Brain.Prompt p = prompt;
        prompt = null;
        if (p != null) p.chose(choice);
    }

    // ---- touch ----

    @Override
    public void down(float rawX, float rawY) {
        brain.touchDown();
    }

    @Override
    public void tap() {
        brain.tap();
    }

    @Override
    public void longPress() {
        brain.longPress();
    }

    @Override
    public void rub() {
        brain.rub();
    }

    @Override
    public void dragStart(float rawX, float rawY) {
        brain.dragStart(rawX, rawY);
    }

    @Override
    public void drag(float rawX, float rawY) {
        brain.drag(rawX, rawY);
    }

    @Override
    public void release(float vx, float vy) {
        brain.release(vx, vy);
    }

    // ---- controls used by the bridge, the den and the notification ----

    void setVisible(boolean v) {
        visible = v;
        hideUntil = 0;
        settings.set("visible", String.valueOf(v));
        if (v) startLoop();
        refreshNotification();
    }

    void hideFor(long ms) {
        hideUntil = System.currentTimeMillis() + ms;
        render();
    }

    void showNow() {
        hideUntil = 0;
        if (!visible) setVisible(true);
    }

    void summon(int stageIndex) {
        if (stageIndex < 0 || stageIndex >= stages.size()) return;
        if (!stages.get(stageIndex).on) return;
        brain.moveTo(stageIndex);
    }

    int stageIndexOf(String name) {
        for (int i = 0; i < stages.size(); i++) if (stages.get(i).name.equals(name)) return i;
        return -1;
    }

    void installNeedsTap() {
        say(new Lines.Line("", "Android wants your OK for this one. Tap \"Install\" for me?"), null, null);
    }

    // ---- jobs ----

    void jobBegin(String kind, String label, String pkg, String version, String detail) {
        if (Registry.SELF.equals(pkg) && "install".equals(kind)) markSelfUpdate(version);
        if ("install".equals(kind)) jobPkg = pkg;
        brain.startJob(kind, label, Brain.vars(label, version, null));
        history.add(kind + "_start", pkg, label, null, detail, version);
    }

    /** Returns true if the app was new to the registry. */
    boolean jobEnd(String kind, String label, String pkg, String version, boolean ok, String msg, JSONObject reg) {
        boolean isNew = false;
        if ("install".equals(kind) && ok && pkg != null) {
            JSONObject f = reg != null ? reg : new JSONObject();
            if (label != null && !f.has("label") && !registry.has(pkg)) Util.put(f, "label", label);
            Util.put(f, "lastInstall", System.currentTimeMillis());
            Util.put(f, "lastVersion", version);
            Util.put(f, "update", "");
            isNew = registry.upsert(pkg, f);
            foreground.forget(pkg);
            cancelEvent("update:" + pkg);
        }
        boolean self = Registry.SELF.equals(pkg);
        if ("install".equals(kind)) main.postDelayed(() -> jobPkg = null, 3000);
        brain.finishJob(kind, ok, Brain.vars(label, version, msg), isNew, self);
        history.add(kind, pkg, label, ok, msg, version);
        if (!ok) notifyEvent(("build".equals(kind) ? "Build failed: " : "Install failed: ") + label, msg, null);
        return isNew;
    }

    /** Installs an APK from storage on the Thor itself. */
    void installFile(File apk, String source, String labelHint) {
        Installer.Apk a = Installer.inspect(this, apk);
        if (a == null) {
            brain.finishJob("install", false, Brain.vars(apk.getName(), null, "it isn't a valid APK"), false, false);
            history.add("install", null, apk.getName(), false, "not a valid APK", null);
            return;
        }
        String label = labelHint != null ? labelHint : registry.has(a.pkg) ? registry.label(a.pkg) : a.label;
        jobBegin("install", label, a.pkg, a.versionName, source + ": " + apk.getPath());
        String sig = Installer.signatureProblem(this, apk, a.pkg);
        if (sig != null) {
            jobEnd("install", label, a.pkg, a.versionName, false, "I can't install it: " + sig, null);
            return;
        }
        Installer.install(this, apk, brain.stage().displayId, (ok, msg) -> {
            if (ok) archive(apk, a);
            jobEnd("install", label, a.pkg, a.versionName, ok, msg, Util.obj("source", source));
        });
    }

    private void archive(File apk, Installer.Apk a) {
        File dir = new File(Paths.APKS, a.pkg);
        File dest = new File(dir, (a.versionName == null ? String.valueOf(a.versionCode) : a.versionName).replaceAll("[^\\w.-]", "_") + ".apk");
        try {
            if (!apk.getCanonicalPath().equals(dest.getCanonicalPath())) Util.copy(apk, dest);
            File[] all = dir.listFiles((d, n) -> n.endsWith(".apk"));
            if (all != null && all.length > 3) {
                Arrays.sort(all, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                for (int i = 3; i < all.length; i++) all[i].delete();
            }
        } catch (Exception e) {
            Log.w("archive: " + e);
        }
    }

    private void markSelfUpdate(String version) {
        Util.writeText(new File(getFilesDir(), "self_update.json"), Util.obj("version", version, "t", System.currentTimeMillis()).toString());
    }

    /** After installing a new Coronamon, the new process celebrates instead of greeting. */
    private boolean selfUpdated() {
        File f = new File(getFilesDir(), "self_update.json");
        JSONObject o = Util.readJson(f);
        f.delete();
        if (o == null || System.currentTimeMillis() - o.optLong("t") > 15 * 60_000L) return false;
        String v = version();
        history.add("install", Registry.SELF, "Coronamon", true, "self-update", v);
        registry.upsert(Registry.SELF, Util.obj("lastInstall", System.currentTimeMillis(), "lastVersion", v));
        main.postDelayed(() -> brain.finishJob("install", true, Brain.vars("Coronamon", v, null), false, true), 1500);
        return true;
    }

    // ---- events ----

    /**
     * Something installed or updated an app without going through Coronamon (plain adb install,
     * another store). Managed apps get a cheer; new adb installs come from Grok Build on the Mac, so
     * he starts looking after them too.
     */
    private void outsideInstall(String pkg, boolean fresh) {
        if (pkg.equals(jobPkg)) return;
        boolean managed = registry.has(pkg);
        if (!managed && !(fresh && registry.installer(pkg) == null)) return;
        JSONObject live = registry.live(pkg);
        if (!live.optBoolean("installed")) return;
        String seen = pkg + "@" + live.optLong("versionCode") + "@" + live.optLong("lastUpdate");
        if (seen.equals(lastOutside)) return;
        lastOutside = seen;
        String version = live.optString("versionName", null);
        String label = registry.label(pkg);
        JSONObject f = Util.obj("lastInstall", System.currentTimeMillis(), "lastVersion", version, "update", "");
        if (!managed) {
            Util.put(f, "label", label);
            Util.put(f, "kind", "installed");
            Util.put(f, "source", "adb");
        }
        boolean isNew = registry.upsert(pkg, f);
        foreground.forget(pkg);
        cancelEvent("update:" + pkg);
        String installer = live.optString("installer", "adb");
        history.add("install", pkg, label, true, (fresh ? "installed" : "updated") + " by " + installer
                + (isNew ? "; Coronamon is looking after it now" : ""), version);
        long now = System.currentTimeMillis();
        Long last = cheered.get(pkg);
        if (brain.ghost || (last != null && now - last < 10 * 60_000L)) return;
        cheered.put(pkg, now);
        brain.finishJob("install", true, Brain.vars(label, version, null), isNew, false);
    }

    private final Map<String, Long> cheered = new HashMap<>();
    private String lastOutside;

    private void onCrash(String pkg, String kind, File report, String headline, Bugs.Result bug, boolean managed, boolean react) {
        String label = registry.label(pkg);
        JSONArray versions = bug.bug.optJSONArray("versions");
        String version = versions != null && versions.length() > 0 ? versions.optString(versions.length() - 1)
                : registry.live(pkg).optString("versionName", null);
        if (managed) {
            JSONObject e = registry.entry(pkg);
            int n = e == null ? 0 : e.optInt("crashes");
            registry.upsert(pkg, Util.obj("lastCrash", bug.bug.optLong("last"), "lastCrashKind", kind,
                    "lastCrashHeadline", headline, "crashes", n + 1));
        }
        if (react || bug.isNew) history.add(kind, pkg, label, false, headline + " | " + report.getPath(), version);
        if (!managed || !react) return;
        brain.crashed(label, kind.equals("anr"));
        if (bug.cameBack) brain.news("bug_back", Brain.vars(label, version, null), false);
        notifyEvent(label + (kind.equals("anr") ? " froze" : " crashed"),
                headline + "\nSaved for Grok. It goes to the Mac next time the Thor is plugged in.", null, "bug:" + pkg);
    }

    /** Package in front on each screen, by screen name. */
    JSONObject screensNow() {
        JSONObject o = new JSONObject();
        for (Map.Entry<Integer, String> e : foreground.current().entrySet()) {
            if (e.getValue() != null) Util.put(o, Screens.name(e.getKey()), e.getValue());
        }
        return o;
    }

    // ---- bugs ----

    /** A Grok-built app reported a problem it caught itself (renderer crash, script error, failed launch). */
    void appReported(String pkg, String kind, String title, String detail) {
        String label = registry.label(pkg);
        String version = registry.live(pkg).optString("versionName", null);
        Bugs.Result r = bugs.appReport(pkg, label, kind, title, detail, version, Util.obj("screens", screensNow()));
        if (r.isNew || r.cameBack) history.add("issue", pkg, label, null, title, version);
        if (r.cameBack) brain.news("bug_back", Brain.vars(label, version, null), false);
    }

    /** The user's own report from the den. pkg may be empty for a problem with the Thor itself. */
    JSONObject reportProblem(String pkg, String category, String text, JSONObject capture, boolean github) {
        boolean app = pkg != null && !pkg.isEmpty();
        String label = app ? registry.label(pkg) : "the Thor";
        String version = app ? registry.live(pkg).optString("versionName", null) : null;
        JSONObject b = bugs.userReport(app ? pkg : "", label, version, category, text, capture, github);
        history.add("report", app ? pkg : null, label, null, b.optString("title"), version);
        brain.news("bug_saved", Brain.vars(label, version, null), true);
        return b;
    }

    /** The Mac took bugs; firstTime are the ones Grok hadn't seen before. */
    void bugsAcked(JSONArray firstTime) {
        int n = firstTime.length();
        if (n == 0) return;
        JSONObject one = firstTime.optJSONObject(0);
        String what = n == 1 ? one.optString("app") + "'s " + kindWord(one.optString("kind")) : n + " problems";
        history.add("bugs_sent", null, null, true, what, null);
        brain.news("bugs_sent", Brain.vars(n == 1 ? one.optString("app") : null, null, what), true);
    }

    void bugFixed(JSONObject b) {
        cancelEvent("bug:" + b.optString("pkg"));
        history.add("bug_fixed", b.optString("pkg", null), b.optString("app"), true, b.optString("title"), b.optString("fixedIn", null));
        brain.news("bug_fixed", Brain.vars(b.optString("app"), b.optString("fixedIn", null), b.optString("title")), true);
    }

    /** An update went up on GitHub from the Mac. */
    void published(String pkg, String label, String version, String url) {
        if (pkg != null && registry.has(pkg)) {
            registry.upsert(pkg, Util.obj("published", version, "publishedAt", System.currentTimeMillis(), "publishedUrl", url));
            label = registry.label(pkg);
        }
        history.add("published", pkg, label, true, url, version);
        brain.news("published", Brain.vars(label, version, null), true);
    }

    private static String kindWord(String kind) {
        switch (kind) {
            case "anr":
                return "freeze";
            case "report":
                return "report";
            case "renderer":
                return "page crash";
            case "js":
                return "script error";
            case "launch":
                return "launch problem";
            default:
                return "crash";
        }
    }

    private void onTopApps(Map<Integer, String> top) {
        for (Brain.Stage s : stages) {
            String pkg = top.get(s.displayId);
            s.topPkg = pkg;
            s.topLabel = pkg == null ? null : registry.label(pkg);
            s.game = foreground.avoid(pkg);
        }
        if (topApps != null) {
            for (Map.Entry<Integer, String> e : top.entrySet()) {
                String pkg = e.getValue();
                if (pkg == null || pkg.equals(topApps.get(e.getKey())) || topApps.containsValue(pkg)) continue;
                if (registry.has(pkg) && !Registry.SELF.equals(pkg)) brain.appOpened(pkg, registry.label(pkg));
            }
        }
        topApps = top;
    }

    private final Runnable updateTick = new Runnable() {
        @Override
        public void run() {
            if (!"off".equals(settings.updates())) updates.checkAll(false);
            main.postDelayed(this, 12 * 3600_000L);
        }
    };

    private final Updates.Listener updateListener = new Updates.Listener() {
        @Override
        public void found(String pkg, String label, String version, boolean canInstall) {
            history.add("update_found", pkg, label, null, null, version);
            if (canInstall && "auto".equals(settings.updates()) && registry.amInstaller(pkg)) {
                updates.fetch(pkg);
                return;
            }
            notifyEvent(label + " " + version + " is out", canInstall ? "Tap Update and Coronamon will install it."
                    : "Grok needs to make a new build for this one.", canInstall ? pkg : null, "update:" + pkg);
            brain.updateFound(label, version, canInstall, choice -> {
                if (choice == 0) updates.fetch(pkg);
            });
        }

        @Override
        public void upToDate(String pkg) {
            cancelEvent("update:" + pkg);
        }

        @Override
        public void progress(String pkg, String what) {
            if (what.startsWith("downloading")) brain.startJob("install", registry.label(pkg), Brain.vars(registry.label(pkg), null, null));
            if (what.equals("up to date")) say(lines.pick("update_ok", Brain.vars(registry.label(pkg), null, null)), null, null);
        }

        @Override
        public void ready(String pkg, File apk, String version, String sha256) {
            installFile(apk, "github", registry.label(pkg));
        }

        @Override
        public void failed(String pkg, String why) {
            String label = registry.label(pkg);
            brain.finishJob("install", false, Brain.vars(label, null, why), false, false);
            history.add("update", pkg, label, false, why, null);
        }
    };

    private void checkShizuku() {
        if (shizukuNagged || Shell.running()) return;
        try {
            getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        shizukuNagged = true;
        history.add("shizuku_down", "moe.shizuku.privileged.api", "Shizuku", false, "not running", null);
        say(lines.pick("shizuku_down", null), null, null);
        notifyEvent("Shizuku isn't running", "Pathfinder needs it. Connect the Thor to the Mac and run `coro setup`, or start it from the Shizuku app.", null);
    }

    // ---- Grok Build on the Mac ----

    private String grokState = "unknown", grokMsg;
    private long grokSince;
    private JSONObject grokLast;

    /**
     * What the Mac's watcher sees while the Thor is plugged in: whether Grok Build is running, and the
     * automatic runs that fix what Coronamon found. msg is short enough to say; detail goes in the history.
     */
    boolean grok(String state, String msg, String detail, boolean ok) {
        long now = System.currentTimeMillis();
        String text = detail == null || detail.isEmpty() ? msg : detail;
        Map<String, String> v = Brain.vars(null, null, msg);
        switch (state) {
            case "connected":
                history.add("grok_connected", null, null, true, text, null);
                brain.news("grok", v, true);
                break;
            case "away":
                history.add("grok_away", null, null, null, text, null);
                break;
            case "fixing":
                history.add("grok_fixing", null, null, null, text, null);
                brain.news("grok_fixing", v, true);
                break;
            case "done":
                history.add("grok_done", null, null, ok, text, null);
                brain.news(ok ? "grok_done" : "grok_stuck", v, ok);
                notifyEvent(ok ? "Grok Build finished" : "Grok Build stopped early", text, null, "grok");
                grokLast = Util.obj("t", now, "ok", ok, "msg", text);
                state = "connected";
                text = (ok ? "Last run finished: " : "Last run stopped early: ") + text;
                break;
            case "stopped":
                history.add("grok_stopped", null, null, null, text, null);
                grokLast = Util.obj("t", now, "ok", false, "msg", text);
                state = "connected";
                text = "Last run stopped: " + text;
                break;
            default:
                return false;
        }
        grokState = state;
        grokMsg = text;
        grokSince = now;
        return true;
    }

    /** The Mac's watcher answered a request from the Den's Grok Build page. */
    void macAnswered(JSONObject r, JSONObject result) {
        String action = r.optString("action");
        boolean ok = !"failed".equals(r.optString("state"));
        if ("diagnostics".equals(action)) {
            diag.macDone(r.optString("id"), result);
        } else if (!"status".equals(action)) {
            history.add("mac_control", null, null, ok, Mac.label(action, r.optString("arg")) + ": " + r.optString("msg"), null);
        }
        if ("watch-stop".equals(action) && ok) mac.watcherStopped();
    }

    JSONObject macForDen() {
        JSONObject o = mac.forDen();
        Util.put(o, "diag", diag.snapshot());
        return o;
    }

    // ---- status ----

    JSONObject status() {
        JSONObject o = Util.obj("version", version(), "visible", visible, "hiddenFor",
                Math.max(0, hideUntil - System.currentTimeMillis()), "ghost", ghost, "screen", brain.stage().name,
                "x", Math.round(brain.x), "y", Math.round(brain.y), "move", brain.actor.move.name,
                "energy", Math.round(brain.energy * 100) / 100.0, "job", brain.job, "jobApp", brain.jobApp,
                "speaking", speaking == null ? null : speaking.text, "shizuku", Shell.state(),
                "foregroundWorking", foreground.working());
        JSONArray st = new JSONArray();
        for (Brain.Stage s : stages) {
            st.put(Util.obj("display", s.displayId, "name", s.name, "w", (int) s.w, "h", (int) s.h, "on", s.on,
                    "top", s.topPkg, "avoid", s.game, "scale", Math.round(s.k * 100) / 100.0));
        }
        Util.put(o, "stages", st);
        Util.put(o, "bugs", Util.obj("waiting", bugs.waiting(), "rev", bugs.rev(), "lastSync", bugs.lastSync()));
        Util.put(o, "grok", Util.obj("state", grokState, "since", grokSince, "msg", grokMsg, "last", grokLast));
        Util.put(o, "permissions", permissions());
        Util.put(o, "settings", settings.toJson());
        return o;
    }

    JSONObject permissions() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        boolean usage = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                == AppOpsManager.MODE_ALLOWED;
        return Util.obj(
                "overlay", android.provider.Settings.canDrawOverlays(this),
                "usageStats", usage,
                "dump", checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED,
                "readLogs", checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED,
                "notifications", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                "installs", getPackageManager().canRequestPackageInstalls(),
                "allFiles", Environment.isExternalStorageManager());
    }

    // ---- notifications ----

    private void channels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel pet = new NotificationChannel(CH_PET, "Coronamon on screen", NotificationManager.IMPORTANCE_MIN);
        pet.setShowBadge(false);
        nm.createNotificationChannel(pet);
        NotificationChannel ev = new NotificationChannel(CH_EVENTS, "Installs, crashes and updates", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(ev);
    }

    private PendingIntent serviceIntent(String action, int code, String pkg) {
        Intent i = new Intent(this, PetService.class).setAction(action);
        // Extras don't make PendingIntents distinct; the data does, so each app keeps its own Update button.
        if (pkg != null) i.putExtra("pkg", pkg).setData(Uri.fromParts("package", pkg, null));
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent denIntent() {
        Intent i = new Intent(this, DenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent reportIntent() {
        Intent i = new Intent(this, ReportActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 4, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification petNotification() {
        String where = brain == null || stages.isEmpty() ? "on the Thor" : "on the " + brain.stage().name + " screen";
        String title = visible ? "Coronamon is hanging out " + where : "Coronamon is resting in his den";
        Notification.Builder b = new Notification.Builder(this, CH_PET)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText("Looking after " + (registry == null ? 0 : registry.packages().size()) + " apps from Grok Build")
                .setContentIntent(denIntent())
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Report a problem", reportIntent()).build())
                .addAction(new Notification.Action.Builder(null, "Switch screen", serviceIntent(ACTION_SWITCH, 2, null)).build())
                .addAction(new Notification.Action.Builder(null, visible ? "Hide" : "Show", serviceIntent(ACTION_TOGGLE, 3, null)).build());
        return b.build();
    }

    private void refreshNotification() {
        notifStage = brain.st;
        notifVisible = visible;
        getSystemService(NotificationManager.class).notify(NOTIF, petNotification());
    }

    private int eventId = 100;

    void notifyEvent(String title, String text, String updatePkg) {
        notifyEvent(title, text, updatePkg, null);
    }

    /** A tagged notification replaces the last one with the same tag, and cancelEvent takes it away. */
    void notifyEvent(String title, String text, String updatePkg, String tag) {
        Notification.Builder b = new Notification.Builder(this, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(denIntent())
                .setAutoCancel(true);
        if (updatePkg != null) {
            b.addAction(new Notification.Action.Builder(null, "Update", serviceIntent(ACTION_UPDATE, 500, updatePkg)).build());
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (tag != null) nm.notify(tag, 0, b.build());
        else nm.notify(eventId++, b.build());
    }

    void cancelEvent(String tag) {
        getSystemService(NotificationManager.class).cancel(tag, 0);
    }
}

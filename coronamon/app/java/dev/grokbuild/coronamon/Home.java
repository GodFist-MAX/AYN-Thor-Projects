package dev.grokbuild.coronamon;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Coronamon's home, where he goes while you watch or play something on the top screen. His room
 * fills the bottom screen. When a game or emulator is using the bottom screen too, he still goes
 * home, but only a house button shows there; it opens his messages, which can open his home anyway.
 * Closing the room leaves the house button until what's on the top screen changes.
 */
final class Home implements RoomView.Touch, Tv.Listener {
    private final PetService svc;
    final Messages messages;
    final Tv tv;
    private final Brain.Stage stage = new Brain.Stage();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** You opened the room yourself: from the house button, the den or the Mac. */
    private boolean manual;
    private int manualDisplay = -1;
    /** You closed the room. The house button stays until what's on screen changes. */
    private boolean minimized;
    /** You called him out of his home. He stays out until what's on screen changes. */
    private boolean excused;
    private String contentKey = "";
    private long badgeAt;

    private RoomWin room;
    private IconWin icon;
    private PanelWin panel;
    private final float[] head = new float[2];

    Home(PetService svc) {
        this.svc = svc;
        messages = new Messages(svc);
        tv = new Tv(svc);
        tv.listener = this;
        stage.name = "home";
        stage.w = Room.W;
        stage.h = Room.H;
        stage.k = Room.K;
        stage.floor = Room.WALK_Y;
    }

    boolean roomShowing() {
        return room != null;
    }

    boolean tvReady() {
        return tv.ready();
    }

    /** Every second, from his brain: whether he should be home, and what shows where. */
    boolean check() {
        Brain brain = svc.brain;
        Brain.Stage top = null, bottom = null;
        for (int i = 0; i < svc.stageCount(); i++) {
            Brain.Stage s = svc.stage(i);
            if (s.displayId == Screens.TOP_ID) top = s;
            else bottom = s;
        }
        boolean topBusy = top != null && top.on && top.game;
        boolean bottomBusy = bottom != null && bottom.on && bottom.game;
        svc.foreground.watchWindows(topBusy || bottomBusy);
        String key = topBusy ? top.topPkg + " " + svc.foreground.activity(top.displayId) : bottomBusy ? "bottom " + bottom.topPkg : "";
        if (!key.equals(contentKey)) {
            contentKey = key;
            minimized = false;
            excused = false;
        }
        boolean auto = "home".equals(svc.settings.games()) && !excused && (topBusy || (bottomBusy && (top == null || !top.on)));
        if ((manual || auto) && brain.room == null) {
            enter(bottom != null && bottom.on ? bottom : top != null ? top : bottom);
        } else if (!manual && !auto && brain.room != null) {
            leave(-1);
        }
        layout(top, bottom, bottomBusy);
        long now = System.currentTimeMillis();
        if (brain.room != null && now - tv.fetchedAt() > (room != null ? 3 : 10) * 60_000L) tv.refresh();
        if (now - badgeAt > 5000) badges();
        return brain.room != null;
    }

    private void enter(Brain.Stage where) {
        stage.displayId = where.displayId;
        stage.on = true;
        svc.endSpeechNow();
        svc.brain.goHome(stage);
        tv.refresh();
        Log.i("home: in, for " + (contentKey.isEmpty() ? "a visit" : contentKey));
    }

    private void leave(int to) {
        closePanel();
        closeRoom();
        closeIcon();
        manual = false;
        Brain b = svc.brain;
        int idx = to;
        if (idx < 0) {
            idx = b.st;
            if (!svc.stage(idx).on) for (int i = 0; i < svc.stageCount(); i++) if (svc.stage(i).on) idx = i;
        }
        b.leaveHome(idx);
        Log.i("home: out");
    }

    private void layout(Brain.Stage top, Brain.Stage bottom, boolean bottomBusy) {
        Brain.Stage roomOn = null, iconOn = null;
        boolean visible = svc.visibleNow();
        if (svc.brain.room != null && visible) {
            // Wait for the window check, so a dual-screen game doesn't get the room for a moment.
            if (!svc.foreground.windowsRead()) return;
            if (manual) roomOn = pick(manualDisplay, bottom, top);
            else if (bottom != null && bottom.on && !bottomBusy && !minimized) roomOn = bottom;
            else if (bottom != null && bottom.on) iconOn = bottom;
            else if (top != null && top.on && messages.unread() > 0) iconOn = top;
        }
        showRoom(roomOn);
        showIcon(iconOn);
        if (!visible) closePanel();
    }

    private static Brain.Stage pick(int displayId, Brain.Stage bottom, Brain.Stage top) {
        if (bottom != null && bottom.on && bottom.displayId == displayId) return bottom;
        if (top != null && top.on && top.displayId == displayId) return top;
        return bottom != null && bottom.on ? bottom : top != null && top.on ? top : null;
    }

    private Brain.Stage stageById(int displayId) {
        for (int i = 0; i < svc.stageCount(); i++) if (svc.stage(i).displayId == displayId) return svc.stage(i);
        return null;
    }

    private int displayFor(String screen) {
        if ("top".equals(screen)) return Screens.TOP_ID;
        Brain.Stage bottom = null;
        for (int i = 0; i < svc.stageCount(); i++) if (svc.stage(i).displayId != Screens.TOP_ID) bottom = svc.stage(i);
        if ("bottom".equals(screen) && bottom != null) return bottom.displayId;
        if (room != null) return room.displayId;
        if (icon != null) return icon.displayId;
        return bottom != null && bottom.on ? bottom.displayId : Screens.TOP_ID;
    }

    // ---- what you can do ----

    /** Opens his room on that screen, and sends him home if he isn't. */
    void openRoom(int displayId) {
        manual = true;
        manualDisplay = displayId;
        minimized = false;
        excused = false;
        check();
    }

    /** The room's close button: back to the house button, or he comes out if nothing's on. */
    void closedByYou() {
        manual = false;
        minimized = true;
        check();
    }

    /** You called him to a screen while he was home. */
    void excuse(int stageIndex) {
        excused = true;
        minimized = false;
        if (svc.brain.room != null) leave(stageIndex);
    }

    /** Something needs the screen under the room, like Android asking you to OK an install. */
    void minimize() {
        if (room == null) return;
        manual = false;
        minimized = true;
        check();
    }

    void openPanel(int displayId) {
        closePanel();
        Context ctx = svc.windowContext(displayId);
        Brain.Stage s = stageById(displayId);
        if (ctx == null || s == null) return;
        PanelWin p = new PanelWin(ctx, displayId, (int) s.w, (int) s.h);
        if (!p.add()) return;
        panel = p;
        refreshPanel();
        messages.markSeen();
        badges();
    }

    private void refreshPanel() {
        if (panel != null) panel.view.show(messages.list(), messages, room != null && room.displayId == panel.displayId);
    }

    void closePanel() {
        if (panel == null) return;
        panel.remove();
        panel = null;
    }

    /** From the bridge and the den: open, close, panel, panel-close or status. */
    JSONObject command(String action, String screen) {
        int display = displayFor(screen);
        switch (action == null ? "status" : action) {
            case "open":
                openRoom(display);
                break;
            case "close":
                closedByYou();
                break;
            case "panel":
                openPanel(display);
                break;
            case "panel-close":
                closePanel();
                break;
            case "status":
                break;
            default:
                return Util.obj("ok", false, "error", "home takes open, close, panel, panel-close or status");
        }
        JSONObject o = status();
        Util.put(o, "ok", true);
        return o;
    }

    /** From the bridge: status, next, prev, refresh or watch. */
    JSONObject tvCommand(String action) {
        switch (action == null ? "status" : action) {
            case "next":
                tv.next();
                break;
            case "prev":
                tv.prev();
                break;
            case "refresh":
                tv.refresh();
                break;
            case "watch":
                if (!tv.watchOnTop()) return Util.obj("ok", false, "error", "nothing on his TV to open");
                break;
            case "status":
                break;
            default:
                return Util.obj("ok", false, "error", "tv takes status, next, prev, refresh or watch");
        }
        JSONObject o = tv.status();
        Util.put(o, "ok", true);
        return o;
    }

    JSONObject status() {
        Brain b = svc.brain;
        String showing = room != null ? "room" : icon != null ? "house button" : "nothing";
        Integer on = room != null ? Integer.valueOf(room.displayId) : icon != null ? Integer.valueOf(icon.displayId) : null;
        Tv.Item now = tv.current();
        return Util.obj("home", b.room != null, "showing", showing, "screen", on == null ? null : Screens.name(on),
                "manual", manual, "minimized", minimized, "excused", excused, "watching", contentKey,
                "seat", b.seat == Room.BED ? "bed" : b.seat == Room.CHAIR ? "chair" : b.seat == Room.BEANBAG ? "bean bag" : null,
                "unread", messages.unread(), "panel", panel != null, "tvReady", tv.ready(),
                "tvShowing", now != null && tv.picture() ? now.label() : null);
    }

    /** The service is going, or the screens are being rebuilt. */
    void reset() {
        closePanel();
        closeRoom();
        closeIcon();
        if (svc.brain.room != null) svc.brain.leaveHome(svc.brain.st);
        manual = false;
    }

    void release() {
        reset();
        tv.quit();
    }

    // ---- each frame ----

    void render() {
        if (room == null) return;
        Brain b = svc.brain;
        tv.setOn(!b.asleep());
        room.view.screen = b.seat == Room.CHAIR && b.seatBlend > 0.3f ? b.monitor : b.asleep() ? Room.SCREEN_OFF : Room.SCREEN_SAVER;
        room.view.tvOn = tv.picture();
        room.view.invalidate();
    }

    private void badges() {
        badgeAt = System.currentTimeMillis();
        if (icon == null && room == null) return;
        int n = messages.unread();
        if (icon != null) icon.button.setBadge(n);
        if (room != null) room.mail.setBadge(n);
    }

    // ---- his speech bubble in the room ----

    Bubble bubble() {
        return room == null ? null : room.bubble;
    }

    void showBubble(String text, String src, String[] buttons) {
        if (room == null) return;
        room.bubble.setVisibility(View.VISIBLE);
        room.bubble.show(text, src, buttons);
        placeBubble();
    }

    void hideBubble() {
        if (room != null) room.bubble.setVisibility(View.GONE);
    }

    void placeBubble() {
        if (room == null) return;
        Bubble b = room.bubble;
        int bw = b.getWidth(), bh = b.getHeight();
        if (bw == 0) {
            b.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            bw = b.getMeasuredWidth();
            bh = b.getMeasuredHeight();
        }
        room.view.headTop(head);
        float rw = room.root.getWidth() > 0 ? room.root.getWidth() : room.w;
        float bx = Math.max(6, Math.min(rw - bw - 6, head[0] - bw / 2f));
        float by = Math.max(6, head[1] - bh + 8 * room.dp);
        b.setTranslationX(bx);
        b.setTranslationY(by);
        float tail = head[0] - bx;
        if (Math.abs(tail - b.tailX) > 1) {
            b.tailX = tail;
            b.invalidate();
        }
    }

    // ---- touches in the room ----

    @Override
    public void tapPet() {
        svc.brain.touchDown();
        svc.brain.tap();
    }

    @Override
    public void pressPet() {
        svc.brain.longPress();
    }

    @Override
    public void rubPet() {
        svc.brain.rub();
    }

    @Override
    public void releasePet() {
        svc.brain.release(0, 0);
    }

    @Override
    public void tapSeat(int seat) {
        svc.brain.visit(seat);
    }

    @Override
    public void tapTv() {
        if (room != null) room.toggleCard();
    }

    // ---- the TV ----

    @Override
    public void picture(int w, int h) {
        if (room != null) room.fitTv(w, h);
    }

    @Override
    public void showing(Tv.Item it) {
        if (room == null) return;
        room.caption(it);
        if (room.card.getVisibility() == View.VISIBLE) room.fillCard(null);
    }

    // ---- windows ----

    private void showRoom(Brain.Stage s) {
        if (room != null && (s == null || room.displayId != s.displayId)) closeRoom();
        if (s == null || room != null) return;
        Context ctx = svc.windowContext(s.displayId);
        if (ctx == null) return;
        RoomWin w = new RoomWin(ctx, s.displayId, (int) s.w, (int) s.h);
        if (!w.add()) return;
        room = w;
        stage.displayId = s.displayId;
        tv.attach(w.texture);
        tv.setOn(!svc.brain.asleep());
        badges();
    }

    private void closeRoom() {
        if (room == null) return;
        RoomWin w = room;
        room = null;
        svc.roomClosed();
        tv.setOn(false);
        tv.detach();
        w.remove();
    }

    private void showIcon(Brain.Stage s) {
        if (icon != null && (s == null || icon.displayId != s.displayId)) closeIcon();
        if (s == null || icon != null) return;
        Context ctx = svc.windowContext(s.displayId);
        if (ctx == null) return;
        IconWin w = new IconWin(ctx, s);
        if (!w.add()) return;
        icon = w;
        badges();
    }

    private void closeIcon() {
        if (icon == null) return;
        icon.remove();
        icon = null;
    }

    private static WindowManager.LayoutParams fullScreen(String title) {
        WindowManager.LayoutParams lp = PetService.overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        lp.setTitle(title);
        return lp;
    }

    private abstract static class Win {
        final int displayId;
        final WindowManager wm;
        final float dp;
        View root;
        WindowManager.LayoutParams lp;

        Win(Context ctx, int displayId) {
            this.displayId = displayId;
            wm = ctx.getSystemService(WindowManager.class);
            dp = ctx.getResources().getDisplayMetrics().density;
        }

        int px(float v) {
            return Math.round(v * dp);
        }

        boolean add() {
            try {
                wm.addView(root, lp);
                return true;
            } catch (Exception e) {
                Log.w("home window: " + e);
                return false;
            }
        }

        void remove() {
            try {
                wm.removeViewImmediate(root);
            } catch (Exception ignored) {
            }
        }
    }

    /** The room: the TV's picture behind the room view (it shows through the TV's glass), his bubble, and buttons. */
    private final class RoomWin extends Win {
        final int w, h;
        final float scale, ox, oy;
        final FrameLayout frame;
        final RoomView view;
        final View tvBack;
        final TextureView texture;
        final TextView caption;
        final LinearLayout card, cardButtons;
        final TextView cardTitle, cardSub, cardNote;
        final Bubble bubble;
        final HomeButton close, mail;
        private final Runnable hideCaption, hideCard;

        RoomWin(Context ctx, int displayId, int w, int h) {
            super(ctx, displayId);
            this.w = w;
            this.h = h;
            scale = Math.min(w / Room.W, h / Room.H);
            ox = (w - Room.W * scale) / 2;
            oy = (h - Room.H * scale) / 2;
            frame = new FrameLayout(ctx);
            root = frame;
            lp = fullScreen("Coronamon home");

            int tl = Math.round(ox + Room.TV_L * scale), tt = Math.round(oy + Room.TV_T * scale);
            int tw = Math.round((Room.TV_R - Room.TV_L) * scale), th = Math.round((Room.TV_B - Room.TV_T) * scale);
            tvBack = new View(ctx);
            tvBack.setBackgroundColor(0xFF050608);
            frame.addView(tvBack, at(tl - 2, tt - 2, tw + 4, th + 4));
            texture = new TextureView(ctx);
            frame.addView(texture, at(tl, tt, tw, th));

            view = new RoomView(ctx);
            view.brain = svc.brain;
            view.touch = Home.this;
            frame.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            caption = label(11.5f, 0xFFFFFFFF, true);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setPadding(px(6), px(3), px(6), px(3));
            caption.setBackgroundColor(0xB0000000);
            caption.setVisibility(View.GONE);
            FrameLayout.LayoutParams clp = at(tl, 0, tw, FrameLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = tt + th - px(26);
            frame.addView(caption, clp);
            hideCaption = () -> caption.setVisibility(View.GONE);

            card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(14), px(12), px(14), px(12));
            card.setBackground(round(0xF0201C26, 16, 0x55FFFFFF));
            card.setClickable(true);
            card.setVisibility(View.GONE);
            TextView heading = label(11, 0xFFFFB45A, true);
            heading.setText("ON CORONAMON'S TV");
            heading.setLetterSpacing(0.08f);
            card.addView(heading);
            cardTitle = label(15, 0xFFFFFFFF, true);
            cardSub = label(12.5f, 0xFFD8CCC4, false);
            cardNote = label(12.5f, 0xFFD8CCC4, false);
            card.addView(cardTitle);
            card.addView(cardSub);
            card.addView(cardNote);
            LinearLayout row = new LinearLayout(ctx);
            cardButtons = row;
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, px(10), 0, 0);
            row.addView(pill("◀  Back", 0xFF4A4452, () -> {
                tv.prev();
                fillCard(null);
            }));
            row.addView(pill("Next  ▶", 0xFF4A4452, () -> {
                tv.next();
                fillCard(null);
            }));
            row.addView(pill("Watch on top", 0xFFE65E37, () -> {
                if (tv.watchOnTop()) card.setVisibility(View.GONE);
                else fillCard("I couldn't open that one.");
            }));
            card.addView(row);
            frame.addView(card, new FrameLayout.LayoutParams(Math.min(px(360), w - px(20)), FrameLayout.LayoutParams.WRAP_CONTENT));
            hideCard = () -> card.setVisibility(View.GONE);

            bubble = new Bubble(ctx);
            bubble.setVisibility(View.GONE);
            bubble.listener = new Bubble.Listener() {
                @Override
                public void chose(int index) {
                    svc.roomBubbleDone(index);
                }

                @Override
                public void dismissed() {
                    svc.roomBubbleDone(-1);
                }
            };
            frame.addView(bubble, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));

            int bs = px(54);
            mail = new HomeButton(ctx, HomeButton.MAIL);
            mail.listener = () -> openPanel(displayId);
            FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(bs, bs, Gravity.TOP | Gravity.START);
            mlp.setMargins(px(8), px(8), 0, 0);
            frame.addView(mail, mlp);
            close = new HomeButton(ctx, HomeButton.CLOSE);
            close.listener = Home.this::closedByYou;
            FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(bs, bs, Gravity.TOP | Gravity.END);
            xlp.setMargins(0, px(8), px(8), 0);
            frame.addView(close, xlp);
        }

        @Override
        void remove() {
            main.removeCallbacks(hideCaption);
            main.removeCallbacks(hideCard);
            super.remove();
        }

        private FrameLayout.LayoutParams at(int x, int y, int width, int height) {
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.START);
            p.leftMargin = x;
            p.topMargin = y;
            return p;
        }

        /** Letterboxes the picture inside the TV's glass. */
        void fitTv(int vw, int vh) {
            float l = ox + Room.TV_L * scale, t = oy + Room.TV_T * scale;
            float tw = (Room.TV_R - Room.TV_L) * scale, th = (Room.TV_B - Room.TV_T) * scale;
            float a = vw / (float) vh;
            float fw = tw, fh = tw / a;
            if (fh > th) {
                fh = th;
                fw = th * a;
            }
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) texture.getLayoutParams();
            int nw = Math.round(fw), nh = Math.round(fh), nx = Math.round(l + (tw - fw) / 2), ny = Math.round(t + (th - fh) / 2);
            if (p.width == nw && p.height == nh && p.leftMargin == nx && p.topMargin == ny) return;
            p.width = nw;
            p.height = nh;
            p.leftMargin = nx;
            p.topMargin = ny;
            texture.setLayoutParams(p);
        }

        void caption(Tv.Item it) {
            main.removeCallbacks(hideCaption);
            if (it == null) {
                caption.setVisibility(View.GONE);
                return;
            }
            caption.setText(it.label());
            caption.setVisibility(View.VISIBLE);
            main.postDelayed(hideCaption, 6000);
        }

        void toggleCard() {
            if (card.getVisibility() == View.VISIBLE) {
                card.setVisibility(View.GONE);
                return;
            }
            fillCard(null);
        }

        /** Fills in what's on and shows the card above the TV for a few seconds. */
        void fillCard(String note) {
            Tv.Item it = tv.current();
            boolean any = tv.ready();
            cardTitle.setText(it != null && any ? it.title : "Nothing on yet");
            String sub = it != null && any ? (it.sub == null || it.sub.isEmpty() ? "" : it.sub + "  ·  ") + "from " + it.app : "";
            cardSub.setText(sub);
            cardSub.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            String n = note != null ? note : any ? (svc.brain.asleep() ? "He's asleep, so the TV is off." : "")
                    : "Videos you play in CrossBar, and AniDeck episodes you've downloaded and watched, show up here.";
            cardNote.setText(n);
            cardNote.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
            cardButtons.setVisibility(any ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) card.getLayoutParams();
            card.measure(View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            float tvCx = ox + (Room.TV_L + Room.TV_R) / 2 * scale, tvTop = oy + Room.TV_T * scale;
            p.leftMargin = Math.round(Math.max(px(10), Math.min(w - p.width - px(10), tvCx - p.width / 2f)));
            p.topMargin = Math.round(Math.max(px(10), tvTop - card.getMeasuredHeight() - px(10)));
            card.setLayoutParams(p);
            card.setVisibility(View.VISIBLE);
            main.removeCallbacks(hideCard);
            main.postDelayed(hideCard, 7000);
        }

        private TextView pill(String text, int color, Runnable r) {
            TextView t = label(13, 0xFFFFFFFF, true);
            t.setText(text);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(12), px(7), px(12), px(7));
            t.setBackground(round(color, 15, 0));
            t.setOnClickListener(v -> {
                r.run();
                main.removeCallbacks(hideCard);
                main.postDelayed(hideCard, 7000);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.rightMargin = px(8);
            t.setLayoutParams(p);
            return t;
        }

        private TextView label(float sp, int color, boolean bold) {
            TextView t = new TextView(frame.getContext());
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(color);
            t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            return t;
        }

        private GradientDrawable round(int fill, float radiusDp, int stroke) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill);
            g.setCornerRadius(px(radiusDp));
            if (stroke != 0) g.setStroke(px(1), stroke);
            return g;
        }
    }

    /** The house button: drag it anywhere, tap it for his messages. */
    private final class IconWin extends Win {
        final HomeButton button;
        final int sw, sh, size;

        IconWin(Context ctx, Brain.Stage s) {
            super(ctx, s.displayId);
            sw = (int) s.w;
            sh = (int) s.h;
            size = px(62);
            button = new HomeButton(ctx, HomeButton.HOUSE);
            button.draggable = true;
            button.listener = new HomeButton.Listener() {
                @Override
                public void tap() {
                    openPanel(displayId);
                }

                @Override
                public void drag(float dx, float dy) {
                    lp.x = clampX(lp.x + Math.round(dx));
                    lp.y = clampY(lp.y + Math.round(dy));
                    try {
                        wm.updateViewLayout(root, lp);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void dropped() {
                    svc.settings.homeIcon(displayId, lp.x, lp.y);
                }
            };
            root = button;
            lp = PetService.overlayParams(size, size);
            lp.setTitle("Coronamon house");
            lp.alpha = 0.92f;
            // Top left by default: top right is where CrossBar's clock is.
            int[] spot = svc.settings.homeIcon(displayId);
            lp.x = clampX(spot != null ? spot[0] : px(14));
            lp.y = clampY(spot != null ? spot[1] : px(14));
        }

        private int clampX(int x) {
            return Math.max(0, Math.min(sw - size, x));
        }

        private int clampY(int y) {
            return Math.max(0, Math.min(sh - size, y));
        }
    }

    /** His messages, over a dimmed screen. */
    private final class PanelWin extends Win {
        final MessagesPanel view;

        PanelWin(Context ctx, int displayId, int sw, int sh) {
            super(ctx, displayId);
            view = new MessagesPanel(ctx, sw, sh, new MessagesPanel.Listener() {
                @Override
                public void close() {
                    closePanel();
                }

                @Override
                public void openHome() {
                    closePanel();
                    openRoom(displayId);
                }

                @Override
                public void update(Messages.Msg m) {
                    if (m.pkg != null) svc.updates.fetch(m.pkg);
                    messages.changed();
                }

                @Override
                public void dismiss(Messages.Msg m) {
                    messages.dismiss(m.id);
                    refreshPanel();
                    badges();
                }
            });
            root = view;
            lp = fullScreen("Coronamon messages");
        }
    }
}

package dev.grokbuild.coronamon;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Coronamon's messages: a card over the dimmed screen with the problems and news he's found, each
 * with a Dismiss button (and Update for updates), plus a button to open his home.
 */
final class MessagesPanel extends FrameLayout {
    interface Listener {
        void close();

        void openHome();

        void update(Messages.Msg m);

        void dismiss(Messages.Msg m);
    }

    private static final int INK = 0xFF3A1A10, MUTED = 0xFF8A6A5A, ORANGE = 0xFFE65E37, CARD = 0xFFFFFBF4;

    private final float dp;
    private final Listener listener;
    private final LinearLayout list;
    private final TextView subtitle;
    private final TextView homeBtn;

    MessagesPanel(Context ctx, int screenW, int screenH, Listener l) {
        super(ctx);
        listener = l;
        dp = ctx.getResources().getDisplayMetrics().density;
        setBackgroundColor(0x80000000);
        setOnClickListener(v -> listener.close());

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setBackground(round(CARD, 22, ORANGE, 2.5f));
        card.setPadding(px(18), px(16), px(18), px(14));
        card.setElevation(px(10));
        int cardW = (int) Math.min(px(540), screenW * 0.92f);
        LayoutParams clp = new LayoutParams(cardW, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(card, clp);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        HomeButton house = new HomeButton(ctx, HomeButton.HOUSE);
        house.setClickable(false);
        head.addView(house, new LinearLayout.LayoutParams(px(48), px(48)));
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(px(10), 0, 0, 0);
        titles.addView(text("Coronamon's messages", 19, INK, true));
        subtitle = text("", 13, MUTED, false);
        titles.addView(subtitle);
        head.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView x = button("Close", 0xFF8A7A72);
        x.setOnClickListener(v -> listener.close());
        head.addView(x);
        card.addView(head);

        MaxScroll scroll = new MaxScroll(ctx, (int) (screenH * 0.86f) - px(150));
        scroll.setPadding(0, px(10), 0, px(6));
        scroll.setClipToPadding(false);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        card.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, px(8), 0, 0);
        homeBtn = button("Open Coronamon's home", ORANGE);
        homeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        homeBtn.setPadding(px(22), px(11), px(22), px(11));
        homeBtn.setOnClickListener(v -> listener.openHome());
        foot.addView(homeBtn);
        card.addView(foot);
    }

    /** homeOpen: the room is already showing, so the button just goes back to it. */
    void show(List<Messages.Msg> msgs, Messages unread, boolean homeOpen) {
        list.removeAllViews();
        int n = 0;
        for (Messages.Msg m : msgs) if (unread.unread(m)) n++;
        subtitle.setText(msgs.isEmpty() ? "All quiet. Everything's running fine!"
                : n > 0 ? n + " new, " + msgs.size() + " in all" : msgs.size() + (msgs.size() == 1 ? " message" : " messages"));
        homeBtn.setText(homeOpen ? "Back to Coronamon's home" : "Open Coronamon's home");
        if (msgs.isEmpty()) {
            TextView t = text("Nothing to report. I'll put updates, crashes and anything I need from you here.", 14, MUTED, false);
            t.setPadding(px(4), px(14), px(4), px(14));
            t.setGravity(Gravity.CENTER);
            list.addView(t);
            return;
        }
        for (Messages.Msg m : msgs) list.addView(row(m, unread.unread(m)));
    }

    private View row(Messages.Msg m, boolean isNew) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(px(10), px(10), px(8), px(10));
        row.setBackground(round(isNew ? 0xFFFFF0E2 : 0xFFF8F1E8, 14, 0, 0));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = px(8);
        row.setLayoutParams(rlp);

        View dot = new View(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color(m.kind));
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(11), px(11));
        dlp.topMargin = px(5);
        dlp.rightMargin = px(10);
        row.addView(dot, dlp);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(m.title, 15, INK, true);
        body.addView(title);
        if (!m.text.isEmpty()) {
            TextView t = text(m.text, 13, 0xFF6A5048, false);
            t.setMaxLines(4);
            t.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(t);
        }
        body.addView(text((isNew ? "New · " : "") + ago(m.t), 11, isNew ? ORANGE : MUTED, isNew));
        row.addView(body, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout acts = new LinearLayout(ctx);
        acts.setOrientation(LinearLayout.VERTICAL);
        acts.setGravity(Gravity.END);
        if (m.update) {
            TextView up = button("Update", 0xFF3E9A4E);
            up.setOnClickListener(v -> {
                up.setText("Updating…");
                up.setEnabled(false);
                listener.update(m);
            });
            acts.addView(up);
        }
        TextView no = button("Dismiss", 0xFFB0A49C);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = m.update ? px(6) : 0;
        no.setOnClickListener(v -> listener.dismiss(m));
        acts.addView(no, nlp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = px(8);
        row.addView(acts, alp);
        return row;
    }

    private static int color(String kind) {
        switch (kind) {
            case Messages.LIVE:
            case Messages.BUG:
                return 0xFFE0432F;
            case Messages.WARN:
                return 0xFFF0A030;
            case Messages.UPDATE:
            case Messages.GOOD:
                return 0xFF3E9A4E;
            default:
                return 0xFF4A86D8;
        }
    }

    private static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + (s / 3600 == 1 ? " hour ago" : " hours ago");
        long days = s / 86400;
        return days == 1 ? "yesterday" : days + " days ago";
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setLineSpacing(0, 1.08f);
        return t;
    }

    private TextView button(String s, int color) {
        TextView b = text(s, 13.5f, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(14), px(7), px(14), px(7));
        b.setBackground(round(color, 16, 0, 0));
        b.setClickable(true);
        return b;
    }

    private GradientDrawable round(int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radiusDp));
        if (strokeDp > 0) g.setStroke(px(strokeDp), stroke);
        return g;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    /** A scroll view that grows with its content up to a height, then scrolls. */
    private static final class MaxScroll extends ScrollView {
        private final int max;

        MaxScroll(Context ctx, int max) {
            super(ctx);
            this.max = Math.max(1, max);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }
}

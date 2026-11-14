package dev.crossbar.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

/** Native fallback for videos the web player can't decode (for example some MKV/AVI files). */
public class PlayerActivity extends Activity {
    private VideoView video;
    private MediaController controls;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        setContentView(root);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        controls = new MediaController(this);
        controls.setAnchorView(video);
        video.setMediaController(controls);
        video.setVideoURI(getIntent().getData());
        video.setOnPreparedListener(mp -> video.start());
        video.setOnCompletionListener(mp -> finish());
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "This video can't be played", Toast.LENGTH_LONG).show();
            finish();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.I.lights.video(getIntent().getData());
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.I.lights.video(null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (video.isPlaying()) video.pause();
                else video.start();
                App.I.lights.video(video.isPlaying() ? getIntent().getData() : null);
                controls.show(2500);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                video.seekTo(Math.max(0, video.getCurrentPosition() - 10000));
                controls.show(2500);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                video.seekTo(Math.min(video.getDuration(), video.getCurrentPosition() + 10000));
                controls.show(2500);
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }
}

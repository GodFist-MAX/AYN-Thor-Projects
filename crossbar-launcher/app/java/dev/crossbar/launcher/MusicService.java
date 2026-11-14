package dev.crossbar.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;

/** Background music player for songs on the device, with a media session and notification. */
public class MusicService extends Service implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {
    static volatile MusicService I;
    private static final String CHANNEL = "cb_music";
    private static final int NOTIFICATION_ID = 7;

    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focus;
    private final ArrayList<Long> queue = new ArrayList<>();
    private int index = -1;
    private boolean prepared, shuffle, resumeOnGain;
    /** Playing alongside a game, without audio focus (see App.musicMixes). */
    private volatile boolean mixing;
    private int repeat = 1; // 0 off, 1 all, 2 one
    private int errors;
    private String title = "", artist = "", album = "";
    private long duration, currentId = -1;
    private Bitmap art;

    static void command(Context c, String cmd, String arg) {
        MusicService s = I;
        if (s != null) {
            App.I.main.post(() -> s.handle(cmd, arg));
            return;
        }
        if (!"play".equals(cmd)) return;
        c.startForegroundService(new Intent(c, MusicService.class).putExtra("cmd", cmd).putExtra("arg", arg));
    }

    static JSONObject state() {
        MusicService s = I;
        if (s == null || s.currentId < 0) return Util.obj("active", false);
        int pos = 0;
        boolean playing = false;
        try {
            if (s.prepared) {
                pos = s.mp.getCurrentPosition();
                playing = s.mp.isPlaying();
            }
        } catch (Exception ignored) {
        }
        return Util.obj("active", true, "id", s.currentId, "title", s.title, "artist", s.artist, "album", s.album,
                "playing", playing, "position", pos, "duration", s.duration, "index", s.index,
                "count", s.queue.size(), "shuffle", s.shuffle, "repeat", s.repeat);
    }

    /** Song ids in play order and the current position, for the bottom screen's Up Next list. */
    static JSONObject queue() {
        MusicService s = I;
        JSONArray ids = new JSONArray();
        if (s == null) return Util.obj("index", -1, "ids", ids);
        try {
            for (Long id : new ArrayList<>(s.queue)) ids.put(id);
        } catch (RuntimeException ignored) {
            // changed while copying; the next poll gets a consistent list
        }
        return Util.obj("index", s.index, "ids", ids);
    }

    static Bitmap art() {
        MusicService s = I;
        return s == null ? null : s.art;
    }

    static boolean playing() {
        MusicService s = I;
        try {
            return s != null && s.prepared && s.mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Follows App.musicMixes: lets go of audio focus at once (before the game that's starting asks for
     * it, or Android 12+ fades the music out), and asks for it again once back in CrossBar.
     */
    static void mixChanged() {
        MusicService s = I;
        if (s == null) return;
        if (App.I.musicMixes()) {
            s.mixing = true;
            s.am.abandonAudioFocusRequest(s.focus);
        } else if (s.mixing) {
            App.I.main.post(s::unmix);
        }
    }

    private void unmix() {
        if (!mixing || App.I.musicMixes()) return;
        mixing = false;
        if (prepared && mp.isPlaying() && am.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) pause();
    }

    /** Another app's media started playing. Without audio focus nothing else stops the music for it. */
    static void otherMediaStarted() {
        MusicService s = I;
        if (s != null && s.mixing) App.I.main.post(s::pause);
    }

    private boolean focusGranted() {
        return mixing || am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        I = this;
        am = getSystemService(AudioManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW));
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs).setOnAudioFocusChangeListener(this).build();
        mixing = App.I.musicMixes();
        mp = new MediaPlayer();
        mp.setAudioAttributes(attrs);
        mp.setOnCompletionListener(this);
        mp.setOnErrorListener(this);
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        session = new MediaSession(this, "CrossBar");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(true); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { if (prepared) mp.seekTo((int) pos); update(); }
            @Override public void onStop() { stopAll(); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        if (intent != null) handle(intent.getStringExtra("cmd"), intent.getStringExtra("arg"));
        return START_NOT_STICKY;
    }

    void handle(String cmd, String arg) {
        if (cmd == null) return;
        try {
            switch (cmd) {
                case "play": {
                    JSONObject o = new JSONObject(arg);
                    JSONArray ids = o.getJSONArray("ids");
                    queue.clear();
                    for (int i = 0; i < ids.length(); i++) queue.add(ids.getLong(i));
                    if (queue.isEmpty()) return;
                    index = Math.max(0, Math.min(o.optInt("index"), queue.size() - 1));
                    shuffle = o.optBoolean("shuffle");
                    if (shuffle) {
                        Long first = queue.remove(index);
                        Collections.shuffle(queue);
                        queue.add(0, first);
                        index = 0;
                    }
                    errors = 0;
                    playIndex();
                    break;
                }
                case "toggle":
                    if (prepared && mp.isPlaying()) pause();
                    else resume();
                    break;
                case "pause": pause(); break;
                case "resume": resume(); break;
                case "next": next(true); break;
                case "prev": prev(); break;
                case "seek":
                    if (prepared) mp.seekTo(Integer.parseInt(arg));
                    update();
                    break;
                case "seekBy":
                    if (prepared) {
                        int t = mp.getCurrentPosition() + Integer.parseInt(arg);
                        mp.seekTo(Math.max(0, Math.min(t, (int) duration - 500)));
                    }
                    update();
                    break;
                case "shuffle": shuffle = !shuffle; update(); break;
                case "repeat": repeat = (repeat + 1) % 3; update(); break;
                case "stop": stopAll(); break;
                default: break;
            }
        } catch (Exception ignored) {
        }
    }

    private void playIndex() {
        if (index < 0 || index >= queue.size()) return;
        currentId = queue.get(index);
        Uri u = MediaRepo.uri("audio", currentId);
        loadMeta(u);
        prepared = false;
        try {
            mp.reset();
            mp.setDataSource(this, u);
            mp.setOnPreparedListener(p -> {
                prepared = true;
                errors = 0;
                duration = p.getDuration();
                if (focusGranted()) p.start();
                update();
            });
            mp.prepareAsync();
        } catch (Exception e) {
            onError(mp, 0, 0);
        }
        update();
    }

    private void loadMeta(Uri u) {
        title = "";
        artist = "";
        album = "";
        art = null;
        String[] proj = {MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION};
        try (Cursor c = getContentResolver().query(u, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                title = c.getString(0) == null ? "" : c.getString(0);
                artist = c.getString(1) == null || "<unknown>".equals(c.getString(1)) ? "" : c.getString(1);
                album = c.getString(2) == null ? "" : c.getString(2);
                duration = c.getLong(3);
            }
        } catch (Exception ignored) {
        }
        try {
            art = getContentResolver().loadThumbnail(u, new Size(512, 512), null);
        } catch (Exception ignored) {
        }
    }

    private void pause() {
        if (prepared && mp.isPlaying()) mp.pause();
        update();
    }

    private void resume() {
        if (!prepared) {
            if (index >= 0) playIndex();
            return;
        }
        if (focusGranted()) mp.start();
        update();
    }

    private void next(boolean user) {
        if (queue.isEmpty()) return;
        if (repeat == 2 && !user) {
            playIndex();
            return;
        }
        index++;
        if (index >= queue.size()) {
            if (repeat == 1 || user) {
                index = 0;
            } else {
                index = queue.size() - 1;
                update();
                return;
            }
        }
        playIndex();
    }

    private void prev() {
        if (queue.isEmpty()) return;
        if (prepared && mp.getCurrentPosition() > 3000) {
            mp.seekTo(0);
            update();
            return;
        }
        index = (index - 1 + queue.size()) % queue.size();
        playIndex();
    }

    private void stopAll() {
        try {
            mp.reset();
        } catch (Exception ignored) {
        }
        prepared = false;
        currentId = -1;
        am.abandonAudioFocusRequest(focus);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onCompletion(MediaPlayer p) {
        next(false);
    }

    @Override
    public boolean onError(MediaPlayer p, int what, int extra) {
        prepared = false;
        if (++errors < Math.min(5, queue.size())) App.I.main.postDelayed(() -> next(false), 300);
        update();
        return true;
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (mixing && change != AudioManager.AUDIOFOCUS_GAIN) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnGain = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                resumeOnGain = prepared && mp.isPlaying();
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (prepared) mp.setVolume(0.3f, 0.3f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (prepared) mp.setVolume(1f, 1f);
                if (resumeOnGain) resume();
                resumeOnGain = false;
                break;
            default:
                break;
        }
    }

    private void update() {
        boolean playing = false;
        long pos = 0;
        try {
            playing = prepared && mp.isPlaying();
            pos = prepared ? mp.getCurrentPosition() : 0;
        } catch (Exception ignored) {
        }
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, 1f)
                .build());
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    private PendingIntent action(String cmd, int code) {
        Intent i = new Intent(this, MusicService.class).putExtra("cmd", cmd);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification() {
        boolean playing = false;
        try {
            playing = prepared && mp.isPlaying();
        } catch (Exception ignored) {
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? "CrossBar Music" : title)
                .setContentText(artist)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Previous", action("prev", 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play), playing ? "Pause" : "Play", action("toggle", 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), "Next", action("next", 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    @Override
    public void onDestroy() {
        I = null;
        try {
            mp.release();
        } catch (Exception ignored) {
        }
        session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

/** Explicit, non-sticky phone host and peer player. No boot receiver, polling or permanent wake lock. */
public final class HammerService extends Service {
    static volatile HammerService instance;
    static volatile String status = "Hosting off", playback = "Stopped";
    static volatile boolean starting;
    private volatile Hammer.Server host;
    private HammerNearby.Announcement announcement;
    private MediaPlayer player;
    // MediaPlayer getters can report an asynchronous error while prepareAsync is in flight.
    private boolean playerReady;
    private HammerBuffer buffer;
    private int playerGeneration, hostGeneration;
    private Hammer.Job opening;
    private boolean destroyed;
    private android.media.AudioFocusRequest focus;
    private final Handler main = new Handler(Looper.getMainLooper());
    static void startHost(Context c) {
        Intent i = new Intent(c, HammerService.class).setAction("host");
        c.startForegroundService(i);
    }
    static void stopHost() {
        HammerService s = instance;
        if (s != null) {
            s.hostGeneration++;
            starting = false;
            KillaNet.serve(null);
            KillaNet.announce(false);
            if (s.host != null)
                s.host.close();
            s.host = null;
            if (s.announcement != null) {
                s.announcement.close();
                s.announcement = null;
            }
            status = "Hosting off";
            s.update();
        }
    }
    static void stopPlayback() {
        HammerService s = instance;
        if (s != null) {
            s.releasePlayer();
            s.update();
        }
    }
    static int position() {
        try {
            return instance != null && instance.playerReady && instance.player != null ? instance.player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
    static int duration() {
        try {
            return instance != null && instance.playerReady && instance.player != null ? instance.player.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
    static void seek(int ms) {
        try {
            if (instance != null && instance.playerReady && instance.player != null)
                instance.player.seekTo(ms);
        } catch (Exception ignored) {
        }
    }
    static void pause() {
        HammerService s = instance;
        if (s == null || !s.playerReady || s.player == null)
            return;
        try {
            if (s.player.isPlaying()) {
                s.player.pause();
                playback = "Paused";
            } else if (s.requestFocus()) {
                s.player.start();
                playback = "Playing";
            }
            s.update();
        } catch (Exception ignored) {
        }
    }
    static void play(Context c, List<Hammer.Card> peers, Hammer.Song song, int mode) throws Exception {
        play(c, peers, song, mode, 0);
    }
    static void play(Context c, List<Hammer.Card> peers, Hammer.Song song, int mode, int startAt) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        song.write(new DataOutputStream(b));
        ArrayList<String> cards = new ArrayList<String>();
        for (Hammer.Card p : peers)
            cards.add(p.encode());
        Intent i = new Intent(c, HammerService.class)
                       .setAction("play")
                       .putExtra("song", b.toByteArray())
                       .putStringArrayListExtra("peers", cards)
                       .putExtra("mode", mode).putExtra("startAt", Math.max(0, startAt));
        c.startForegroundService(i);
    }
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager n = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        n.createNotificationChannel(new NotificationChannel("hammer", "HAMMER hosting and playback",
                                                            NotificationManager.IMPORTANCE_LOW));
    }
    @Override
    public int onStartCommand(final Intent intent, int flags, int id) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(84, notification());
        if ("stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("host".equals(intent.getAction()) && host == null && !starting) {
            if (MainActivity.jobBusy) {
                status = "Finish generation before starting hosting";
                update();
                return START_NOT_STICKY;
            }
            starting = true;
            status = "Starting host";
            final int hostStart = ++hostGeneration;
            new Thread(new Runnable() {
                public void run() {
                    Hammer.Server opened = null;
                    String error = null;
                    try {
                        opened = new Hammer.Server(
                            Hammer.serverContext(HammerAndroid.identity(), HammerAndroid.certificate()),
                            new InetSocketAddress(Hammer.PORT), HammerAndroid.store(HammerService.this));
                    } catch (Exception e) {
                        error = "Could not start host: " + e.getMessage();
                    }
                    if (opened != null)
                        try {
                            // the same host answers on the Killa YuE network, found by PeerId instead of address
                            HammerAndroid.network(HammerService.this);
                            KillaNet.serve(opened);
                        } catch (Exception e) {
                            EngineService.addLog("[Network] " + e.getMessage());
                        }
                    final Hammer.Server ready = opened;
                    final String message = error;
                    main.post(new Runnable() {
                        public void run() {
                            if (destroyed || hostStart != hostGeneration) {
                                if (ready != null)
                                    ready.close();
                                return;
                            }
                            starting = false;
                            host = ready;
                            status = ready == null ? message : "Hosting published music";
                            advertise();
                            update();
                        }
                    });
                }
            }, "hammer-start").start();
        } else if ("play".equals(intent.getAction())) {
            releasePlayer();
            final int generation = ++playerGeneration;
            final Hammer.Job prepare = new Hammer.Job();
            opening = prepare;
            playback = "Connecting to hosts";
            new Thread(new Runnable() {
                public void run() {
                    try {
                        final Hammer.Song song = Hammer.Song.read(
                            new DataInputStream(new ByteArrayInputStream(intent.getByteArrayExtra("song"))));
                        final List<Hammer.Card> peers = new ArrayList<Hammer.Card>();
                        for (String card : intent.getStringArrayListExtra("peers"))
                            peers.add(Hammer.Card.parse(card));
                        final int mode = intent.getIntExtra("mode", HammerBuffer.AUTO);
                        long count = 0;
                        Exception failure = null;
                        if (mode != HammerBuffer.ORIGINAL) {
                            for (Hammer.Card peer : HammerRoutes.order(peers)) {
                                prepare.check();
                                try {
                                    count = Hammer.frames(peer, song.id, prepare);
                                    failure = null;
                                    break;
                                } catch (Exception e) {
                                    failure = e;
                                }
                            }
                            if (failure != null)
                                throw failure;
                        }
                        final long frames = count;
                        main.post(new Runnable() {
                            public void run() {
                                if (destroyed || generation != playerGeneration)
                                    return;
                                opening = null;
                                try {
                                    begin(peers, song, mode, frames, intent.getIntExtra("startAt", 0));
                                } catch (Exception e) {
                                    releasePlayer();
                                    playback = "Could not prepare stream";
                                    update();
                                }
                            }
                        });
                    } catch (Exception e) {
                        main.post(new Runnable() {
                            public void run() {
                                if (destroyed || generation != playerGeneration)
                                    return;
                                opening = null;
                                playback = "Host unavailable. Retry from the song menu.";
                                update();
                            }
                        });
                    }
                }
            }, "hammer-prepare").start();
        }
        if (host != null)
            advertise();
        return START_NOT_STICKY;
    }
    private void advertise() {
        try {
            HammerStore store = HammerAndroid.store(this);
            boolean publicHost = !store.catalog("").isEmpty() || store.publicObjects.own() != null;
            // the worldwide public host list on the Killa YuE network; private-circle hosts are never listed
            KillaNet.announce(publicHost && host != null);
            if (publicHost && announcement == null && host != null)
                announcement = new HammerNearby.Announcement(this, HammerAndroid.pin(), host.port());
            else if (!publicHost && announcement != null) {
                announcement.close();
                announcement = null;
            }
        } catch (Exception ignored) {
        }
    }
    private void begin(List<Hammer.Card> peers, final Hammer.Song song, int mode, long frames, final int startAt)
        throws Exception {
        releasePlayer();
        final int generation = ++playerGeneration;
        final HammerBuffer stream = new HammerBuffer(peers, song, mode, frames);
        buffer = stream;
        final MediaPlayer p = new MediaPlayer();
        player = p;
        playback = "Buffering: " + song.title;
        p.setAudioAttributes(new AudioAttributes.Builder()
                                 .setUsage(AudioAttributes.USAGE_MEDIA)
                                 .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                 .build());
        p.setDataSource(new MediaDataSource() {
            public int readAt(long position, byte[] b, int offset, int size) throws IOException {
                return stream.readAt(position, b, offset, size);
            }
            public long getSize() { return stream.size(); }
            public void close() { stream.close(); }
        });
        p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                if (generation != playerGeneration)
                    return;
                playerReady = true;
                if (startAt > 0) mp.seekTo(Math.min(startAt, Math.max(0, mp.getDuration() - 1)));
                if (requestFocus()) {
                    mp.start();
                    playback = "Playing: " + song.title;
                } else
                    playback = "Audio focus unavailable — tap Play";
                update();
            }
        });
        p.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (generation != playerGeneration)
                    return false;
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START)
                    playback = "Buffering: " + song.title;
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END)
                    playback = "Playing: " + song.title;
                return false;
            }
        });
        p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (generation == playerGeneration) {
                    releasePlayer();
                    playback = "Stream interrupted. Open the song and Retry stream.";
                    update();
                }
                return true;
            }
        });
        p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) {
                if (generation == playerGeneration) {
                    releasePlayer();
                    playback = "Finished: " + song.title;
                    update();
                }
            }
        });
        p.prepareAsync();
        update();
    }
    private boolean requestFocus() {
        if (focus == null)
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                                .build())
                        .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                            public void onAudioFocusChange(int change) {
                                if (change < 0 && playerReady && player != null) {
                                    try {
                                        if (!player.isPlaying())
                                            return;
                                        player.pause();
                                        playback = "Paused for other audio";
                                        update();
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        })
                        .build();
        return ((AudioManager)getSystemService(AUDIO_SERVICE)).requestAudioFocus(focus) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }
    private void releasePlayer() {
        playerReady = false;
        ++playerGeneration;
        if (opening != null) {
            opening.cancel();
            opening = null;
        }
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (focus != null) {
            ((AudioManager)getSystemService(AUDIO_SERVICE)).abandonAudioFocusRequest(focus);
            focus = null;
        }
        playback = "Stopped";
    }
    private Notification notification() {
        PendingIntent open =
            PendingIntent.getActivity(this, 84, new Intent(this, HammerActivity.class),
                                      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop =
            PendingIntent.getService(this, 85, new Intent(this, HammerService.class).setAction("stop"),
                                     PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "hammer")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("HAMMER")
            .setContentText(status + " · " + playback)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Stop host and player", stop).build())
            .build();
    }
    private void update() {
        if (destroyed)
            return;
        if (host == null && player == null && opening == null && !starting) {
            stopSelf();
            return;
        }
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(84, notification());
    }
    static String bufferStatus() {
        HammerService s = instance;
        return s != null && s.buffer != null
            ? s.buffer.state + " · " + s.buffer.bufferedBytes / 1024 + " KiB buffered"
            : "";
    }
    static boolean hosting() { return instance != null && instance.host != null; }
    static void disconnect() {
        HammerService s = instance;
        if (s != null && s.host != null) {
            s.host.disconnect();
            s.main.post(new Runnable() {
                public void run() {
                    if (!s.destroyed)
                        s.advertise();
                }
            });
        }
    }
    @Override
    public void onDestroy() {
        if (announcement != null) {
            announcement.close();
            announcement = null;
        }
        destroyed = true;
        if (host != null)
            host.close();
        host = null;
        releasePlayer();
        status = "Hosting off";
        instance = null;
        stopForeground(true);
        super.onDestroy();
    }
    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}

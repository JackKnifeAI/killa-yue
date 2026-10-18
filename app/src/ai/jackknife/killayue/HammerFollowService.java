package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;

/** Direct encrypted subscriptions, with a visible opt-in service and reconnect catch-up. */
public final class HammerFollowService extends Service {
    static volatile String status = "Live Follow off";
    private final List<Watcher> watchers = new ArrayList<Watcher>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private volatile boolean stopped;
    private volatile int generation;
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("hammer-follow", 0);
    }
    static boolean hasArtists(Context c) {
        for (String key : prefs(c).getAll().keySet())
            if (key.startsWith("artist."))
                return true;
        return false;
    }
    static void start(Context c) {
        prefs(c).edit().putBoolean("live", true).apply();
        c.startForegroundService(new Intent(c, HammerFollowService.class));
    }
    static void stop(Context c) {
        prefs(c).edit().putBoolean("live", false).apply();
        c.stopService(new Intent(c, HammerFollowService.class));
    }
    static void changed(Context c) {
        if (prefs(c).getBoolean("live", false))
            c.startForegroundService(new Intent(c, HammerFollowService.class));
    }
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager n = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        n.createNotificationChannel(new NotificationChannel(
            "hammer-live", "HAMMER live connections", NotificationManager.IMPORTANCE_LOW));
        n.createNotificationChannel(new NotificationChannel(
            "hammer-releases", "New music from followed artists", NotificationManager.IMPORTANCE_HIGH));
        wake = ((PowerManager) getSystemService(POWER_SERVICE))
                   .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KillaYuE:HammerLive");
        wake.setReferenceCounted(false);
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "stop".equals(intent.getAction())) {
            prefs(this).edit().putBoolean("live", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!prefs(this).getBoolean("live", false) || !hasArtists(this)) {
            prefs(this).edit().putBoolean("live", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(85, notification("Connecting to peers"));
        stopped = false;
        stopWatchers();
        final int start = ++generation;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final List<HammerStore.Peer> peers =
                        HammerAndroid.store(HammerFollowService.this).followed();
                    main.post(new Runnable() {
                        public void run() {
                            if (stopped || start != generation)
                                return;
                            for (HammerStore.Peer p : peers) {
                                if (watchers.size() == 16)
                                    break;
                                Watcher w = new Watcher(p);
                                watchers.add(w);
                                w.start();
                            }
                            if (watchers.isEmpty()) {
                                prefs(HammerFollowService.this).edit().putBoolean("live", false).apply();
                                stopSelf();
                                return;
                            }
                            report();
                        }
                    });
                } catch (Exception e) {
                    main.post(new Runnable() {
                        public void run() {
                            if (stopped || start != generation)
                                return;
                            status = "Live Follow could not open peers";
                            stopSelf();
                        }
                    });
                }
            }
        }, "hammer-follow-start").start();
        main.removeCallbacks(renew);
        main.post(renew);
        return START_STICKY;
    }
    private final Runnable renew = new Runnable() {
        public void run() {
            if (stopped)
                return;
            report();
            main.postDelayed(this, 5 * 60 * 1000L);
        }
    };
    private void stopWatchers() {
        for (Watcher w : watchers) w.finish();
        watchers.clear();
    }
    private void report() {
        if (stopped)
            return;
        int online = 0;
        for (Watcher w : watchers)
            if (w.online)
                online++;
        // Offline peers cannot deliver instantly; do not keep the CPU awake for failed retries.
        if (online > 0)
            wake.acquire(10 * 60 * 1000L);
        else if (wake.isHeld())
            wake.release();
        status = "Live Follow: " + online + " / " + watchers.size() + " peers connected";
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(85, notification(status));
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 86, new Intent(this, HammerActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop =
            PendingIntent.getService(this, 87, new Intent(this, HammerFollowService.class).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "hammer-live")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("HAMMER Live Follow")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
            .build();
    }
    private final class Watcher extends Thread {
        final HammerStore.Peer peer;
        volatile boolean done, online;
        volatile Hammer.Job job;
        int delay = 2;
        Watcher(HammerStore.Peer peer) {
            super("hammer-live-peer");
            this.peer = peer;
        }
        void finish() {
            done = true;
            if (job != null)
                job.cancel();
            interrupt();
        }
        @Override
        public void run() {
            while (!done) {
                job = new Hammer.Job();
                if (done) {
                    job.cancel();
                    return;
                }
                try {
                    Hammer.subscribe(peer.card, job, new Hammer.Listener() {
                        public void catalog(List<Hammer.Song> songs) throws Exception {
                            if (done || stopped)
                                return;
                            delay = 2;
                            online = true;
                            received(Watcher.this, songs);
                            main.post(new Runnable() {
                                public void run() {
                                    report();
                                }
                            });
                        }
                    });
                    delay = 2;
                } catch (
                    Exception ignored) { /* Pins, invitations and private catalog data never enter logs. */
                }
                online = false;
                main.post(new Runnable() {
                    public void run() {
                        report();
                    }
                });
                if (done)
                    return;
                try {
                    Thread.sleep(
                        delay * 1000L + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000));
                } catch (InterruptedException ignored) {
                }
                delay = Math.min(60, delay * 2);
            }
        }
    }
    private synchronized void received(Watcher watcher, List<Hammer.Song> songs) throws Exception {
        if (stopped || watcher.done)
            return;
        HammerStore.Peer peer = watcher.peer;
        SharedPreferences prefs = prefs(this);
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet())
            if (entry.getKey().startsWith("artist.")) {
                String artist = entry.getKey().substring(7), label = String.valueOf(entry.getValue());
                String baseline =
                    "baseline." + artist + "." + Hammer.hash(peer.card.encode().getBytes("UTF-8"));
                boolean first = !prefs.getBoolean(baseline, false);
                Set<String> seen =
                    new HashSet<String>(prefs.getStringSet("seen." + artist, Collections.<String>emptySet()));
                for (Hammer.Song song : songs)
                    if (Hammer.hash(Base64.getDecoder().decode(song.key)).equals(artist) && seen.add(song.id)
                        && !first) {
                        PendingIntent open = PendingIntent.getActivity(this, song.id.hashCode(),
                            new Intent(this, HammerActivity.class)
                                .setData(android.net.Uri.parse("hammer-release:" + song.id))
                                .putExtra("refresh", true)
                                .putExtra("song", song.id),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        Notification.Builder n =
                            new Notification.Builder(this, "hammer-releases")
                                .setSmallIcon(android.R.drawable.ic_media_play)
                                .setContentTitle(peer.card.isPrivate() ? "New music in your private circle"
                                                                       : label + " released a track")
                                .setContentText(
                                    peer.card.isPrivate() ? "Open HAMMER to refresh and listen" : song.title)
                                .setVisibility(Notification.VISIBILITY_PRIVATE)
                                .setOnlyAlertOnce(true)
                                .setCategory(Notification.CATEGORY_SOCIAL)
                                .setAutoCancel(true)
                                .setContentIntent(open);
                        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                            .notify(song.id, 86, n.build());
                    }
                if (seen.size() > 8192) {
                    List<String> keep = new ArrayList<String>(seen);
                    seen = new HashSet<String>(keep.subList(keep.size() - 4096, keep.size()));
                }
                prefs.edit().putStringSet("seen." + artist, seen).putBoolean(baseline, true).apply();
            }
    }
    @Override
    public void onDestroy() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        stopWatchers();
        if (wake != null && wake.isHeld())
            wake.release();
        status = "Live Follow off";
        stopForeground(true);
        super.onDestroy();
    }
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

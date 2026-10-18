package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.content.*;
import android.os.*;
import android.system.*;
import java.io.*;
import java.util.concurrent.*;

/** Binds only to our permissionless codec worker. Never falls back to in-process parsing. */
final class HammerCodec implements HammerMedia.Codec {
    private final Context app;
    private final boolean leaf;
    private final java.util.LinkedHashMap<String, HammerCodec> sessions =
        new java.util.LinkedHashMap<String, HammerCodec>(4, .75f, true);
    private volatile IBinder remote;
    private IBinder retired;
    private CountDownLatch ready;
    private ServiceConnection connection;
    private String scope = "";
    private long lastUse;
    private final ScheduledExecutorService idle = Executors.newSingleThreadScheduledExecutor();
    HammerCodec(Context c) { this(c, false); }
    private HammerCodec(Context c, boolean leaf) {
        app = c.getApplicationContext();
        this.leaf = leaf;
        if (leaf)
            idle.scheduleWithFixedDelay(new Runnable() {
                public void run() {
                    synchronized (HammerCodec.this) {
                        if (connection != null && System.nanoTime() - lastUse > 30000000000L)
                            release();
                    }
                }
            }, 30, 30, TimeUnit.SECONDS);
    }
    private void release() {
        ServiceConnection old = connection;
        connection = null;
        IBinder oldRemote = remote;
        remote = null;
        scope = "";
        final CountDownLatch dead = new CountDownLatch(1);
        if (oldRemote != null)
            try {
                oldRemote.linkToDeath(new IBinder.DeathRecipient() {
                    public void binderDied() { dead.countDown(); }
                }, 0);
            } catch (Exception ignored) {
                dead.countDown();
            }
        if (old != null)
            try {
                app.unbindService(old);
            } catch (Exception ignored) {
            }
        if (Build.VERSION.SDK_INT < 29 && oldRemote != null)
            try {
                retired = oldRemote;
                dead.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
    }
    private synchronized IBinder connect(String nextScope) throws Exception {
        Hammer.require(Looper.myLooper() != Looper.getMainLooper(), "Media processing must run off UI");
        lastUse = System.nanoTime();
        if (scope.equals(nextScope) && remote != null && remote.isBinderAlive())
            return remote;
        release();
        Hammer.require(retired == null || !retired.isBinderAlive(), "Previous codec scope has not stopped");
        retired = null;
        scope = nextScope;
        ready = new CountDownLatch(1);
        final CountDownLatch latch = ready;
        connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
                if (connection == this)
                    remote = b;
                latch.countDown();
            }
            public void onServiceDisconnected(ComponentName n) {
                if (connection == this)
                    remote = null;
            }
            public void onNullBinding(ComponentName n) { latch.countDown(); }
            public void onBindingDied(ComponentName n) {
                if (connection == this)
                    remote = null;
                latch.countDown();
            }
        };
        Intent intent = new Intent(app, HammerCodecService.class);
        boolean bound = Build.VERSION.SDK_INT >= 29
                            ? app.bindIsolatedService(intent, Context.BIND_AUTO_CREATE,
                                                      "media" + nextScope.substring(0, 16) + "x" +
                                                          Long.toHexString(System.nanoTime()),
                                                      app.getMainExecutor(), connection)
                            : app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Hammer.require(bound, "Could not start isolated codec");
        Hammer.require(latch.await(5, TimeUnit.SECONDS) && remote != null, "Isolated codec unavailable");
        return remote;
    }
    private synchronized Parcel call(int op, File f, String id, int segment, int rate, byte[] bytes)
        throws Exception {
        String key = Hammer.hash(
            (f == null ? "incoming-opus"
                       : "file:" + f.getCanonicalPath() + ":" + f.length() + ":" + f.lastModified())
                .getBytes("UTF-8"));
        if (!leaf) {
            HammerCodec session = sessions.get(key);
            if (session == null) {
                int limit = Build.VERSION.SDK_INT >= 29 ? 2 : 1;
                while (sessions.size() >= limit) {
                    java.util.Iterator<HammerCodec> it = sessions.values().iterator();
                    HammerCodec old = it.next();
                    synchronized (old) {
                        old.release();
                        Hammer.require(Build.VERSION.SDK_INT >= 29 || old.retired == null ||
                                           !old.retired.isBinderAlive(),
                                       "Previous codec scope has not stopped");
                        old.idle.shutdownNow();
                    }
                    it.remove();
                }
                session = new HammerCodec(app, true);
                sessions.put(key, session);
            }
            return session.call(op, f, id, segment, rate, bytes);
        }
        IBinder target = connect(key);
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        ParcelFileDescriptor pfd = null;
        FileDescriptor raw = null;
        boolean ok = false;
        try {
            in.writeInterfaceToken(HammerCodecService.DESCRIPTOR);
            if (f != null) {
                raw = Os.open(f.getPath(),
                              OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
                StructStat stat = Os.fstat(raw);
                Hammer.require(OsConstants.S_ISREG(stat.st_mode), "Only regular media files are allowed");
                pfd = ParcelFileDescriptor.dup(raw);
                pfd.writeToParcel(in, 0);
            }
            if (op == 2) {
                in.writeString(id);
                in.writeInt(segment);
                in.writeInt(rate);
            }
            if (op == 3)
                in.writeByteArray(bytes);
            Hammer.require(target.transact(op, in, out, 0), "Unsupported codec request");
            out.readException();
            ok = true;
            return out;
        } finally {
            in.recycle();
            if (!ok)
                out.recycle();
            if (pfd != null)
                pfd.close();
            if (raw != null)
                Os.close(raw);
        }
    }
    public long frames(File f) throws Exception {
        Parcel p = call(1, f, null, 0, 0, null);
        try {
            return p.readLong();
        } finally {
            p.recycle();
        }
    }
    public byte[] encode(File f, String id, int segment, int rate) throws Exception {
        Parcel p = call(2, f, id, segment, rate, null);
        try {
            return p.createByteArray();
        } finally {
            p.recycle();
        }
    }
    public byte[] decode(byte[] packet) throws Exception {
        Hammer.require(packet.length <= 262144, "Packet too large");
        Parcel p = call(3, null, null, 0, 0, packet);
        try {
            return p.createByteArray();
        } finally {
            p.recycle();
        }
    }
    static final class Image {
        final byte[] jpeg;
        final android.graphics.Bitmap bitmap;
        Image(byte[] jpeg, android.graphics.Bitmap bitmap) {
            this.jpeg = jpeg;
            this.bitmap = bitmap;
        }
    }
    Image imagePreview(File f) throws Exception {
        Hammer.require(f.length() <= 16 * 1024 * 1024, "Choose an image under 16 MiB");
        Parcel p = call(4, f, null, 0, 0, null);
        try {
            byte[] jpeg = p.createByteArray();
            int width = p.readInt(), height = p.readInt();
            Hammer.require(jpeg != null && jpeg.length <= HammerPublic.IMAGE_LIMIT && width > 0 &&
                               width <= 384 && height > 0 && height <= 384,
                           "Invalid processed artwork");
            int[] pixels = p.createIntArray();
            Hammer.require(pixels != null && pixels.length == width * height, "Invalid artwork pixels");
            // Main process consumes bounded pixels, never runs a second parser on worker-supplied JPEG.
            return new Image(jpeg, android.graphics.Bitmap.createBitmap(
                                       pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888));
        } finally {
            p.recycle();
        }
    }
    byte[] image(File f) throws Exception {
        Image image = imagePreview(f);
        image.bitmap.recycle();
        return image.jpeg;
    }
}

package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.Service;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

/** Permissionless media worker. Receives one read-only descriptor, never an app file path or key. */
public final class HammerCodecService extends Service {
    static final String DESCRIPTOR = "ai.jackknife.killayue.HammerCodec.v1";
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final Binder binder = new Binder() {
        @Override
        protected synchronized boolean onTransact(int code, Parcel in, Parcel out, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                out.writeString(DESCRIPTOR);
                return true;
            }
            ScheduledFuture<?> timeout = watchdog.schedule(new Runnable() {
                public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
            }, 12, TimeUnit.SECONDS);
            try {
                in.enforceInterface(DESCRIPTOR);
                if (code == 3) {
                    byte[] packet = in.createByteArray();
                    Hammer.require(packet != null && packet.length <= 262144, "Packet too large");
                    byte[] pcm = HammerNative.decode(packet);
                    Hammer.require(pcm.length <= 384000, "Decoded segment too large");
                    out.writeNoException();
                    out.writeByteArray(pcm);
                } else if (code >= 1 && code <= 4) {
                    ParcelFileDescriptor fd = ParcelFileDescriptor.CREATOR.createFromParcel(in);
                    try {

                        if (code == 1) {
                            long frames = HammerNative.framesFd(fd.getFd());
                            out.writeNoException();
                            out.writeLong(frames);
                        } else if (code == 2) {
                            String identity = in.readString();
                            int segment = in.readInt(), rate = in.readInt();
                            Hammer.require(Hammer.digest(identity), "Invalid recording identity");
                            byte[] bytes = HammerNative.encodeFd(fd.getFd(), identity, segment, rate);
                            Hammer.require(bytes.length <= 262144, "Segment too large");
                            out.writeNoException();
                            out.writeByteArray(bytes);
                        } else {
                            CleanImage image = cleanImage(fd.getFileDescriptor());
                            out.writeNoException();
                            out.writeByteArray(image.jpeg);
                            out.writeInt(image.width);
                            out.writeInt(image.height);
                            out.writeIntArray(image.pixels);
                        }
                    } finally {
                        fd.close();
                    }
                } else
                    return false;
            } catch (Exception e) {
                out.setDataPosition(0);
                out.writeException(new IllegalArgumentException("Media rejected"));
            } finally {
                timeout.cancel(false);
            }
            return true;
        }
    };
    private static final class CleanImage {
        byte[] jpeg;
        int width, height;
        int[] pixels;
    }
    private CleanImage cleanImage(FileDescriptor fd) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd, null, o);
        Hammer.require(o.outWidth > 0 && o.outHeight > 0 && (long)o.outWidth * o.outHeight <= 40000000,
                       "Image dimensions exceed limit");
        Hammer.require("image/jpeg".equals(o.outMimeType) || "image/png".equals(o.outMimeType) ||
                           "image/webp".equals(o.outMimeType),
                       "Choose JPEG, PNG or WebP");
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 768)
            o.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeFileDescriptor(fd, null, o);
        Hammer.require(decoded != null, "Could not decode image");
        Bitmap clean = null;
        try {
            double scale = Math.min(1.0, 384.0 / Math.max(decoded.getWidth(), decoded.getHeight()));
            clean =
                Bitmap.createBitmap(Math.max(1, (int)(decoded.getWidth() * scale)),
                                    Math.max(1, (int)(decoded.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(clean);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(decoded, null, new Rect(0, 0, clean.getWidth(), clean.getHeight()),
                              new Paint(Paint.FILTER_BITMAP_FLAG));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Hammer.require(clean.compress(Bitmap.CompressFormat.JPEG, 85, bytes), "Could not encode image");
            Hammer.require(bytes.size() <= HammerPublic.IMAGE_LIMIT, "Image too large");
            CleanImage result = new CleanImage();
            result.jpeg = bytes.toByteArray();
            result.width = clean.getWidth();
            result.height = clean.getHeight();
            result.pixels = new int[result.width * result.height];
            clean.getPixels(result.pixels, 0, result.width, 0, 0, result.width, result.height);
            return result;
        } finally {
            decoded.recycle();
            if (clean != null)
                clean.recycle();
        }
    }
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    @Override
    public boolean onUnbind(Intent intent) {
        // No native buffers or descriptors survive a media scope. Android creates a fresh worker next time.
        android.os.Process.killProcess(android.os.Process.myPid());
        return false;
    }
    @Override
    public void onDestroy() {
        watchdog.shutdownNow();
        super.onDestroy();
    }
}

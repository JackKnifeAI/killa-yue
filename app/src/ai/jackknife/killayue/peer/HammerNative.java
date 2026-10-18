package ai.jackknife.killayue.peer;

import java.io.IOException;

/** The same C++ codecs and bounded playback cache run on Android and the desktop host. */
public final class HammerNative {
    private HammerNative() {}
    static {
        System.loadLibrary("hammer");
    }
    public static native long frames(String path) throws IOException;
    public static native byte[] encode(String path, String identity, int segment, int bitrate)
        throws IOException;
    public static native byte[] decode(byte[] packet) throws IOException;
    public static native byte[] range(String path, long offset, int length) throws IOException;
    public static native long cacheCreate() throws IOException;
    public static native void cachePut(long cache, long block, byte[] bytes) throws IOException;
    public static native int cacheRead(
        long cache, long block, byte[] destination, int offset, int start, int count) throws IOException;
    public static native boolean cacheHas(long cache, long block) throws IOException;
    public static native int cacheBytes(long cache) throws IOException;
    public static native void cacheClose(long cache);
    public static native int autoBitrate(long elapsedMillis, int previous);
}

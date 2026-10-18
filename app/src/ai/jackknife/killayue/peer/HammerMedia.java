package ai.jackknife.killayue.peer;

import java.io.*;

/** Android installs an isolated-process implementation; desktop uses the bounded native codec. */
public final class HammerMedia {
    private HammerMedia() {}
    public interface Codec {
        long frames(File file) throws Exception;
        byte[] encode(File file, String identity, int segment, int bitrate) throws Exception;
        byte[] decode(byte[] packet) throws Exception;
    }
    public static volatile Codec codec = new Codec() {
        public long frames(File f) throws Exception { return HammerNative.frames(f.getPath()); }
        public byte[] encode(File f, String id, int n, int rate) throws Exception {
            return HammerNative.encode(f.getPath(), id, n, rate);
        }
        public byte[] decode(byte[] packet) throws Exception { return HammerNative.decode(packet); }
    };
}

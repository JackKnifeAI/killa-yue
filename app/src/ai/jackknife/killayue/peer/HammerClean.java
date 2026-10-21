package ai.jackknife.killayue.peer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Songs leave the phone with what describes the music and nothing that identifies the person. A published copy keeps
 * the title, artist, album, genre, track and disc, BPM and key, the plain and timed lyrics, the "Killa YuE" mark and the
 * cover: everything Killa YuE writes and reads. It drops the rest: comments other programs wrote, dates, the names of
 * composers, engineers and owners, ISRC codes, private and custom frames, URLs and embedded files, and from the cover
 * its EXIF (GPS, camera), XMP and colour-profile blocks. A song made in Killa YuE already carries only kept data, so
 * it is published as it is and keeps its id.
 *
 * The result depends only on the input's bytes, so publishing the same file again gives the same song id.
 */
public final class HammerClean {
    private HammerClean() {}

    static final Set<String> ID3_KEEP = new HashSet<String>(Arrays.asList(
        "TIT2", "TPE1", "TPE2", "TALB", "TCON", "TRCK", "TPOS", "TBPM", "TKEY", "USLT", "SYLT", "APIC", "TSSE"));
    static final Set<String> VORBIS_KEEP = new HashSet<String>(Arrays.asList(
        "TITLE", "ARTIST", "ALBUMARTIST", "ALBUM", "GENRE", "TRACKNUMBER", "TRACKTOTAL", "TOTALTRACKS", "DISCNUMBER",
        "DISCTOTAL", "TOTALDISCS", "BPM", "KEY", "INITIALKEY", "LYRICS", "UNSYNCEDLYRICS"));
    static final Set<String> RIFF_INFO_KEEP = new HashSet<String>(Arrays.asList("INAM", "IART", "IPRD", "IGNR", "ITRK"));
    static final String MARK = "Killa YuE";
    static final int TAG_LIMIT = 32 << 20;

    /**
     * Writes a clean copy of {@code in} to {@code out} and returns true, or returns false (writing nothing) when
     * {@code in} carries nothing to remove. Throws when the file is not audio this understands.
     */
    public static boolean clean(File in, File out, Hammer.Job job) throws Exception {
        String name = in.getName().toLowerCase(Locale.ROOT);
        try (RandomAccessFile f = new RandomAccessFile(in, "r")) {
            Plan p = name.endsWith(".flac") ? flac(f) : name.endsWith(".wav") ? wav(f) : mp3(f);
            if (p.suffix.length == 0 && p.end == f.length() && p.start == p.prefix.length &&
                Arrays.equals(p.prefix, read(f, 0, p.prefix.length)))
                return false;
            try (FileOutputStream fo = new FileOutputStream(out);
                 OutputStream o = new BufferedOutputStream(fo, 65536)) {
                o.write(p.prefix);
                f.seek(p.start);
                byte[] b = new byte[65536];
                long left = p.end - p.start;
                while (left > 0) {
                    job.check();
                    int n = f.read(b, 0, (int)Math.min(b.length, left));
                    Hammer.require(n > 0, "Audio file ended early");
                    o.write(b, 0, n);
                    left -= n;
                }
                o.write(p.suffix);
                o.flush();
                fo.getFD().sync();
            }
            return true;
        }
    }

    /** The clean file: {@code prefix}, bytes {@code start} to {@code end} of the original, then {@code suffix}. */
    static final class Plan {
        final byte[] prefix, suffix;
        final long start, end;
        Plan(byte[] prefix, long start, long end, byte[] suffix) {
            this.prefix = prefix;
            this.start = start;
            this.end = end;
            this.suffix = suffix;
        }
    }

    // ---- bytes

    static byte[] read(RandomAccessFile f, long at, int n) throws IOException {
        if (at < 0 || n < 0 || at + n > f.length())
            return null;
        byte[] b = new byte[n];
        f.seek(at);
        f.readFully(b);
        return b;
    }
    static boolean is(byte[] b, int at, String magic) {
        if (b == null || at < 0 || at + magic.length() > b.length)
            return false;
        for (int i = 0; i < magic.length(); i++)
            if (b[at + i] != (byte)magic.charAt(i))
                return false;
        return true;
    }
    static int syncsafe(byte[] b, int at) {
        return ((b[at] & 0x7f) << 21) | ((b[at + 1] & 0x7f) << 14) | ((b[at + 2] & 0x7f) << 7) | (b[at + 3] & 0x7f);
    }
    static int be32(byte[] b, int at) {
        return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }
    static long le32(byte[] b, int at) {
        return (b[at] & 0xffL) | ((b[at + 1] & 0xffL) << 8) | ((b[at + 2] & 0xffL) << 16) | ((b[at + 3] & 0xffL) << 24);
    }
    static void putLe32(byte[] b, int at, long v) {
        for (int i = 0; i < 4; i++)
            b[at + i] = (byte)(v >>> (8 * i));
    }
    static void writeLe32(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 4; i++)
            o.write((int)(v >>> (8 * i)));
    }
    static void writeBe32(ByteArrayOutputStream o, int v) {
        for (int i = 3; i >= 0; i--)
            o.write(v >>> (8 * i));
    }

    // ---- pictures: JPEG and PNG keep the image and lose EXIF, XMP, IPTC, comments and colour-profile blocks

    /** The picture without its hidden data, or null when it is not a well-formed JPEG or PNG. */
    static byte[] picture(byte[] img) {
        try {
            if (img.length > 4 && (img[0] & 0xff) == 0xff && (img[1] & 0xff) == 0xd8)
                return jpeg(img);
            if (img.length > 8 && (img[0] & 0xff) == 0x89 && is(img, 1, "PNG"))
                return png(img);
        } catch (RuntimeException damaged) {
        }
        return null;
    }
    private static byte[] jpeg(byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length);
        o.write(0xff);
        o.write(0xd8);
        int i = 2;
        while (i + 4 <= b.length) {
            if ((b[i] & 0xff) != 0xff)
                return null;
            int m = b[i + 1] & 0xff;
            if (m == 0xff) {  // fill byte
                i++;
                continue;
            }
            if (m == 0xda) {  // start of scan: the image data runs to the end
                o.write(b, i, b.length - i);
                return o.toByteArray();
            }
            if (m == 0x01 || (m >= 0xd0 && m <= 0xd7)) {
                o.write(b, i, 2);
                i += 2;
                continue;
            }
            int len = ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
            if (len < 2 || i + 2 + len > b.length)
                return null;
            boolean hidden = (m >= 0xe1 && m <= 0xed) || m == 0xef || m == 0xfe;  // APP1-13, APP15, COM
            if (!hidden)
                o.write(b, i, 2 + len);
            i += 2 + len;
        }
        return null;
    }
    private static final Set<String> PNG_KEEP = new HashSet<String>(Arrays.asList(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "pHYs", "sBIT", "bKGD"));
    private static byte[] png(byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length);
        o.write(b, 0, 8);
        int i = 8;
        while (i + 12 <= b.length) {
            int len = be32(b, i);
            if (len < 0 || i + 12L + len > b.length)
                return null;
            String type = new String(b, i + 4, 4, StandardCharsets.ISO_8859_1);
            if (PNG_KEEP.contains(type))
                o.write(b, i, 12 + len);
            i += 12 + len;
            if (type.equals("IEND"))
                return o.toByteArray();
        }
        return null;
    }

    // ---- MP3: ID3v2 at the front rebuilt from the kept frames; ID3v1, APE, Lyrics3 and trailing ID3v2 removed

    static Plan mp3(RandomAccessFile f) throws IOException {
        long start = 0;
        byte[] tag = new byte[0];
        boolean first = true;
        while (true) {  // some files carry more than one tag at the front: the first one's kept frames survive
            byte[] h = read(f, start, 10);
            if (!is(h, 0, "ID3") || (h[6] | h[7] | h[8] | h[9]) < 0)
                break;
            long size = 10L + syncsafe(h, 6) + ((h[5] & 0x10) != 0 ? 10 : 0);
            Hammer.require(start + size <= f.length(), "Damaged ID3 tag");
            if (first && size <= TAG_LIMIT)
                tag = id3(read(f, start, (int)size));
            first = false;
            start += size;
        }
        long end = f.length();
        boolean more = true;
        while (more && end > start) {
            more = false;
            if (is(read(f, end - 128, 3), 0, "TAG")) {  // ID3v1, and the enhanced "TAG+" block before it
                end -= 128;
                if (is(read(f, end - 227, 4), 0, "TAG+"))
                    end -= 227;
                more = true;
            }
            byte[] ape = read(f, end - 32, 32);
            if (is(ape, 0, "APETAGEX")) {  // APE footer: the size counts the items and the footer, plus a header
                long size = le32(ape, 12) + ((ape[23] & 0x80) != 0 ? 32 : 0);
                Hammer.require(size >= 32 && size <= end - start, "Damaged APE tag");
                end -= size;
                more = true;
            }
            byte[] l3 = read(f, end - 15, 15);
            if (is(l3, 6, "LYRICS200")) {  // Lyrics3 v2: six digits of size, then the marker
                long size = Long.parseLong(new String(l3, 0, 6, StandardCharsets.US_ASCII).trim()) + 15;
                Hammer.require(size <= end - start && is(read(f, end - size, 11), 0, "LYRICSBEGIN"),
                               "Damaged Lyrics3 tag");
                end -= size;
                more = true;
            }
            byte[] foot = read(f, end - 10, 10);
            if (is(foot, 0, "3DI")) {  // an ID3v2.4 tag at the end, found by its footer
                long size = 20L + syncsafe(foot, 6);
                Hammer.require(size <= end - start, "Damaged ID3 tag");
                end -= size;
                more = true;
            }
        }
        Hammer.require(end > start, "No MP3 audio in this file");
        return new Plan(tag, start, end, new byte[0]);
    }

    /** The tag with only the kept frames, in its own version (2.3 or 2.4), or no tag when none are kept. */
    static byte[] id3(byte[] t) {
        int major = t[3];
        if (major != 3 && major != 4)
            return new byte[0];  // ID3v2.2 and unknown versions: dropped whole
        int flags = t[5] & 0xff;
        byte[] body = Arrays.copyOfRange(t, 10, 10 + syncsafe(t, 6));
        if (major == 3 && (flags & 0x80) != 0)
            body = resync(body);  // the whole 2.3 tag was unsynchronised
        int i = 0;
        if ((flags & 0x40) != 0 && body.length >= 4)  // extended header
            i = major == 3 ? 4 + be32(body, 0) : syncsafe(body, 0);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        while (i >= 0 && i + 10 <= body.length && body[i] != 0) {
            String id = new String(body, i, 4, StandardCharsets.ISO_8859_1);
            int size = major == 4 ? syncsafe(body, i + 4) : be32(body, i + 4);
            int format = body[i + 9] & 0xff;
            if (size < 0 || i + 10L + size > body.length)
                break;
            byte[] data = Arrays.copyOfRange(body, i + 10, i + 10 + size);
            i += 10 + size;
            // compressed, encrypted, grouped or unsynchronised frames are dropped rather than decoded
            boolean encoded = major == 4 ? (format & 0x4f) != 0 : (format & 0xe0) != 0;
            if (encoded || !ID3_KEEP.contains(id))
                continue;
            if (id.equals("TSSE") && !MARK.equals(text(data)))
                continue;  // other programs' software stamps
            if (id.equals("APIC") && (data = apic(data)) == null)
                continue;
            frames.write(id.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
            if (major == 4) {
                frames.write((data.length >> 21) & 0x7f);
                frames.write((data.length >> 14) & 0x7f);
                frames.write((data.length >> 7) & 0x7f);
                frames.write(data.length & 0x7f);
            } else
                writeBe32(frames, data.length);
            frames.write(0);
            frames.write(0);
            frames.write(data, 0, data.length);
        }
        if (frames.size() == 0)
            return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = frames.size();
        out.write(new byte[] {'I', 'D', '3', (byte)major, 0, 0}, 0, 6);
        out.write((n >> 21) & 0x7f);
        out.write((n >> 14) & 0x7f);
        out.write((n >> 7) & 0x7f);
        out.write(n & 0x7f);
        out.write(frames.toByteArray(), 0, n);
        return out.toByteArray();
    }
    private static byte[] resync(byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length);
        for (int i = 0; i < b.length; i++) {
            o.write(b[i]);
            if ((b[i] & 0xff) == 0xff && i + 1 < b.length && b[i + 1] == 0)
                i++;
        }
        return o.toByteArray();
    }
    /** A text frame's value (encodings 0 and 3, enough to recognise our own mark). */
    private static String text(byte[] d) {
        if (d.length < 1 || (d[0] != 0 && d[0] != 3))
            return "";
        int n = d.length;
        while (n > 1 && d[n - 1] == 0)
            n--;
        return new String(d, 1, n - 1, d[0] == 3 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
    }
    /** A cover frame with an empty description and a clean JPEG or PNG; null drops it. */
    private static byte[] apic(byte[] d) {
        int enc = d.length > 0 ? d[0] : -1;
        if (enc < 0 || enc > 3)
            return null;
        int i = 1;
        while (i < d.length && d[i] != 0)
            i++;
        String mime = new String(d, 1, Math.max(0, i - 1), StandardCharsets.ISO_8859_1);
        i++;
        if (i >= d.length)
            return null;
        int type = d[i++] & 0xff;
        if (enc == 1 || enc == 2) {  // UTF-16 description ends with two zero bytes on an even boundary
            while (i + 1 < d.length && !(d[i] == 0 && d[i + 1] == 0))
                i += 2;
            i += 2;
        } else {
            while (i < d.length && d[i] != 0)
                i++;
            i++;
        }
        if (i >= d.length)
            return null;
        byte[] img = picture(Arrays.copyOfRange(d, i, d.length));
        if (img == null)
            return null;
        if (mime.isEmpty())
            mime = img[0] == (byte)0x89 ? "image/png" : "image/jpeg";
        ByteArrayOutputStream o = new ByteArrayOutputStream(img.length + 32);
        o.write(0);
        byte[] m = mime.getBytes(StandardCharsets.ISO_8859_1);
        o.write(m, 0, m.length);
        o.write(0);
        o.write(type);
        o.write(0);
        o.write(img, 0, img.length);
        return o.toByteArray();
    }

    // ---- FLAC: STREAMINFO, SEEKTABLE, the kept Vorbis comments and clean pictures

    static Plan flac(RandomAccessFile f) throws IOException {
        long at = 0;
        while (true) {  // ID3v2 some taggers put in front of FLAC: dropped
            byte[] h = read(f, at, 10);
            if (!is(h, 0, "ID3"))
                break;
            at += 10L + syncsafe(h, 6) + ((h[5] & 0x10) != 0 ? 10 : 0);
        }
        Hammer.require(is(read(f, at, 4), 0, "fLaC"), "This is not FLAC audio");
        long p = at + 4;
        boolean last = false, streaminfo = false;
        List<byte[]> blocks = new ArrayList<byte[]>();
        while (!last) {
            byte[] h = read(f, p, 4);
            Hammer.require(h != null, "Damaged FLAC header");
            last = (h[0] & 0x80) != 0;
            int type = h[0] & 0x7f;
            int len = ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
            Hammer.require(p + 4 + len <= f.length(), "Damaged FLAC header");
            byte[] data = null;
            if (type == 0 || type == 3)  // STREAMINFO, SEEKTABLE
                data = read(f, p + 4, len);
            else if (type == 4)
                data = vorbis(read(f, p + 4, len));
            else if (type == 6)
                data = flacPicture(read(f, p + 4, len));
            if (data != null) {
                ByteArrayOutputStream b = new ByteArrayOutputStream(data.length + 4);
                b.write(type);
                b.write(data.length >> 16);
                b.write(data.length >> 8);
                b.write(data.length);
                b.write(data, 0, data.length);
                blocks.add(b.toByteArray());
                streaminfo |= type == 0;
            }
            p += 4 + len;
        }
        Hammer.require(streaminfo, "Damaged FLAC header");
        ByteArrayOutputStream keep = new ByteArrayOutputStream();
        keep.write("fLaC".getBytes(StandardCharsets.US_ASCII), 0, 4);
        blocks.get(blocks.size() - 1)[0] |= (byte)0x80;
        for (byte[] b : blocks)
            keep.write(b, 0, b.length);
        return new Plan(keep.toByteArray(), p, f.length(), new byte[0]);
    }
    /** Vorbis comments with only the kept fields and an empty vendor string; null when none are kept. */
    private static byte[] vorbis(byte[] d) {
        try {
            int vlen = (int)le32(d, 0);
            int i = 4 + vlen, n = (int)le32(d, i);
            i += 4;
            ByteArrayOutputStream fields = new ByteArrayOutputStream();
            int kept = 0;
            for (int k = 0; k < n; k++) {
                int len = (int)le32(d, i);
                if (len < 0 || i + 4L + len > d.length)
                    break;
                String s = new String(d, i + 4, len, StandardCharsets.UTF_8);
                int eq = s.indexOf('=');
                if (eq > 0 && VORBIS_KEEP.contains(s.substring(0, eq).toUpperCase(Locale.ROOT))) {
                    fields.write(d, i, 4 + len);
                    kept++;
                }
                i += 4 + len;
            }
            if (kept == 0)
                return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            writeLe32(o, 0);
            writeLe32(o, kept);
            o.write(fields.toByteArray(), 0, fields.size());
            return o.toByteArray();
        } catch (RuntimeException damaged) {
            return null;
        }
    }
    private static byte[] flacPicture(byte[] d) {
        try {
            int type = be32(d, 0), mlen = be32(d, 4);
            String mime = new String(d, 8, mlen, StandardCharsets.ISO_8859_1);
            int i = 8 + mlen, dlen = be32(d, i);
            i += 4 + dlen;
            byte[] dims = Arrays.copyOfRange(d, i, i + 16);
            int len = be32(d, i + 16);
            byte[] img = picture(Arrays.copyOfRange(d, i + 20, i + 20 + len));
            if (img == null)
                return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream(img.length + 64);
            writeBe32(o, type);
            byte[] m = mime.getBytes(StandardCharsets.ISO_8859_1);
            writeBe32(o, m.length);
            o.write(m, 0, m.length);
            writeBe32(o, 0);  // empty description
            o.write(dims, 0, 16);
            writeBe32(o, img.length);
            o.write(img, 0, img.length);
            return o.toByteArray();
        } catch (RuntimeException damaged) {
            return null;
        }
    }

    // ---- WAV: fmt, fact, ds64, the kept INFO fields, and the audio

    static Plan wav(RandomAccessFile f) throws IOException {
        byte[] h = read(f, 0, 12);
        boolean rf64 = is(h, 0, "RF64") || is(h, 0, "BW64");
        Hammer.require((is(h, 0, "RIFF") || rf64) && is(h, 8, "WAVE"), "This is not WAV audio");
        ByteArrayOutputStream keep = new ByteArrayOutputStream();
        keep.write(h, 0, 12);
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        long p = 12, dataAt = -1, dataLen = 0;
        boolean fmt = false;
        while (p + 8 <= f.length()) {
            byte[] c = read(f, p, 8);
            String id = new String(c, 0, 4, StandardCharsets.US_ASCII);
            long len = le32(c, 4);
            if (id.equals("data") && dataAt < 0) {
                if (len == 0xffffffffL || p + 8 + len > f.length())  // RF64, or a recorder that never fixed the size
                    len = f.length() - p - 8;
                dataAt = p;
                dataLen = len;
            } else if (p + 8 + len <= f.length() && len <= TAG_LIMIT) {
                if (id.equals("fmt ") || id.equals("fact") || id.equals("ds64")) {
                    byte[] b = read(f, p, (int)(8 + len));
                    keep.write(b, 0, b.length);
                    if ((len & 1) != 0)
                        keep.write(0);
                    fmt |= id.equals("fmt ");
                } else if (id.equals("LIST")) {
                    byte[] b = read(f, p + 8, (int)len);
                    if (is(b, 0, "INFO"))
                        for (int i = 4; i + 8 <= b.length;) {
                            int n = (int)le32(b, i + 4);
                            if (n < 0 || i + 8L + n > b.length)
                                break;
                            if (RIFF_INFO_KEEP.contains(new String(b, i, 4, StandardCharsets.US_ASCII))) {
                                info.write(b, i, 8 + n);
                                if ((n & 1) != 0)
                                    info.write(0);
                            }
                            i += 8 + n + (n & 1);
                        }
                }
            }
            if (dataAt == p)
                p += 8 + dataLen + (dataLen & 1);
            else
                p += 8 + len + (len & 1);
        }
        Hammer.require(fmt && dataAt > 0, "No WAV audio in this file");
        if (info.size() > 0) {
            keep.write("LIST".getBytes(StandardCharsets.US_ASCII), 0, 4);
            writeLe32(keep, 4 + info.size());
            keep.write("INFO".getBytes(StandardCharsets.US_ASCII), 0, 4);
            keep.write(info.toByteArray(), 0, info.size());
        }
        long end = Math.min(f.length(), dataAt + 8 + dataLen + (dataLen & 1));
        byte[] prefix = keep.toByteArray();
        long total = prefix.length + (end - dataAt);
        if (rf64) {  // the 64-bit sizes live in ds64, right after the header
            Hammer.require(is(prefix, 12, "ds64"), "Damaged RF64 header");
            putLe32(prefix, 20, total - 8);
            putLe32(prefix, 24, (total - 8) >>> 32);
        } else
            putLe32(prefix, 4, total - 8);
        return new Plan(prefix, dataAt, end, new byte[0]);
    }
}

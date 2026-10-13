package ai.jackknife.killayue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Timed lyrics that travel with a song: an .lrc file beside the audio, and, for MP3,
 * an ID3v2.4 tag holding the title, the plain lyrics (USLT) and the timed lyrics (SYLT).
 */
public class Tags {
    static String lrc(String title, ArrayList<Karaoke.Line> lines) {
        StringBuilder b = new StringBuilder();
        b.append("[ti:").append(title.replace("]", ")")).append("]\n[re:Killa YuE]\n");
        for (int i = 0; i < lines.size(); i++) {
            int ms = lines.get(i).start;
            b.append(String.format(Locale.US, "[%02d:%02d.%02d]", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10));
            b.append(lines.get(i).text).append('\n');
        }
        return b.toString();
    }

    private static byte[] synchsafe(int n) {
        return new byte[] {(byte) ((n >> 21) & 0x7f), (byte) ((n >> 14) & 0x7f), (byte) ((n >> 7) & 0x7f), (byte) (n & 0x7f)};
    }

    private static void frame(ByteArrayOutputStream out, String id, byte[] body) throws Exception {
        out.write(id.getBytes("ISO-8859-1"));
        out.write(synchsafe(body.length));
        out.write(0);
        out.write(0);
        out.write(body);
    }

    private static byte[] text(String s) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(3); // UTF-8
        b.write(s.getBytes("UTF-8"));
        return b.toByteArray();
    }

    static byte[] id3(String title, ArrayList<Karaoke.Line> lines) throws Exception {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frame(frames, "TIT2", text(title));
        frame(frames, "TSSE", text("Killa YuE"));
        if (lines.size() > 0) {
            StringBuilder plain = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) plain.append(lines.get(i).text).append('\n');
            ByteArrayOutputStream u = new ByteArrayOutputStream();
            u.write(3);
            u.write("eng".getBytes("ISO-8859-1"));
            u.write(0); // empty description
            u.write(plain.toString().getBytes("UTF-8"));
            frame(frames, "USLT", u.toByteArray());

            ByteArrayOutputStream s = new ByteArrayOutputStream();
            s.write(3);
            s.write("eng".getBytes("ISO-8859-1"));
            s.write(2); // times in milliseconds
            s.write(1); // content: lyrics
            s.write(0); // empty description
            for (int i = 0; i < lines.size(); i++) {
                s.write(lines.get(i).text.getBytes("UTF-8"));
                s.write(0);
                int ms = lines.get(i).start;
                s.write(new byte[] {(byte) (ms >> 24), (byte) (ms >> 16), (byte) (ms >> 8), (byte) ms});
            }
            frame(frames, "SYLT", s.toByteArray());
        }
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.write(new byte[] {'I', 'D', '3', 4, 0, 0});
        tag.write(synchsafe(frames.size()));
        tag.write(frames.toByteArray());
        return tag.toByteArray();
    }

    /** Length of an ID3v2 tag at the start of the data, or 0 when there is none. */
    static int existingTag(byte[] d) {
        if (d.length < 10 || d[0] != 'I' || d[1] != 'D' || d[2] != '3') return 0;
        int size = ((d[6] & 0x7f) << 21) | ((d[7] & 0x7f) << 14) | ((d[8] & 0x7f) << 7) | (d[9] & 0x7f);
        return Math.min(d.length, 10 + size + ((d[5] & 0x10) != 0 ? 10 : 0));
    }

    /** Writes the .lrc beside the audio and, for an MP3, replaces its tag. Returns the .lrc file. */
    static File write(File audio, String title, ArrayList<Karaoke.Line> lines) throws Exception {
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        File lrc = new File(audio.getParentFile(), (dot > 0 ? name.substring(0, dot) : name) + ".lrc");
        Store.write(lrc, lrc(title, lines).getBytes("UTF-8"));
        if (name.toLowerCase(Locale.US).endsWith(".mp3")) {
            byte[] d = Store.read(audio);
            int skip = existingTag(d);
            ByteArrayOutputStream out = new ByteArrayOutputStream(d.length + 4096);
            out.write(id3(title, lines));
            out.write(d, skip, d.length - skip);
            File tmp = new File(audio.getParentFile(), name + ".tagging");
            Store.write(tmp, out.toByteArray());
            long stamp = audio.lastModified();
            if (!tmp.renameTo(audio)) {
                tmp.delete();
                throw new Exception("Could not replace " + name);
            }
            audio.setLastModified(stamp);
        }
        return lrc;
    }
}

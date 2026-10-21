package ai.jackknife.killayue.peer;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * What a music file already carries, read so publishing needs no extra steps: the cover, title and genre from an
 * MP3's ID3v2 tag (as Suno, DistroKid, Bandcamp and most tools write them), or a cover image beside the file
 * ("Song.jpg", "cover.jpg", "folder.jpg"). Covers are made into a small JPEG that fits HAMMER's artwork limit.
 */
final class EmbeddedArt {
    String title = "", genre = "";
    byte[] picture;  // the original embedded or neighbouring image, any format ImageIO reads

    static EmbeddedArt read(File audio) {
        EmbeddedArt a = new EmbeddedArt();
        try {
            a.id3(audio);
        } catch (Exception unreadableTag) {
        }
        if (a.picture == null) {
            String base = audio.getName().replaceFirst("\\.[^.]+$", "");
            for (String name : new String[] {base + ".jpg", base + ".jpeg", base + ".png", "cover.jpg", "cover.png",
                                             "folder.jpg", "folder.png"}) {
                File f = new File(audio.getParentFile(), name);
                if (f.isFile() && f.length() < 16L << 20)
                    try {
                        a.picture = java.nio.file.Files.readAllBytes(f.toPath());
                        break;
                    } catch (IOException ignored) {
                    }
            }
        }
        return a;
    }

    private void id3(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            byte[] head = new byte[10];
            in.readFully(head);
            if (head[0] != 'I' || head[1] != 'D' || head[2] != '3')
                return;
            int version = head[3];
            if (version < 3 || version > 4)
                return;
            int size = syncsafe(head, 6);
            if (size <= 0 || size > 32 << 20)
                return;
            byte[] tag = new byte[size];
            in.readFully(tag);
            int at = 0;
            if ((head[5] & 0x40) != 0)  // an extended header comes first
                at += version == 4 ? syncsafe(tag, 0) : 4 + int32(tag, 0);
            while (at + 10 <= size) {
                String id = new String(tag, at, 4, StandardCharsets.ISO_8859_1);
                if (id.charAt(0) == 0)
                    break;
                int len = version == 4 ? syncsafe(tag, at + 4) : int32(tag, at + 4);
                int body = at + 10;
                if (len <= 0 || body + len > size)
                    break;
                if (id.equals("TIT2"))
                    title = text(tag, body, len);
                else if (id.equals("TCON"))
                    genre = text(tag, body, len).replaceAll("^\\(\\d+\\)", "");
                else if (id.equals("APIC") && picture == null)
                    picture = apic(tag, body, len);
                at = body + len;
            }
        }
    }

    private static int syncsafe(byte[] b, int o) {
        return (b[o] & 0x7f) << 21 | (b[o + 1] & 0x7f) << 14 | (b[o + 2] & 0x7f) << 7 | (b[o + 3] & 0x7f);
    }
    private static int int32(byte[] b, int o) {
        return (b[o] & 0xff) << 24 | (b[o + 1] & 0xff) << 16 | (b[o + 2] & 0xff) << 8 | (b[o + 3] & 0xff);
    }

    /** A text frame: an encoding byte, then the text (one value; later ones, split by zeros, are dropped). */
    private static String text(byte[] b, int o, int len) {
        int enc = b[o];
        java.nio.charset.Charset cs = enc == 1 ? StandardCharsets.UTF_16 : enc == 2 ? StandardCharsets.UTF_16BE
                                    : enc == 3 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        String s = new String(b, o + 1, len - 1, cs);
        int zero = s.indexOf('\0');
        return (zero >= 0 ? s.substring(0, zero) : s).replace("​", "").trim();
    }

    /** An attached picture: encoding, MIME type, picture type, description, then the image bytes. */
    private static byte[] apic(byte[] b, int o, int len) {
        int end = o + len, at = o + 1, enc = b[o];
        while (at < end && b[at] != 0) at++;  // MIME type
        at += 2;                              // its zero, then the picture type
        if (enc == 1 || enc == 2) {           // a UTF-16 description ends with two zeros
            while (at + 1 < end && (b[at] != 0 || b[at + 1] != 0)) at += 2;
            at += 2;
        } else {
            while (at < end && b[at] != 0) at++;
            at++;
        }
        if (at >= end)
            return null;
        byte[] out = new byte[end - at];
        System.arraycopy(b, at, out, 0, out.length);
        return out;
    }

    /** The cover as a JPEG of at most 512 pixels a side, within HAMMER's artwork limit; null when unreadable. */
    byte[] coverJpeg() {
        if (picture == null)
            return null;
        try {
            BufferedImage src = javax.imageio.ImageIO.read(new ByteArrayInputStream(picture));
            if (src == null)
                return null;
            for (int side = 512; side >= 160; side -= 96) {
                double k = Math.min(1.0, side / (double)Math.max(src.getWidth(), src.getHeight()));
                int w = Math.max(1, (int)Math.round(src.getWidth() * k)), h = Math.max(1, (int)Math.round(src.getHeight() * k));
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(src, 0, 0, w, h, null);
                g.dispose();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Iterator<javax.imageio.ImageWriter> it = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg");
                javax.imageio.ImageWriter wr = it.next();
                javax.imageio.ImageWriteParam p = wr.getDefaultWriteParam();
                p.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(0.82f);
                try (javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(out)) {
                    wr.setOutput(ios);
                    wr.write(null, new javax.imageio.IIOImage(img, null, null), p);
                } finally {
                    wr.dispose();
                }
                if (out.size() <= HammerPublic.IMAGE_LIMIT)
                    return out.toByteArray();
            }
        } catch (Exception unreadableImage) {
        }
        return null;
    }
}

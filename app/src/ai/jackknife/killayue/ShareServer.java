package ai.jackknife.killayue;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Phone-to-phone sharing over the local network, no internet needed. While the QR screen is
 * open, a small web server on this phone offers one thing (the app, a song or an album) behind
 * a random code in the address; the other phone scans the QR with its camera and downloads in
 * its browser. Nothing else on the phone is reachable, and the server stops with the screen.
 */
public class ShareServer {
    static final int PORT = 8778;

    static class Item {
        final String label, name, type;
        final File file;
        final String lyrics;

        Item(String label, File file, String type, String lyrics) {
            this.label = label;
            this.file = file;
            this.name = file.getName();
            this.type = type;
            this.lyrics = lyrics;
        }
    }

    private static ShareServer current;

    final String kind, title, token;
    final List<Item> items;
    final File cover, apk, zip;
    private ServerSocket socket;
    volatile int served;
    volatile String hostHere = "";
    Context app;
    String skin = "gold";
    volatile long bytesSent;

    private ShareServer(String kind, String title, List<Item> items, File cover, File apk, File zip) {
        this.kind = kind;
        this.title = title;
        this.items = items;
        this.cover = cover;
        this.apk = apk;
        this.zip = zip;
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 10; i++) t.append(abc.charAt(r.nextInt(abc.length())));
        token = t.toString();
    }

    /** This phone's address on Wi-Fi or its own hotspot, or null when it is on neither. */
    static String localAddress() {
        String best = null;
        int bestRank = 99;
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                int rank = n.startsWith("wlan") ? 0 : (n.startsWith("swlan") || n.startsWith("ap") || n.contains("softap")) ? 1 : 5;
                if (n.startsWith("rmnet") || n.startsWith("tun") || n.startsWith("ccmni") || n.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (rank < bestRank) {
                        bestRank = rank;
                        best = a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // no address
        }
        return best;
    }

    /** Starts sharing (stopping any earlier share) and returns the address to put in the QR code. */
    static synchronized String start(Context c, String kind, String title, List<Item> items, File cover, File zip) throws Exception {
        stop();
        String ip = localAddress();
        if (ip == null) throw new Exception("This phone is not on Wi-Fi. Join the same Wi-Fi as the other phone, or turn on your hotspot and let the other phone join it.");
        File apk = new File(c.getPackageCodePath());
        ShareServer s = new ShareServer(kind, title, items, cover, apk, zip);
        s.app = c.getApplicationContext();
        s.skin = Skin.id(c);
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        int port = PORT;
        for (int i = 0; i < 10; i++) {
            try {
                ss.bind(new java.net.InetSocketAddress(port + i));
                port = port + i;
                break;
            } catch (Exception e) {
                if (i == 9) throw new Exception("Could not open a port for sharing");
            }
        }
        s.socket = ss;
        current = s;
        final ShareServer me = s;
        Thread t = new Thread(new Runnable() {
            public void run() {
                me.acceptLoop();
            }
        }, "share-server");
        t.setDaemon(true);
        t.start();
        return "http://" + ip + ":" + port + "/" + s.token + "/";
    }

    static synchronized void stop() {
        if (current != null) {
            try {
                current.socket.close();
            } catch (Exception e) {
                // already closed
            }
            current = null;
        }
    }

    static ShareServer current() {
        return current;
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                final Socket c = socket.accept();
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        handle(c);
                    }
                }, "share-client");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                return; // closed
            }
        }
    }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String first = in.readLine();
            if (first == null) return;
            String line;
            long from = -1;
            while ((line = in.readLine()) != null && line.length() > 0) {
                // only Range matters: big model files resume where an interrupted copy stopped
                String l = line.toLowerCase(java.util.Locale.US);
                if (l.startsWith("range: bytes=")) {
                    try {
                        from = Long.parseLong(l.substring(13, l.indexOf('-', 13)).trim());
                    } catch (Exception e) {
                        from = -1;
                    }
                }
            }
            rangeStart.set(Long.valueOf(from));
            String[] parts = first.split(" ");
            hostHere = c.getLocalAddress().getHostAddress() + ":" + c.getLocalPort();
            String path = parts.length > 1 ? parts[1] : "/";
            OutputStream out = c.getOutputStream();
            String base = "/" + token + "/";
            if (!path.startsWith(base)) {
                send(out, 404, "text/plain; charset=utf-8", "Not here.".getBytes("UTF-8"));
                return;
            }
            String rest = path.substring(base.length());
            if (rest.length() == 0) {
                send(out, 200, "text/html; charset=utf-8", page().getBytes("UTF-8"));
            } else if (rest.equals("app.apk")) {
                sendFile(out, apk, "application/vnd.android.package-archive", "killa-yue.apk");
            } else if (rest.equals("cover.jpg") && cover != null) {
                sendFile(out, cover, "image/jpeg", null);
            } else if (rest.equals("album.zip") && zip != null) {
                sendFile(out, zip, "application/zip", ShareServer.safe(title) + ".zip");
            } else if (rest.startsWith("a/")) {
                byte[] img = art(rest.substring(2));
                if (img == null) send(out, 404, "text/plain; charset=utf-8", "Not here.".getBytes("UTF-8"));
                else send(out, 200, rest.endsWith(".ttf") ? "font/ttf" : rest.endsWith(".jpg") ? "image/jpeg" : "image/png", img);
            } else if (rest.equals("models.json") && "models".equals(kind)) {
                org.json.JSONArray a = new org.json.JSONArray();
                for (int i = 0; i < items.size(); i++) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("name", items.get(i).name);
                    o.put("size", items.get(i).file.length());
                    a.put(o);
                }
                send(out, 200, "application/json", a.toString().getBytes("UTF-8"));
            } else if (rest.startsWith("m/") && "models".equals(kind)) {
                String name = java.net.URLDecoder.decode(rest.substring(2), "UTF-8");
                Item hit = null;
                for (int i = 0; i < items.size(); i++) if (items.get(i).name.equals(name)) hit = items.get(i);
                if (hit == null) send(out, 404, "text/plain; charset=utf-8", "Not here.".getBytes("UTF-8"));
                else sendFile(out, hit.file, "application/octet-stream", hit.name);
            } else if (rest.startsWith("f/")) {
                int slash = rest.indexOf('/', 2);
                int i = Integer.parseInt(slash > 0 ? rest.substring(2, slash) : rest.substring(2));
                Item it = items.get(i);
                boolean play = rest.endsWith("?play");
                sendFile(out, it.file, it.type, play ? null : it.label + it.name.substring(it.name.lastIndexOf('.')));
            } else {
                send(out, 404, "text/plain; charset=utf-8", "Not here.".getBytes("UTF-8"));
            }
        } catch (Exception e) {
            // the other phone went away mid-transfer; nothing to do
        } finally {
            try {
                c.close();
            } catch (Exception e) {
                // closed
            }
        }
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    // the Range start of the request being answered on this thread, or -1
    private static final ThreadLocal<Long> rangeStart = new ThreadLocal<Long>();

    private void sendFile(OutputStream out, File f, String type, String downloadName) throws Exception {
        if (f == null || !f.isFile()) {
            send(out, 404, "text/plain; charset=utf-8", "This file is gone.".getBytes("UTF-8"));
            return;
        }
        long len = f.length();
        Long asked = rangeStart.get();
        long rg = asked == null ? -1 : asked.longValue();
        long start = rg > 0 && rg < len ? rg : 0;
        StringBuilder h = new StringBuilder(start > 0 ? "HTTP/1.1 206 Partial Content" : "HTTP/1.1 200 OK")
                .append("\r\nContent-Type: ").append(type)
                .append("\r\nContent-Length: ").append(len - start).append("\r\nAccept-Ranges: bytes\r\nConnection: close\r\n");
        if (start > 0) h.append("Content-Range: bytes ").append(start).append('-').append(len - 1).append('/').append(len).append("\r\n");
        if (downloadName != null) {
            h.append("Content-Disposition: attachment; filename=\"").append(downloadName.replace('"', '\''))
                    .append("\"; filename*=UTF-8''").append(URLEncoder.encode(downloadName, "UTF-8").replace("+", "%20")).append("\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        InputStream in = new FileInputStream(f);
        try {
            long skipped = 0;
            while (skipped < start) {
                long k = in.skip(start - skipped);
                if (k <= 0) throw new Exception("Could not seek");
                skipped += k;
            }
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                bytesSent += n;
            }
        } finally {
            in.close();
        }
        out.flush();
        if (downloadName != null) served++;
    }

    static String safe(String s) {
        return Albums.safeName(s);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------- the page the other phone sees: the app's own art, in the sharer's skin ----------

    private static final String[] ART = {"bg.png", "wall.jpg", "btnA.png", "btnB.png", "dial.png", "round.png", "staff.png",
        "note1.png", "note2.png", "note3.png", "note4.png", "note5.png", "note6.png"};
    private static final java.util.HashMap<String, byte[]> artCache = new java.util.HashMap<String, byte[]>();

    /** One of the skin's images or the two Killa fonts, read from the APK; null for anything else. */
    private byte[] art(String name) throws Exception {
        String path = null;
        if ("script.ttf".equals(name)) path = "fonts/KillaGel-BoldScript.ttf";
        else if ("text.ttf".equals(name)) path = "fonts/KillaMeridian-Text.ttf";
        else for (int i = 0; i < ART.length; i++) if (ART[i].equals(name)) path = "skins/" + skin + "/" + name;
        if (path == null) return null;
        synchronized (artCache) {
            byte[] b = artCache.get(path);
            if (b == null) {
                try {
                    b = Api.readAll(app.getAssets().open(path));
                } catch (Exception e) {
                    return null;
                }
                artCache.put(path, b);
            }
            return b;
        }
    }

    static String hex(int c) {
        return String.format(java.util.Locale.US, "#%06X", c & 0xFFFFFF);
    }

    private String css() {
        boolean gold = "gold".equals(skin);
        String back = gold ? "url(a/bg.png) center/440px repeat" : "url(a/wall.jpg) center/cover fixed";
        String titleFont = gold ? "font:400 clamp(38px,11vw,62px)/1.08 KS,Georgia,serif;" : "font:700 clamp(30px,8vw,46px)/1.15 KT,Georgia,serif;text-transform:uppercase;letter-spacing:.08em;";
        return "@font-face{font-family:KS;src:url(a/script.ttf)}@font-face{font-family:KT;src:url(a/text.ttf)}"
                + ":root{--acc:" + hex(Skin.ACCENT) + ";--hi:" + hex(Skin.HI) + ";--lo:" + hex(Skin.LO) + ";--tx:" + hex(Skin.TEXT)
                + ";--dim:" + hex(Skin.DIM) + ";--edge:" + hex(Skin.EDGE) + ";--deck:" + hex(Skin.DECK) + "}"
                + "html{background:var(--deck)}*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
                + "body{margin:0;min-height:100vh;color:var(--tx);font:16px/1.6 KT,Georgia,serif;"
                + "background:radial-gradient(ellipse at 50% 18%,rgba(0,0,0,0) 0%,rgba(0,0,0,.45) 55%,rgba(0,0,0,.88) 100%)," + back + "}"
                + ".w{max-width:560px;margin:0 auto;padding:26px 18px 70px}"
                + ".medal{display:block;width:58vw;max-width:250px;margin:6px auto 0;animation:breathe 4.6s ease-in-out infinite}"
                + "@keyframes breathe{0%,100%{filter:drop-shadow(0 0 16px var(--lo))}50%{filter:drop-shadow(0 0 36px var(--acc));transform:scale(1.015)}}"
                + "h1{" + titleFont + "text-align:center;margin:16px 0 2px;word-wrap:break-word;"
                + "background:linear-gradient(100deg,var(--lo) 0%,var(--acc) 28%,var(--hi) 44%,#fffaf0 50%,var(--hi) 56%,var(--acc) 72%,var(--lo) 100%);"
                + "background-size:260% 100%;-webkit-background-clip:text;background-clip:text;color:transparent;"
                + "animation:shine 7s linear infinite;filter:drop-shadow(0 3px 5px rgba(0,0,0,.85))}"
                + "h1.long{font-size:clamp(26px,7.6vw,40px);line-height:1.15}"
                + "@keyframes shine{from{background-position:130% 0}to{background-position:-130% 0}}"
                + ".sub{text-align:center;color:var(--dim);letter-spacing:.22em;text-transform:uppercase;font-size:11.5px}"
                + ".staff{position:relative;height:clamp(44px,13vw,64px);margin:22px -6px;background:url(a/staff.png) center/100% 100% no-repeat}"
                + ".staff img{position:absolute;height:72%;top:12%;opacity:0;animation:drift 10s linear infinite;filter:drop-shadow(0 0 6px var(--acc))}"
                + "@keyframes drift{0%{left:13%;opacity:0}12%{opacity:1}86%{opacity:1}100%{left:90%;opacity:0}}"
                + "a.cta,button.cta{display:block;width:100%;max-width:440px;height:clamp(70px,20vw,92px);margin:20px auto;border:0;"
                + "background:url(a/btnA.png) center/100% 100% no-repeat;color:var(--hi);font:600 clamp(16px,4.6vw,20px)/1 KT,serif;"
                + "letter-spacing:.07em;text-decoration:none;display:flex;align-items:center;justify-content:center;"
                + "text-shadow:0 0 14px var(--acc),0 1px 2px #000;transition:transform .12s,filter .12s}"
                + ".cta:active{transform:scale(.97);filter:brightness(1.2)}"
                + "a.alt{display:flex;align-items:center;justify-content:center;max-width:360px;height:60px;margin:14px auto;"
                + "background:url(a/btnB.png) center/100% 100% no-repeat;color:var(--acc);font:600 15px KT,serif;letter-spacing:.08em;text-decoration:none}"
                + ".card{position:relative;background:linear-gradient(180deg,rgba(14,14,14,.86),rgba(6,6,6,.93));border:1px solid var(--edge);"
                + "border-radius:22px;padding:18px 18px 20px;margin:20px 0;box-shadow:inset 0 1px 0 rgba(255,255,255,.05),inset 0 0 0 1px rgba(0,0,0,.6),0 16px 44px rgba(0,0,0,.65)}"
                + ".card::before{content:'';position:absolute;inset:6px;border:1px solid rgba(255,255,255,.04);border-radius:17px;pointer-events:none}"
                + ".cover{display:block;width:100%;border-radius:18px;box-shadow:0 0 0 2px var(--lo),0 0 0 6px #0b0b0b,0 0 0 8px var(--acc),0 22px 60px rgba(0,0,0,.85)}"
                + ".frame{padding:8px;margin:6px 4px 0}"
                + "h2{font:400 clamp(28px,8vw,38px)/1.1 " + (gold ? "KS" : "KT") + ",serif;color:var(--acc);text-align:center;margin:34px 0 8px;"
                + "text-shadow:0 0 18px rgba(0,0,0,.9),0 2px 3px #000}"
                + ".rule{height:1px;margin:26px 12%;background:linear-gradient(90deg,transparent,var(--acc),transparent)}"
                + ".tr{display:flex;align-items:center;gap:14px;padding:12px 2px;border-bottom:1px solid rgba(128,110,60,.25)}"
                + ".tr:last-child{border-bottom:0}.tr .n{font:400 28px " + (gold ? "KS" : "KT") + ";color:var(--acc);width:30px;text-align:center}"
                + ".tr .t{flex:1;min-width:0}.tr .t div{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
                + ".play{width:60px;height:60px;flex:none;border:0;padding:0;background:url(a/round.png) center/contain no-repeat;position:relative;cursor:pointer}"
                + ".play::after{content:'';position:absolute;left:25px;top:19px;border-left:17px solid var(--hi);border-top:11px solid transparent;"
                + "border-bottom:11px solid transparent;filter:drop-shadow(0 0 6px var(--acc))}"
                + ".play.on::after{left:21px;top:20px;width:6px;height:20px;border:0;background:var(--hi);box-shadow:12px 0 0 var(--hi)}"
                + ".big{width:96px;height:96px}.big::after{left:40px;top:31px;border-left-width:26px;border-top-width:17px;border-bottom-width:17px}"
                + ".big.on::after{left:34px;top:32px;width:9px;height:32px;box-shadow:19px 0 0 var(--hi)}"
                + ".bar{height:5px;background:rgba(255,255,255,.08);border-radius:3px;margin-top:8px;overflow:hidden}"
                + ".bar i{display:block;height:100%;width:0;background:linear-gradient(90deg,var(--lo),var(--hi));box-shadow:0 0 10px var(--acc)}"
                + ".time{font-size:12px;color:var(--dim);letter-spacing:.1em;margin-top:4px}"
                + ".dl{color:var(--acc);font-size:12px;text-decoration:none;letter-spacing:.14em;text-transform:uppercase}"
                + ".deck{display:flex;align-items:center;gap:18px}.deck .t{flex:1}"
                + "ol.st{list-style:none;padding:0;margin:8px 0 0}ol.st li{position:relative;padding:2px 0 14px 50px;min-height:52px}"
                + "ol.st li img{position:absolute;left:6px;top:0;height:42px;filter:drop-shadow(0 0 5px var(--lo))}"
                + "pre.ly{white-space:pre-wrap;font:15.5px/1.75 KT,Georgia,serif;color:var(--tx);text-align:center;margin:0}"
                + ".note{color:var(--dim);font-size:13px;line-height:1.55}"
                + "details{margin-top:6px}summary{cursor:pointer;color:var(--acc);text-align:center;letter-spacing:.14em;text-transform:uppercase;font-size:12.5px;list-style:none;padding:10px}"
                + "summary::-webkit-details-marker{display:none}"
                + ".files{margin:10px 0 0;padding:0;list-style:none}.files li{display:flex;justify-content:space-between;gap:10px;padding:7px 0;"
                + "border-bottom:1px dashed rgba(128,110,60,.3);font-size:13.5px}.files li span:last-child{color:var(--acc);white-space:nowrap}"
                + ".foot{text-align:center;color:var(--dim);font-size:12px;margin-top:40px;letter-spacing:.06em}"
                + ".foot b{display:block;font:400 " + (gold ? "26px KS" : "16px KT") + ";color:var(--acc);letter-spacing:0;margin-bottom:6px}";
    }

    private static String staff() {
        return "<div class='staff'><img src='a/note1.png' style='animation-delay:0s'><img src='a/note4.png' style='animation-delay:2.5s'>"
                + "<img src='a/note2.png' style='animation-delay:5s'><img src='a/note6.png' style='animation-delay:7.5s'></div>";
    }

    private static String steps(String[] lines) {
        StringBuilder b = new StringBuilder("<ol class='st'>");
        for (int i = 0; i < lines.length; i++) b.append("<li><img src='a/note").append(1 + i % 6).append(".png' alt=''>").append(lines[i]).append("</li>");
        return b.append("</ol>").toString();
    }

    private static final String[] INSTALL = {
        "Tap <b>Download Killa YuE</b>. If your browser warns that the file might be harmful, choose <b>Download anyway</b>: it is the real app, signed by its makers.",
        "Open the download. If Android asks, let your browser <b>install unknown apps</b>, then go back and tap <b>Install</b>.",
        "Open Killa YuE and allow storage access.",
        "On the <b>Engine</b> tab, press <b>Get</b> on the song model and the sound decoder (about 4 GB, so use Wi-Fi), or copy them from a friend's phone with <b>Share the models by QR</b>.",
        "Write a style and your lyrics on <b>Create</b> and press <b>Generate song</b>. Put the phone on its charger: a full song takes 20 to 60 minutes.",
    };

    private static String needs() {
        return "<p class='note' style='text-align:center'>Needs a phone with a Qualcomm Snapdragon (Adreno GPU), Android 9 or later and about 6 GB of free memory "
                + "for full-length songs. Album covers need a Snapdragon 8 Elite. Free and open source: github.com/JackKnifeAI/killa-yue</p>";
    }

    private String getApp(boolean primary) {
        return (primary ? "<a class='cta' href='app.apk'>Download Killa YuE</a>" : "<a class='alt' href='app.apk'>Get Killa YuE</a>")
                + "<details" + (primary ? " open" : "") + "><summary>How to install it</summary>" + steps(INSTALL) + needs() + "</details>";
    }

    private String player(int i, Item it, boolean big) throws Exception {
        String href = "f/" + i + "/" + URLEncoder.encode(it.name, "UTF-8");
        return "<button class='play" + (big ? " big" : "") + "' data-src='" + href + "?play' aria-label='Play'></button>";
    }

    private String page() throws Exception {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>")
                .append("<meta name='theme-color' content='").append(hex(Skin.DECK)).append("'><title>").append(esc(title)).append(" · Killa YuE</title><style>")
                .append(css()).append("</style></head><body><div class='w'>");
        if ("app".equals(kind)) {
            b.append("<img class='medal' src='a/dial.png' alt='Killa YuE'><h1>Killa YuE</h1><div class='sub'>Songs made in your hand</div>").append(staff());
            b.append("<div class='card'><p style='margin:0;text-align:center;font-size:17px'>Full songs with vocals and lyrics, written, sung and painted with a cover ")
                    .append("<b>entirely on your phone</b>. No cloud. No subscription. No account. Nothing you make leaves your hands.</p></div>");
            b.append(getApp(true));
        } else if ("models".equals(kind)) {
            long total = 0;
            for (int i = 0; i < items.size(); i++) total += items.get(i).file.length();
            String self = "http://" + hostHere + "/" + token + "/";
            b.append("<img class='medal' src='a/dial.png' alt=''><h1>The Models</h1><div class='sub'>").append(items.size()).append(" files · ")
                    .append(String.format(java.util.Locale.US, "%.1f GB", total / 1e9)).append(" · straight from your friend's phone</div>").append(staff());
            b.append("<a class='cta' href='killayue://models?from=").append(URLEncoder.encode(self, "UTF-8")).append("'>Open in Killa YuE</a>");
            b.append("<div class='card'>").append(steps(new String[] {
                "No Killa YuE yet? Get it below first, open it once and allow storage access, then come back to this page.",
                "Tap <b>Open in Killa YuE</b>. The app copies the models it is missing over Wi-Fi: <b>no mobile data</b>.",
                "Every file is checked against its published checksum, and an interrupted copy picks up where it stopped.",
                "Keep both phones on the same Wi-Fi, with your friend's share screen open, until the Engine tab says ready.",
            })).append("<ul class='files'>");
            for (int i = 0; i < items.size(); i++) {
                b.append("<li><span>").append(esc(items.get(i).name)).append("</span><span>")
                        .append(String.format(java.util.Locale.US, "%.2f GB", items.get(i).file.length() / 1e9)).append("</span></li>");
            }
            b.append("</ul><p class='note'>The YuE2 model files are by M-A-P, licensed CC BY-NC 4.0: free to use and share, not for commercial use.</p></div>");
            b.append("<div class='rule'></div>").append(getApp(false));
        } else {
            boolean album = "album".equals(kind);
            if (cover != null) b.append("<div class='frame'><img class='cover' src='cover.jpg' alt=''></div>");
            else b.append("<img class='medal' src='a/dial.png' alt=''>");
            b.append(title.length() > 22 ? "<h1 class='long'>" : "<h1>").append(esc(title)).append("</h1><div class='sub'>")
                    .append(album ? items.size() + (items.size() == 1 ? " song" : " songs") + " · " : "").append("made on a phone with Killa YuE</div>").append(staff());
            if (album) {
                if (zip != null) b.append("<a class='cta' href='album.zip'>Download the album</a>");
                b.append("<div class='card'>");
                for (int i = 0; i < items.size(); i++) {
                    Item it = items.get(i);
                    b.append("<div class='tr'><div class='n'>").append(i + 1).append("</div><div class='t'><div>").append(esc(it.label))
                            .append("</div><div class='bar'><i></i></div><a class='dl' href='f/").append(i).append('/')
                            .append(URLEncoder.encode(it.name, "UTF-8")).append("'>Download</a></div>").append(player(i, it, false)).append("</div>");
                }
                b.append("</div>");
            } else {
                Item it = items.get(0);
                b.append("<div class='card'><div class='deck'>").append(player(0, it, true))
                        .append("<div class='t'><div class='sub' style='text-align:left'>Press play</div><div class='bar'><i></i></div>")
                        .append("<div class='time'>0:00</div></div></div></div>");
                b.append("<a class='cta' href='f/0/").append(URLEncoder.encode(it.name, "UTF-8")).append("'>Download the song</a>");
                if (it.lyrics != null && it.lyrics.trim().length() > 0) {
                    b.append("<h2>Lyrics</h2><div class='card'><pre class='ly'>").append(esc(it.lyrics.trim())).append("</pre></div>");
                }
            }
            b.append("<h2>Make your own</h2><div class='sub' style='margin-bottom:6px'>Killa YuE is free</div>").append(getApp(false));
        }
        b.append("<div class='foot'><b>Killa YuE</b>made on a phone · free and open source · github.com/JackKnifeAI/killa-yue</div></div>");
        // one shared player: tapping a gold button plays that song, its bar shows progress
        b.append("<audio id='au' preload='none'></audio><script>(function(){var au=document.getElementById('au'),cur=null;")
                .append("function bar(btn){var r=btn.closest('.tr')||btn.closest('.deck');return r?r.querySelector('.bar i'):null}")
                .append("function fmt(s){s=Math.floor(s||0);return Math.floor(s/60)+':'+('0'+s%60).slice(-2)}")
                .append("document.querySelectorAll('.play').forEach(function(b){b.addEventListener('click',function(){")
                .append("if(cur===b){if(au.paused){au.play();b.classList.add('on')}else{au.pause();b.classList.remove('on')}return}")
                .append("if(cur){cur.classList.remove('on');var o=bar(cur);if(o)o.style.width='0'}cur=b;au.src=b.getAttribute('data-src');au.play();b.classList.add('on')})});")
                .append("au.addEventListener('timeupdate',function(){if(!cur)return;var i=bar(cur);if(i&&au.duration)i.style.width=(100*au.currentTime/au.duration)+'%';")
                .append("var t=document.querySelector('.deck .time');if(t)t.textContent=fmt(au.currentTime)+' / '+fmt(au.duration)});")
                .append("au.addEventListener('ended',function(){if(cur)cur.classList.remove('on');var all=[].slice.call(document.querySelectorAll('.tr .play'));")
                .append("var k=all.indexOf(cur);if(k>=0&&k+1<all.length)all[k+1].click()})})();</script></body></html>");
        return b.toString();
    }

    /** Items for one library song. */
    static List<Item> songItems(Context c, org.json.JSONObject m) {
        List<Item> l = new ArrayList<Item>();
        String a = m.optString("_audio");
        File f = new File(Store.library(c), a);
        l.add(new Item(m.optString("_title", "Untitled"), f, a.endsWith(".wav") ? "audio/wav" : "audio/mpeg", m.optString("lyrics")));
        return l;
    }
}

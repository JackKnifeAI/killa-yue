package ai.jackknife.killayue;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/** Client for the local yue-server HTTP API. */
public class Api {
    static final String HOST = "http://127.0.0.1:" + EngineService.PORT;
    static final String BOUNDARY = "yue2-batch-boundary";

    static class Part {
        String type = "";
        byte[] body;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    private static byte[] call(String method, String path, String type, byte[] body, int[] codeOut) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(HOST + path).openConnection();
        try {
            con.setRequestMethod(method);
            con.setConnectTimeout(4000);
            con.setReadTimeout(120000);
            if (body != null) {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", type);
                con.setFixedLengthStreamingMode(body.length);
                OutputStream o = con.getOutputStream();
                o.write(body);
                o.close();
            }
            int code = con.getResponseCode();
            if (codeOut != null) codeOut[0] = code;
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            return in == null ? new byte[0] : readAll(in);
        } finally {
            con.disconnect();
        }
    }

    private static JSONObject json(String method, String path, String type, byte[] body) throws Exception {
        int[] code = new int[1];
        byte[] r = call(method, path, type, body, code);
        String s = new String(r, "UTF-8");
        JSONObject j;
        try {
            j = new JSONObject(s);
        } catch (Exception e) {
            throw new Exception("Engine answered " + code[0] + ": " + s);
        }
        if (code[0] >= 400) throw new Exception(j.optString("error", "Engine answered " + code[0]));
        return j;
    }

    static boolean healthy() {
        try {
            return "ok".equals(json("GET", "/health", null, null).optString("status"));
        } catch (Exception e) {
            return false;
        }
    }

    static JSONObject props() throws Exception {
        return json("GET", "/props", null, null);
    }

    static String synth(JSONObject req) throws Exception {
        return json("POST", "/synth", "application/json", req.toString().getBytes("UTF-8")).getString("id");
    }

    static String status(String id) throws Exception {
        return json("GET", "/job?id=" + id, null, null).optString("status");
    }

    static void cancel(String id) {
        try {
            call("POST", "/job?id=" + id + "&cancel=1", "application/json", new byte[0], null);
        } catch (Exception e) {
            // the job may already be gone; nothing to undo
        }
    }

    static byte[] resultRaw(String id) throws Exception {
        int[] code = new int[1];
        byte[] r = call("GET", "/job?id=" + id + "&result=1", null, null, code);
        if (code[0] != 200) throw new Exception("No result from the engine (" + code[0] + "): " + new String(r, "UTF-8"));
        return r;
    }

    /** Uploads an audio file as the "audio" form part to /transcribe or /tokenize. */
    static String upload(String path, byte[] audio, String name, boolean melodyOnly) throws Exception {
        String b = "----killayue" + System.currentTimeMillis();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (melodyOnly) {
            o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"melody_only\"\r\n\r\n1\r\n").getBytes("UTF-8"));
        }
        o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"" + name
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes("UTF-8"));
        o.write(audio);
        o.write(("\r\n--" + b + "--\r\n").getBytes("UTF-8"));
        return json("POST", path, "multipart/form-data; boundary=" + b, o.toByteArray()).getString("id");
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Splits a multipart/mixed result into its parts. */
    static ArrayList<Part> parts(byte[] raw) throws Exception {
        ArrayList<Part> out = new ArrayList<Part>();
        byte[] mark = ("--" + BOUNDARY).getBytes("UTF-8");
        byte[] sep = "\r\n\r\n".getBytes("UTF-8");
        int pos = indexOf(raw, mark, 0);
        while (pos >= 0) {
            int head = pos + mark.length;
            if (head + 1 < raw.length && raw[head] == '-' && raw[head + 1] == '-') break;
            int bodyStart = indexOf(raw, sep, head);
            if (bodyStart < 0) break;
            String headers = new String(raw, head, bodyStart - head, "UTF-8");
            bodyStart += sep.length;
            int next = indexOf(raw, mark, bodyStart);
            if (next < 0) break;
            int bodyEnd = next;
            if (bodyEnd >= 2 && raw[bodyEnd - 2] == '\r' && raw[bodyEnd - 1] == '\n') bodyEnd -= 2;
            Part p = new Part();
            String[] lines = headers.split("\r\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].toLowerCase();
                if (l.startsWith("content-type:")) p.type = l.substring(13).trim();
            }
            p.body = new byte[bodyEnd - bodyStart];
            System.arraycopy(raw, bodyStart, p.body, 0, p.body.length);
            out.add(p);
            pos = next;
        }
        return out;
    }
}

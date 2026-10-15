package ai.jackknife.killayue.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for Studio projects, in plain Java so the project model runs and is tested
 * anywhere (Android's org.json is not available off the phone). Objects read as LinkedHashMap, keeping their
 * key order; arrays as ArrayList; numbers as Double.
 */
public final class Json {
    static final int MAX_DEPTH = 512;  // a project is a few levels deep; deeper is damage, never a crash

    private final String s;
    private int i, depth;

    private Json(String s) {
        this.s = s;
    }

    /** The value in text. Throws IllegalArgumentException("JSON: ... at N") for anything that is not exactly one JSON value. */
    public static Object parse(String text) {
        Json j = new Json(text);
        j.space();
        Object v = j.value();
        j.space();
        if (j.i != text.length()) throw j.error("unexpected text after the value");
        return v;
    }

    /** Compact JSON for a Map, List, String, Number, Boolean or null. Throws for NaN or infinity. */
    public static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    // ---------- reading ----------

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + i);
    }

    private void space() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) throw error("the text ends where a value should be");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == '-' || c >= '0' && c <= '9') return number();
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        throw error("unexpected character '" + c + "'");
    }

    private void deeper() {
        if (++depth > MAX_DEPTH) throw error("nested more than " + MAX_DEPTH + " deep");
    }

    private LinkedHashMap<String, Object> object() {
        deeper();
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        i++; // {
        space();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            depth--;
            return m;
        }
        while (true) {
            space();
            if (i >= s.length() || s.charAt(i) != '"') throw error("a key in quotes was expected");
            String k = string();
            space();
            if (i >= s.length() || s.charAt(i) != ':') throw error("':' was expected");
            i++;
            space();
            m.put(k, value());
            space();
            if (i >= s.length()) throw error("the text ends inside an object");
            char c = s.charAt(i++);
            if (c == '}') {
                depth--;
                return m;
            }
            if (c != ',') throw error("',' or '}' was expected");
        }
    }

    private ArrayList<Object> array() {
        deeper();
        ArrayList<Object> a = new ArrayList<Object>();
        i++; // [
        space();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            depth--;
            return a;
        }
        while (true) {
            space();
            a.add(value());
            space();
            if (i >= s.length()) throw error("the text ends inside an array");
            char c = s.charAt(i++);
            if (c == ']') {
                depth--;
                return a;
            }
            if (c != ',') throw error("',' or ']' was expected");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++; // opening quote
        while (true) {
            if (i >= s.length()) throw error("the text ends inside a string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw error("a control character inside a string");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw error("the text ends inside an escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("a short \\u escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException x) {
                        throw error("a bad \\u escape");
                    }
                    i += 4;
                    break;
                default:
                    throw error("an unknown escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        if (i >= s.length()) throw error("a number with no digits");
        if (s.charAt(i) == '0') i++;
        else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        else throw error("a number with no digits");
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw error("a number with no digits after '.'");
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw error("a number with no exponent digits");
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        return Double.valueOf(Double.parseDouble(s.substring(start, i)));
    }

    // ---------- writing ----------

    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                write(b, o);
            }
            b.append(']');
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("JSON: cannot write " + d);
            if (v instanceof Long || v instanceof Integer) b.append(((Number) v).longValue());
            else if (v instanceof Float && d != Math.rint(d)) b.append(Float.toString(((Float) v).floatValue())); // shortest: 0.1, not 0.10000000149
            else if (d == Math.rint(d) && Math.abs(d) <= 9007199254740992.0) b.append((long) d);
            else b.append(Double.toString(d));
        } else if (v instanceof Boolean) {
            b.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else {
            throw new IllegalArgumentException("JSON: cannot write a " + v.getClass().getSimpleName());
        }
    }

    private static void quote(StringBuilder b, String t) {
        b.append('"');
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else if (Character.isHighSurrogate(c) && k + 1 < t.length() && Character.isLowSurrogate(t.charAt(k + 1))) b.append(c).append(t.charAt(++k));
            else if (Character.isSurrogate(c)) b.append(String.format("\\u%04x", (int) c)); // a lone half: UTF-8 would turn it into ?
            else b.append(c);
        }
        b.append('"');
    }
}

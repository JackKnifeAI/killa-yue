package ai.jackknife.killayue.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class JsonTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) {
        String text = "{\"name\":\"Gold \\\"in\\\" the\\\\Wire\\n\\u00e9 \\ud83c\\udfb5\",\"gain\":-3.5,\"n\":48000,\"on\":true,\"x\":null,\"list\":[1,2,[]]}";
        Object v = Json.parse(text);
        same("round trip keeps every character, order and number", text.replace("\\u00e9", "é").replace("\\ud83c\\udfb5", "🎵"), Json.write(v));
        LinkedHashMap<?, ?> m = (LinkedHashMap<?, ?>) v;
        same("strings unescaped", "Gold \"in\" the\\Wire\né 🎵", m.get("name"));
        same("numbers are doubles", -3.5, m.get("gain"));
        same("whole numbers print without .0", "48000", Json.write(m.get("n")));
        same("nested arrays", "[1,2,[]]", Json.write(m.get("list")));
        boolean threw = false;
        try {
            Json.parse("{\"a\":1,}");
        } catch (IllegalArgumentException e) {
            threw = e.getMessage().startsWith("JSON:");
        }
        same("a trailing comma is an error with a message", true, threw);
        threw = false;
        try {
            Json.parse("{\"a\":1");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        same("a cut-off file is an error", true, threw);
        threw = false;
        try {
            Json.write(Double.NaN);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        same("NaN cannot be written", true, threw);
        same("control characters are escaped", "\"a\\u0001b\\tc\"", Json.write("a\u0001b\tc"));
        ArrayList<Object> empty = new ArrayList<Object>();
        same("empty array", "[]", Json.write(empty));
        System.out.println("JsonTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}

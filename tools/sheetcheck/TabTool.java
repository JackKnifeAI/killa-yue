package ai.jackknife.killayue;

/** java ai.jackknife.killayue.TabTool : one line per fretted instrument for check_tabs.py: id, abcjs tab option, open strings. */
public class TabTool {
    public static void main(String[] a) {
        for (Instruments.Instrument ins : Instruments.ALL) {
            if (ins.tab == null) continue;
            StringBuilder s = new StringBuilder();
            for (int p : ins.strings) s.append(s.length() > 0 ? "," : "").append(p);
            System.out.println(ins.id + "\t" + Tabs.option(ins) + "\t" + s);
        }
    }
}

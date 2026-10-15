package ai.jackknife.killayue;

import java.util.Arrays;

/** Tests for SheetLayout: where the sheet viewer puts each page as you scroll and zoom. */
public class SheetLayoutTest {
    private static int failed, passed;

    private static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) {
        float[][] a4 = {{595, 842}, {595, 842}, {595, 842}};
        SheetLayout l = new SheetLayout(a4, 1080, 2000, 20);
        same("a page fills the width less a margin each side", 1040.0, (double) l.pageW());
        double h = 1040.0 * 842 / 595;
        same("its height keeps the paper's shape", Math.round(h), Math.round(l.height(0)));
        same("pages are stacked with a margin between", Math.round(20 + h + 20), Math.round(l.top(1)));
        same("all three pages and four margins", Math.round(3 * h + 4 * 20), Math.round(l.contentH()));
        same("at the top, the first two pages show", "[0, 1]", Arrays.toString(l.visible(0)));
        same("scrolled to the end, the last two", "[1, 2]", Arrays.toString(l.visible(l.contentH() - 2000)));

        same("scrolling stops at the end", (double) (l.contentH() - 2000), (double) l.clampY(99999));
        same("and at the top", 0.0, (double) l.clampY(-50));
        same("at fit width the page cannot slide sideways", 0.0, (double) l.clampX(300));

        float[] s = l.zoom(2, 540, 1000, 0, 0);
        same("zooming in doubles the page", 2080.0, (double) (l.pageW()));
        same("the point under the fingers stays put", "[540.0, 1000.0]", Arrays.toString(s));
        same("zoomed in, the page slides sideways up to its edge", (double) (l.contentW() - 1080), (double) l.clampX(99999));
        l.zoom(10, 0, 0, 0, 0);
        same("zoom stops at four times", 4.0, (double) l.scale());
        l.zoom(0.1f, 0, 0, 0, 0);
        same("and at fit width", 1.0, (double) l.scale());

        SheetLayout odd = new SheetLayout(a4, 1080, 2000, 20);
        odd.zoom(2, 0, 0, 0, 0);
        for (float bad : new float[] {Float.NaN, 0, -1, Float.POSITIVE_INFINITY}) {
            float[] keep = odd.zoom(bad, 540, 1000, 300, 700);
            same("a zoom factor of " + bad + " (a pinch with no spread) changes nothing", "2.0 [300.0, 700.0]",
                    odd.scale() + " " + Arrays.toString(keep));
        }

        SheetLayout tall = new SheetLayout(new float[][] {{595, 842}}, 1080, 3000, 20);
        same("a page shorter than the screen does not scroll", 0.0, (double) tall.clampY(500));

        System.out.println("SheetLayoutTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}

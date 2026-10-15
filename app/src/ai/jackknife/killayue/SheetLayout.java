package ai.jackknife.killayue;

/**
 * Where the sheet viewer puts each page: pages one under another, fitted to the screen's width with a
 * margin round each, all of it scaled by the zoom (fit width to four times). Positions are in screen pixels
 * from the top left of the whole column of pages; the viewer scrolls over that column.
 */
class SheetLayout {
    static final float MAX_ZOOM = 4;

    private final float[][] pages; // each page's width and height, any unit
    private final float viewW, viewH, gap;
    private float scale = 1;

    SheetLayout(float[][] pages, float viewW, float viewH, float gap) {
        this.pages = pages;
        this.viewW = viewW;
        this.viewH = viewH;
        this.gap = gap;
    }

    float scale() {
        return scale;
    }

    float pageW() {
        return (viewW - 2 * gap) * scale;
    }

    float height(int i) {
        return pageW() * pages[i][1] / pages[i][0];
    }

    float left() {
        return gap * scale;
    }

    float top(int i) {
        float y = gap * scale;
        for (int k = 0; k < i; k++) y += height(k) + gap * scale;
        return y;
    }

    float contentW() {
        return viewW * scale;
    }

    float contentH() {
        return pages.length == 0 ? 0 : top(pages.length - 1) + height(pages.length - 1) + gap * scale;
    }

    /** The first and last page with any part on screen when scrolled down by scrollY. */
    int[] visible(float scrollY) {
        int first = -1, last = -1;
        for (int i = 0; i < pages.length; i++) {
            float t = top(i);
            if (t + height(i) >= scrollY && t <= scrollY + viewH) {
                if (first < 0) first = i;
                last = i;
            }
        }
        return new int[] {first, last};
    }

    float clampX(float x) {
        return Math.max(0, Math.min(x, contentW() - viewW));
    }

    float clampY(float y) {
        return Math.max(0, Math.min(y, contentH() - viewH));
    }

    /**
     * Zooms by factor (kept between fit width and MAX_ZOOM) about the screen point fx, fy, and returns the
     * new scroll position {x, y} that keeps the music under that point where it was, before clamping.
     */
    float[] zoom(float factor, float fx, float fy, float scrollX, float scrollY) {
        if (!(factor > 0) || Float.isInfinite(factor)) return new float[] {scrollX, scrollY}; // a pinch with no spread
        float old = scale;
        scale = Math.max(1, Math.min(MAX_ZOOM, scale * factor));
        float k = scale / old;
        return new float[] {(scrollX + fx) * k - fx, (scrollY + fy) * k - fy};
    }
}

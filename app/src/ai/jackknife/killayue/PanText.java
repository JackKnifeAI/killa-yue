package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.widget.TextView;

/**
 * One line of text that never ends in "…": when it is too long for its space it glides to its end, rests, and glides
 * back, so the whole title can be read.
 */
public class PanText extends TextView {
    private static final float SPEED_DP = 28f;  // glide speed, dp a second
    private static final long REST = 1400;      // pause at each end, ms
    private final long born = SystemClock.uptimeMillis();

    public PanText(Context c) {
        super(c);
        setSingleLine(true);
        setHorizontallyScrolling(true);
        setEllipsize(null);
    }

    /** How far the text overflows its space, in pixels; 0 when it fits. */
    private float overflow() {
        CharSequence t = getText();
        float room = getWidth() - getTotalPaddingLeft() - getTotalPaddingRight();
        return t == null || room <= 0 ? 0 : Math.max(0, getPaint().measureText(t.toString()) - room);
    }

    /** Where the glide is at {@code now}: from 0 to {@code over}, resting at both ends. */
    static float pan(long now, float over, float speed) {
        if (over <= 0)
            return 0;
        long glide = (long)(over / speed * 1000), cycle = 2 * (REST + glide), t = now % cycle;
        if (t < REST)
            return 0;
        if (t < REST + glide)
            return over * (t - REST) / glide;
        if (t < 2 * REST + glide)
            return over;
        return over * (1 - (t - 2 * REST - glide) / (float)glide);
    }

    @Override
    protected void onDraw(Canvas c) {
        float over = overflow();
        int x = Math.round(pan(SystemClock.uptimeMillis() - born, over, SPEED_DP * getResources().getDisplayMetrics().density));
        if (getScrollX() != x)
            setScrollX(x);
        super.onDraw(c);
        if (over > 0 && isShown())
            postInvalidateDelayed(33);
    }
}

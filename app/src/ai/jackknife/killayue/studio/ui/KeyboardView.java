package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

/** Two octaves in two rows, with independent touch pointers and black-key hit testing. */
final class KeyboardView extends View {
    interface Target {
        void note(int key, float velocity, boolean on);
    }
    private static final int[] WHITE = {0, 2, 4, 5, 7, 9, 11}, BLACK = {1, 3, 6, 8, 10}, EDGE = {1, 2, 4, 5, 6};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseIntArray fingers = new SparseIntArray();
    private final Target target;
    int base = 48;
    KeyboardView(Context c, Target target) {
        super(c);
        this.target = target;
        setContentDescription("Two-row playable piano keyboard. Lower row C3, upper row C4.");
        setFocusable(true);
    }
    void octave(int step) {
        release();
        base = Math.max(36, Math.min(60, base + step * 12));
        setContentDescription("Two-row piano keyboard, starting at MIDI note " + base);
        invalidate();
    }
    void release() {
        for (int i = fingers.size() - 1; i >= 0; i--) change(fingers.keyAt(i), -1, .8f);
        fingers.clear();
        invalidate();
    }
    private boolean held(int key) {
        for (int i = 0; i < fingers.size(); i++)
            if (fingers.valueAt(i) == key)
                return true;
        return false;
    }
    private void change(int pointer, int key, float velocity) {
        int old = fingers.get(pointer, -1);
        if (old == key)
            return;
        fingers.delete(pointer);
        if (old >= 0 && !held(old))
            target.note(old, 0, false);
        if (key >= 0) {
            boolean was = held(key);
            fingers.put(pointer, key);
            if (!was)
                target.note(key, velocity, true);
        }
        invalidate();
    }
    private int key(float x, float y) {
        if (x < 0 || x >= getWidth() || y < 0 || y >= getHeight())
            return -1;
        float h = getHeight() / 2f, w = getWidth() / 7f;
        int row = (int) (y / h), octave = base + (1 - row) * 12;
        float yy = y - row * h;
        if (yy < h * .62f)
            for (int i = 0; i < BLACK.length; i++)
                if (Math.abs(x - EDGE[i] * w) < w * .29f)
                    return octave + BLACK[i];
        return octave + WHITE[Math.min(6, (int) (x / w))];
    }
    protected void onDraw(Canvas c) {
        float h = getHeight() / 2f, w = getWidth() / 7f;
        paint.setTextSize(Ui.dp(getContext(), 12));
        for (int row = 0; row < 2; row++) {
            int octave = base + (1 - row) * 12;
            float y = row * h;
            for (int i = 0; i < 7; i++) {
                paint.setColor(held(octave + WHITE[i]) ? Skin.ACCENT : 0xfff1eadc);
                c.drawRect(i * w + 1, y + 1, (i + 1) * w - 1, y + h - 2, paint);
                paint.setColor(0xff222222);
                if (i == 0)
                    c.drawText("C" + (octave / 12 - 1), i * w + 6, y + h - 10, paint);
            }
            for (int i = 0; i < BLACK.length; i++) {
                paint.setColor(held(octave + BLACK[i]) ? Skin.HI : 0xff171717);
                float x = EDGE[i] * w;
                c.drawRect(x - w * .29f, y + 1, x + w * .29f, y + h * .62f, paint);
            }
        }
    }
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            release();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            getParent().requestDisallowInterceptTouchEvent(true);
            change(e.getPointerId(i), key(e.getX(i), e.getY(i)), .8f);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) change(e.getPointerId(i), key(e.getX(i), e.getY(i)), .8f);
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            change(e.getPointerId(e.getActionIndex()), -1, 0);
            if (action == MotionEvent.ACTION_UP)
                performClick();
            return true;
        }
        return true;
    }
    public boolean performClick() {
        super.performClick();
        return true;
    }
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }
}

package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.MidiNotes;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * The playable piano: all 88 keys, a window of them on screen in one or two rows, and a gold map of the whole
 * keyboard above to slide that window. Ivory and ebony keys glow gold where they are held, by a finger, a MIDI
 * keyboard or the MIDI effects. Where a finger lands sets how hard the note plays: near the player is loud, near
 * the felt is soft, like the far end of a real key. With a scale chosen, keys in the scale are bright and its root
 * is marked.
 */
final class GrandKeysView extends View {
    interface Target {
        void note(int key, float velocity, boolean on);
    }
    private static final boolean[] BLACK = {false, true, false, true, false, false, true, false, true, false, true, false};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseIntArray fingers = new SparseIntArray();
    private final boolean[] lit = new boolean[128];  // sounding from elsewhere: MIDI in, effects, playback
    private final Target target;
    /** The lowest white key on screen, the number of white keys across, rows (1 or 2). */
    int first = 39, across = 15, rows = 1;
    /** Scale highlighting: root 0-11 and pitch classes, or null for none. */
    int root = -1;
    boolean[] inScale;
    float fixedVelocity = -1;
    private boolean mapDrag;
    private float mapGrab;

    GrandKeysView(Context c, Target target) {
        super(c);
        this.target = target;
        text.setTypeface(Skin.text());
        setFocusable(true);
        describe();
    }
    private void describe() {
        setContentDescription("Piano keyboard from " + name(white(first)) + ", " + across + " white keys"
            + (rows == 2 ? " in two rows" : "") + ". Slide the map above the keys to move along all 88.");
    }
    static String name(int key) {
        String[] n = {"C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};
        return n[key % 12] + (key / 12 - 1);
    }
    /** MIDI key of the white key numbered w from A0 (w = 0). */
    static int white(int w) {
        int[] steps = {0, 2, 3, 5, 7, 8, 10};  // A B C D E F G from A
        return 21 + (w / 7) * 12 + steps[w % 7];
    }
    static int whiteIndex(int key) {
        int k = key - 21, oct = k / 12, pc = k % 12;
        int[] idx = {0, 0, 1, 2, 2, 3, 3, 4, 5, 5, 6, 6};  // from A
        return oct * 7 + idx[pc];
    }
    static final int WHITES = 52;
    void scroll(int whites) {
        release();
        first = Math.max(0, Math.min(WHITES - across * rows, first + whites));
        describe();
        invalidate();
    }
    void zoom(int whitesAcross) {
        release();
        across = Math.max(7, Math.min(26, whitesAcross));
        first = Math.max(0, Math.min(WHITES - across * rows, first));
        describe();
        invalidate();
    }
    void rows(int n) {
        release();
        rows = n;
        first = Math.max(0, Math.min(WHITES - across * rows, first));
        describe();
        invalidate();
    }
    /** Shows the octave holding key (C at the left where possible). */
    void reveal(int key) {
        int w = whiteIndex(Math.max(21, Math.min(108, key)));
        if (w >= first && w < first + across * rows)
            return;
        first = Math.max(0, Math.min(WHITES - across * rows, w - 2));
        invalidate();
    }
    void light(int key, boolean on) {
        if (key >= 0 && key < 128 && lit[key] != on) {
            lit[key] = on;
            postInvalidateOnAnimation();
        }
    }
    void unlightAll() {
        java.util.Arrays.fill(lit, false);
        invalidate();
    }
    void scale(int root, boolean[] pcs) {
        this.root = root;
        inScale = pcs;
        invalidate();
    }

    void release() {
        for (int i = fingers.size() - 1; i >= 0; i--) {
            int k = fingers.valueAt(i);
            fingers.removeAt(i);
            if (!held(k))
                target.note(k, 0, false);
        }
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
                target.note(key, fixedVelocity > 0 ? fixedVelocity : velocity, true);
        }
        invalidate();
    }

    float map() { return Ui.dp(getContext(), 22); }
    float felt() { return Ui.dp(getContext(), 7); }
    float rowHeight() { return (getHeight() - map() - felt() * rows) / rows; }
    float whiteWidth() { return getWidth() / (float) across; }

    /** The key under a point and how hard it plays there, or -1. */
    private int key(float x, float y, float[] velocity) {
        if (y < map() || x < 0 || x >= getWidth() || y >= getHeight())
            return -1;
        float rh = rowHeight(), ww = whiteWidth();
        int row = (int) ((y - map()) / (rh + felt()));
        if (row >= rows)
            row = rows - 1;
        // the top row plays the keys after the bottom row's
        int base = first + (rows - 1 - row) * across;
        float top = map() + row * (rh + felt()) + felt(), yy = y - top;
        if (yy < 0)
            return -1;
        int w = base + (int) (x / ww);
        if (w >= WHITES)
            return -1;
        float blackH = rh * .62f;
        if (yy < blackH) {
            // a black key between this white and the next, or the one before
            float within = x / ww - (int) (x / ww);
            int wk = white(w);
            if (within > .68f && wk + 1 <= 108 && BLACK[(wk + 1) % 12]) {
                velocity[0] = .28f + .72f * (yy / blackH);
                return wk + 1;
            }
            if (within < .32f && wk - 1 >= 21 && BLACK[(wk - 1) % 12]) {
                velocity[0] = .28f + .72f * (yy / blackH);
                return wk - 1;
            }
        }
        velocity[0] = .28f + .72f * Math.min(1, yy / rh);
        return white(w);
    }

    private boolean pressed(int key) { return held(key) || lit[key]; }
    private boolean dim(int key) {
        return inScale != null && root >= 0 && !inScale[((key - root) % 12 + 12) % 12];
    }

    protected void onDraw(Canvas c) {
        float ww = whiteWidth(), rh = rowHeight(), r = Ui.dp(getContext(), 4);
        drawMap(c);
        for (int row = 0; row < rows; row++) {
            int base = first + (rows - 1 - row) * across;
            float top = map() + row * (rh + felt());
            // felt: a dark red strip with a gold pin line, where the keys go under the fallboard
            paint.setShader(null);
            paint.setColor(0xff3a0d12);
            c.drawRect(0, top, getWidth(), top + felt(), paint);
            paint.setColor(Skin.ACCENT);
            c.drawRect(0, top + felt() - Ui.dp(getContext(), 1), getWidth(), top + felt(), paint);
            top += felt();
            for (int i = 0; i < across && base + i < WHITES; i++) {
                int k = white(base + i);
                float x = i * ww;
                RectF key = new RectF(x + 1.2f, top, x + ww - 1.2f, top + rh);
                boolean down = pressed(k);
                int topColor = down ? 0xfff6dc9a : dim(k) ? 0xffcfc6b3 : 0xfffdf9ef;
                int bottomColor = down ? Skin.ACCENT : dim(k) ? 0xffaea590 : 0xffe6dbc2;
                paint.setShader(new LinearGradient(0, top, 0, top + rh, topColor, bottomColor, Shader.TileMode.CLAMP));
                c.drawRoundRect(key, r, r, paint);
                c.drawRect(key.left, key.top, key.right, key.top + r, paint);
                paint.setShader(null);
                // the lip: a darker band at the near end, shorter when the key is down
                paint.setColor(down ? 0x40000000 : 0x2a000000);
                c.drawRect(key.left, key.bottom - Ui.dp(getContext(), down ? 3 : 6), key.right, key.bottom - r, paint);
                if (down) {
                    paint.setColor(Skin.glow(110));
                    c.drawRoundRect(new RectF(key.left + 2, key.top, key.right - 2, key.bottom - 2), r, r, paint);
                }
                if (k % 12 == root % 12 && root >= 0) {
                    paint.setColor(Skin.ACCENT);
                    c.drawCircle(x + ww / 2, key.bottom - Ui.dp(getContext(), 26), Ui.dp(getContext(), 3), paint);
                }
                if (k % 12 == 0 || ww > Ui.dp(getContext(), 40)) {
                    text.setColor(k % 12 == 0 ? 0xff6b5a3a : 0x806b5a3a);
                    text.setTextSize(Ui.dp(getContext(), k % 12 == 0 ? 11 : 9));
                    text.setTextAlign(Paint.Align.CENTER);
                    c.drawText(name(k), x + ww / 2, key.bottom - Ui.dp(getContext(), 10), text);
                }
            }
            for (int i = 0; i < across && base + i < WHITES; i++) {
                int k = white(base + i) + 1;
                if (k > 108 || !BLACK[k % 12] || (i == across - 1))
                    continue;
                float cx = (i + 1) * ww, bw = ww * .6f, bh = rh * .62f;
                RectF key = new RectF(cx - bw / 2, top, cx + bw / 2, top + bh);
                boolean down = pressed(k);
                paint.setShader(new LinearGradient(0, top, 0, top + bh, down ? 0xff5a4320 : dim(k) ? 0xff2a2725 : 0xff34302c,
                                                   down ? 0xff1a1206 : 0xff050505, Shader.TileMode.CLAMP));
                c.drawRoundRect(key, r * .8f, r * .8f, paint);
                paint.setShader(null);
                // a lacquer highlight down the face, and a gold edge when held
                paint.setColor(down ? Skin.glow(150) : 0x30ffffff);
                c.drawRect(key.left + bw * .18f, top + Ui.dp(getContext(), 2), key.left + bw * .3f, key.bottom - Ui.dp(getContext(), 8), paint);
                paint.setColor(down ? Skin.ACCENT : 0xff000000);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(getContext(), down ? 2 : 1));
                c.drawRoundRect(key, r * .8f, r * .8f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
    private void drawMap(Canvas c) {
        float h = map() - Ui.dp(getContext(), 6), y = Ui.dp(getContext(), 2), unit = getWidth() / (float) WHITES;
        paint.setShader(null);
        paint.setColor(0xff141110);
        c.drawRoundRect(new RectF(0, y, getWidth(), y + h), h / 2, h / 2, paint);
        for (int w = 0; w < WHITES; w++) {
            int k = white(w);
            paint.setColor(pressed(k) ? Skin.ACCENT : 0xffd8cfbb);
            c.drawRect(w * unit + .6f, y + 2, (w + 1) * unit - .6f, y + h - 2, paint);
            if (k + 1 <= 108 && BLACK[(k + 1) % 12]) {
                paint.setColor(pressed(k + 1) ? Skin.HI : 0xff0c0c0c);
                c.drawRect((w + .7f) * unit, y + 2, (w + 1.3f) * unit, y + h * .6f, paint);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 2));
        paint.setColor(Skin.ACCENT);
        c.drawRoundRect(new RectF(first * unit, y, (first + across * rows) * unit, y + h), Ui.dp(getContext(), 3), Ui.dp(getContext(), 3), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Skin.glow(40));
        c.drawRect(first * unit, y, (first + across * rows) * unit, y + h, paint);
    }

    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        float[] v = new float[1];
        if (action == MotionEvent.ACTION_CANCEL) {
            mapDrag = false;
            release();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN && e.getY() < map()) {
            mapDrag = true;
            float unit = getWidth() / (float) WHITES;
            mapGrab = e.getX() / unit - first;
            if (mapGrab < 0 || mapGrab > across * rows)
                mapGrab = across * rows / 2f;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (mapDrag) {
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_DOWN) {
                float unit = getWidth() / (float) WHITES;
                int w = Math.round(e.getX() / unit - mapGrab);
                if (w != first) {
                    first = Math.max(0, Math.min(WHITES - across * rows, w));
                    describe();
                    invalidate();
                }
            }
            if (action == MotionEvent.ACTION_UP)
                mapDrag = false;
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            getParent().requestDisallowInterceptTouchEvent(true);
            int k = key(e.getX(i), e.getY(i), v);
            change(e.getPointerId(i), k, v[0]);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                int k = key(e.getX(i), e.getY(i), v);
                change(e.getPointerId(i), k, v[0]);
            }
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
    static boolean valid(int key) { return key >= MidiNotes.LOW && key <= MidiNotes.HIGH; }
}

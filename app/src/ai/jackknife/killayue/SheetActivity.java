package ai.jackknife.killayue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Sheet music in the app: the exported PDF on paper over the skin's background, scrolled and pinched to
 * zoom, with Print (Android's print dialog: any printer it knows, or Save as PDF) and Share. Pages are drawn
 * by Android's own PdfRenderer on a worker thread; while a finger moves, the whole-page pictures are
 * scaled, and once it rests the part on screen is drawn again sharp at that zoom.
 */
public class SheetActivity extends Activity {
    static final String FILE = "file", SHARE = "share";

    /** Opens a PDF from Documents/Killa YuE; share lists the files Share sends (the PDF first). */
    static void open(Context c, String pdfName, String[] share) {
        Intent i = new Intent(c, SheetActivity.class);
        i.putExtra(FILE, pdfName);
        i.putExtra(SHARE, share != null ? share : new String[] {pdfName});
        c.startActivity(i);
    }

    private HandlerThread worker;
    private Handler work;
    private final Handler ui = new Handler();
    private PdfRenderer pdf;
    private ParcelFileDescriptor fd;
    private float[][] sizes;
    private File file;
    private Pages pages;

    @Override
    protected void onCreate(Bundle b) {
        setTheme(Skin.themeFor(Skin.id(this)));
        super.onCreate(b);
        Skin.load(this);
        final String name = getIntent().getStringExtra(FILE);
        final String[] share = getIntent().getStringArrayExtra(SHARE);
        file = new File(MainActivity.scoreDir(), name == null ? "" : name);
        try {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            pdf = new PdfRenderer(fd);
            sizes = new float[pdf.getPageCount()][];
            for (int i = 0; i < sizes.length; i++) {
                PdfRenderer.Page p = pdf.openPage(i);
                sizes[i] = new float[] {p.getWidth(), p.getHeight()};
                p.close();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not open " + name + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        worker = new HandlerThread("sheet-pages");
        worker.start();
        work = new Handler(worker.getLooper());

        float d = getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(Skin.background(this));
        TextView title = new TextView(this);
        title.setText(name.replaceAll("\\.pdf$", ""));
        title.setTextColor(Skin.ACCENT);
        title.setTextSize(Skin.SCRIPT_TITLES ? 24 : 18);
        title.setTypeface(Skin.script());
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (6 * d));
        col.addView(title);
        pages = new Pages(this);
        col.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (12 * d));
        Button print = plate("Print", true), send = plate("Share", false), close = plate("Close", false);
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(0, (int) (52 * d), 1.4f);
        LinearLayout.LayoutParams narrow = new LinearLayout.LayoutParams(0, (int) (52 * d), 1);
        narrow.leftMargin = (int) (10 * d);
        bar.addView(print, wide);
        bar.addView(send, narrow);
        bar.addView(close, new LinearLayout.LayoutParams(narrow));
        col.addView(bar);
        setContentView(col);

        print.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                print(name);
            }
        });
        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ArrayList<Uri> uris = new ArrayList<Uri>();
                for (String f : share) uris.add(TrackProvider.uri(f));
                Intent s = new Intent(Intent.ACTION_SEND_MULTIPLE);
                s.setType("*/*");
                s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(s, "Share the sheet music"));
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (work == null) return;
        work.post(new Runnable() { // the renderer is only touched on its own thread
            public void run() {
                try {
                    pdf.close();
                    fd.close();
                } catch (Exception e) {
                    // closing anyway
                }
            }
        });
        worker.quitSafely();
    }

    private Button plate(String s, boolean main) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(main ? 18 : 15);
        b.setTextColor(main ? Skin.ACCENT : Skin.TEXT);
        b.setShadowLayer(5, 0, 2, 0xCC000000);
        b.setTypeface(main ? Skin.script() : Skin.text(), Typeface.BOLD);
        b.setBackground(new Skin.Plate(main));
        return b;
    }

    /** Android's print dialog for the PDF as it is: A4, every page. */
    private void print(String name) {
        final String doc = name;
        final int count = sizes.length;
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        pm.print(name.replaceAll("\\.pdf$", ""), new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes old, PrintAttributes now, CancellationSignal cancel, LayoutResultCallback done, Bundle extras) {
                if (cancel.isCanceled()) {
                    done.onLayoutCancelled();
                    return;
                }
                done.onLayoutFinished(new PrintDocumentInfo.Builder(doc).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(count).build(), !now.equals(old));
            }

            @Override
            public void onWrite(PageRange[] which, ParcelFileDescriptor out, CancellationSignal cancel, WriteResultCallback done) {
                try {
                    InputStream in = new FileInputStream(file);
                    OutputStream o = new FileOutputStream(out.getFileDescriptor());
                    try {
                        byte[] buf = new byte[65536];
                        for (int n; (n = in.read(buf)) > 0; ) {
                            if (cancel.isCanceled()) {
                                done.onWriteCancelled();
                                return;
                            }
                            o.write(buf, 0, n);
                        }
                    } finally {
                        in.close();
                        o.close();
                    }
                    done.onWriteFinished(new PageRange[] {PageRange.ALL_PAGES});
                } catch (Exception e) {
                    done.onWriteFailed(e.getMessage());
                }
            }
        }, new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
    }

    /** The pages, one under another, on the skin's background. */
    class Pages extends View {
        private SheetLayout layout;
        private float sx, sy; // scroll
        private final OverScroller fling;
        private final GestureDetector gestures;
        private final ScaleGestureDetector pinch;
        private final Bitmap[] whole;        // each page fitted to the width, drawn as needed
        private final boolean[] asked;
        private Bitmap sharp;                // what is on screen, drawn at the current zoom
        private float sharpX, sharpY, sharpScale;
        private int sized, asks; // count screen sizes (old page pictures are dropped) and sharp drawings (only the last counts)
        private final Paint paper = new Paint(), shade = new Paint(), smooth = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Runnable settle = new Runnable() {
            public void run() {
                renderSharp();
            }
        };

        Pages(Context c) {
            super(c);
            fling = new OverScroller(c);
            whole = new Bitmap[sizes.length];
            asked = new boolean[sizes.length];
            paper.setColor(Color.WHITE);
            shade.setColor(0x66000000);
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    fling.forceFinished(true);
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    moveTo(sx + dx, sy + dy);
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    fling.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) layout.clampX(1e9f), 0, (int) layout.clampY(1e9f));
                    postInvalidateOnAnimation();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    zoomBy(layout.scale() > 1.4f ? 1 / layout.scale() : 2.5f, e.getX(), e.getY());
                    return true;
                }
            });
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector p) {
                    zoomBy(p.getScaleFactor(), p.getFocusX(), p.getFocusY());
                    return true;
                }
            });
            pinch.setQuickScaleEnabled(false); // a double tap zooms by itself; quick scale would fight it
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float gap = 14 * getResources().getDisplayMetrics().density;
            layout = new SheetLayout(sizes, w, h, gap);
            sx = sy = 0;
            for (int i = 0; i < whole.length; i++) {
                whole[i] = null;
                asked[i] = false;
            }
            sized++;
            sharp = null;
        }

        private void moveTo(float x, float y) {
            sx = layout.clampX(x);
            sy = layout.clampY(y);
            changed();
        }

        private void zoomBy(float factor, float fx, float fy) {
            float[] s = layout.zoom(factor, fx, fy, sx, sy);
            moveTo(s[0], s[1]);
        }

        /** Something moved: draw again now, and sharply once it rests. */
        private void changed() {
            removeCallbacks(settle);
            postDelayed(settle, 180);
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            pinch.onTouchEvent(e);
            if (!pinch.isInProgress()) gestures.onTouchEvent(e);
            return true;
        }

        @Override
        public void computeScroll() {
            if (fling.computeScrollOffset()) {
                sx = layout.clampX(fling.getCurrX());
                sy = layout.clampY(fling.getCurrY());
                changed();
                postInvalidateOnAnimation();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (layout == null) return;
            int[] v = layout.visible(sy);
            if (v[0] < 0) return;
            float shadow = 3 * getResources().getDisplayMetrics().density;
            for (int i = v[0]; i <= v[1]; i++) {
                RectF r = pageRect(i);
                c.drawRect(r.left + shadow, r.top + shadow, r.right + shadow, r.bottom + shadow, shade);
                c.drawRect(r, paper);
                if (whole[i] != null) c.drawBitmap(whole[i], null, r, smooth);
                else askWhole(i);
            }
            for (int i = 0; i < whole.length; i++) { // keep only the pages near the screen
                if ((i < v[0] - 1 || i > v[1] + 1) && whole[i] != null) {
                    whole[i] = null;
                    asked[i] = false;
                }
            }
            if (sharp != null && sharpX == sx && sharpY == sy && sharpScale == layout.scale()) c.drawBitmap(sharp, 0, 0, null);
        }

        private RectF pageRect(int i) {
            float left = layout.left() - sx, top = layout.top(i) - sy;
            return new RectF(left, top, left + layout.pageW(), top + layout.height(i));
        }

        /** Page i at the fitted width, at most 1440 pixels across, drawn on the worker. */
        private void askWhole(final int i) {
            if (asked[i]) return;
            asked[i] = true;
            final int gen = sized;
            final int w = (int) Math.min(1440, layout.pageW() / layout.scale());
            final int h = Math.round(w * sizes[i][1] / sizes[i][0]);
            work.post(new Runnable() {
                public void run() {
                    final Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bm.eraseColor(Color.WHITE);
                    try {
                        PdfRenderer.Page p = pdf.openPage(i);
                        p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        p.close();
                    } catch (Exception e) {
                        return;
                    }
                    ui.post(new Runnable() {
                        public void run() {
                            if (gen != sized) return;
                            whole[i] = bm;
                            invalidate();
                        }
                    });
                }
            });
        }

        /** What is on screen now, drawn from the PDF at this zoom, so lines and symbols stay sharp. */
        private void renderSharp() {
            if (layout == null || layout.scale() < 1.2f || getWidth() == 0) {
                asks++; // a drawing still on the way is not wanted
                sharp = null;
                return;
            }
            final int gen = ++asks;
            final float x = sx, y = sy, scale = layout.scale();
            final int w = getWidth(), h = getHeight();
            final int[] v = layout.visible(sy);
            final RectF[] rects = new RectF[v[1] - v[0] + 1];
            for (int i = v[0]; i <= v[1]; i++) rects[i - v[0]] = pageRect(i);
            work.post(new Runnable() {
                public void run() {
                    final Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    for (int i = v[0]; i <= v[1]; i++) {
                        RectF r = rects[i - v[0]];
                        Rect clip = new Rect(Math.max(0, (int) Math.floor(r.left)), Math.max(0, (int) Math.floor(r.top)),
                                Math.min(w, (int) Math.ceil(r.right)), Math.min(h, (int) Math.ceil(r.bottom)));
                        if (clip.isEmpty()) continue;
                        Canvas c = new Canvas(bm);
                        c.drawRect(clip, paper);
                        Matrix m = new Matrix();
                        m.setScale(r.width() / sizes[i][0], r.height() / sizes[i][1]);
                        m.postTranslate(r.left, r.top);
                        try {
                            PdfRenderer.Page p = pdf.openPage(i);
                            p.render(bm, clip, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            p.close();
                        } catch (Exception e) {
                            return;
                        }
                    }
                    ui.post(new Runnable() {
                        public void run() {
                            if (gen != asks) return;
                            sharp = bm;
                            sharpX = x;
                            sharpY = y;
                            sharpScale = scale;
                            invalidate();
                        }
                    });
                }
            });
        }
    }
}

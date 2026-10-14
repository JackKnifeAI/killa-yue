package ai.jackknife.killayue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

/**
 * Lays a prepared score (ScorePrep) out with abcjs, bundled in app/assets/abcjs and run offline in a
 * WebView that is never shown, and hands back abcjs's SVG for SvgScore and ScorePages to draw.
 * Call on the main thread; the answer comes on the main thread, once.
 */
class Engrave {
    static final int STAFF_WIDTH = 760; // ScorePrep fills lines to 700, so a dense line still fits

    interface Done {
        void laidOut(String svg);

        void failed(String why);
    }

    static void run(Activity a, String abc, final Done done) {
        final ViewGroup root = (ViewGroup) a.getWindow().getDecorView();
        final WebView web = new WebView(a);
        web.setVisibility(View.INVISIBLE); // laid out, so abcjs can measure text, but never drawn
        root.addView(web, new FrameLayout.LayoutParams(STAFF_WIDTH, 1));
        web.getSettings().setJavaScriptEnabled(true);
        final Bridge bridge = new Bridge(web, root, done);
        web.addJavascriptInterface(bridge, "Killa");
        final String call = "engrave(" + JSONObject.quote(abc) + "," + STAFF_WIDTH + ")";
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(call, null);
            }
        });
        web.loadUrl("file:///android_asset/abcjs/engrave.html");
        web.postDelayed(new Runnable() {
            public void run() {
                bridge.finish(null, "The music engraver did not answer within 30 seconds.");
            }
        }, 30000);
    }

    static class Bridge {
        private final WebView web;
        private final ViewGroup root;
        private final Done done;
        private boolean over;

        Bridge(WebView web, ViewGroup root, Done done) {
            this.web = web;
            this.root = root;
            this.done = done;
        }

        @JavascriptInterface
        public void svg(String markup) {
            finish(markup, null);
        }

        @JavascriptInterface
        public void fail(String why) {
            finish(null, "abcjs could not read part of this score:\n" + why);
        }

        void finish(final String svg, final String why) {
            web.post(new Runnable() {
                public void run() {
                    if (over) return;
                    over = true;
                    root.removeView(web);
                    web.destroy();
                    if (svg != null) done.laidOut(svg);
                    else done.failed(why);
                }
            });
        }
    }
}

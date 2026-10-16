package ai.jackknife.killayue.studio.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.NativeEngine;

/**
 * The Studio: its own screen in the app, in the skin's dress, portrait or landscape. The Vault (projects),
 * Arrange (the timeline), the Mixer and the Splitter (stems), swapped by the bar at the bottom. The session
 * (engine, project, undo tree) outlives the screen, so rotating or leaving and coming back never stops it.
 */
public class StudioActivity extends Activity implements StudioSession.Listener {
    static final String SONG = "song";  // a library song's meta file name: open it as a new project

    public static void open(Context c) {
        c.startActivity(new Intent(c, StudioActivity.class));
    }

    /** "Open in Studio" from a song's menu: a new project made from that song. */
    public static void openSong(Context c, File meta) {
        Intent i = new Intent(c, StudioActivity.class);
        i.putExtra(SONG, meta.getPath());
        c.startActivity(i);
    }

    StudioSession session;
    private FrameLayout content;
    private TextView title;
    private Screen[] screens;
    private Button[] tabs;
    private int shown = -1;
    private VaultScreen vault;
    private boolean visible;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        public void doFrame(long t) {
            if (!visible) return;
            NativeEngine.Status s = session.status();
            if (shown >= 0) screens[shown].frame(s);
            String err = session.takeError();
            if (err != null) Ui.toast(StudioActivity.this, err);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        setTheme(Skin.themeFor(this, Skin.id(this)));
        super.onCreate(b);
        Skin.load(this);
        session = StudioSession.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = Ui.column(this);
        root.setBackground(Skin.background(this));
        title = Ui.title(this, "Studio");
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 4));
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), 0);
        Button back = RoundFace.button(this, "‹");
        back.setTextSize(26);
        back.setContentDescription("Back to Killa YuE");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        View balance = new View(this);  // keeps the title centred on the screen
        head.addView(balance, new LinearLayout.LayoutParams(Ui.dp(this, 54), 1));
        root.addView(head);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        vault = new VaultScreen(this);
        screens = new Screen[] {vault, new ArrangeScreen(this), new MixerScreen(this), new SplitterScreen(this)};
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 8));
        tabs = new Button[screens.length];
        for (int i = 0; i < screens.length; i++) {
            content.addView(screens[i].view(), new FrameLayout.LayoutParams(-1, -1));
            screens[i].view().setVisibility(View.GONE);
            final int idx = i;
            tabs[i] = Ui.button(this, screens[i].name(), false);
            tabs[i].setTextSize(13);
            LinearLayout.LayoutParams lp = Ui.weight();
            lp.leftMargin = Ui.dp(this, 3);
            lp.rightMargin = Ui.dp(this, 3);
            bar.addView(tabs[i], lp);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    show(idx);
                }
            });
        }
        root.addView(bar);
        setContentView(root);
        session.listen(this);

        if (session.engine() == null) Ui.toast(this, "The Studio's audio could not start: " + session.error());
        String song = getIntent().getStringExtra(SONG);
        if (song != null && b == null) {
            show(0);
            vault.newFromSong(new File(song));
        } else {
            show(session.project() == null ? 0 : 1);
        }
        projectChanged();
    }

    void show(int i) {
        if (i == shown) return;
        if (i > 0 && session.project() == null) {
            Ui.toast(this, "Open a project first, or make one from a song.");
            i = 0;
        }
        if (shown >= 0) {
            screens[shown].hidden();
            screens[shown].view().setVisibility(View.GONE);
        }
        shown = i;
        screens[i].view().setVisibility(View.VISIBLE);
        screens[i].shown();
        for (int k = 0; k < tabs.length; k++) {
            tabs[k].setBackground(new Skin.Plate(false, k == i));
            tabs[k].setTextColor(k == i ? Skin.HI : Skin.LO);
            tabs[k].setTypeface(Skin.text(), k == i ? Typeface.BOLD : Typeface.NORMAL);
            tabs[k].setShadowLayer(k == i ? Ui.dp(this, 7) : 0, 0, 0, k == i ? (0xEE << 24) | Skin.GLOW : 0);
        }
    }

    /** A project was opened (from the Vault): to the timeline. */
    void opened() {
        show(1);
    }

    public void projectChanged() {
        title.setText(session.project() == null ? "Studio" : session.project().name);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    @Override
    protected void onPause() {
        visible = false;
        session.flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        session.unlisten(this);
        for (Screen s : screens) if (s instanceof StudioSession.Listener) session.unlisten((StudioSession.Listener) s);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        vault.result(request, result, data);
    }
}

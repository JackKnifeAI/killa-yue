package ai.jackknife.killayue.studio.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.studio.Mixer;
import ai.jackknife.killayue.studio.Effects;
import ai.jackknife.killayue.studio.MixerEdit;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.HashMap;

import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.Edit;
import ai.jackknife.killayue.studio.FaderScale;
import ai.jackknife.killayue.studio.NativeEngine;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.Track;

/**
 * Track/group strips, shared wet returns and master, with ordered insert racks. Drags are one undo step;
 * only a knob or fader cap captures them, leaving the rest of the strip free to scroll.
 */
final class MixerScreen implements Screen, StudioSession.Listener {
    private final StudioActivity a;
    private final HorizontalScrollView view;
    private final LinearLayout root;
    private final LinearLayout strips;
    private final ArrayList<Strip> list = new ArrayList<Strip>();
    private final HashMap<String, float[]> meters = new HashMap<String, float[]>();
    private String layoutKey = "";

    /** Killa Studio's gold knob face, shared by every strip. */
    private static android.graphics.Bitmap knobArt;

    MixerScreen(StudioActivity a) {
        this.a = a;
        root = Ui.column(a);
        root.setBackground(Skin.background(a));
        Button group = Ui.button(a, "Add group", false);
        root.addView(group, new LinearLayout.LayoutParams(-1, Ui.dp(a, 44)));
        group.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (project() == null) return;
                if (project().mixer.groups.size() >= Mixer.MAX_GROUPS) { Ui.toast(a, "Eight groups is the limit."); return; }
                nameGroup(null);
            }
        });
        ScrollView vertical = new ScrollView(a);
        vertical.setFillViewport(true);
        root.addView(vertical, new LinearLayout.LayoutParams(-1, 0, 1f));
        view = new HorizontalScrollView(a);
        view.setFillViewport(true);
        vertical.addView(view, new ScrollView.LayoutParams(-1, -1));
        if (knobArt == null) {
            try (java.io.InputStream in = a.getAssets().open("studio/gold-knob.png")) {
                knobArt = android.graphics.BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                knobArt = null;  // the drawn knob below still works without the art
            }
        }
        strips = Ui.row(a);
        strips.setMinimumHeight(Ui.dp(a, 500));
        strips.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 8));
        view.addView(strips);
        a.session.listen(this);
    }

    public String name() {
        return "Mixer";
    }

    public View view() {
        return root;
    }

    public void shown() {
        projectChanged();
    }

    public void hidden() {}

    public void projectChanged() {
        Project p = a.session.project();
        StringBuilder key = new StringBuilder();
        if (p != null) {
            key.append("open:");
            for (Track t : p.tracks) key.append(t.id).append(',');
            for (String g : p.mixer.groups) key.append(g).append(',');
        }
        if (!key.toString().equals(layoutKey)) {
            layoutKey = key.toString();
            strips.removeAllViews();
            list.clear();
            if (p != null) {
                for (Track t : p.tracks) add(new Strip(a, t.id));
                for (String g : p.mixer.groups) add(new Strip(a, g));
                add(new Strip(a, Mixer.REVERB));
                add(new Strip(a, Mixer.DELAY));
                add(new Strip(a, null));
            }
        }
        for (Strip s : list) s.invalidate();
    }

    private void add(Strip s) {
        list.add(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 104), -1);
        lp.rightMargin = Ui.dp(a, 6);
        strips.addView(s, lp);
    }

    public void frame(NativeEngine.Status s) {
        if (s == null) return;
        a.session.meters(meters);
        for (Strip st : list) {
            float[] m = st.id == null ? new float[] {s.peakL, s.peakR} : meters.get(st.id);
            st.meter(m);
        }
    }

    Project project() { return a.session.project(); }
    String stripName(String id) {
        if (Mixer.MASTER.equals(id)) return "Master";
        if (Mixer.REVERB.equals(id)) return "Reverb";
        if (Mixer.DELAY.equals(id)) return "Delay";
        Track t = project().track(id);
        return t == null ? project().mixer.get(id).name : t.name;
    }
    void save(Mixer m, String label) { a.session.run(new MixerEdit(project(), m, label, null)); }

    void nameGroup(final String id) {
        final EditText field = Ui.edit(a, "Group name", 1);
        field.setText(id == null ? "Group " + (project().mixer.groups.size() + 1) : stripName(id));
        new Lux.Builder(a).setTitle(id == null ? "Add group" : "Rename group").setView(field)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) { Ui.toast(a, "Give the group a name."); return; }
                    Mixer m = project().mixer.copy();
                    if (id == null) m.addGroup(name); else m.edit(id).name = name;
                    save(m, id == null ? "Add group" : "Rename group");
                }
            }).setNegativeButton("Cancel", null).show();
    }
    void groupMenu(final String id) {
        new Lux.Builder(a).setTitle(stripName(id)).setItems(new String[] {"Rename", "Remove group (tracks go to Master)"},
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (w == 0) nameGroup(id);
                    else { Mixer m = project().mixer.copy(); m.removeGroup(id); save(m, "Remove group"); }
                }
            }).show();
    }
    void routing(final String id) {
        final ArrayList<String> ids = new ArrayList<String>();
        ids.add(""); ids.addAll(project().mixer.groups);
        String[] labels = new String[ids.size()];
        labels[0] = "Master";
        for (int i = 1; i < labels.length; i++) labels[i] = stripName(ids.get(i));
        new Lux.Builder(a).setTitle("Send " + stripName(id) + " to").setItems(labels,
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    Mixer m = project().mixer.copy(); m.edit(id).route = ids.get(w); save(m, "Route " + stripName(id));
                }
            }).show();
    }
    String effectName(Mixer.Slot s) {
        if (s.kind == 1) return String.format(java.util.Locale.ROOT, "Trim %+.1f dB", s.value);
        if (s.kind == 2) return "Stereo width " + Math.round(s.value * 100) + "%";
        return Effects.get(s.kind).name;
    }
    void rack(final String id) {
        final ArrayList<String> slots = new ArrayList<String>();
        ArrayList<String> labels = new ArrayList<String>();
        for (Mixer.Slot s : project().mixer.get(id).chain) {
            slots.add(s.id);
            labels.add((s.bypass ? "Bypassed · " : "") + effectName(s));
        }
        if (slots.size() < Mixer.MAX_SLOTS) labels.add("Add effect…");
        new Lux.Builder(a).setTitle(stripName(id) + " · FX in order").setItems(labels.toArray(new String[0]),
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (w == slots.size()) addEffect(id); else effect(id, slots.get(w));
                }
            }).show();
    }
    void addEffect(final String id) {
        new Lux.Builder(a).setTitle("Add effect").setItems(Effects.names(),
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    Mixer m = project().mixer.copy(); m.addSlot(id, w + 1); save(m, "Add effect"); rack(id);
                }
            }).show();
    }
    int slotIndex(Mixer m, String id, String slot) {
        for (int i = 0; i < m.get(id).chain.size(); i++) if (slot.equals(m.get(id).chain.get(i).id)) return i;
        return -1;
    }
    void effect(final String id, final String slot) {
        int index = slotIndex(project().mixer, id, slot);
        if (index < 0) return;
        final Mixer.Slot current = project().mixer.get(id).chain.get(index);
        new Lux.Builder(a).setTitle(effectName(current))
            .setItems(new String[] {"Parameters and presets…", current.bypass ? "Enable" : "Bypass", "Move earlier", "Move later", "Remove"},
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) { effectValue(id, slot); return; }
                        Mixer m = project().mixer.copy();
                        int k = slotIndex(m, id, slot);
                        if (k < 0) return;
                        ArrayList<Mixer.Slot> chain = m.edit(id).chain;
                        if (w == 1) chain.get(k).bypass = !chain.get(k).bypass;
                        else if (w == 2 && k > 0) java.util.Collections.swap(chain, k, k - 1);
                        else if (w == 3 && k + 1 < chain.size()) java.util.Collections.swap(chain, k, k + 1);
                        else if (w == 4) chain.remove(k);
                        else { rack(id); return; }
                        save(m, w == 1 ? "Bypass effect" : w == 4 ? "Remove effect" : "Reorder effects"); rack(id);
                    }
                }).show();
    }
    void effectValue(final String id, final String slot) {
        int index = slotIndex(project().mixer, id, slot);
        if (index < 0) return;
        final Mixer.Slot current = project().mixer.get(id).chain.get(index);
        final Effects.Definition definition = Effects.get(current.kind);
        final LinearLayout form = Ui.column(a);
        int padding = Ui.dp(a, 16); form.setPadding(padding, padding, padding, padding);
        final EditText[] fields = new EditText[definition.params.length];
        Button presets = Ui.button(a, "Presets…", false);
        form.addView(presets);
        for (int j = 0; j < fields.length; j++) {
            Effects.Param param = definition.params[j];
            form.addView(Ui.label(a, param.name + " · " + param.min + " to " + param.max + " " + param.unit));
            EditText field = Ui.edit(a, param.name, 1);
            field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            field.setText(Float.toString(current.get(j)));
            fields[j] = field; form.addView(field);
        }
        if (fields.length == 0) form.addView(Ui.label(a, "Flips both channels. Use Bypass to turn it off."));
        presets.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String[] names = new String[definition.presets.length];
                for (int i = 0; i < names.length; i++) names[i] = definition.presets[i].name;
                new Lux.Builder(a).setTitle(definition.name + " presets").setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        float[] values = definition.presets[which].values;
                        for (int j = 0; j < fields.length; j++) fields[j].setText(Float.toString(values[j]));
                    }
                }).show();
            }
        });
        ScrollView scroll = new ScrollView(a); scroll.addView(form);
        final android.app.AlertDialog dialog = new Lux.Builder(a).setTitle(definition.name).setView(scroll)
            .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Mixer m = project().mixer.copy(); int k = slotIndex(m, id, slot);
                    if (k < 0) { dialog.dismiss(); return; }
                    Mixer.Slot changed = m.edit(id).chain.get(k);
                    for (int j = 0; j < fields.length; j++) changed.set(j, Float.parseFloat(fields[j].getText().toString().trim()));
                    Effects.validate(changed);
                    save(m, definition.name + " parameters"); dialog.dismiss(); rack(id);
                } catch (IllegalArgumentException ex) { Ui.toast(a, "Check the parameter ranges. " + ex.getMessage()); }
            }
        });
    }

    static float dbToPos(float db) {
        return FaderScale.pos(db);
    }

    static float posToDb(float u) {
        return FaderScale.db(u);
    }

    /** One channel strip, drawn whole: name, pan knob, fader and meter, mute and solo. id null: the master. */
    final class Strip extends View {
        final String id;
        private final Mixer.Strip empty = new Mixer.Strip();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final ai.jackknife.killayue.LuxFrame panel = new ai.jackknife.killayue.LuxFrame(12, 6);
        private final GestureDetector taps;
        private float meterL, meterR, holdL, holdR;
        private long lastFrame;
        private Object gesture;
        private int dragging;  // 0 none, 1 fader, 2 pan, 3 reverb send, 4 delay send
        private float downY, downValue;

        Strip(Context c, String id) {
            super(c);
            this.id = id;
            text.setTypeface(Skin.text());
            text.setTextAlign(Paint.Align.CENTER);
            line.setStrokeWidth(Ui.dp(c, 3));
            line.setStrokeCap(Paint.Cap.ROUND);
            taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    if (e.getY() < Ui.dp(a, 54)) {
                        if (track() != null) routing(id);
                        else if (id != null && project().mixer.isGroup(id)) groupMenu(id);
                        return true;
                    }
                    if (e.getY() >= Ui.dp(a, 180) && e.getY() < Ui.dp(a, 210)) { rack(mixId()); return true; }
                    return tapLamps(e.getX(), e.getY());
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (onCap(e.getX(), e.getY())) set(0f, false);
                    else if (id != null && onKnob(e.getX(), e.getY())) setting("pan", 0f, false);
                    else if (sendAt(e.getX(), e.getY()) > 0) setting(sendAt(e.getX(), e.getY()) == 3 ? "reverb" : "delay", 0f, false);
                    return true;
                }
            });
        }

        Track track() {
            Project p = a.session.project();
            return p == null || id == null ? null : p.track(id);
        }

        String mixId() { return id == null ? Mixer.MASTER : id; }
        Mixer.Strip state() {
            Mixer.Strip s = project().mixer.strips.get(mixId());
            return s == null ? empty : s;
        }
        float pan() { Track t = track(); return t == null ? state().pan : t.pan; }
        boolean sends() { return id != null && project().mixer.sends(id); }

        float db() {
            Project p = a.session.project();
            if (p == null) return 0;
            Track t = track();
            return id == null ? p.masterDb : t == null ? state().gainDb : t.gainDb;
        }

        private float nameH() {
            return Ui.dp(getContext(), 54);
        }

        private float knobH() {
            return Ui.dp(getContext(), 64);
        }

        private float lampsH() {
            return id == null ? Ui.dp(getContext(), 16) : Ui.dp(getContext(), 48);
        }

        private float faderTop() {
            return Ui.dp(getContext(), 226);
        }

        private float faderBottom() {
            return getHeight() - lampsH() - Ui.dp(getContext(), 14);
        }

        /** 1 fader, 2 knob, 3 lamps, 0 the name. */
        private boolean tapLamps(float x, float y) {
            Track t = track();
            if (id == null) return false;
            for (int k = 0; k < (t != null || project().mixer.isGroup(id) ? 2 : 1); k++) {
                if (Math.hypot(x - lampX(k), y - lampY()) > lampR() + Ui.dp(getContext(), 6)) continue;
                if (t != null) a.session.run(k == 0 ? Edit.mute(t.id, !t.mute) : Edit.solo(t.id, !t.solo));
                else setting(k == 0 ? "mute" : "solo", (k == 0 ? state().mute : state().solo) ? 0 : 1, false);
                return true;
            }
            return false;
        }

        /** The knob, a little generous for a finger. */
        private boolean onKnob(float x, float y) {
            float cx = getWidth() / 2f, cy = nameH() + knobH() / 2, rad = knobH() * 0.30f;
            return Math.hypot(x - cx, y - cy) <= rad + Ui.dp(getContext(), 10);
        }

        private int sendAt(float x, float y) {
            if (!sends()) return 0;
            for (int k = 0; k < 2; k++)
                if (Math.hypot(x - getWidth() * (k == 0 ? .28f : .72f), y - Ui.dp(a, 148)) <= Ui.dp(a, 20)) return k + 3;
            return 0;
        }

        /** The fader's cap where it sits now, a little generous for a finger. */
        private boolean onCap(float x, float y) {
            float top = faderTop(), bottom = faderBottom(), cy = bottom - dbToPos(db()) * (bottom - top);
            float sx = getWidth() * 0.36f;
            return Math.abs(x - sx) <= Ui.dp(getContext(), 22) && Math.abs(y - cy) <= Ui.dp(getContext(), 20);
        }

        // mute and solo: small round player buttons, clear of the panel's corner rivets
        private float lampR() {
            return Ui.dp(getContext(), 12);
        }

        private float lampX(int k) {
            return getWidth() * (k == 0 ? 0.31f : 0.69f);
        }

        private float lampY() {
            return getHeight() - Ui.dp(getContext(), 26);
        }

        private void set(float db, boolean drag) {
            if (id != null && track() == null) { setting("gain", db, drag); return; }
            Edit e = id == null ? Edit.masterGain(db) : Edit.gain(id, db);
            if (drag) a.session.drag(e, gesture);
            else a.session.run(e);
        }

        private void setting(String key, float value, boolean drag) {
            if ("pan".equals(key) && track() != null) {
                Edit e = Edit.pan(id, value);
                if (drag) a.session.drag(e, gesture); else a.session.run(e);
                return;
            }
            Mixer m = project().mixer.copy();
            Mixer.Strip strip = m.edit(mixId());
            if ("gain".equals(key)) strip.gainDb = value;
            else if ("pan".equals(key)) strip.pan = value;
            else if ("reverb".equals(key)) strip.reverb = value;
            else if ("delay".equals(key)) strip.delay = value;
            else if ("mute".equals(key)) strip.mute = value != 0;
            else if ("solo".equals(key)) strip.solo = value != 0;
            MixerEdit e = new MixerEdit(project(), m, key + " · " + stripName(mixId()), mixId() + ":" + key);
            if (drag) a.session.drag(e, gesture); else a.session.run(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            taps.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // only the fader's cap and the knob itself take hold; anywhere else pans the mixer
                    dragging = onCap(e.getX(), e.getY()) ? 1 : id != null && onKnob(e.getX(), e.getY()) ? 2 : sendAt(e.getX(), e.getY());
                    gesture = new Object();
                    downY = e.getY();
                    downValue = dragging == 1 ? dbToPos(db()) : dragging == 3 ? state().reverb : dragging == 4 ? state().delay : pan();
                    if (dragging != 0) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = downY - e.getY();
                    if (Math.abs(dy) < Ui.dp(getContext(), 3)) return true;
                    if (dragging == 1) {
                        float span = faderBottom() - faderTop();
                        set(Math.round(posToDb(downValue + dy / span) * 10f) / 10f, true);
                    } else if (dragging == 2 && id != null) {
                        float pan = Math.max(-1f, Math.min(1f, downValue + dy / Ui.dp(getContext(), 120)));
                        if (Math.abs(pan) < 0.03f) pan = 0f;  // a little catch at the centre
                        setting("pan", Math.round(pan * 100f) / 100f, true);
                    } else if (dragging == 3 || dragging == 4) {
                        setting(dragging == 3 ? "reverb" : "delay", Math.round(Math.max(0f, Math.min(1f, downValue + dy / Ui.dp(a, 120))) * 100f) / 100f, true);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = 0;
                    gesture = null;
                    return true;
                default:
                    return true;
            }
        }

        void meter(float[] m) {
            long now = System.nanoTime();
            float dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1e9f;
            lastFrame = now;
            float l = m == null ? 0 : m[0], rr = m == null ? 0 : m[1];
            // fall at 24 dB a second; the held peak lingers a second, then falls
            float fall = (float) Math.pow(10, -24 * dt / 20);
            meterL = Math.max(l, meterL * fall);
            meterR = Math.max(rr, meterR * fall);
            holdL = Math.max(meterL, holdL * (float) Math.pow(10, -8 * dt / 20));
            holdR = Math.max(meterR, holdR * (float) Math.pow(10, -8 * dt / 20));
            invalidate();
        }

        String shortText(String value, float width) {
            while (value.length() > 1 && text.measureText(value) > width) value = value.substring(0, value.length()-1);
            return value;
        }

        @Override
        protected void onDraw(Canvas c) {
            Project p = a.session.project();
            if (p == null) return;
            Track t = track();

            float w = getWidth(), h = getHeight();
            boolean heard = t == null || p.audible(t);
            // the strip's plate
            fill.setShader(null);
            panel.setBounds(0, 0, (int) w, (int) h);  // the song-options panel: leather, gold rims, a rivet in each corner
            panel.draw(c);
            // name, with the track's colour above it
            fill.setColor(t == null ? Skin.ACCENT : t.color);
            c.drawRect(Ui.dp(getContext(), 16), Ui.dp(getContext(), 9), w - Ui.dp(getContext(), 16), Ui.dp(getContext(), 11), fill);
            text.setTextSize(Ui.dp(getContext(), 12));
            text.setColor(heard ? Skin.TEXT : Skin.DIM);
            String name = stripName(mixId());
            while (name.length() > 1 && text.measureText(name) > w - Ui.dp(getContext(), 8)) name = name.substring(0, name.length() - 1);
            c.drawText(name, w / 2, Ui.dp(getContext(), 24), text);
            text.setTextSize(Ui.dp(a, 10));
            text.setColor(Skin.DIM);
            String route = t == null ? (project().mixer.isGroup(mixId()) ? "Group · tap name" : "Shared return") : state().route.isEmpty() ? "To Master ▾" : "To " + stripName(state().route) + " ▾";
            if (id != null) c.drawText(shortText(route, w - Ui.dp(a, 8)), w/2, Ui.dp(a, 43), text);
            // the pan knob
            if (id != null) {
                // Killa Studio's knob: a ring of ticks lit out from the centre, the gold face, a dark pointer
                float cx = w / 2, cy = nameH() + knobH() / 2, rad = knobH() * 0.30f;
                float lineW = line.getStrokeWidth();
                line.setStrokeWidth(Ui.dp(getContext(), 1));
                for (int i = 0; i <= 24; i++) {
                    double tick = Math.toRadians(135 + i * 270 / 24.0);
                    float at = (i - 12) / 12f;  // -1 left .. 1 right
                    boolean lit = pan() < 0 ? at <= 0 && at >= pan() : at >= 0 && at <= pan();
                    line.setColor(lit ? Skin.ACCENT : 0xff494332);
                    c.drawLine(cx + (float) Math.cos(tick) * (rad + 3), cy + (float) Math.sin(tick) * (rad + 3),
                            cx + (float) Math.cos(tick) * (rad + 6), cy + (float) Math.sin(tick) * (rad + 6), line);
                }
                fill.setShader(new android.graphics.RadialGradient(cx, cy, rad + 2, new int[] {0xff564721, 0xff080808, 0xffb48a39},
                        new float[] {0, .92f, 1}, android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rad + 2, fill);
                fill.setShader(null);
                fill.setColor(0xFFFFFFFF);
                if (knobArt != null) c.drawBitmap(knobArt, null, new RectF(cx - rad, cy - rad, cx + rad, cy + rad), fill);
                else {
                    fill.setColor(Skin.EDGE);
                    c.drawCircle(cx, cy, rad * 0.8f, fill);
                }
                double ang = Math.toRadians(-90 + pan() * 135);
                line.setStrokeWidth(getResources().getDisplayMetrics().density * 2.5f);
                line.setColor(0xff33240d);
                c.drawLine(cx + (float) Math.cos(ang) * rad * 0.48f, cy + (float) Math.sin(ang) * rad * 0.48f,
                        cx + (float) Math.cos(ang) * rad * 0.81f, cy + (float) Math.sin(ang) * rad * 0.81f, line);
                line.setStrokeWidth(lineW);
                text.setTextSize(Ui.dp(getContext(), 10));
                text.setColor(Skin.DIM);
                c.drawText(pan() == 0 ? "C" : (pan() < 0 ? "L" : "R") + Math.round(Math.abs(pan()) * 100), cx, cy + rad + Ui.dp(getContext(), 12), text);
            }
            if (sends()) {
                for (int k = 0; k < 2; k++) {
                    float value = k == 0 ? state().reverb : state().delay;
                    float cx = w * (k == 0 ? .28f : .72f), cy = Ui.dp(a, 148), rad = Ui.dp(a, 14);
                    fill.setColor(0xffffffff);
                    if (knobArt != null) c.drawBitmap(knobArt, null, new RectF(cx-rad, cy-rad, cx+rad, cy+rad), fill);
                    else { fill.setColor(Skin.ACCENT); c.drawCircle(cx, cy, rad, fill); }
                    double angle = Math.toRadians(135 + value * 270);
                    line.setColor(0xff33240d);
                    c.drawLine(cx, cy, cx + (float)Math.cos(angle)*rad*.8f, cy + (float)Math.sin(angle)*rad*.8f, line);
                    text.setTextSize(Ui.dp(a, 9)); text.setColor(Skin.TEXT);
                    c.drawText((k == 0 ? "REV " : "DLY ") + Math.round(value*100), cx, Ui.dp(a, 176), text);
                }
            }
            text.setTextSize(Ui.dp(a, 11)); text.setColor(Skin.ACCENT);
            c.drawText("FX rack · " + state().chain.size() + "/8", w/2, Ui.dp(a, 201), text);
            // fader slot and meter
            float top = faderTop(), bottom = faderBottom(), span = bottom - top;
            float slotX = w * 0.36f, meterX = w * 0.66f, mw = Ui.dp(getContext(), 5);
            fill.setColor(Skin.DECK);
            r.set(slotX - Ui.dp(getContext(), 3), top, slotX + Ui.dp(getContext(), 3), bottom);
            c.drawRoundRect(r, 4, 4, fill);
            // 0 dB mark
            float zero = bottom - dbToPos(0) * span;
            line.setStrokeWidth(Ui.dp(getContext(), 1));
            line.setColor(Skin.hi(120));
            c.drawLine(slotX - Ui.dp(getContext(), 12), zero, slotX - Ui.dp(getContext(), 6), zero, line);
            line.setStrokeWidth(Ui.dp(getContext(), 3));
            // meters: green to amber to red, in dBFS on the fader's scale
            for (int ch = 0; ch < 2; ch++) {
                float x = meterX + ch * (mw + Ui.dp(getContext(), 2));
                fill.setShader(null);
                fill.setColor(Skin.DECK);
                c.drawRect(x, top, x + mw, bottom, fill);
                float lv = ch == 0 ? meterL : meterR, hold = ch == 0 ? holdL : holdR;
                float db = lv <= 1e-6f ? -60f : (float) (20 * Math.log10(lv));
                float y = bottom - dbToPos(db) * span;
                fill.setShader(new LinearGradient(0, bottom, 0, top, new int[] {0xff3fae5a, 0xffe0b04a, 0xffe05050},
                        new float[] {0f, dbToPos(-6), 1f}, Shader.TileMode.CLAMP));
                if (lv > 1e-6f) c.drawRect(x, y, x + mw, bottom, fill);
                fill.setShader(null);
                float hdb = hold <= 1e-6f ? -60f : (float) (20 * Math.log10(hold));
                fill.setColor(hold >= 1f ? 0xffe05050 : Skin.HI);
                if (hold > 1e-6f) c.drawRect(x, bottom - dbToPos(hdb) * span - 2, x + mw, bottom - dbToPos(hdb) * span, fill);
            }
            // the fader cap
            float db = db(), y = bottom - dbToPos(db) * span;
            r.set(slotX - Ui.dp(getContext(), 14), y - Ui.dp(getContext(), 9), slotX + Ui.dp(getContext(), 14), y + Ui.dp(getContext(), 9));
            fill.setColor(Skin.ACCENT);
            c.drawRoundRect(r, Ui.dp(getContext(), 4), Ui.dp(getContext(), 4), fill);
            fill.setColor(Skin.DECK);
            c.drawRect(r.left + Ui.dp(getContext(), 3), y - 1, r.right - Ui.dp(getContext(), 3), y + 1, fill);
            text.setTextSize(Ui.dp(getContext(), 11));
            text.setColor(Skin.TEXT);
            c.drawText(db <= -59.9f ? "-∞" : String.format(java.util.Locale.ROOT, "%+.1f", db), w / 2, bottom + Ui.dp(getContext(), 12), text);
            // mute and solo lamps
            if (id != null) {
                float ly = lampY(), lr = lampR();
                android.graphics.Bitmap round = Skin.round();
                for (int k = 0; k < (t != null || project().mixer.isGroup(id) ? 2 : 1); k++) {
                    boolean on = t == null ? (k == 0 ? state().mute : state().solo) : (k == 0 ? t.mute : t.solo);
                    float lx = lampX(k);
                    int lit = k == 0 ? 0xe0607e : (Skin.GLOW & 0xFFFFFF);
                    if (on) {  // the player's glow, in the lamp's colour
                        fill.setShader(new android.graphics.RadialGradient(lx, ly, lr * 1.6f, 0xCC000000 | lit, lit,
                                Shader.TileMode.CLAMP));
                        c.drawCircle(lx, ly, lr * 1.6f, fill);
                        fill.setShader(null);
                    }
                    fill.setColor(0xFFFFFFFF);
                    if (round != null) c.drawBitmap(round, null, new RectF(lx - lr, ly - lr, lx + lr, ly + lr), fill);
                    else {
                        fill.setColor(Skin.DECK);
                        c.drawCircle(lx, ly, lr, fill);
                    }
                    text.setTextSize(Ui.dp(getContext(), 10));
                    text.setColor(on ? (0xFF000000 | lit) : Skin.DIM);
                    c.drawText(k == 0 ? "M" : "S", lx, ly + Ui.dp(getContext(), 4), text);
                }
            }
        }
    }
}

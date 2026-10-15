package ai.jackknife.killayue.studio;

import java.util.ArrayList;

/** A track of the project: its level, pan, mute and solo, and its clips. */
public final class Track {
    public String id, name;
    public float gainDb, pan;
    public boolean mute, solo;
    public int color = PALETTE[0];  // ARGB, for its lane and strip
    public String role;             // what it is, or null: "song", "stem:vocals", "take", ...

    /** Track colours, given in turn to new tracks. */
    public static final int[] PALETTE = {0xffe0b04a, 0xff3fb8af, 0xffe0607e, 0xff9b7bd8, 0xff8cc152, 0xffe8884a, 0xff4a9fe0, 0xffd45fbf};
    public final ArrayList<Clip> clips = new ArrayList<Clip>();
}

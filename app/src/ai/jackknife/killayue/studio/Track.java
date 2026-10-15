package ai.jackknife.killayue.studio;

import java.util.ArrayList;

/** A track of the project: its level, pan, mute and solo, and its clips. */
public final class Track {
    public String id, name;
    public float gainDb, pan;
    public boolean mute, solo;
    public final ArrayList<Clip> clips = new ArrayList<Clip>();
}

package ai.jackknife.killayue.studio;

/** A stretch of an audio file on a track: it plays file frames from offset, starting at project frame start, for length frames. */
public final class Clip {
    public String id, file;
    public long start, offset, length, fadeIn, fadeOut;
    public float gainDb;
}

package ai.jackknife.killayue.studio;

/**
 * One change to a project that can be undone. Each factory returns an edit that records what it replaced the
 * first time it is applied, so revert restores exactly that, and a redo applies the very same change again
 * (the same IDs included).
 */
public abstract class Edit {
    abstract void apply(Project p);

    abstract void revert(Project p);

    public abstract String label();

    static Track need(Project p, String id) {
        Track t = p.track(id);
        if (t == null) throw new IllegalArgumentException("There is no track " + id + ".");
        return t;
    }

    /** A change to one value of a track (or the master), remembered on first apply. */
    private abstract static class Setting<T> extends Edit {
        private final String name;
        private boolean saved;
        private T old;

        Setting(String name) {
            this.name = name;
        }

        abstract T get(Project p);

        abstract void set(Project p, T v);

        abstract T value();

        void apply(Project p) {
            if (!saved) {
                old = get(p);
                saved = true;
            }
            set(p, value());
        }

        void revert(Project p) {
            set(p, old);
        }

        public String label() {
            return name;
        }
    }

    public static Edit gain(final String trackId, final float db) {
        return new Setting<Float>("Level") {
            Float get(Project p) { return need(p, trackId).gainDb; }
            void set(Project p, Float v) { need(p, trackId).gainDb = v; }
            Float value() { return db; }
        };
    }

    public static Edit pan(final String trackId, final float pan) {
        if (!(pan >= -1f && pan <= 1f)) throw new IllegalArgumentException("Pan must be between -1 and 1.");
        return new Setting<Float>("Pan") {
            Float get(Project p) { return need(p, trackId).pan; }
            void set(Project p, Float v) { need(p, trackId).pan = v; }
            Float value() { return pan; }
        };
    }

    public static Edit mute(final String trackId, final boolean on) {
        return new Setting<Boolean>(on ? "Mute" : "Unmute") {
            Boolean get(Project p) { return need(p, trackId).mute; }
            void set(Project p, Boolean v) { need(p, trackId).mute = v; }
            Boolean value() { return on; }
        };
    }

    public static Edit solo(final String trackId, final boolean on) {
        return new Setting<Boolean>(on ? "Solo" : "Unsolo") {
            Boolean get(Project p) { return need(p, trackId).solo; }
            void set(Project p, Boolean v) { need(p, trackId).solo = v; }
            Boolean value() { return on; }
        };
    }

    public static Edit rename(final String trackId, final String name) {
        return new Setting<String>("Rename") {
            String get(Project p) { return need(p, trackId).name; }
            void set(Project p, String v) { need(p, trackId).name = v; }
            String value() { return name; }
        };
    }

    public static Edit masterGain(final float db) {
        return new Setting<Float>("Master level") {
            Float get(Project p) { return p.masterDb; }
            void set(Project p, Float v) { p.masterDb = v; }
            Float value() { return db; }
        };
    }

    /** Removes a track; undo puts the same track back in its place. */
    public static Edit removeTrack(final String trackId) {
        return new Edit() {
            private Track removed;
            private int index;

            void apply(Project p) {
                Track t = need(p, trackId);
                index = p.tracks.indexOf(t);
                removed = t;
                p.tracks.remove(index);
            }

            void revert(Project p) {
                p.tracks.add(index, removed);
            }

            public String label() {
                return "Remove track";
            }
        };
    }

    /**
     * Adds a track holding one clip of a whole file at the start. The IDs are taken on the first apply; a redo
     * puts back the same track. Undo also gives the IDs back, so undoing everything leaves the project exactly
     * as it was (undo is strictly last in, first out, so no later ID can be in use).
     */
    public static Edit addTrack(final String name, final String file, final long length) {
        return new Edit() {
            private Track added;
            private int idsBefore, idsAfter;

            void apply(Project p) {
                if (added == null) {
                    idsBefore = p.nextId;
                    added = p.addTrack(name);
                    p.addClip(added, file, 0, 0, length);
                    idsAfter = p.nextId;
                } else {
                    p.tracks.add(added);
                    p.nextId = idsAfter;
                }
            }

            void revert(Project p) {
                p.tracks.remove(added);
                p.nextId = idsBefore;
            }

            public String label() {
                return "Add track";
            }
        };
    }
}

package ai.jackknife.killayue;

import java.util.Arrays;

/**
 * Chord names read exactly, and the shape to play each chord on a fretted instrument. A name the table does
 * not know is refused, never guessed. A shape is searched for on the fretboard: the most playable one that
 * sounds only the chord's notes and every note it needs, with the root (or a slash chord's bass) lowest on a
 * guitar. Playable means a four-fret window, at most four fingers (a barre across the lowest fret counts as
 * one), and no fingers tangled. Of the playable shapes, the one with the fewest fingers, least stretch,
 * lowest position and fewest unplayed strings wins, which finds the shapes in every chord book.
 */
class Chords {
    static class Chord {
        String name;
        int root, bass;       // pitch classes, 0 = C
        int[] tones;          // semitones above the root, the root (0) first, rising
        boolean[] optional;   // tones a shape may leave out (the fifth of a seventh chord, inner extensions)
    }

    // suffix spellings, then the tones, then the tones a shape may leave out
    private static final Object[][] QUALITIES = {
        {new String[] {"", "maj", "M"}, new int[] {0, 4, 7}, new int[] {}},
        {new String[] {"m", "min", "-"}, new int[] {0, 3, 7}, new int[] {}},
        {new String[] {"5"}, new int[] {0, 7}, new int[] {}},
        {new String[] {"6", "maj6"}, new int[] {0, 4, 7, 9}, new int[] {7}},
        {new String[] {"m6", "min6"}, new int[] {0, 3, 7, 9}, new int[] {7}},
        {new String[] {"7", "dom7"}, new int[] {0, 4, 7, 10}, new int[] {7}},
        {new String[] {"maj7", "M7", "Δ7", "Δ", "ma7"}, new int[] {0, 4, 7, 11}, new int[] {7}},
        {new String[] {"m7", "min7", "-7"}, new int[] {0, 3, 7, 10}, new int[] {7}},
        {new String[] {"mMaj7", "mmaj7", "m(maj7)", "mM7", "minmaj7"}, new int[] {0, 3, 7, 11}, new int[] {7}},
        {new String[] {"dim", "o", "°"}, new int[] {0, 3, 6}, new int[] {}},
        {new String[] {"dim7", "o7", "°7"}, new int[] {0, 3, 6, 9}, new int[] {}},
        {new String[] {"m7b5", "ø", "ø7", "min7b5", "-7b5"}, new int[] {0, 3, 6, 10}, new int[] {}},
        {new String[] {"aug", "+"}, new int[] {0, 4, 8}, new int[] {}},
        {new String[] {"aug7", "+7", "7#5", "7+5"}, new int[] {0, 4, 8, 10}, new int[] {}},
        {new String[] {"sus2"}, new int[] {0, 2, 7}, new int[] {}},
        {new String[] {"sus4", "sus"}, new int[] {0, 5, 7}, new int[] {}},
        {new String[] {"7sus4", "7sus"}, new int[] {0, 5, 7, 10}, new int[] {7}},
        {new String[] {"7sus2"}, new int[] {0, 2, 7, 10}, new int[] {7}},
        {new String[] {"add9", "add2"}, new int[] {0, 4, 7, 14}, new int[] {7}},
        {new String[] {"madd9", "m(add9)", "madd2"}, new int[] {0, 3, 7, 14}, new int[] {7}},
        {new String[] {"9"}, new int[] {0, 4, 7, 10, 14}, new int[] {7}},
        {new String[] {"maj9", "M9"}, new int[] {0, 4, 7, 11, 14}, new int[] {7}},
        {new String[] {"m9", "min9", "-9"}, new int[] {0, 3, 7, 10, 14}, new int[] {7}},
        {new String[] {"6/9", "69", "6add9"}, new int[] {0, 4, 7, 9, 14}, new int[] {7}},
        {new String[] {"7b9"}, new int[] {0, 4, 7, 10, 13}, new int[] {7}},
        {new String[] {"7#9"}, new int[] {0, 4, 7, 10, 15}, new int[] {7}},
        {new String[] {"7b5"}, new int[] {0, 4, 6, 10}, new int[] {}},
        {new String[] {"11"}, new int[] {0, 4, 7, 10, 14, 17}, new int[] {4, 7, 14}},
        {new String[] {"m11", "min11"}, new int[] {0, 3, 7, 10, 14, 17}, new int[] {7, 14}},
        {new String[] {"13"}, new int[] {0, 4, 7, 10, 14, 21}, new int[] {7, 14}},
        {new String[] {"maj13", "M13"}, new int[] {0, 4, 7, 11, 14, 21}, new int[] {7, 14}},
        {new String[] {"m13", "min13"}, new int[] {0, 3, 7, 10, 14, 21}, new int[] {7, 14}},
        {new String[] {"maj7#11"}, new int[] {0, 4, 7, 11, 18}, new int[] {7}},
    };

    private static final String LETTERS = "CDEFGAB";
    private static final int[] NATURAL = {0, 2, 4, 5, 7, 9, 11};

    /** True for a name that means no chord is played. */
    static boolean noChord(String name) {
        String n = name.trim();
        return n.equals("N.C.") || n.equals("NC") || n.equals("N.C") || n.equals("N");
    }

    /** "Ebmaj7", "Bb7sus4", "D/F#". Throws IllegalArgumentException for a name it does not know. */
    static Chord parse(String name) {
        String n = name.trim();
        int[] at = new int[1];
        int root = note(n, at);
        if (root < 0) throw new IllegalArgumentException("\"" + name + "\" is not a chord name");
        String rest = n.substring(at[0]);
        int bass = root;
        int slash = rest.lastIndexOf('/');
        if (slash >= 0) {
            int[] b = new int[1];
            String after = rest.substring(slash + 1);
            int pc = note(after, b);
            if (pc >= 0 && b[0] == after.length()) { // "/F#" is a bass note; the "/9" of "6/9" is not
                bass = pc;
                rest = rest.substring(0, slash);
            }
        }
        for (Object[] q : QUALITIES) {
            for (String s : (String[]) q[0]) {
                if (!s.equals(rest)) continue;
                Chord c = new Chord();
                c.name = n;
                c.root = root;
                c.bass = bass;
                c.tones = (int[]) q[1];
                c.optional = new boolean[c.tones.length];
                for (int o : (int[]) q[2]) for (int k = 0; k < c.tones.length; k++) if (c.tones[k] == o) c.optional[k] = true;
                return c;
            }
        }
        throw new IllegalArgumentException("The chord \"" + name + "\" is not one the app knows how to play yet.");
    }

    /** A note letter with sharps or flats at the start of s, as a pitch class; at[0] is where it ends. -1 if none. */
    private static int note(String s, int[] at) {
        if (s.length() == 0) return -1;
        int l = LETTERS.indexOf(s.charAt(0));
        if (l < 0) return -1;
        int pc = NATURAL[l], i = 1;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '#' || c == '♯') pc++;
            else if (c == 'b' || c == '♭') pc--;
            else break;
        }
        at[0] = i;
        return ((pc % 12) + 12) % 12;
    }

    // ---------- shapes ----------

    static final int TOP_WINDOW = 12; // the highest fret a four-fret window starts at

    /**
     * The shape for the chord on the instrument's chord strings (Instruments.chordStrings), lowest string
     * first: a fret for each string, 0 open, -1 not played. Throws if no playable shape exists.
     */
    static int[] shape(Chord c, Instruments.Instrument ins) {
        int[] strings = ins.chordStrings();
        boolean rooted = strings.length >= 6; // a guitar's lowest note is the bass; 4-string chords are not voiced that way
        boolean[] tone = new boolean[12];
        for (int t : c.tones) tone[(c.root + t) % 12] = true;
        tone[c.bass] = true;
        int[] best = null;
        double bestCost = Double.MAX_VALUE;
        int[] f = new int[strings.length];
        for (int w = 1; w <= TOP_WINDOW; w++) {
            // each string: not played, open, or a fret in the window, only where it sounds a chord tone
            int[][] options = new int[strings.length][];
            for (int s = 0; s < strings.length; s++) {
                int[] o = new int[6];
                int n = 0;
                o[n++] = -1;
                if (tone[strings[s] % 12]) o[n++] = 0;
                for (int fr = w; fr < w + 4; fr++) if (tone[(strings[s] + fr) % 12]) o[n++] = fr;
                options[s] = Arrays.copyOf(o, n);
            }
            int[] pick = new int[strings.length];
            while (true) {
                for (int s = 0; s < strings.length; s++) f[s] = options[s][pick[s]];
                double cost = cost(c, f, strings, rooted);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = f.clone();
                }
                int s = 0;
                while (s < strings.length && ++pick[s] == options[s].length) pick[s++] = 0;
                if (s == strings.length) break;
            }
        }
        if (best == null) throw new IllegalArgumentException("No playable " + ins.name.toLowerCase() + " shape for " + c.name + ".");
        return best;
    }

    /** How hard and how thin a shape is; MAX_VALUE if it is not the chord or cannot be played. */
    static double cost(Chord c, int[] f, int[] strings, boolean rooted) {
        int sounding = 0, muted = 0, interior = 0, low = -1, maxFret = 0, minFret = 99;
        boolean[] heard = new boolean[12];
        for (int s = 0; s < f.length; s++) {
            if (f[s] < 0) {
                muted++;
                continue;
            }
            sounding++;
            int p = strings[s] + f[s];
            if (low < 0 || p < low) low = p;
            heard[p % 12] = true;
            if (f[s] > 0) {
                maxFret = Math.max(maxFret, f[s]);
                minFret = Math.min(minFret, f[s]);
            }
        }
        if (sounding < Math.min(3, f.length)) return Double.MAX_VALUE;
        for (int k = 0; k < c.tones.length; k++) if (!c.optional[k] && !heard[(c.root + c.tones[k]) % 12]) return Double.MAX_VALUE;
        if (!heard[c.bass]) return Double.MAX_VALUE;
        if (rooted && low % 12 != c.bass) return Double.MAX_VALUE;
        // strings not played between played ones
        int first = -1, last = -1;
        for (int s = 0; s < f.length; s++) if (f[s] >= 0) {
            if (first < 0) first = s;
            last = s;
        }
        for (int s = first; s <= last; s++) if (f[s] < 0) interior++;
        int fingers = fingers(f, minFret);
        if (fingers > 4) return Double.MAX_VALUE;
        boolean barre = barred(f, minFret);
        int optionalHeard = 0;
        for (int k = 0; k < c.tones.length; k++) if (c.optional[k] && heard[(c.root + c.tones[k]) % 12]) optionalHeard++;
        // a guitar often leaves one or two bass strings out, rarely more, and strums through its top strings;
        // a ukulele, mandolin or banjo plays all four
        double unplayed = 0;
        if (f.length >= 6) {
            for (int s = 0; s < first; s++) unplayed += s < 2 ? 1.0 : 3.0;
            unplayed += 3.0 * (f.length - 1 - last);
        } else {
            unplayed = 10.0 * muted;
        }
        int stretch = maxFret > 0 ? maxFret - minFret : 0;
        double position = f.length >= 6 ? 0.5 : 1.0; // four-string chords live near the nut; a guitar moves up for barre shapes
        return unplayed + 6.0 * interior + 0.6 * fingers + (barre ? 1.5 : 0) + position * maxFret + 0.8 * stretch - 0.3 * optionalHeard;
    }

    /**
     * Fingers a shape needs: one per fretted note, or one barre across the lowest fret plus one per note above
     * it, whichever is fewer. 99 if fingers would have to cross: two notes on one fret, held by two fingers,
     * with a note on a higher fret on a string between them.
     */
    static int fingers(int[] f, int minFret) {
        return Math.min(plain(f), withBarre(f, minFret));
    }

    /** True if the shape is held with a barre (it needs fewer fingers that way, or cannot be held without). */
    static boolean barred(int[] f, int minFret) {
        return withBarre(f, minFret) < plain(f);
    }

    private static int plain(int[] f) {
        int fretted = 0;
        for (int x : f) if (x > 0) fretted++;
        return crosses(f, -1) ? 99 : fretted;
    }

    private static int withBarre(int[] f, int b) {
        if (!canBarre(f, b) || crosses(f, b)) return 99;
        int above = 0;
        for (int x : f) if (x > b) above++;
        return 1 + above;
    }

    /** A barre at fret b lies from the lowest string fretted at b to the top string; every string under it must be fretted at b or above. */
    private static boolean canBarre(int[] f, int b) {
        int from = -1;
        for (int s = 0; s < f.length; s++) if (f[s] == b) {
            from = s;
            break;
        }
        if (from < 0) return false;
        int atB = 0;
        for (int s = from; s < f.length; s++) {
            if (f[s] < b) return false;
            if (f[s] == b) atB++;
        }
        return atB >= 2;
    }

    /**
     * True if two notes on one fret, held by separate fingers (not the barre at fret barred), have more than one
     * higher-fret note between them, or one with the two notes more than two strings apart. One between two
     * strings apart is fine: the D chord's ring finger reaches between index and middle.
     */
    private static boolean crosses(int[] f, int barred) {
        for (int i = 0; i < f.length; i++) {
            if (f[i] <= 0 || f[i] == barred) continue;
            for (int j = i + 1; j < f.length; j++) {
                if (f[j] != f[i]) continue;
                int higher = 0;
                for (int k = i + 1; k < j; k++) if (f[k] > f[i]) higher++;
                if (higher > 1 || higher == 1 && j - i > 2) return true;
            }
        }
        return false;
    }
}

package ai.jackknife.killayue;

/**
 * Tablature under a fretted instrument's part, drawn by abcjs. abcjs puts each note on the highest string
 * that can play it, which is the lowest fret, and marks a note no string can play with "?". Our copy of
 * abcjs is patched to accept a re-entrant string (the ukulele's high G, the banjo's short fifth string):
 * such a string is never chosen, because a lower-numbered fret is always free on another string.
 */
class Tabs {
    private static final String[] NAMES = {"C", "^C", "D", "^D", "E", "F", "^F", "G", "^G", "A", "^A", "B"};

    /** A MIDI note in ABC: 60 is "C", 48 "C,", 72 "c", 84 "c'". */
    static String abcNote(int midi) {
        String n = NAMES[midi % 12];
        int octave = midi / 12 - 5; // 0 for the octave from middle C
        StringBuilder b = new StringBuilder(octave >= 1 ? n.toLowerCase() : n);
        for (int o = octave; o > 1; o--) b.append('\'');
        for (int o = octave; o < 0; o++) b.append(',');
        return b.toString();
    }

    /**
     * abcjs's tablature option for the instrument's part, or null if it has no tab. abcjs works out frets
     * from the pitch it reads off the staff, which is the written pitch, less an octave under a clef with an
     * 8 below; the strings are given at that same pitch.
     */
    static String option(Instruments.Instrument ins) {
        if (ins.tab == null) return null;
        int shift = ins.semitones - (ins.clef.endsWith("-8") ? 12 : 0);
        StringBuilder b = new StringBuilder("[{\"instrument\":\"").append(ins.tab).append("\",\"label\":\"\",\"tuning\":[");
        for (int i = 0; i < ins.strings.length; i++) b.append(i > 0 ? "," : "").append('"').append(abcNote(ins.strings[i] + shift)).append('"');
        int top = ins.strings[ins.strings.length - 1] + shift + 24;
        return b.append("],\"highestNote\":\"").append(abcNote(top)).append("\"}]").toString();
    }

    /** "E A D G B E"; a re-entrant string, higher than the string above it in the tab, in lower case: "g D G B D". */
    static String tuningName(Instruments.Instrument ins) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ins.strings.length; i++) {
            String n = NAMES[ins.strings[i] % 12].replace("^", "") + (NAMES[ins.strings[i] % 12].startsWith("^") ? "♯" : "");
            boolean reentrant = i + 1 < ins.strings.length && ins.strings[i] > ins.strings[i + 1];
            b.append(i > 0 ? " " : "").append(reentrant ? n.toLowerCase() : n);
        }
        return b.toString();
    }
}

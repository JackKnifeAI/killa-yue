package ai.jackknife.killayue;

import java.util.ArrayList;

/**
 * The instruments a song's line can be written out for: how each one transposes (how far its written
 * notes sit above the sounding ones), its clef and its written range. Ranges are the practical ones
 * players expect in a part; they decide only whether a line is moved by whole octaves to fit.
 */
class Instruments {
    static class Instrument {
        final String id, name, shortName, keyLabel, group, clef;
        final int steps, semitones; // written = sounding + this interval (letter steps, semitones)
        final int low, high;        // written range, MIDI note numbers
        String tab;                 // abcjs's tablature for a fretted instrument (its line count), or null
        int[] strings;              // open strings as they sound, MIDI, the tab's bottom line first

        Instrument(String group, String id, String name, String shortName, String keyLabel, int steps, int semitones, String clef, int low, int high) {
            this.group = group;
            this.id = id;
            this.name = name;
            this.shortName = shortName;
            this.keyLabel = keyLabel;
            this.steps = steps;
            this.semitones = semitones;
            this.clef = clef;
            this.low = low;
            this.high = high;
        }

        /** "Alto Sax in E♭", "Violin" */
        String title() {
            return keyLabel.length() > 0 ? name + " " + keyLabel : name;
        }

        /** The lowest open string, leaving out a re-entrant one (higher than the string above it). */
        int lowestString() {
            int low = Integer.MAX_VALUE;
            for (int i = 0; i < strings.length; i++) {
                if (i + 1 < strings.length && strings[i] > strings[i + 1]) continue;
                low = Math.min(low, strings[i]);
            }
            return low;
        }

        /** A fretted instrument's strings for its tab, as they sound, the tab's bottom line first. */
        Instrument tab(String abcjsTab, int... strings) {
            this.tab = abcjsTab;
            this.strings = strings;
            return this;
        }
    }

    static final ArrayList<Instrument> ALL = new ArrayList<Instrument>();

    private static Instrument add(String group, String id, String name, String shortName, String keyLabel, int steps, int semitones, String clef, int low, int high) {
        Instrument i = new Instrument(group, id, name, shortName, keyLabel, steps, semitones, clef, low, high);
        ALL.add(i);
        return i;
    }

    static {
        // "auto" picks treble or bass clef from where the line sits
        add("Voices", "soprano", "Soprano", "S.", "", 0, 0, "treble", 60, 84);
        add("Voices", "alto-voice", "Alto", "A.", "", 0, 0, "treble", 53, 77);
        add("Voices", "tenor-voice", "Tenor", "T.", "", 7, 12, "treble-8", 60, 84);
        add("Voices", "bass-voice", "Bass", "B.", "", 0, 0, "bass", 40, 64);

        add("Woodwinds", "flute", "Flute", "Fl.", "", 0, 0, "treble", 60, 96);
        add("Woodwinds", "piccolo", "Piccolo", "Picc.", "", -7, -12, "treble", 62, 96);
        add("Woodwinds", "oboe", "Oboe", "Ob.", "", 0, 0, "treble", 58, 91);
        add("Woodwinds", "clarinet", "Clarinet", "Cl.", "in B♭", 1, 2, "treble", 52, 91);
        add("Woodwinds", "bass-clarinet", "Bass Clarinet", "B. Cl.", "in B♭", 8, 14, "treble", 52, 86);
        add("Woodwinds", "bassoon", "Bassoon", "Bsn.", "", 0, 0, "bass", 34, 75);
        add("Woodwinds", "soprano-sax", "Soprano Sax", "S. Sax", "in B♭", 1, 2, "treble", 58, 90);
        add("Woodwinds", "alto-sax", "Alto Sax", "A. Sax", "in E♭", 5, 9, "treble", 58, 90);
        add("Woodwinds", "tenor-sax", "Tenor Sax", "T. Sax", "in B♭", 8, 14, "treble", 58, 90);
        add("Woodwinds", "bari-sax", "Baritone Sax", "Bar. Sax", "in E♭", 12, 21, "treble", 57, 90);

        add("Brass", "trumpet", "Trumpet", "Tpt.", "in B♭", 1, 2, "treble", 54, 86);
        add("Brass", "flugelhorn", "Flugelhorn", "Flgh.", "in B♭", 1, 2, "treble", 54, 84);
        add("Brass", "horn", "Horn", "Hn.", "in F", 4, 7, "treble", 54, 84);
        add("Brass", "trombone", "Trombone", "Tbn.", "", 0, 0, "bass", 40, 72);
        add("Brass", "euphonium", "Euphonium", "Euph.", "", 0, 0, "bass", 34, 70);
        add("Brass", "tuba", "Tuba", "Tba.", "", 0, 0, "bass", 26, 65);

        add("Strings", "violin", "Violin", "Vln.", "", 0, 0, "treble", 55, 100);
        add("Strings", "viola", "Viola", "Vla.", "", 0, 0, "alto", 48, 88);
        add("Strings", "cello", "Cello", "Vc.", "", 0, 0, "bass", 36, 81);
        add("Strings", "double-bass", "Double Bass", "Cb.", "", 7, 12, "bass", 40, 79);
        // abcjs's tabs come in 6 (guitar), 5 (fiveString) and 4 (violin, mandolin) lines
        add("Strings", "guitar", "Guitar", "Gtr.", "", 7, 12, "treble-8", 52, 95).tab("guitar", 40, 45, 50, 55, 59, 64);
        add("Strings", "bass-guitar", "Bass Guitar", "B. Gtr.", "", 7, 12, "bass", 40, 67).tab("violin", 28, 33, 38, 43);
        add("Strings", "ukulele", "Ukulele", "Uke", "", 0, 0, "treble", 60, 81).tab("mandolin", 67, 60, 64, 69); // high G
        add("Strings", "mandolin", "Mandolin", "Mand.", "", 0, 0, "treble", 55, 88).tab("mandolin", 55, 62, 69, 76);
        add("Strings", "banjo", "Banjo", "Bjo.", "", 7, 12, "treble-8", 62, 86).tab("fiveString", 67, 50, 55, 59, 62); // short 5th string g
        add("Strings", "harp", "Harp", "Hp.", "", 0, 0, "auto", 24, 103);

        add("Keys and mallets", "piano", "Piano", "Pno.", "", 0, 0, "auto", 21, 108);
        add("Keys and mallets", "organ", "Organ or Synth", "Org.", "", 0, 0, "auto", 36, 96);
        add("Keys and mallets", "marimba", "Marimba", "Mar.", "", 0, 0, "auto", 45, 96);
        add("Keys and mallets", "vibraphone", "Vibraphone", "Vib.", "", 0, 0, "treble", 53, 89);
        add("Keys and mallets", "xylophone", "Xylophone", "Xyl.", "", -7, -12, "treble", 53, 96);
        add("Keys and mallets", "glockenspiel", "Glockenspiel", "Glock.", "", -14, -24, "treble", 55, 84);
        add("Keys and mallets", "harmonica", "Harmonica", "Harm.", "", 0, 0, "treble", 60, 96);
        add("Keys and mallets", "c-instrument", "C Instrument", "C Inst.", "", 0, 0, "auto", 36, 96);
    }

    static Instrument find(String id) {
        for (Instrument i : ALL) if (i.id.equals(id)) return i;
        return null;
    }
}

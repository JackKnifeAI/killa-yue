package ai.jackknife.killayue.studio;
/** MIDI 1 byte-stream decoder: packets may split messages; realtime bytes do not disturb running status. */
public final class MidiInput {
    public interface Target {
        void note(int channel, int key, int velocity, boolean on);
        void control(int channel, int controller, int value);
    }
    private final Target target;
    private int status, first, count;
    private boolean sysex;
    public MidiInput(Target target) {
        this.target = target;
    }
    public void reset() {
        status = first = count = 0;
        sysex = false;
    }
    public void receive(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length)
            throw new IllegalArgumentException("Invalid MIDI packet.");
        for (int i = offset; i < offset + length; i++) {
            int b = bytes[i] & 255;
            if (b >= 248)
                continue;
            if ((b & 128) != 0) {
                count = 0;
                if (b >= 240) {
                    status = 0;
                    sysex = b == 240;
                    continue;
                }
                sysex = false;
                status = b;
                continue;
            }
            if (sysex || status == 0)
                continue;
            int kind = status & 240, need = (kind == 192 || kind == 208) ? 1 : 2;
            if (count++ == 0)
                first = b;
            if (count < need)
                continue;
            count = 0;
            if (kind == 128 || kind == 144)
                target.note(status & 15, first, b, kind == 144 && b != 0);
            else if (kind == 176)
                target.control(status & 15, first, b);
        }
    }
}

package ai.jackknife.killayue.studio;

/**
 * The designed modern sounds, synthesised once per key and layer and then streamed like samples: an FM electric
 * piano, an 808, a detuned analog pad, a filtered synth bass and a saw lead. Original; no third-party samples.
 */
abstract class Synth implements InstrumentSamples.Generator {
    final int rate;
    final double hz;
    final int layer, layers;
    boolean stereo;
    long n;
    Synth(int rate, double hz, int layer, int layers) {
        this.rate = rate;
        this.hz = hz;
        this.layer = layer;
        this.layers = layers;
    }
    double t() { return n / (double) rate; }
    /** How hard the layer is played, 0 (softest) to 1 (hardest). */
    double hard() { return layers <= 1 ? 1 : layer / (double) (layers - 1); }

    static Synth of(int id, int rate, double hz, int layer, int layers) {
        switch (id) {
            case 2: return new Keys(rate, hz, layer, layers);
            case 3: return new Eight(rate, hz, layer, layers);
            case 4: return new Pad(rate, hz, layer, layers);
            case 5: return new Bass(rate, hz, layer, layers);
            case 6: return new Lead(rate, hz, layer, layers);
            case 7: return new Drums(rate, hz, layer, layers);
            default: throw new IllegalArgumentException("Not a designed sound.");
        }
    }

    /** A band-limited sawtooth (polyBLEP), phase in cycles. */
    static final class Saw {
        double phase;
        Saw(double start) { phase = start; }
        double next(double inc) {
            double v = 2 * phase - 1;
            if (phase < inc) {
                double x = phase / inc;
                v -= x + x - x * x - 1;
            } else if (phase > 1 - inc) {
                double x = (phase - 1) / inc;
                v -= x * x + x + x + 1;
            }
            phase += inc;
            if (phase >= 1)
                phase -= 1;
            return v;
        }
    }

    /** A resonant state-variable low-pass (Chamberlin, oversampled twice so it stays stable to 0.4 rate). */
    static final class Svf {
        double low, band;
        double next(double in, double cutoff, double q, int rate) {
            double f = 2 * Math.sin(Math.PI * Math.min(cutoff, rate * .2) / (2 * rate)), damp = 1 / Math.max(.5, q);
            for (int i = 0; i < 2; i++) {
                low += f * band;
                double high = in - low - damp * band;
                band += f * high;
            }
            return low;
        }
    }

    /** Two-operator FM with a tine partial: soft layers are mellow, hard ones bark. */
    static final class Keys extends Synth {
        double pc, pm, pt;
        Keys(int rate, double hz, int layer, int layers) { super(rate, hz, layer, layers); }
        public void next(float[] f) {
            double t = t(), w = 2 * Math.PI / rate;
            double index = (.8 + 2.6 * hard()) * Math.exp(-t * (2.5 + hz / 900));
            double mod = Math.sin(pm) * index, tine = Math.sin(pt) * .18 * hard() * Math.exp(-t * 18);
            double v = Math.sin(pc + mod) * Math.exp(-t * (.55 + hz / 1400)) + tine;
            pc += w * hz;
            pm += w * hz;
            pt += w * hz * 14;
            f[0] = (float) (v * (.42 + .1 * hard()));
            n++;
        }
    }

    /** An 808: a sine that drops in pitch, a click, and soft saturation that gets hotter when played hard. */
    static final class Eight extends Synth {
        double phase;
        Eight(int rate, double hz, int layer, int layers) { super(rate, hz, layer, layers); }
        public void next(float[] f) {
            double t = t(), freq = hz * (1 + 1.6 * Math.exp(-t * 38));
            phase += 2 * Math.PI * freq / rate;
            double v = Math.sin(phase) * Math.exp(-t * .75) + .25 * Math.exp(-t * 900) * Math.sin(phase * 7);
            double drive = 2.2;
            f[0] = (float) (Math.tanh(v * drive) / Math.tanh(drive) * .6);
            n++;
        }
    }

    /** Seven detuned saws spread across the stereo field through a slowly opening filter. */
    static final class Pad extends Synth {
        final Saw[] saws = new Saw[7];
        final Svf left = new Svf(), right = new Svf();
        static final double[] DETUNE = {-.11, -.065, -.025, 0, .025, .065, .11};
        Pad(int rate, double hz, int layer, int layers) {
            super(rate, hz, layer, layers);
            stereo = true;
            java.util.Random r = new java.util.Random(Math.round(hz * 1000));
            for (int i = 0; i < saws.length; i++) saws[i] = new Saw(r.nextDouble());
        }
        public void next(float[] f) {
            double t = t(), l = 0, r = 0;
            for (int i = 0; i < saws.length; i++) {
                double v = saws[i].next(Math.min(.45, hz * Math.pow(2, DETUNE[i] / 12) / rate));
                double pan = (i + .5) / saws.length;
                l += v * Math.cos(pan * Math.PI / 2);
                r += v * Math.sin(pan * Math.PI / 2);
            }
            double cutoff = hz * (2.5 + 3 * (1 - Math.exp(-t / 2.5))) + 300 + 120 * Math.sin(t * 1.3);
            double env = Math.min(1, t / .6) * .13;
            f[0] = (float) (left.next(l, cutoff, .9, rate) * env);
            f[1] = (float) (right.next(r, cutoff, .9, rate) * env);
            n++;
        }
    }

    /** A saw and a square sub an octave down through a resonant filter swept by an envelope. */
    static final class Bass extends Synth {
        final Saw saw = new Saw(0);
        double sub;
        final Svf filter = new Svf();
        Bass(int rate, double hz, int layer, int layers) { super(rate, hz, layer, layers); }
        public void next(float[] f) {
            double t = t();
            double s = saw.next(Math.min(.45, hz / rate));
            sub += hz / 2 / rate;
            if (sub >= 1)
                sub -= 1;
            double sq = Math.sin(2 * Math.PI * sub) * .9;
            double cutoff = hz * (1.5 + (3 + 9 * hard()) * Math.exp(-t * 7)) + 60;
            double v = filter.next(s * .7 + sq * .6, cutoff, 2.2, rate) * Math.exp(-t * .45) * Math.min(1, t * 400);
            f[0] = (float) (Math.tanh(v * 1.4) * .55);
            n++;
        }
    }

    /**
     * A drum kit on the General MIDI drum keys. Kicks, snares, claps, hats, toms and cymbals are made from tuned
     * sines, filtered noise and metallic square clusters; keys with no drum play a short tuned percussion tone.
     */
    static final class Drums extends Synth {
        final int key;
        final java.util.Random noise;
        final Svf band = new Svf(), band2 = new Svf();
        final double[] metal = {205.3, 304.4, 369.6, 522.7, 540, 800};
        final double[] metalPhase = new double[6];
        double phase, hp;
        Drums(int rate, double hz, int layer, int layers) {
            super(rate, hz, layer, layers);
            key = (int) Math.round(69 + 12 * Math.log(hz / 440) / Math.log(2));
            noise = new java.util.Random(key * 31L + layer);
        }
        double white() { return noise.nextDouble() * 2 - 1; }
        double metal(double scale) {
            double v = 0;
            for (int i = 0; i < metal.length; i++) {
                metalPhase[i] += metal[i] * scale / rate;
                if (metalPhase[i] >= 1)
                    metalPhase[i] -= 1;
                v += metalPhase[i] < .5 ? 1 : -1;
            }
            double high = v - hp;
            hp += (v - hp) * .2;  // one-pole high-pass: keep the shimmer
            return high / metal.length;
        }
        double tom(double t, double base) {
            phase += 2 * Math.PI * base * (1 + .5 * Math.exp(-t * 30)) / rate;
            return Math.sin(phase) * Math.exp(-t * 7) * .8 + white() * .08 * Math.exp(-t * 40);
        }
        public void next(float[] f) {
            double t = t(), v, g = .55 + .45 * hard();
            switch (key) {
                case 35:
                case 36:  // kick
                    phase += 2 * Math.PI * (48 + 110 * Math.exp(-t * 28)) / rate;
                    v = Math.tanh(Math.sin(phase) * Math.exp(-t * 6) * 2.2) * .9 + white() * .25 * Math.exp(-t * 300);
                    break;
                case 37:  // side stick
                    v = band.next(white(), 1800, 4, rate) * Math.exp(-t * 90) * 1.4;
                    break;
                case 38:
                case 40:  // snare
                    phase += 2 * Math.PI * 185 * (1 + .3 * Math.exp(-t * 40)) / rate;
                    v = Math.sin(phase) * Math.exp(-t * 22) * .55 + band.next(white(), 4200, .8, rate) * Math.exp(-t * 14) * .9;
                    break;
                case 39: {  // clap: three quick bursts, then a tail
                    double env = 0;
                    for (int i = 0; i < 3; i++) {
                        double d = t - i * .011;
                        if (d >= 0)
                            env = Math.max(env, Math.exp(-d * 180));
                    }
                    env = Math.max(env, .45 * Math.exp(-Math.max(0, t - .022) * 16) * (t > .022 ? 1 : 0));
                    v = band.next(white(), 1500, 1.4, rate) * env * 1.5;
                    break;
                }
                case 42:
                case 44:  // closed and pedal hats
                    v = (metal(1.6) * .8 + white() * .25) * Math.exp(-t * (key == 42 ? 55 : 70)) * .8;
                    break;
                case 46:  // open hat
                    v = (metal(1.6) * .8 + white() * .25) * Math.exp(-t * 5.5) * .7;
                    break;
                case 49:
                case 57:  // crash
                    v = (metal(2.3) * .6 + band2.next(white(), 6000, .6, rate) * .6) * Math.exp(-t * 1.7) * .6;
                    break;
                case 51:
                case 59:  // ride
                    v = (metal(2.9) * .7 + white() * .06) * Math.exp(-t * 2.4) * .45 + Math.sin(2 * Math.PI * 3200 * t) * .05 * Math.exp(-t * 6);
                    break;
                case 41: v = tom(t, 82); break;
                case 43: v = tom(t, 98); break;
                case 45: v = tom(t, 117); break;
                case 47: v = tom(t, 139); break;
                case 48: v = tom(t, 165); break;
                case 50: v = tom(t, 196); break;
                case 54:  // tambourine
                    v = (metal(4.1) * .5 + white() * .3) * Math.exp(-t * 12) * .7;
                    break;
                case 56:  // cowbell
                    v = ((Math.sin(2 * Math.PI * 562 * t) > 0 ? 1 : -1) + (Math.sin(2 * Math.PI * 845 * t) > 0 ? 1 : -1)) * .18 * Math.exp(-t * 11);
                    break;
                case 70:  // maracas
                    v = band.next(white(), 7000, .7, rate) * Math.exp(-t * 45) * .7;
                    break;
                case 75:  // claves
                    v = Math.sin(2 * Math.PI * 2500 * t) * Math.exp(-t * 60) * .6;
                    break;
                default:  // a tuned percussion tone, so every key answers
                    phase += 2 * Math.PI * hz / rate;
                    v = Math.sin(phase + .8 * Math.sin(phase * 2.01) * Math.exp(-t * 20)) * Math.exp(-t * 9) * .5;
            }
            f[0] = (float) (v * g * .8);
            n++;
        }
    }

    /** Two saws a few cents apart with delayed vibrato, through a gentle filter. */
    static final class Lead extends Synth {
        final Saw a = new Saw(0), b = new Saw(.37);
        final Svf filter = new Svf();
        Lead(int rate, double hz, int layer, int layers) { super(rate, hz, layer, layers); }
        public void next(float[] f) {
            double t = t();
            double vib = Math.min(1, Math.max(0, (t - .35) / .5)) * .12 * Math.sin(2 * Math.PI * 5.4 * t);
            double freq = hz * Math.pow(2, vib / 12);
            double v = a.next(Math.min(.45, freq * 1.0035 / rate)) + b.next(Math.min(.45, freq * .9965 / rate));
            double cutoff = hz * 5 + 1800 * Math.exp(-t * 3);
            f[0] = (float) (filter.next(v, cutoff, 1.1, rate) * .22 * Math.min(1, t * 120));
            n++;
        }
    }
}

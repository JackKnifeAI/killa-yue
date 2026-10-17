package ai.jackknife.killayue.studio;

/** Parameter definitions shared by rack editors, validation and automation. No Android dependencies. */
public final class Effects {
    public static final int TRIM=1, WIDTH=2, POLARITY=3, EQ=4, COMPRESSOR=5, LIMITER=6, REVERB=7, DELAY=8, SATURATION=9, FILTER=10;
    public static final int PARAMS=8, SLOT_STRIDE=12, MAX_WET=16;
    public static final class Param {
        public final String name, unit;
        public final float min, max, initial;
        public final boolean whole;
        Param(String name, String unit, float min, float max, float initial, boolean whole) {
            this.name=name; this.unit=unit; this.min=min; this.max=max; this.initial=initial; this.whole=whole;
        }
    }
    public static final class Preset {
        public final String name;
        public final float[] values;
        Preset(String name, float... values) { this.name=name; this.values=values; }
    }
    public static final class Definition {
        public final String name;
        public final Param[] params;
        public final Preset[] presets;
        Definition(String name, Param[] params, Preset... presets) { this.name=name; this.params=params; this.presets=presets; }
    }
    private static Param p(String n, String u, float lo, float hi, float value) { return new Param(n,u,lo,hi,value,false); }
    private static Param mode() { return new Param("Mode: 0 low-pass, 1 high-pass, 2 band-pass","",0,2,0,true); }
    private static final Definition[] ALL = {
        null,
        new Definition("Trim", new Param[]{p("Level","dB",-24,24,0)}, new Preset("Unity",0), new Preset("Headroom",-6)),
        new Definition("Stereo width", new Param[]{p("Width","x",0,2,1)}, new Preset("Natural",1), new Preset("Mono",0), new Preset("Wide",1.5f)),
        new Definition("Polarity invert", new Param[]{}, new Preset("Invert")),
        new Definition("EQ", new Param[]{p("Low shelf (120 Hz)","dB",-18,18,0),p("Mid bell","dB",-18,18,0),p("High shelf (8 kHz)","dB",-18,18,0),p("Mid frequency","Hz",200,6000,1000),p("Mid Q","",.1f,10,1),p("Low cut (0 off)","Hz",0,2000,0),p("High cut (0 off)","Hz",0,20000,0)},
            new Preset("Flat",0,0,0,1000,1,0,0),new Preset("Vocal clarity",-2,2,1,2500,1,80,0),new Preset("Warm",2,-1,-2,1500,.7f,30,16000)),
        new Definition("Compressor",new Param[]{p("Threshold","dB",-60,0,-18),p("Ratio",":1",1,20,4),p("Attack","ms",.1f,200,10),p("Release","ms",10,2000,120),p("Makeup","dB",0,24,0),p("Mix","x",0,1,1)},
            new Preset("Gentle",-18,2,20,180,0,1),new Preset("Vocal",-20,3,8,100,3,1),new Preset("Parallel drums",-24,6,15,80,4,.5f)),
        new Definition("Peak limiter",new Param[]{p("Ceiling","dB",-24,0,-1),p("Release","ms",10,2000,80),p("Input gain","dB",0,24,0)},
            new Preset("Safety",-1,80,0),new Preset("Gentle level",-1,150,3),new Preset("More level",-1,100,6)),
        new Definition("Reverb",new Param[]{p("Room decay","x",.1f,.85f,.65f),p("Damping","x",0,1,.4f),p("Wet mix","x",0,1,.2f),p("Pre-delay","ms",0,100,12)},
            new Preset("Small room",.5f,.5f,.15f,8),new Preset("Vocal room",.7f,.4f,.22f,25),new Preset("Long space",.85f,.25f,.35f,40)),
        new Definition("Delay",new Param[]{p("Time","ms",1,1000,250),p("Feedback","x",0,.65f,.3f),p("Wet mix","x",0,1,.25f),p("Ping-pong amount","",0,1,0)},
            new Preset("Slap",90,.12f,.2f,0),new Preset("Echo",250,.3f,.25f,0),new Preset("Ping-pong",375,.45f,.3f,1)),
        new Definition("Saturation",new Param[]{p("Drive","dB",0,30,6),p("Tone","Hz",500,20000,8000),p("Mix","x",0,1,.5f),p("Output","dB",-24,0,-3)},
            new Preset("Warm",3,12000,.3f,-1),new Preset("Crunch",15,6000,.7f,-6),new Preset("Soft edge",6,8000,.5f,-3)),
        new Definition("Filter",new Param[]{mode(),p("Cutoff","Hz",20,20000,20000),p("Resonance Q","",.2f,10,.7071f),p("Mix","x",0,1,1)},
            new Preset("Low-pass",0,4000,.7071f,1),new Preset("High-pass",1,120,.7071f,1),new Preset("Telephone",2,1500,.7f,1))
    };
    private Effects() {}
    public static Definition get(int kind) {
        if (kind < 1 || kind >= ALL.length) throw new IllegalArgumentException("Unknown mixer effect.");
        return ALL[kind];
    }
    public static String[] names() {
        String[] names=new String[ALL.length-1];
        for(int i=1;i<ALL.length;i++) names[i-1]=ALL[i].name;
        return names;
    }
    public static void defaults(Mixer.Slot slot) {
        Definition d=get(slot.kind);
        for(int i=0;i<PARAMS;i++) slot.set(i,i<d.params.length?d.params[i].initial:0);
    }
    public static void preset(Mixer.Slot slot, int index) {
        Preset preset=get(slot.kind).presets[index];
        defaults(slot);
        for(int i=0;i<preset.values.length;i++) slot.set(i,preset.values[i]);
    }
    public static void validate(Mixer.Slot slot) {
        Definition d=get(slot.kind);
        for(int i=0;i<PARAMS;i++) {
            float value=slot.get(i);
            if(i>=d.params.length) { Mixer.range(value,0,0,"unused effect parameter"); continue; }
            Param p=d.params[i]; Mixer.range(value,p.min,p.max,p.name);
            if(p.whole && value!=Math.round(value)) Mixer.bad("Choose a whole number for "+p.name+".");
        }
    }
    public static boolean wet(int kind) { return kind==REVERB || kind==DELAY; }
    public static double tail(Mixer.Slot slot) { return tail(slot,48000); }
    public static double tail(Mixer.Slot slot,int rate) {
        if(slot.bypass) return 0;
        if(slot.kind==EQ) {
            double tail=0;
            if(slot.get(0)!=0)tail+=filterTail(4,120,.7071,slot.get(0),rate);
            if(slot.get(1)!=0)tail+=filterTail(3,slot.get(3),slot.get(4),slot.get(1),rate);
            if(slot.get(2)!=0)tail+=filterTail(5,8000,.7071,slot.get(2),rate);
            if(slot.get(5)>0)tail+=filterTail(1,slot.get(5),.7071,0,rate);
            if(slot.get(6)>0)tail+=filterTail(0,slot.get(6),.7071,0,rate);
            return tail;
        }
        if(slot.kind==FILTER&&slot.get(3)>0)return filterTail(0,slot.get(1),slot.get(2),0,rate);
        if(slot.kind==SATURATION&&slot.get(2)>0)return filterTail(0,slot.get(1),.7071,0,rate);
        if(slot.kind==REVERB && slot.get(2)>0) return 4 + slot.get(3)/1000.0;
        if(slot.kind==DELAY && slot.get(2)>0) {
            double repeats=slot.get(1)<=0 ? 1 : 1+Math.ceil(Math.log(.00001)/Math.log(slot.get(1)));
            return repeats*slot.get(0)/1000.0;
        }
        return 0;
    }
    /** Decay allowance from the same denominator poles used by the native biquads. */
    private static double filterTail(int mode,double hz,double q,double db,int rate) {
        hz=Math.max(5,Math.min(rate*.45,hz));double w=2*Math.PI*hz/rate,cs=Math.cos(w),sn=Math.sin(w);
        double alpha=sn/(2*q),A=Math.pow(10,db/40),b=Math.sqrt(2*A)*sn;
        double a0=1+alpha,a1=-2*cs,a2=1-alpha;
        if(mode==3){a0=1+alpha/A;a2=1-alpha/A;}
        else if(mode==4){a0=(A+1)+(A-1)*cs+b;a1=-2*((A-1)+(A+1)*cs);a2=(A+1)+(A-1)*cs-b;}
        else if(mode==5){a0=(A+1)-(A-1)*cs+b;a1=2*((A-1)-(A+1)*cs);a2=(A+1)-(A-1)*cs-b;}
        a1/=a0;a2/=a0;double discriminant=a1*a1-4*a2;
        double radius=discriminant<0?Math.sqrt(Math.max(0,a2)):Math.max(Math.abs((-a1+Math.sqrt(discriminant))/2),Math.abs((-a1-Math.sqrt(discriminant))/2));
        if(radius<1e-12)return 0;
        return (Math.ceil(Math.log(1e-7)/Math.log(Math.min(radius,.999999999999)))+2)/rate;
    }
}

package ai.jackknife.killayue.studio;
import java.io.File;
import java.util.Arrays;
/** Exercises every preset through the Java/native boundary and the actual WAV exporter. */
public final class NativeEffectsTest {
    static int passed;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;}
    public static void main(String[] args)throws Exception{
        NativeEngine.load();File dir=new File(System.getProperty("java.io.tmpdir"),"effects-bridge-"+System.nanoTime());
        new File(dir,"audio").mkdirs();NativeEngineTest.wav(new File(dir,"audio/tone.wav"),48000,2,.25f,4800);
        NativeEngine e=NativeEngine.offline(48000);
        try{
            for(int kind=4;kind<=10;kind++){
                Project p=new Project("Preset export",48000);Track t=p.addTrack("Source");p.addClip(t,"audio/tone.wav",0,0,4800);
                Mixer.Slot slot=p.mixer.addSlot(t.id,kind);
                for(int preset=0;preset<Effects.get(kind).presets.length;preset++){
                    Effects.preset(slot,preset);e.load(p,dir);File out=new File(dir,"effect.wav");e.export(out,9600,false);
                    float[] values=new float[19200];WavIn wav=WavIn.open(out);check(wav.frames==9600,"export length");wav.read(0,values,9600);wav.close();
                    boolean finite=true;for(float v:values)finite&=!Float.isNaN(v)&&!Float.isInfinite(v);check(finite,"finite preset export "+kind+":"+preset);
                    slot.bypass=true;e.mixer(p);e.export(out,9600,false);wav=WavIn.open(out);wav.read(0,values,9600);wav.close();
                    check(Math.abs(values[100]-.25f)<.00001f && Math.abs(values[10000])<.00001f,"bypass exports original clip and silence "+kind);
                    slot.bypass=false;
                }
            }
            Project p=new Project("Delay tail",48000);Track t=p.addTrack("Source");p.addClip(t,"audio/tone.wav",0,0,4800);
            Mixer.Slot delay=p.mixer.addSlot(t.id,Effects.DELAY);delay.set(0,250);delay.set(1,.3f);delay.set(2,1);e.load(p,dir);
            File first=new File(dir,"first.wav"),second=new File(dir,"second.wav");e.export(first,48000,false);e.export(second,48000,false);
            float[] a=new float[96000],b=new float[96000];WavIn wav=WavIn.open(first);wav.read(0,a,48000);wav.close();wav=WavIn.open(second);wav.read(0,b,48000);wav.close();
            check(Arrays.equals(a,b),"wet exports restart state deterministically");check(a[0]==0 && Math.abs(a[24000]-.25f)<.00001f,"delay tail survives source end in native export");
            check(Math.abs(a[48000]-.075f)<.00001f,"feedback repeat survives source end");
        }finally{e.close();Projects.delete(dir);}
        System.out.println("NativeEffectsTest: "+passed+" passed");
    }
}

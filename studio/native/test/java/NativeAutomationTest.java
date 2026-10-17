package ai.jackknife.killayue.studio;
import java.io.File;
import java.util.Arrays;
public final class NativeAutomationTest {
    static int passed;
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);passed++;}
    static float[] read(File file,int frames)throws Exception{float[] data=new float[frames*2];WavIn w=WavIn.open(file);try{w.read(0,data,frames);}finally{w.close();}return data;}
    static float gain(double db){return (float)Math.pow(10,db/20);}
    public static void main(String[] args)throws Exception{
        NativeEngine.load();File dir=new File(System.getProperty("java.io.tmpdir"),"automation-bridge-"+System.nanoTime());new File(dir,"audio").mkdirs();NativeEngineTest.wav(new File(dir,"audio/steady.wav"),48000,2,.2f,192000);
        NativeEngine engine=NativeEngine.offline(48000);
        try{
            Project p=new Project("Tempo envelopes",48000);Track t=p.addTrack("Steady");p.addClip(t,"audio/steady.wav",0,0,192000);p.performance.tempo=120;p.performance.tempos.put(48000L,60.0);
            Automation.Lane lane=p.mixer.automation.add(t.id,"",0);lane.points.put(0.0,-12f);lane.points.put(4.0,0f);
            engine.load(p,dir);File out=new File(dir,"curve.wav");engine.export(out,144001,false);float[] first=read(out,144001);
            check(Math.abs(first[2*48000]-.2f*gain(-6))<.00003,"tempo boundary exports at beat two");
            check(Math.abs(first[2*72000]-.2f*gain(-4.5))<.00003,"interpolation follows slower beat rate after tempo change");
            engine.export(out,144001,false);check(Arrays.equals(first,read(out,144001)),"automated exports reset previous gains");
            lane.enabled=false;engine.mixer(p);engine.export(out,96000,false);float[] dry=read(out,96000);check(Math.abs(dry[48000]-.2f)<.00001,"muting lane restores manual level");
            p.mixer.automation.lanes.clear();Mixer.Slot trim=p.mixer.addSlot(t.id,Effects.TRIM);trim.value=-6;
            Automation.Lane amount=p.mixer.automation.add(t.id,trim.id,0);amount.points.put(0.0,-20f);amount.points.put(4.0,0f);
            Mixer.Slot width=p.mixer.addSlot(t.id,Effects.WIDTH);width.value=1;java.util.Collections.swap(p.mixer.edit(t.id).chain,0,1);
            engine.mixer(p);engine.export(out,144001,false);float[] fx=read(out,144001);
            check(Math.abs(fx[2*72000]-.2f*gain(-7.5))<.00005,"reordered trim keeps its automation and tempo map");
            amount.enabled=false;engine.mixer(p);engine.export(out,48000,false);fx=read(out,48000);check(Math.abs(fx[2000]-.2f*gain(-6))<.00001,"disabled FX lane restores its static value");
        }finally{engine.close();Projects.delete(dir);}
        System.out.println("NativeAutomationTest: "+passed+" passed");
    }
}

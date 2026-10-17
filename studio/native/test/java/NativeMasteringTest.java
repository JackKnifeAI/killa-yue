package ai.jackknife.killayue.studio;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
public final class NativeMasteringTest {
    static int passed;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;}
    static final MasteringJob.Progress QUIET=new MasteringJob.Progress(){public void at(String stage,float fraction){}};
    static void wave(File file,int rate,boolean spikes)throws Exception{
        NativeImport writer=NativeImport.open(rate,2,rate,file);
        try{float[] samples=new float[2048];for(int at=0;at<rate*2;at+=1024){int count=Math.min(1024,rate*2-at);
            for(int i=0;i<count;i++){float sample=(float)((spikes?.001:.1)*Math.sin(2*Math.PI*1000*(at+i)/rate));if(spikes&&(at+i)%(rate/2)==0)sample=.95f;samples[2*i]=samples[2*i+1]=sample;}writer.push(samples,count);}writer.finish();}finally{writer.close();}
    }
    static Project project(int rate){Project p=new Project("Mastering fixture",rate);Track t=p.addTrack("Tone");p.addClip(t,"audio/tone.wav",0,0,rate*2L);return p;}
    public static void main(String[] args)throws Exception{
        NativeEngine.load();File dir=new File(System.getProperty("java.io.tmpdir"),"master-bridge-"+System.nanoTime());new File(dir,"audio").mkdirs();
        try{
            wave(new File(dir,"audio/tone.wav"),48000,false);Project p=project(48000);String original=p.toText();
            for(int preset=0;preset<3;preset++){
                Mixer result=MasteringJob.run(p,dir,dir,preset,QUIET,new AtomicBoolean());Project finished=p.copy();finished.mixer=result;finished.validate();Mastering m=result.mastering;
                check(p.toText().equals(original),"analysis leaves source untouched");check(m.measured(finished),"measurement stamp matches final project");
                check(Math.abs(m.afterLufs-Mastering.TARGETS[preset])<.2,"preset reaches measured target "+preset+": "+m.afterLufs);
                check(m.peakDb<=-.999,"mastered sample peak stays below ceiling");
                NativeEngine engine=NativeEngine.offline(48000);File output=new File(dir,"actual.wav");
                try{engine.load(finished,dir);engine.export(output,finished.renderFrames(),true);MasteringJob.Measurement actual=MasteringJob.measure(output,dir,"Check",QUIET,new AtomicBoolean());
                    check(Math.abs(actual.lufs-m.afterLufs)<.0001,"reported result matches exported audio");
                    m.before=true;engine.mixer(finished);engine.export(output,finished.renderFrames(),true);actual=MasteringJob.measure(output,dir,"Check A",QUIET,new AtomicBoolean());
                    check(Math.abs(actual.lufs-m.beforeLufs)<.0001,"A/B before matches measured original");
                }finally{engine.close();}
            }
            wave(new File(dir,"audio/tone.wav"),44100,false);Project other=project(44100);Mixer resampled=MasteringJob.run(other,dir,dir,1,QUIET,new AtomicBoolean());
            check(Math.abs(resampled.mastering.afterLufs+14)<.2,"44.1 kHz rendering uses calibrated resampled metering");
            wave(new File(dir,"audio/spikes.wav"),48000,true);Project peaks=new Project("Peaks",48000);Track track=peaks.addTrack("Spikes");peaks.addClip(track,"audio/spikes.wav",0,0,96000);
            Mixer limited=MasteringJob.run(peaks,dir,dir,2,QUIET,new AtomicBoolean());check(limited.mastering.peakDb<=-.999,"limiting controls high crest material");
            check(Mastering.slot(limited,limited.mastering.trim).value<=24,"gain search stays bounded even if target cannot be reached");
            try{MasteringJob.run(peaks,dir,dir,0,QUIET,new AtomicBoolean(true));throw new AssertionError("cancelled task");}catch(IOException expected){passed++;}
            Project ring=new Project("Filter tail",48000);Track impulse=ring.addTrack("Impulse");ring.addClip(impulse,"audio/spikes.wav",0,0,1);Mixer.Slot filter=ring.mixer.addSlot(impulse.id,Effects.FILTER);filter.set(1,20);filter.set(2,10);
            long frames=ring.renderFrames();check(frames>96000,"resonant filter export gets its decay interval");NativeEngine engine=NativeEngine.offline(48000);
            try{File output=new File(dir,"ring.wav");engine.load(ring,dir);engine.export(output,frames,false);WavIn w=WavIn.open(output);float[] audio=new float[(int)frames*2];try{w.read(0,audio,(int)frames);}finally{w.close();}
                double energy=0,end=0;for(int i=100;i<audio.length;i++)energy+=audio[i]*audio[i];for(int i=audio.length-1000;i<audio.length;i++)end+=audio[i]*audio[i];check(energy>1e-6&&end<1e-12,"filter rings after source end then decays inside export");
            }finally{engine.close();}
            boolean clean=true;for(File file:dir.listFiles())if(file.getName().startsWith("mastering-"))clean=false;check(clean,"temporary mastering workspaces are removed");
        }finally{Projects.delete(dir);}
        System.out.println("NativeMasteringTest: "+passed+" passed");
    }
}

package ai.jackknife.killayue.studio;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Renders a detached project, measures it, and returns one reviewable mixer transaction. */
public final class MasteringJob {
    public interface Progress { void at(String stage,float fraction); }
    public static final class Measurement {
        public final double lufs,peakDb;
        Measurement(double lufs,double peakDb){this.lufs=lufs;this.peakDb=peakDb;}
    }
    private MasteringJob() {}
    private static void cancelled(AtomicBoolean cancel)throws IOException {if(cancel.get())throw new IOException("Cancelled.");}
    public static Mixer run(Project source,File projectDir,File cache,int preset,Progress progress,AtomicBoolean cancel)throws IOException {
        cancelled(cancel);Project p=source.copy();p.mixer=Mastering.preset(p,preset);p.validate();long frames=p.renderFrames(projectDir);
        if(frames<p.rate*.4)throw new IOException("Use at least 0.4 seconds of audio for loudness measurement.");
        long free=cache.getUsableSpace();double needed=frames*8.0*(2+48000.0/p.rate)+(16<<20);
        if(free>0&&free<needed)throw new IOException("There is not enough free space to measure this mix.");
        File work=new File(cache,"mastering-"+System.nanoTime());if(!work.mkdir())throw new IOException("Could not create the mastering workspace.");
        try{
            File audio=new File(work,"mix.wav");Mastering settings=p.mixer.mastering;
            settings.before=true;render(p,projectDir,audio,frames,"Rendering before",progress,cancel);
            Measurement before=measure(audio,work,"Measuring before",progress,cancel);
            if(!Double.isFinite(before.lufs))throw new IOException("This mix is too quiet to measure. Raise its levels first.");
            settings.before=false;Mixer.Slot trim=Mastering.slot(p.mixer,settings.trim);Measurement after=null;
            for(int pass=0;pass<4;pass++){
                render(p,projectDir,audio,frames,"Rendering mastered mix · pass "+(pass+1),progress,cancel);
                after=measure(audio,work,"Measuring mastered mix",progress,cancel);
                if(!Double.isFinite(after.lufs))throw new IOException("The mastered mix is too quiet to measure.");
                double difference=Mastering.TARGETS[preset]-after.lufs;
                if(Math.abs(difference)<.15||pass==3)break;
                float next=Math.max(-24,Math.min(24,trim.value+(float)difference));
                if(next==trim.value)break;trim.value=next;
            }
            cancelled(cancel);settings.beforeLufs=before.lufs;settings.afterLufs=after.lufs;settings.peakDb=after.peakDb;settings.stamp=Mastering.signature(p);
            p.validate();progress.at("Mastering ready",1);return p.mixer;
        }finally{Projects.delete(work);}
    }
    static void render(Project project,File dir,File out,final long frames,final String stage,final Progress progress,final AtomicBoolean cancel)throws IOException {
        cancelled(cancel);Project p=project.copy();p.performance.click=false;
        final NativeEngine engine=NativeEngine.offline(p.rate);final AtomicBoolean done=new AtomicBoolean();
        Thread watch=new Thread(new Runnable(){public void run(){while(!done.get()){
            if(cancel.get())engine.cancelExport();progress.at(stage,Math.min(1,engine.exportProgress()/(float)Math.max(1,frames)));
            try{Thread.sleep(100);}catch(InterruptedException ex){return;}
        }}},"studio-mastering-progress");
        try{engine.load(p,dir);cancelled(cancel);watch.start();engine.export(out,frames,true);}
        finally{
            done.set(true);watch.interrupt();boolean interrupted=false;
            while(watch.isAlive())try{watch.join();}catch(InterruptedException ex){interrupted=true;}
            engine.close();if(interrupted)Thread.currentThread().interrupt();
        }
    }
    /** Stream through the canonical 48 kHz meter; peak always refers to the original rendered samples. */
    public static Measurement measure(File audio,File workspace,String stage,Progress progress,AtomicBoolean cancel)throws IOException {
        File measured=audio,converted=new File(workspace,"meter-48k.wav");double peak=0;
        WavIn input=WavIn.open(audio);
        try{
            float[] buf=new float[8192*input.channels];NativeImport converter=null;
            try{
                if(input.rate!=48000){converter=NativeImport.open(input.rate,input.channels,48000,converted);measured=converted;}
                for(long at=0;at<input.frames;){cancelled(cancel);int n=input.read(at,buf,8192);if(n<=0)throw new IOException("The rendered audio ended early.");
                    for(int i=0;i<n*input.channels;i++)peak=Math.max(peak,Math.abs(buf[i]));
                    if(converter!=null)converter.push(buf,n);at+=n;progress.at(stage,at/(float)Math.max(1,input.frames)*.3f);
                }
                if(converter!=null)converter.finish();
            }finally{if(converter!=null)converter.close();}
        }finally{input.close();}
        try{
            WavIn wav=WavIn.open(measured);Loudness48 meter=new Loudness48();
            try{float[] buf=new float[8192*wav.channels];
                for(long at=0;at<wav.frames;){cancelled(cancel);int n=wav.read(at,buf,8192);if(n<=0)throw new IOException("The rendered audio ended early.");meter.push(buf,n,wav.channels);at+=n;progress.at(stage,.3f+.7f*at/(float)Math.max(1,wav.frames));}
            }finally{wav.close();}
            return new Measurement(meter.integrated(),peak==0?Double.NEGATIVE_INFINITY:20*Math.log10(peak));
        }finally{converted.delete();new File(converted.getPath()+".kpk").delete();}
    }
}

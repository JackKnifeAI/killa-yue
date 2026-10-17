package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.studio.*;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
/** Test-only controller fixture; leaves existing user projects untouched. */
public final class ChamberProof {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static void log(Activity a,String text){Log.i("StudioTest",text);try{FileWriter w=new FileWriter(new File(a.getFilesDir(),"studio-test.txt"),true);w.write(text+"\n");w.close();}catch(Exception ignored){}}
    public static void start(final Activity a){try{
        final StudioSession session=StudioSession.get(a);final File dir=Projects.create(StudioSession.root(a),"Chamber device proof",session.rate(),"","",System.currentTimeMillis());
        Project seed=ProjectStore.load(dir).project;seed.masterDb=-30;seed.performance.pattern=true;
        for(int bank=0;bank<4;bank++)for(int step=0;step<16;step+=4)seed.performance.chamber.patterns[bank][bank][step]=.4f+bank*.15f;
        seed.performance.chamber.song=true;seed.performance.chamber.chain.clear();for(int bank=0;bank<4;bank++)seed.performance.chamber.chain.add(bank);ProjectStore.save(seed,dir);session.open(dir);
        final Project p=session.project();Project changed=p.copy();changed.performance.chamber.active=2;changed.performance.chamber.repeat=3;changed.performance.chamber.levels=true;changed.performance.chamber.levelPad=2;
        session.run(ClipEdits.change(p,changed,"Chamber controls proof"));check(session.undo()&&!p.performance.chamber.levels&&p.performance.chamber.active==0,"controls undo");check(session.redo(-1)&&p.performance.chamber.levels&&p.performance.chamber.active==2,"controls redo");session.flush();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable(){public void run(){try{
            ProjectStore.Loaded saved=ProjectStore.load(dir);check(saved.project.performance.chamber.chain.size()==4&&saved.project.performance.chamber.patterns[3][3][0]>.8f,"four banks and chain saved");ProjectStore.LoadedHistory history=ProjectStore.loadHistory(dir,saved.project,saved.originalText);check(history.history.undo()&&!saved.project.performance.chamber.levels,"reopened Chamber undo");log(a,"RESULT PASS Chamber controller: banks, chain, controls, undo/redo and reopened history. Fixture: "+dir);StudioActivity.open(a);
        }catch(Throwable ex){log(a,"RESULT FAIL Chamber controller: "+ex);}}},2500);
    }catch(Throwable ex){log(a,"RESULT FAIL Chamber controller: "+ex);}}
}

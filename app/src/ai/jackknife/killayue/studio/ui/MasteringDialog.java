package ai.jackknife.killayue.studio.ui;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.Mastering;
import ai.jackknife.killayue.studio.MasteringJob;
import ai.jackknife.killayue.studio.Mixer;
import ai.jackknife.killayue.studio.MixerEdit;
import ai.jackknife.killayue.studio.Project;

final class MasteringDialog {
    static void show(final StudioActivity a){
        final Project p=a.session.project();if(p==null)return;final Mastering m=p.mixer.mastering;
        LinearLayout form=Ui.column(a);int pad=Ui.dp(a,16);form.setPadding(pad,pad,pad,pad);
        String text="Choose a loudness target with EQ and peak limiting. Your tracks stay editable.";
        if(m.installed(p.mixer)){
            text=Mastering.NAMES[m.preset]+"\nListening to "+(m.before?"Before (A)":"Mastered (B)")+". Export uses this choice.";
            if(m.measured(p))text+=String.format(Locale.ROOT,"\nMeasured before: %.1f LUFS\nMeasured mastered: %.1f LUFS\nMastered sample peak: %.1f dBFS",m.beforeLufs,m.afterLufs,m.peakDb);
            else text+="\nThe mix has changed since measurement. Apply a preset again to measure the new mix.";
            if(m.measured(p)&&Math.abs(m.afterLufs-Mastering.TARGETS[m.preset])>.3)text+="\nThe measured result differs from the target. Adjust the mix or choose another preset.";
        }else if(m.preset>=0)text="The mastering rack has changed. Choose a preset to rebuild it, or remove its remaining effects.";
        form.addView(Ui.text(a,text,Skin.TEXT,15));
        Button choose=Ui.button(a,"Choose preset and measure…",true);form.addView(choose);
        Button compare=Ui.button(a,m.before?"Listen to mastered (B)":"Listen to before (A)",false);if(m.installed(p.mixer))form.addView(compare);
        Button remove=Ui.button(a,"Remove mastering",false);if(m.preset>=0)form.addView(remove);
        ScrollView scroll=new ScrollView(a);scroll.addView(form);
        final AlertDialog dialog=new Lux.Builder(a).setTitle("Mastering").setView(scroll).setNegativeButton("Close",null).create();dialog.show();
        choose.setOnClickListener(new View.OnClickListener(){public void onClick(View v){dialog.dismiss();new Lux.Builder(a).setTitle("Mastering target").setItems(Mastering.NAMES,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){run(a,which);}}).show();}});
        compare.setOnClickListener(new View.OnClickListener(){public void onClick(View v){Mixer next=p.mixer.copy();next.mastering.before=!next.mastering.before;a.session.run(new MixerEdit(p,next,"Mastering A/B",null));dialog.dismiss();show(a);}});
        remove.setOnClickListener(new View.OnClickListener(){public void onClick(View v){Mixer next=p.mixer.copy();Mastering.remove(next);a.session.run(new MixerEdit(p,next,"Remove mastering",null));dialog.dismiss();}});
    }
    static void run(final StudioActivity a,final int preset){
        if(a.session.recording.busy()){Ui.toast(a,"Finish the take before mastering.");return;}
        final Project original=a.session.project();if(original==null)return;
        final Project snapshot=original.copy();final String signature=Mastering.signature(original);final File dir=a.session.dir();
        final AtomicBoolean cancel=new AtomicBoolean(),finished=new AtomicBoolean();
        final TextView message=Ui.text(a,"Preparing the mix…",Skin.TEXT,15);int pad=Ui.dp(a,20);message.setPadding(pad,pad,pad,pad);
        final AlertDialog dialog=new Lux.Builder(a).setTitle(Mastering.NAMES[preset]).setView(message).setCancelable(false).setNegativeButton("Cancel",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){cancel.set(true);}}).create();
        message.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){if(!finished.get())cancel.set(true);}});dialog.show();
        new Thread(new Runnable(){public void run(){Mixer result=null;String error=null;
            try{result=MasteringJob.run(snapshot,dir,a.getCacheDir(),preset,new MasteringJob.Progress(){long last;
                public void at(final String stage,final float fraction){long now=System.currentTimeMillis();if(cancel.get()||(now-last<100&&fraction<1))return;last=now;
                    a.runOnUiThread(new Runnable(){public void run(){if(!cancel.get())message.setText(stage+": "+Math.round(100*fraction)+"%");}});}
            },cancel);}catch(Exception ex){error=ex.getMessage()==null?ex.toString():ex.getMessage();}
            final Mixer made=result;final String why=error;finished.set(true);
            a.runOnUiThread(new Runnable(){public void run(){
                if(a.isFinishing()||a.isDestroyed())return;dialog.dismiss();if(cancel.get())return;
                if(made==null){new Lux.Builder(a).setTitle("Could not master this mix").setMessage(why).setPositiveButton("OK",null).show();return;}
                if(a.session.project()!=original||!Mastering.signature(original).equals(signature)){Ui.toast(a,"The mix changed while it was being measured. Apply the preset again.");return;}
                a.session.run(new MixerEdit(original,made,"Mastering: "+Mastering.NAMES[preset],null));show(a);
            }});
        }},"studio-mastering").start();
    }
}

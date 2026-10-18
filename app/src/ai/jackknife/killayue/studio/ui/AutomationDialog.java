package ai.jackknife.killayue.studio.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Map;
import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.Automation;
import ai.jackknife.killayue.studio.Effects;
import ai.jackknife.killayue.studio.Json;
import ai.jackknife.killayue.studio.Mixer;
import ai.jackknife.killayue.studio.MixerEdit;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.TempoMap;
import ai.jackknife.killayue.studio.Track;

/** Beat-based lane editor; previews a gesture locally and saves exactly once on release. */
final class AutomationDialog {
    static String stripName(Project p,String id) {
        Track t=p.track(id);if(t!=null)return t.name;
        if(Mixer.MASTER.equals(id))return "Master";
        if(Mixer.REVERB.equals(id))return "Reverb return";
        if(Mixer.DELAY.equals(id))return "Delay return";
        return p.mixer.get(id).name;
    }
    static String name(Project p,Automation.Lane lane) {
        String fx="";
        if(!lane.slot.isEmpty()){
            int at=Automation.slotIndex(p.mixer,lane);
            fx=" · "+(at+1)+" "+Effects.get(p.mixer.get(lane.strip).chain.get(at).kind).name;
        }
        return stripName(p,lane.strip)+fx+" · "+Automation.parameter(p.mixer,lane).name;
    }
    static Automation.Lane find(Mixer m,String key){for(Automation.Lane lane:m.automation.lanes)if(lane.key().equals(key))return lane;return null;}
    static void show(final StudioActivity a) {
        final Project p=a.session.project();if(p==null)return;
        final ArrayList<String> keys=new ArrayList<String>();ArrayList<String> names=new ArrayList<String>();
        for(Automation.Lane lane:p.mixer.automation.lanes){keys.add(lane.key());names.add((lane.enabled?"":"Muted · ")+name(p,lane));}
        names.add("Add lane…");
        new Lux.Builder(a).setTitle("Automation").setItems(names.toArray(new String[0]),new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,int which){if(which==keys.size())chooseStrip(a);else edit(a,keys.get(which));}
        }).show();
    }
    static void chooseStrip(final StudioActivity a) {
        final Project p=a.session.project();final ArrayList<String> ids=new ArrayList<String>();
        for(Track t:p.tracks)ids.add(t.id);ids.addAll(p.mixer.groups);ids.add(Mixer.REVERB);ids.add(Mixer.DELAY);ids.add(Mixer.MASTER);
        String[] names=new String[ids.size()];for(int i=0;i<names.length;i++)names[i]=stripName(p,ids.get(i));
        new Lux.Builder(a).setTitle("Automate which strip?").setItems(names,new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,int which){chooseParameter(a,ids.get(which));}
        }).show();
    }
    static void chooseParameter(final StudioActivity a,final String strip) {
        final Project p=a.session.project();final ArrayList<String> slots=new ArrayList<String>();final ArrayList<Integer> params=new ArrayList<Integer>();ArrayList<String> labels=new ArrayList<String>();
        for(int i=0;i<4;i++){
            Automation.Lane lane=new Automation.Lane();lane.strip=strip;lane.parameter=i;
            try{labels.add(Automation.parameter(p.mixer,lane).name);slots.add("");params.add(i);}catch(IllegalArgumentException ignored){}
        }
        for(int i=0;i<p.mixer.get(strip).chain.size();i++){
            Mixer.Slot slot=p.mixer.get(strip).chain.get(i);Effects.Definition def=Effects.get(slot.kind);
            for(int j=0;j<def.params.length;j++){labels.add((i+1)+" "+def.name+" · "+def.params[j].name);slots.add(slot.id);params.add(j);}
            labels.add((i+1)+" "+def.name+" · Bypass");slots.add(slot.id);params.add(Automation.BYPASS);
        }
        new Lux.Builder(a).setTitle("Automation parameter").setItems(labels.toArray(new String[0]),new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,int which){
                Mixer m=p.mixer.copy();Automation.Lane lane=m.automation.find(strip,slots.get(which),params.get(which));
                if(lane==null){lane=m.automation.add(strip,slots.get(which),params.get(which));Effects.Param param=Automation.parameter(m,lane);
                    lane.points.put(0.0,Math.max(param.min,Math.min(param.max,Automation.base(p,lane))));
                    try{a.session.run(new MixerEdit(p,m,"Add automation lane",null));}catch(IllegalArgumentException ex){Ui.toast(a,ex.getMessage());return;}}
                edit(a,lane.key());
            }
        }).show();
    }
    static void edit(final StudioActivity a,String key) {
        if(find(a.session.project().mixer,key)==null)return;
        final LaneView lane=new LaneView(a,key);LinearLayout form=Ui.column(a);
        form.addView(Ui.label(a,"Tap to add a point; drag to move it. Grid: ¼ beat. Each release is one undo step."));
        form.addView(lane,new LinearLayout.LayoutParams(-1,Ui.dp(a,240)));
        HorizontalScrollView scroll=new HorizontalScrollView(a);LinearLayout controls=Ui.row(a);scroll.addView(controls);form.addView(scroll);
        String[] labels={"Edit point","Delete point","Mute / enable","Delete lane","Undo","◀","▶","Zoom in","Zoom out"};
        for(int i=0;i<labels.length;i++){final int action=i;Button b=Ui.button(a,labels[i],false);controls.addView(b);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){lane.action(action);}});}
        lane.dialog=new Lux.Builder(a).setTitle(name(a.session.project(),lane.current())).setView(form).setNegativeButton("Close",null).create();lane.dialog.show();
    }
    static final class LaneView extends View {
        final StudioActivity a;final String key;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final Path path=new Path();
        final Runnable ticker=new Runnable(){public void run(){invalidate();postDelayed(this,50);}};
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(ticker);}
        @Override protected void onDetachedFromWindow(){removeCallbacks(ticker);super.onDetachedFromWindow();}
        AlertDialog dialog;Mixer draft;Double selected;double page,span=16;boolean touching;
        LaneView(StudioActivity a,String key){super(a);this.a=a;this.key=key;setContentDescription("Automation lane. Tap or drag points; use Edit point for exact beat and value.");
            if(a.session.status()!=null)page=Math.floor(new TempoMap(a.session.project().rate,a.session.project().performance).beatAt(a.session.status().position)/span)*span;
            page=Math.max(0,Math.min(Automation.MAX_BEAT-span,page));setBackground(Skin.background(a));}
        Automation.Lane current(){return find(draft==null?a.session.project().mixer:draft,key);}
        Effects.Param parameter(){return Automation.parameter(draft==null?a.session.project().mixer:draft,current());}
        float left(){return Ui.dp(a,46);}float right(){return Math.max(left()+1,getWidth()-Ui.dp(a,14));}
        float top(){return Ui.dp(a,28);}float bottom(){return getHeight()-Ui.dp(a,30);}
        float x(double beat){return left()+(float)((beat-page)/span)*(right()-left());}
        float y(float value){Effects.Param p=parameter();return bottom()-(value-p.min)/(p.max-p.min)*(bottom()-top());}
        float at(Automation.Lane lane,double beat){if(!parameter().whole)return lane.value(beat);Map.Entry<Double,Float> point=lane.points.floorEntry(beat);return (point==null?lane.points.firstEntry():point).getValue();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);Automation.Lane lane=current();if(lane==null)return;Effects.Param param=parameter();paint.setTypeface(Skin.text());paint.setTextSize(Ui.dp(a,11));paint.setStrokeWidth(Ui.dp(a,1));paint.setColor(Skin.DIM);paint.setStyle(Paint.Style.FILL);
            c.drawText(lane.enabled?"Enabled · "+param.unit:"Muted lane · "+param.unit,left(),Ui.dp(a,16),paint);
            c.drawText(Float.toString(param.max),0,top()+4,paint);c.drawText(Float.toString(param.min),0,bottom(),paint);
            for(int i=0;i<=4;i++){float y=top()+(bottom()-top())*i/4;c.drawLine(left(),y,right(),y,paint);}
            for(int i=0;i<=8;i++){double beat=page+span*i/8;float x=x(beat);c.drawLine(x,top(),x,bottom(),paint);c.drawText(String.format(java.util.Locale.ROOT,"%.1f",beat),x-8,bottom()+Ui.dp(a,18),paint);}
            if(lane.points.isEmpty())return;
            c.save();c.clipRect(left(),top()-Ui.dp(a,10),right(),bottom()+Ui.dp(a,10));paint.setColor(lane.enabled?Skin.ACCENT:Skin.DIM);paint.setStrokeWidth(Ui.dp(a,2));paint.setStyle(Paint.Style.STROKE);
            path.reset();float previous=at(lane,page);path.moveTo(left(),y(previous));
            for(Map.Entry<Double,Float> point:lane.points.subMap(page,true,page+span,true).entrySet()){
                if(param.whole)path.lineTo(x(point.getKey()),y(previous));path.lineTo(x(point.getKey()),y(point.getValue()));previous=point.getValue();}
            path.lineTo(right(),y(at(lane,page+span)));c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
            for(Map.Entry<Double,Float> point:lane.points.subMap(page,true,page+span,true).entrySet())c.drawCircle(x(point.getKey()),y(point.getValue()),Ui.dp(a,point.getKey().equals(selected)?8:5),paint);
            if(a.session.status()!=null){double beat=new TempoMap(a.session.project().rate,a.session.project().performance).beatAt(a.session.status().position);
                paint.setColor(Skin.TEXT);paint.setStrokeWidth(Ui.dp(a,1));c.drawLine(x(beat),top(),x(beat),bottom(),paint);}
            c.restore();
        }
        double beat(float x){return Math.max(0,Math.min(Automation.MAX_BEAT,Math.rint((page+(x-left())/(right()-left())*span)*4)/4));}
        float value(float y){Effects.Param p=parameter();float v=p.min+(bottom()-y)/(bottom()-top())*(p.max-p.min);v=Math.max(p.min,Math.min(p.max,v));return p.whole?Math.round(v):v;}
        void point(float px,float py){Automation.Lane lane=current();if(selected!=null)lane.points.remove(selected);selected=beat(px);lane.points.put(selected,value(py));invalidate();}
        @Override public boolean onTouchEvent(MotionEvent event){if(current()==null)return false;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){if(event.getX()<left()||event.getX()>right()||event.getY()<top()||event.getY()>bottom())return false;
                draft=a.session.project().mixer.copy();selected=null;double nearest=Ui.dp(a,20)*Ui.dp(a,20);
                for(Map.Entry<Double,Float> p:current().points.entrySet()){double dx=x(p.getKey())-event.getX(),dy=y(p.getValue())-event.getY();if(dx*dx+dy*dy<nearest){nearest=dx*dx+dy*dy;selected=p.getKey();}}
                touching=true;getParent().requestDisallowInterceptTouchEvent(true);if(selected==null)point(event.getX(),event.getY());invalidate();return true;}
            if(!touching)return false;
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE){point(event.getX(),event.getY());return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP){touching=false;getParent().requestDisallowInterceptTouchEvent(false);Mixer next=draft;draft=null;save(next,"Automation point");performClick();return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){touching=false;getParent().requestDisallowInterceptTouchEvent(false);draft=null;invalidate();return true;}
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
        void save(Mixer next,String label){try{Project p=a.session.project();if(!Json.write(next.json()).equals(Json.write(p.mixer.json())))a.session.run(new MixerEdit(p,next,label,null));}
            catch(IllegalArgumentException ex){Ui.toast(a,ex.getMessage());}invalidate();}
        void action(int action){Automation.Lane lane=current();if(lane==null){dialog.dismiss();return;}
            if(action==0){editPoint();return;}
            if(action==4){a.session.undo();selected=null;if(current()==null)dialog.dismiss();else invalidate();return;}
            if(action>=5){if(action==5)page-=span;else if(action==6)page+=span;else span=Math.max(1,Math.min(256,span*(action==7?.5:2)));page=Math.max(0,Math.min(Automation.MAX_BEAT-span,page));invalidate();return;}
            Mixer m=a.session.project().mixer.copy();Automation.Lane next=find(m,key);
            if(action==1){if(selected==null){Ui.toast(a,"Select a point first.");return;}next.points.remove(selected);selected=null;save(m,"Delete automation point");}
            else if(action==2){next.enabled=!next.enabled;save(m,"Toggle automation lane");}
            else {m.automation.lanes.remove(next);save(m,"Delete automation lane");dialog.dismiss();}
        }
        void editPoint(){final Automation.Lane lane=current();if(selected==null||!lane.points.containsKey(selected)){Ui.toast(a,"Select a point first.");return;}
            final Double old=selected;final Effects.Param p=parameter();LinearLayout form=Ui.column(a);form.addView(Ui.label(a,"Beat (0 = start)"));final EditText time=Ui.edit(a,"Beat",1);time.setText(old.toString());form.addView(time);
            form.addView(Ui.label(a,p.name+" · "+p.min+" to "+p.max+" "+p.unit));final EditText value=Ui.edit(a,p.name,1);value.setText(lane.points.get(old).toString());form.addView(value);
            for(EditText field:new EditText[]{time,value})field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            final AlertDialog edit=new Lux.Builder(a).setTitle("Automation point").setView(form).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();edit.show();
            edit.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener(){public void onClick(View v){try{
                double beat=Double.parseDouble(time.getText().toString());float amount=Float.parseFloat(value.getText().toString());Mixer m=a.session.project().mixer.copy();Automation.Lane next=find(m,key);next.points.remove(old);next.points.put(beat,amount);m.validate(a.session.project());save(m,"Edit automation point");selected=beat;edit.dismiss();
            }catch(IllegalArgumentException ex){Ui.toast(a,ex.getMessage());}}});
        }
    }
}

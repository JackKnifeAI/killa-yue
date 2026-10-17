package ai.jackknife.killayue.studio;
import java.util.Map;
public final class AutomationTest {
    static int passed;
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);passed++;}
    static void invalid(Project p,String why){try{p.validate();throw new AssertionError(why);}catch(IllegalArgumentException expected){passed++;}}
    static long frame(float[] data,int point){int at=Mixer.AUTO_OFFSET+2+Automation.MAX_LANES*Automation.HEADER+point*Automation.POINT;return (long)data[at]+((long)data[at+1]<<16);}
    static float value(float[] data,int point){return data[Mixer.AUTO_OFFSET+2+Automation.MAX_LANES*Automation.HEADER+point*Automation.POINT+2];}
    public static void main(String[] args){
        Project p=new Project("Curves",48000);Track t=p.addTrack("Tone");
        p.performance.tempo=120;p.performance.tempos.put(48000L,60.0);
        Automation.Lane gain=p.mixer.automation.add(t.id,"",0);gain.points.put(0.0,-12f);gain.points.put(4.0,0f);p.validate();
        check(p.toText().equals(Project.fromText(p.toText()).toText()),"lanes round trip exactly");
        float[] data=p.mixer.nativeData(p);int h=Mixer.AUTO_OFFSET+2;
        check(data[Mixer.AUTO_OFFSET]==1&&data[h+4]==3,"tempo boundary adds a native control point");
        check(frame(data,0)==0&&frame(data,1)==48000&&frame(data,2)==144000,"beat points follow tempo map");
        check(value(data,1)==-6,"tempo-boundary value interpolates in beats");
        check(gain.value(-1)==-12&&gain.value(9)==0&&gain.value(2)==-6,"curve holds endpoints and interpolates");
        Mixer.Slot eq=p.mixer.addSlot(t.id,Effects.EQ);Automation.Lane mid=p.mixer.automation.add(t.id,eq.id,1);mid.points.put(0.0,6f);
        data=p.mixer.nativeData(p);check(data[h+Automation.HEADER+1]==0&&data[h+Automation.HEADER+2]==1,"effect parameter resolves by slot identity");
        p.mixer.addSlot(t.id,Effects.COMPRESSOR);java.util.Collections.swap(p.mixer.edit(t.id).chain,0,1);
        data=p.mixer.nativeData(p);check(data[h+Automation.HEADER+1]==1,"automation follows effect when reordered");
        History history=new History(p);String before=p.toText();Mixer m=p.mixer.copy();Automation.Lane change=m.automation.find(t.id,"",0);change.points.put(2.0,-3f);
        history.run(new MixerEdit(p,m,"Draw point",null));check(history.steps().size()==2,"one committed gesture is one undo step");history.undo();check(p.toText().equals(before),"undo restores curve and ordering");history.redo();
        History restored=History.fromJson(Json.parse(Json.write(history.toJson("a"))),p.copy());restored.undo();check(restored.project().toText().equals(before),"saved history restores points");
        String withLanes=p.toText();history.run(Edit.removeTrack(t.id));check(p.mixer.automation.lanes.isEmpty(),"track removal prunes lanes");history.undo();check(p.toText().equals(withLanes),"track undo restores all lanes in order");
        Project stem=p.copy();stem.mixer=stem.mixer.stem(t.id);stem.validate();check(stem.mixer.automation.lanes.size()==2,"stem retains volume and effect automation");
        Automation.Lane send=p.mixer.automation.add(t.id,"",2);send.points.put(0.0,.5f);check(p.mixer.stem(t.id).automation.lanes.size()==2,"stem leaves send automation out");
        check(p.mixer.tailFrames(p.rate)==4L*p.rate,"automated sends include shared wet tails");
        Project bad=p.copy();bad.mixer.automation.lanes.get(0).points.put(2.0,Float.NaN);invalid(bad,"nonfinite value rejected");
        bad=p.copy();bad.mixer.automation.lanes.get(0).points.put(Double.NaN,0f);invalid(bad,"nonfinite beat rejected");
        bad=p.copy();bad.mixer.automation.lanes.get(0).strip="t999";invalid(bad,"missing strip rejected");
        bad=p.copy();bad.mixer.automation.lanes.get(1).slot="e999";invalid(bad,"missing effect rejected");
        Project many=new Project("Many lanes",48000);for(int i=0;i<64;i++){Track tr=many.addTrack("Track");many.mixer.automation.add(tr.id,"",0);many.mixer.automation.add(tr.id,"",1);}many.validate();many.mixer.automation.add(Mixer.MASTER,"",0);invalid(many,"lane limit");
        Project old=new Project("Old",48000);Map<String,Object> json=old.toJson();((Map<?,?>)json.get("mixer")).remove("automation");
        try{Project.fromText(Json.write(json));throw new AssertionError("missing current lanes");}catch(IllegalArgumentException expected){passed++;}
        json.put("version",5);Project loaded=Project.fromText(Json.write(json));check(loaded.version==Project.VERSION&&loaded.mixer.automation.lanes.isEmpty(),"v5 migration adds empty lanes");
        Project tail=new Project("Automated delay",48000);Track tr=tail.addTrack("Track");Mixer.Slot delay=tail.mixer.addSlot(tr.id,Effects.DELAY);
        long shortTail=tail.mixer.tailFrames(48000);Automation.Lane time=tail.mixer.automation.add(tr.id,delay.id,0);time.points.put(0.0,1000f);
        check(tail.mixer.tailFrames(48000)==4*shortTail,"export allowance covers automated maximum delay time");
        Automation.Lane bypass=tail.mixer.automation.add(tr.id,delay.id,Automation.BYPASS);bypass.points.put(0.0,.5f);invalid(tail,"bypass requires a discrete value");
        System.out.println("AutomationTest: "+passed+" passed");
    }
}

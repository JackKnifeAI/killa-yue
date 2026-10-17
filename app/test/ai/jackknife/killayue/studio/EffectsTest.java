package ai.jackknife.killayue.studio;
import java.util.List;
import java.util.Map;
public final class EffectsTest {
    static int passed;
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);passed++;}
    static void invalid(Project p,String why){try{p.validate();throw new AssertionError(why);}catch(IllegalArgumentException expected){passed++;}}
    public static void main(String[] args){
        for(int kind=1;kind<=10;kind++){
            Project p=new Project("Effects",48000);Track t=p.addTrack("Tone");Mixer.Slot slot=p.mixer.addSlot(t.id,kind);
            p.validate();check(p.toText().equals(Project.fromText(p.toText()).toText()),"default round trip "+kind);
            Effects.Definition d=Effects.get(kind);
            for(int preset=0;preset<d.presets.length;preset++){
                Effects.preset(slot,preset);p.validate();
                Project loaded=Project.fromText(p.toText());Mixer.Slot copy=loaded.mixer.get(t.id).chain.get(0);
                float[] packed=loaded.mixer.nativeData(loaded);
                for(int j=0;j<Effects.PARAMS;j++)check(copy.get(j)==slot.get(j)&&packed[2+8+(j==0?1:j+2)]==slot.get(j),"preset parameter reaches JNI "+kind+":"+j);
            }
            if(d.params.length>0){slot.set(0,Float.NaN);invalid(p,"NaN rejected");slot.set(0,d.params[0].max+1);invalid(p,"range rejected");}
        }
        Project p=new Project("Identity",48000);Track t=p.addTrack("Track");p.mixer.nextId=123456789;
        Mixer.Slot delay=p.mixer.addSlot(t.id,Effects.DELAY);delay.set(0,1000);delay.set(1,.5f);delay.set(2,1);
        float[] packed=p.mixer.nativeData(p);check(((int)packed[20]+((int)packed[21]<<16))==123456789,"large IDs remain exact in floats");
        check(p.mixer.tailFrames(p.rate)==18*48000,"delay tail covers feedback decay");
        History h=new History(p);Mixer m=p.mixer.copy();m.edit(t.id).chain.get(0).set(1,.25f);h.run(new MixerEdit(p,m,"Feedback",null));h.undo();
        check(p.mixer.get(t.id).chain.get(0).get(1)==.5f,"undo restores parameter vector");h.redo();check(p.mixer.get(t.id).chain.get(0).get(1)==.25f,"redo restores parameter vector");
        p.mixer.get(t.id).chain.get(0).bypass=true;check(p.mixer.tailFrames(p.rate)==0,"bypassed insert adds no tail");
        Project limited=new Project("Limit",48000);for(int track=0;track<2;track++){Track a=limited.addTrack("Rack");for(int i=0;i<8;i++)limited.mixer.addSlot(a.id,Effects.REVERB);}
        limited.validate();limited.mixer.addSlot(Mixer.MASTER,Effects.DELAY);invalid(limited,"wet memory limit enforced");
        Project longTail=new Project("Long serial delays",48000);Track longTrack=longTail.addTrack("Long track");
        for(String strip:new String[]{longTrack.id,Mixer.MASTER})for(int i=0;i<8;i++){
            Mixer.Slot slot=longTail.mixer.addSlot(strip,Effects.DELAY);slot.set(0,1000);slot.set(1,.65f);slot.set(2,1);}
        longTail.validate();check(longTail.mixer.tailFrames(48000)>60L*48000,"long serial tails are not cut at an arbitrary minute");
        Project legacy=new Project("Old",48000);Track oldTrack=legacy.addTrack("Old track");legacy.mixer.addSlot(oldTrack.id,Effects.TRIM).value=-6;
        Map<String,Object> json=legacy.toJson();json.put("version",4);
        Map<?,?> mixer=(Map<?,?>)json.get("mixer");Map<?,?> strips=(Map<?,?>)mixer.get("strips");
        Map<?,?> strip=(Map<?,?>)strips.get(oldTrack.id);Map<?,?> oldSlot=(Map<?,?>)((List<?>)strip.get("chain")).get(0);oldSlot.remove("extra");
        Project migrated=Project.fromText(Json.write(json));check(migrated.version==Project.VERSION&&migrated.mixer.get(oldTrack.id).chain.get(0).value==-6,"v4 utility racks migrate without changing sound");
        System.out.println("EffectsTest: "+passed+" passed");
    }
}

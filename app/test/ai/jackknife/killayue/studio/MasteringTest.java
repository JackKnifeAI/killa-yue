package ai.jackknife.killayue.studio;
import java.util.Map;
public final class MasteringTest {
    static int passed;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;}
    public static void main(String[] args){
        Project p=new Project("Master",48000);p.addTrack("Source");String manual=p.mixer.addSlot(Mixer.MASTER,Effects.TRIM).id;
        p.mixer=Mastering.preset(p,1);p.validate();Mastering m=p.mixer.mastering;
        check(m.installed(p.mixer)&&p.mixer.get(Mixer.MASTER).chain.size()==4,"preset keeps manual rack and adds three owned effects");
        check(p.toText().equals(Project.fromText(p.toText()).toText()),"mastering round trip");
        m.beforeLufs=-20.0;m.afterLufs=-14.0;m.peakDb=-1.0;m.stamp=Mastering.signature(p);check(m.measured(p),"measurement belongs to current mix");
        m.before=true;check(m.measured(p),"A/B does not invalidate measurement");p.mixer.nextId++;p.nextId++;check(m.measured(p),"ID allocation counters do not invalidate restored audio");
        float[] data=p.mixer.nativeData(p);int base=2+74*Mixer.STRIDE+8;
        check(data[base+2]==0,"A preserves manual Master effect");for(int i=1;i<4;i++)check(data[base+i*Effects.SLOT_STRIDE+2]==1,"A bypasses every owned effect");
        m.before=false;Automation.Lane lane=p.mixer.automation.add(Mixer.MASTER,m.trim,0);lane.points.put(0.0,3f);
        check(p.mixer.nativeData(p)[Mixer.AUTO_OFFSET]==1,"B uses owned effect automation");m.before=true;check(p.mixer.nativeData(p)[Mixer.AUTO_OFFSET]==0,"A excludes owned effect automation");
        History h=new History(p);Mixer removed=p.mixer.copy();Mastering.remove(removed);h.run(new MixerEdit(p,removed,"Remove mastering",null));
        check(p.mixer.mastering.preset==-1&&p.mixer.get(Mixer.MASTER).chain.size()==1&&p.mixer.get(Mixer.MASTER).chain.get(0).id.equals(manual),"remove keeps manual effects");
        check(p.mixer.automation.lanes.isEmpty(),"remove prunes owned automation");h.undo();check(p.mixer.mastering.installed(p.mixer)&&p.mixer.automation.lanes.size()==1,"undo restores owned effects and lanes");
        p.mixer=Mastering.preset(p,2);check(p.mixer.get(Mixer.MASTER).chain.size()==4&&p.mixer.automation.lanes.isEmpty(),"replacement avoids duplicate mastering racks");
        Mastering current=p.mixer.mastering;current.beforeLufs=-20.0;current.afterLufs=-10.0;current.peakDb=-1.0;current.stamp=Mastering.signature(p);
        Mastering.slot(p.mixer,current.eq).set(1,2);check(!current.measured(p),"changing sound invalidates measurement");
        Project full=new Project("Full",48000);for(int i=0;i<6;i++)full.mixer.addSlot(Mixer.MASTER,Effects.TRIM);String original=full.toText();try{Mastering.preset(full,0);throw new AssertionError("full rack");}catch(IllegalArgumentException expected){passed++;}check(full.toText().equals(original),"failed preset leaves source intact");
        Project old=new Project("Old",48000);Map<String,Object> json=old.toJson();((Map<?,?>)json.get("mixer")).remove("mastering");json.put("version",6);Project migrated=Project.fromText(Json.write(json));check(migrated.mixer.mastering.preset==-1,"v6 migrates without processing");
        json.put("version",Project.VERSION);try{Project.fromText(Json.write(json));throw new AssertionError("missing mastering");}catch(IllegalArgumentException expected){passed++;}
        Mixer.Slot filter=new Mixer.Slot();filter.kind=Effects.FILTER;Effects.defaults(filter);filter.set(1,20);filter.set(2,10);check(Effects.tail(filter,48000)>2&&Effects.tail(filter,48000)<3,"low resonant filter retains decaying response");
        Mixer.Slot eq=new Mixer.Slot();eq.kind=Effects.EQ;Effects.defaults(eq);check(Effects.tail(eq,48000)==0,"flat EQ adds no silence");eq.set(5,25);check(Effects.tail(eq,48000)>.1,"mastering low cut gets a decay allowance");
        System.out.println("MasteringTest: "+passed+" passed");
    }
}

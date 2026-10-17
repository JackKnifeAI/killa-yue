package ai.jackknife.killayue.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Beat-anchored mixer envelopes. The native copy includes tempo-boundary points. */
public final class Automation {
    public static final int BYPASS=8;
    public static final int MAX_LANES=128, MAX_POINTS=4096, HEADER=5, POINT=3;
    public static final int PACKED=2+MAX_LANES*HEADER+MAX_POINTS*POINT;
    public static final double MAX_BEAT=100000;
    public static final class Lane {
        public String strip, slot="";
        public int parameter;
        public boolean enabled=true;
        public final TreeMap<Double,Float> points=new TreeMap<Double,Float>();
        public String key(){return strip+":"+slot+":"+parameter;}
        public float value(double beat) {
            Map.Entry<Double,Float> left=points.floorEntry(beat),right=points.ceilingEntry(beat);
            if(left==null)return points.firstEntry().getValue();
            if(right==null || right.getKey().equals(left.getKey()))return left.getValue();
            double f=(beat-left.getKey())/(right.getKey()-left.getKey());
            return (float)(left.getValue()+(right.getValue()-left.getValue())*f);
        }
    }
    public final ArrayList<Lane> lanes=new ArrayList<Lane>();
    public Lane find(String strip,String slot,int parameter) {
        for(Lane lane:lanes)if(lane.strip.equals(strip)&&lane.slot.equals(slot)&&lane.parameter==parameter)return lane;
        return null;
    }
    public Lane add(String strip,String slot,int parameter) {
        Lane existing=find(strip,slot,parameter);if(existing!=null)return existing;
        Lane lane=new Lane();lane.strip=strip;lane.slot=slot;lane.parameter=parameter;lanes.add(lane);return lane;
    }
    public void removeStrip(String strip){for(int i=lanes.size()-1;i>=0;i--)if(lanes.get(i).strip.equals(strip))lanes.remove(i);}
    public void removeSlot(String slot){for(int i=lanes.size()-1;i>=0;i--)if(lanes.get(i).slot.equals(slot))lanes.remove(i);}
    public static int slotIndex(Mixer m,Lane lane) {
        List<Mixer.Slot> slots=m.get(lane.strip).chain;
        for(int i=0;i<slots.size();i++)if(slots.get(i).id.equals(lane.slot))return i;
        return -1;
    }
    public static Effects.Param parameter(Mixer m,Lane lane) {
        if(!lane.slot.isEmpty()) {
            int at=slotIndex(m,lane);
            if(at<0)throw new IllegalArgumentException("The automated effect no longer exists.");
            if(lane.parameter==BYPASS)return new Effects.Param("Bypass (0 on, 1 bypass)","",0,1,0,true);
            Effects.Param[] params=Effects.get(m.get(lane.strip).chain.get(at).kind).params;
            if(lane.parameter<0||lane.parameter>=params.length)throw new IllegalArgumentException("Unknown automated parameter.");
            return params[lane.parameter];
        }
        switch(lane.parameter){
            case 0:return new Effects.Param("Volume","dB",-60,12,0,false);
            case 1:if(!Mixer.MASTER.equals(lane.strip))return new Effects.Param("Pan","",-1,1,0,false);break;
            case 2:if(m.sends(lane.strip))return new Effects.Param("Reverb send","x",0,1,0,false);break;
            case 3:if(m.sends(lane.strip))return new Effects.Param("Delay send","x",0,1,0,false);break;
            default:break;
        }
        throw new IllegalArgumentException("That strip does not have this control.");
    }
    public static float base(Project p,Lane lane) {
        if(!lane.slot.isEmpty()){
            Mixer.Slot slot=p.mixer.get(lane.strip).chain.get(slotIndex(p.mixer,lane));
            return lane.parameter==BYPASS?(slot.bypass?1:0):slot.get(lane.parameter);
        }
        Track track=p.track(lane.strip);Mixer.Strip s=p.mixer.get(lane.strip);
        if(lane.parameter==0)return Mixer.MASTER.equals(lane.strip)?p.masterDb:track==null?s.gainDb:track.gainDb;
        if(lane.parameter==1)return track==null?s.pan:track.pan;
        return lane.parameter==2?s.reverb:s.delay;
    }
    public void validate(Project p,Mixer mixer) {
        if(lanes.size()>MAX_LANES)throw new IllegalArgumentException("Use up to 128 automation lanes.");
        java.util.HashSet<String> keys=new java.util.HashSet<String>();int count=0,expanded=0;
        TempoMap tempo=new TempoMap(p.rate,p.performance);
        for(Lane lane:lanes){
            if(lane==null||lane.strip==null||lane.slot==null||!keys.add(lane.key()))throw new IllegalArgumentException("Invalid or duplicate automation lane.");
            if(p.track(lane.strip)==null&&!mixer.groups.contains(lane.strip)&&!Mixer.MASTER.equals(lane.strip)&&!Mixer.REVERB.equals(lane.strip)&&!Mixer.DELAY.equals(lane.strip))
                throw new IllegalArgumentException("The automated strip no longer exists.");
            Effects.Param param=parameter(mixer,lane);
            for(Map.Entry<Double,Float> point:lane.points.entrySet()) {
                double beat=point.getKey();float value=point.getValue();
                if(!(beat>=0&&beat<=MAX_BEAT))throw new IllegalArgumentException("Automation beats must be between 0 and 100000.");
                Mixer.range(value,param.min,param.max,param.name);
                if(param.whole&&value!=Math.round(value))throw new IllegalArgumentException("Use whole values for "+param.name+".");
                if(++count>MAX_POINTS)throw new IllegalArgumentException("Use up to 4096 automation points across the project.");
                long frame=tempo.frameAt(beat);
                if(frame<0||frame>=(1L<<40))throw new IllegalArgumentException("Automation is too far along the timeline.");
            }
            if(lane.enabled&&!lane.points.isEmpty()) {
                java.util.TreeSet<Long> frames=new java.util.TreeSet<Long>();
                for(Double beat:lane.points.keySet())frames.add(tempo.frameAt(beat));
                if(!param.whole)for(Long frame:p.performance.tempos.keySet()) {
                    double beat=tempo.beatAt(frame);if(beat>lane.points.firstKey()&&beat<lane.points.lastKey())frames.add(frame);
                }
                expanded+=frames.size();if(expanded>MAX_POINTS)throw new IllegalArgumentException("Automation plus tempo boundaries exceeds 4096 points; simplify a lane.");
            }
        }
    }
    public ArrayList<Object> json() {
        ArrayList<Object> out=new ArrayList<Object>();
        for(Lane lane:lanes){LinkedHashMap<String,Object> item=new LinkedHashMap<String,Object>();
            item.put("strip",lane.strip);item.put("slot",lane.slot);item.put("parameter",lane.parameter);item.put("enabled",lane.enabled);
            ArrayList<Object> points=new ArrayList<Object>();for(Map.Entry<Double,Float> point:lane.points.entrySet()){
                ArrayList<Object> pair=new ArrayList<Object>();pair.add(point.getKey());pair.add(point.getValue());points.add(pair);}
            item.put("points",points);out.add(item);}
        return out;
    }
    public static Automation read(Object json) {
        Automation out=new Automation();
        if(!(json instanceof List))throw new IllegalArgumentException("Damaged automation lanes.");
        for(Object item:(List<?>)json){if(!(item instanceof Map))throw new IllegalArgumentException("Damaged automation lane.");Map<?,?> m=(Map<?,?>)item;
            if(!(m.get("strip") instanceof String)||!(m.get("slot") instanceof String)||!(m.get("parameter") instanceof Number)||!(m.get("enabled") instanceof Boolean)||!(m.get("points") instanceof List))throw new IllegalArgumentException("Damaged automation target.");
            Lane lane=new Lane();lane.strip=(String)m.get("strip");lane.slot=(String)m.get("slot");double parameter=((Number)m.get("parameter")).doubleValue();
            if(parameter<0||parameter>BYPASS||parameter!=Math.rint(parameter))throw new IllegalArgumentException("Damaged automation parameter.");
            lane.parameter=(int)parameter;lane.enabled=(Boolean)m.get("enabled");
            for(Object pair:(List<?>)m.get("points")){if(!(pair instanceof List)||((List<?>)pair).size()!=2)throw new IllegalArgumentException("Damaged automation point.");
                List<?> values=(List<?>)pair;if(!(values.get(0) instanceof Number)||!(values.get(1) instanceof Number))throw new IllegalArgumentException("Damaged automation value.");
                double beat=((Number)values.get(0)).doubleValue();if(lane.points.put(beat,((Number)values.get(1)).floatValue())!=null)throw new IllegalArgumentException("Repeated automation beat.");}
            out.lanes.add(lane);}
        return out;
    }
    /** Five-float lane headers, then frame-low/frame-high/value points. Frame halves are exact integers. */
    public void pack(Project project,float[] out,int offset) {
        TempoMap tempo=new TempoMap(project.rate,project.performance);int laneCount=0,pointCount=0;
        for(Lane lane:lanes){if(!lane.enabled||lane.points.isEmpty()||project.mixer.mastering.bypass(project.mixer,lane.slot))continue;
            Effects.Param param=parameter(project.mixer,lane);TreeMap<Long,Float> frames=new TreeMap<Long,Float>();
            for(Map.Entry<Double,Float> point:lane.points.entrySet())frames.put(tempo.frameAt(point.getKey()),point.getValue());
            if(!param.whole)for(Long at:project.performance.tempos.keySet()){
                double beat=tempo.beatAt(at);if(beat>lane.points.firstKey()&&beat<lane.points.lastKey())frames.put(at,lane.value(beat));}
            if(pointCount+frames.size()>MAX_POINTS)throw new IllegalArgumentException("Automation plus tempo boundaries exceeds 4096 points; simplify a lane.");
            int strip=stripIndex(project,lane.strip),slot=lane.slot.isEmpty()?-1:slotIndex(project.mixer,lane);
            int header=offset+2+laneCount*HEADER;out[header]=strip;out[header+1]=slot;out[header+2]=lane.parameter;out[header+3]=pointCount;out[header+4]=frames.size();
            for(Map.Entry<Long,Float> point:frames.entrySet()){
                long frame=point.getKey();if(frame<0||frame>=(1L<<40))throw new IllegalArgumentException("Automation is too far along the timeline.");
                int at=offset+2+MAX_LANES*HEADER+pointCount*POINT;out[at]=frame&65535;out[at+1]=frame>>>16;out[at+2]=point.getValue();pointCount++;}
            laneCount++;
        }
        out[offset]=laneCount;out[offset+1]=pointCount;
    }
    static int stripIndex(Project p,String id) {
        if(Mixer.MASTER.equals(id))return 74;if(Mixer.REVERB.equals(id))return 72;if(Mixer.DELAY.equals(id))return 73;
        int group=p.mixer.groups.indexOf(id);if(group>=0)return 64+group;
        for(int i=0;i<p.tracks.size();i++)if(p.tracks.get(i).id.equals(id))return i;
        throw new IllegalArgumentException("Missing automation strip.");
    }
}

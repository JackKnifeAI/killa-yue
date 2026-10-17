package ai.jackknife.killayue.studio;
import java.util.LinkedHashMap;
import java.util.Map;

/** The three owned Master inserts, A/B state, and measurements tied to the exact saved mix. */
public final class Mastering {
    public static final String[] NAMES={"Dynamic · -18 LUFS","Balanced · -14 LUFS","Loud · -10 LUFS"};
    public static final float[] TARGETS={-18,-14,-10};
    public int preset=-1;
    public String eq="",trim="",limiter="",stamp="";
    public boolean before;
    public Double beforeLufs,afterLufs,peakDb;
    public boolean owns(String id){return !id.isEmpty()&&(id.equals(eq)||id.equals(trim)||id.equals(limiter));}
    public static Mixer.Slot slot(Mixer m,String id){for(Mixer.Slot slot:m.get(Mixer.MASTER).chain)if(slot.id.equals(id))return slot;return null;}
    public boolean installed(Mixer m){Mixer.Slot a=slot(m,eq),b=slot(m,trim),c=slot(m,limiter);return a!=null&&b!=null&&c!=null&&a.kind==Effects.EQ&&b.kind==Effects.TRIM&&c.kind==Effects.LIMITER;}
    public boolean bypass(Mixer m,String id){return before&&installed(m)&&owns(id);}
    public static void remove(Mixer m){Mastering old=m.mastering;for(int i=m.edit(Mixer.MASTER).chain.size()-1;i>=0;i--){Mixer.Slot slot=m.edit(Mixer.MASTER).chain.get(i);if(old.owns(slot.id)){m.automation.removeSlot(slot.id);m.edit(Mixer.MASTER).chain.remove(i);}}m.mastering=new Mastering();}
    public static Mixer preset(Project p,int choice){
        if(choice<0||choice>=NAMES.length)throw new IllegalArgumentException("Unknown mastering preset.");
        Mixer m=p.mixer.copy();remove(m);
        if(m.get(Mixer.MASTER).chain.size()>Mixer.MAX_SLOTS-3)throw new IllegalArgumentException("Make room for three mastering effects on Master.");
        Mastering mastering=m.mastering;mastering.preset=choice;
        Mixer.Slot eq=m.addSlot(Mixer.MASTER,Effects.EQ);mastering.eq=eq.id;
        float[][] curves={{0,0,0,1000,1,25,0},{-1,0,1,1000,1,30,0},{-2,-1,1,800,.7f,35,18000}};
        for(int i=0;i<curves[choice].length;i++)eq.set(i,curves[choice][i]);
        mastering.trim=m.addSlot(Mixer.MASTER,Effects.TRIM).id;
        Mixer.Slot limiter=m.addSlot(Mixer.MASTER,Effects.LIMITER);mastering.limiter=limiter.id;limiter.set(0,-1);limiter.set(1,new float[]{180,100,60}[choice]);
        return m;
    }
    public void validate(){
        if(preset< -1||preset>=NAMES.length||eq==null||trim==null||limiter==null||stamp==null)throw new IllegalArgumentException("Damaged mastering settings.");
        if(preset<0){if(!eq.isEmpty()||!trim.isEmpty()||!limiter.isEmpty()||before)throw new IllegalArgumentException("Mastering has no preset.");}
        else if(!eq.matches("e[1-9][0-9]{0,8}")||!trim.matches("e[1-9][0-9]{0,8}")||!limiter.matches("e[1-9][0-9]{0,8}")||eq.equals(trim)||eq.equals(limiter)||trim.equals(limiter))throw new IllegalArgumentException("Damaged mastering effect IDs.");
        for(Double value:new Double[]{beforeLufs,afterLufs,peakDb})if(value!=null&&!(value>=-100&&value<=40))throw new IllegalArgumentException("Damaged mastering measurement.");
        if(!stamp.isEmpty()&&!stamp.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Damaged mastering measurement stamp.");
    }
    public LinkedHashMap<String,Object> json(){LinkedHashMap<String,Object> out=new LinkedHashMap<String,Object>();out.put("preset",preset);out.put("eq",eq);out.put("trim",trim);out.put("limiter",limiter);out.put("before",before);out.put("stamp",stamp);out.put("beforeLufs",beforeLufs);out.put("afterLufs",afterLufs);out.put("peakDb",peakDb);return out;}
    public static Mastering read(Object value){
        if(!(value instanceof Map))throw new IllegalArgumentException("Damaged mastering object.");Map<?,?> m=(Map<?,?>)value;Mastering out=new Mastering();
        if(!(m.get("preset") instanceof Number)||!(m.get("before") instanceof Boolean))throw new IllegalArgumentException("Damaged mastering preset.");
        double preset=((Number)m.get("preset")).doubleValue();if(preset!=Math.rint(preset)||preset< -1||preset>=NAMES.length)throw new IllegalArgumentException("Damaged mastering preset.");out.preset=(int)preset;
        out.eq=text(m,"eq");out.trim=text(m,"trim");out.limiter=text(m,"limiter");out.stamp=text(m,"stamp");out.before=(Boolean)m.get("before");
        out.beforeLufs=number(m.get("beforeLufs"));out.afterLufs=number(m.get("afterLufs"));out.peakDb=number(m.get("peakDb"));out.validate();return out;
    }
    private static String text(Map<?,?> m,String key){if(!(m.get(key) instanceof String))throw new IllegalArgumentException("Damaged mastering text.");return (String)m.get(key);}
    private static Double number(Object value){if(value==null)return null;if(!(value instanceof Number))throw new IllegalArgumentException("Damaged mastering measurement.");return ((Number)value).doubleValue();}
    @SuppressWarnings("unchecked")
    public static String signature(Project p){
        Map<String,Object> json=p.toJson();Map<String,Object> mixer=(Map<String,Object>)json.get("mixer"),mastering=(Map<String,Object>)mixer.get("mastering");
        for(String key:new String[]{"before","stamp","beforeLufs","afterLufs","peakDb"})mastering.remove(key);
        for(String key:new String[]{"nextId","name","sourceSong","sourceTitle","loopStart","loopEnd","loopOn"})json.remove(key);
        mixer.remove("nextId");
        for (Object track : (java.util.List<?>)json.get("tracks")) ((Map<String,Object>)((Map<?,?>)track).get("notes")).remove("nextId");
        ((Map<String,Object>)json.get("performance")).put("click",false);
        try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(Json.write(json).getBytes("UTF-8"));StringBuilder text=new StringBuilder();for(byte b:hash){int n=b&255;if(n<16)text.append('0');text.append(Integer.toHexString(n));}return text.toString();}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}catch(java.io.UnsupportedEncodingException ex){throw new IllegalStateException(ex);}
    }
    public boolean measured(Project p){return installed(p.mixer)&&beforeLufs!=null&&afterLufs!=null&&stamp.equals(signature(p));}
}

package ai.jackknife.killayue.studio;
/** Generated signals follow EBU Tech 3341 table 1 cases 1–5; no copyrighted audio is embedded. */
public final class LoudnessTest {
    static int passed;
    static void near(double actual,double expected,double tolerance,String why){if(Math.abs(actual-expected)>tolerance||Double.isNaN(actual))throw new AssertionError(why+": "+actual);passed++;}
    static void tone(Loudness48 meter,double seconds,double db,int channels){
        long n=Math.round(seconds*48000);float[] data=new float[1024*channels];double amplitude=Math.pow(10,db/20);
        for(long at=0;at<n;at+=1024){int count=(int)Math.min(1024,n-at);for(int i=0;i<count;i++)for(int c=0;c<channels;c++)data[i*channels+c]=(float)(amplitude*Math.sin(2*Math.PI*1000*(at+i)/48000));meter.push(data,count,channels);}
    }
    public static void main(String[] args){
        Loudness48 m=new Loudness48();tone(m,20,-23,2);near(m.integrated(),-23,.1,"stereo alignment at -23");near(m.peakDb(),-23,.00001,"sample peak");
        m=new Loudness48();tone(m,20,-33,2);near(m.integrated(),-33,.1,"stereo alignment at -33");
        m=new Loudness48();tone(m,10,-36,2);tone(m,60,-23,2);tone(m,10,-36,2);near(m.integrated(),-23,.1,"relative gate");
        m=new Loudness48();tone(m,10,-72,2);tone(m,10,-36,2);tone(m,60,-23,2);tone(m,10,-36,2);tone(m,10,-72,2);near(m.integrated(),-23,.1,"absolute and relative gates");
        m=new Loudness48();tone(m,20,-26,2);tone(m,20.1,-20,2);tone(m,20,-26,2);near(m.integrated(),-23,.1,"energy average across changing levels");
        m=new Loudness48();tone(m,20,-23,1);near(m.integrated(),-26.01,.1,"mono channel weight");
        m=new Loudness48();m.push(new float[96000],48000,2);if(m.integrated()!=Double.NEGATIVE_INFINITY)throw new AssertionError("silence has no finite loudness");passed++;
        System.out.println("LoudnessTest: "+passed+" passed");
    }
}

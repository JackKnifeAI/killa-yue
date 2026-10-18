package ai.jackknife.killayue.studio;
import java.util.ArrayList;

/** Stereo/mono integrated loudness at 48 kHz. Original implementation of ITU-R BS.1770 gating.
 * Coefficients: BS.1770-2 Annex 1 tables 1 and 2. Other rates must be resampled before measurement.
 * This meter reports sample peak; it does not claim true-peak measurement or broadcast certification.
 */
public final class Loudness48 {
    private static final int WINDOW=19200,HOP=4800;
    private static final double ABS=Math.pow(10,(-70+.691)/10);
    private static final class Filter {
        final double b0,b1,b2,a1,a2;
        double z1,z2;
        Filter(double b0,double b1,double b2,double a1,double a2){this.b0=b0;this.b1=b1;this.b2=b2;this.a1=a1;this.a2=a2;}
        double tick(double x){double y=b0*x+z1;z1=b1*x-a1*y+z2;z2=b2*x-a2*y;return y;}
    }
    private final Filter[] shelf=new Filter[2],highpass=new Filter[2];
    private final double[] ring=new double[WINDOW];
    private final ArrayList<Double> blocks=new ArrayList<Double>();
    private int index;
    private long frames;
    private double energy,peak;
    public Loudness48(){for(int c=0;c<2;c++){
        shelf[c]=new Filter(1.53512485958697,-2.69169618940638,1.19839281085285,-1.69065929318241,.73248077421585);
        highpass[c]=new Filter(1,-2,1,-1.99004745483398,.99007225036621);
    }}
    public void push(float[] samples,int count,int channels){
        if(channels<1||channels>2||count<0||(long)count*channels>samples.length)throw new IllegalArgumentException("Use mono or stereo samples.");
        for(int i=0;i<count;i++){
            double value=0;
            for(int c=0;c<channels;c++){
                float sample=samples[i*channels+c];if(Float.isNaN(sample)||Float.isInfinite(sample))throw new IllegalArgumentException("Audio contains a non-finite sample.");
                peak=Math.max(peak,Math.abs(sample));double weighted=highpass[c].tick(shelf[c].tick(sample));value+=weighted*weighted;
            }
            energy+=value-ring[index];ring[index]=value;index=(index+1)%WINDOW;frames++;
            if(frames>=WINDOW&&(frames-WINDOW)%HOP==0)blocks.add(Math.max(0,energy)/WINDOW);
        }
    }
    public double integrated(){
        double total=0;int count=0;
        for(double power:blocks)if(power>ABS){total+=power;count++;}
        if(count==0)return Double.NEGATIVE_INFINITY;
        double gate=Math.max(ABS,total/count/10);total=0;count=0;
        for(double power:blocks)if(power>gate){total+=power;count++;}
        return count==0?Double.NEGATIVE_INFINITY:-.691+10*Math.log10(total/count);
    }
    public double peakDb(){return peak==0?Double.NEGATIVE_INFINITY:20*Math.log10(peak);}
    public long frames(){return frames;}
}

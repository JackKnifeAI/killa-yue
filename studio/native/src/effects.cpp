#include "studio/effects.h"
#include "studio/mix.h"
#include <algorithm>
#include <cmath>
namespace studio {
static bool within(float v,float lo,float hi) { return std::isfinite(v) && v>=lo && v<=hi; }
EffectConfig EffectConfig::read(const float* f) {
    EffectConfig c; c.kind=(int)f[0];c.p[0]=f[1];c.bypass=f[2]!=0;
    for(int j=1;j<8;++j)c.p[j]=f[j+2];
    c.id=(uint32_t)f[10]+((uint32_t)f[11]<<16);return c;
}
bool EffectConfig::valid() const {
    static const float lo[11][8]={{0},{-24},{0},{0},{-18,-18,-18,200,.1f,0,0},{-60,1,.1f,10,0,0},{-24,10,0},{.1f,0,0,0},{1,0,0,0},{0,500,0,-24},{0,20,.2f,0}};
    static const float hi[11][8]={{0},{24},{2},{0},{18,18,18,6000,10,2000,20000},{0,20,200,2000,24,1},{0,2000,24},{.85f,1,1,100},{1000,.65f,1,1},{30,20000,1,0},{2,20000,10,1}};
    if(kind<1 || kind>10 || (kind>=4 && id==0)) return false;
    for(int j=0;j<8;++j)if(!within(p[j],lo[kind][j],hi[kind][j]))return false;
    return kind!=10 || p[0]==std::floor(p[0]);
}
void Biquad::reset() { c={{1,0,0,0,0}};step.fill(0);z1[0]=z1[1]=z2[0]=z2[1]=0;ready=false; }
void Biquad::prepare(int mode,double hz,double q,double db,int rate,int frames) {
    std::array<double,5> target{{1,0,0,0,0}};
    if(hz>0) {
        hz=std::max(5.0,std::min(rate*.45,hz));
        const double w=6.283185307179586*hz/rate, cs=std::cos(w), sn=std::sin(w);
        const double a=sn/(2*q), A=std::pow(10.0,db/40), b=std::sqrt(2*A)*sn;
        double b0=1,b1=0,b2=0,a0=1,a1=0,a2=0;
        if(mode==0){b0=b2=(1-cs)/2;b1=1-cs;a0=1+a;a1=-2*cs;a2=1-a;}
        else if(mode==1){b0=b2=(1+cs)/2;b1=-(1+cs);a0=1+a;a1=-2*cs;a2=1-a;}
        else if(mode==2){b0=a;b2=-a;a0=1+a;a1=-2*cs;a2=1-a;}
        else if(mode==3){b0=1+a*A;b1=-2*cs;b2=1-a*A;a0=1+a/A;a1=-2*cs;a2=1-a/A;}
        else if(mode==4){b0=A*((A+1)-(A-1)*cs+b);b1=2*A*((A-1)-(A+1)*cs);b2=A*((A+1)-(A-1)*cs-b);a0=(A+1)+(A-1)*cs+b;a1=-2*((A-1)+(A+1)*cs);a2=(A+1)+(A-1)*cs-b;}
        else {b0=A*((A+1)+(A-1)*cs+b);b1=-2*A*((A-1)+(A+1)*cs);b2=A*((A+1)+(A-1)*cs-b);a0=(A+1)-(A-1)*cs+b;a1=2*((A-1)-(A+1)*cs);a2=(A+1)-(A-1)*cs-b;}
        target={{b0/a0,b1/a0,b2/a0,a1/a0,a2/a0}};
    }
    if(!ready){c=target;ready=true;}
    for(int i=0;i<5;++i)step[i]=(target[i]-c[i])/std::max(1,frames);
}
void Biquad::tick(float& l,float& r) {
    for(int i=0;i<5;++i)c[i]+=step[i];
    float* values[2]={&l,&r};
    for(int ch=0;ch<2;++ch){double x=*values[ch],y=c[0]*x+z1[ch];z1[ch]=c[1]*x-c[3]*y+z2[ch];z2[ch]=c[2]*x-c[4]*y;
        if(std::fabs(z1[ch])<1e-24)z1[ch]=0;
        if(std::fabs(z2[ch])<1e-24)z2[ch]=0;
        *values[ch]=std::isfinite(y)?(float)y:0;}
}
float EffectsRack::Ring::read(size_t delay) const {
    delay=std::max((size_t)1,std::min(delay,data.size()));
    return written<delay?0:data[(pos+data.size()-delay)%data.size()];
}
void EffectsRack::Ring::put(float v){data[pos]=std::fabs(v)<1e-24f?0:v;pos=(pos+1)%data.size();written=std::min(written+1,data.size());}
EffectsRack::Wet::Wet(int rate):left(rate+2),right(rate+2),preL(rate/10+2),preR(rate/10+2) {
    const float lengths[8]={.0297f,.0371f,.0411f,.0437f,.0307f,.0383f,.0421f,.0451f};
    for(int i=0;i<8;++i)comb[i]=Ring(std::max(1,(int)(rate*lengths[i])));
    for(int i=0;i<4;++i)allpass[i]=Ring(std::max(1,(int)(rate*(i%2==0?.005f:.0017f))));
}
void EffectsRack::Wet::reset(){left.reset();right.reset();preL.reset();preR.reset();for(auto& r:comb)r.reset();for(auto& r:allpass)r.reset();damping.fill(0);}
void EffectsRack::Wet::tick(const EffectConfig& c,int rate,float l,float r,float& wl,float& wr,float delayMs){
    if(c.kind==8){
        const float delay=std::max(1.f,delayMs*rate/1000);const size_t n=(size_t)delay;const float fraction=delay-n;
        wl=left.read(n)*(1-fraction)+left.read(n+1)*fraction;wr=right.read(n)*(1-fraction)+right.read(n+1)*fraction;
        const float cross=c.p[3];left.put(l+c.p[1]*(wl*(1-cross)+wr*cross));right.put(r+c.p[1]*(wr*(1-cross)+wl*cross));return;
    }
    size_t pre=(size_t)(c.p[3]*rate/1000);float in[2]={pre?preL.read(pre):l,pre?preR.read(pre):r};preL.put(l);preR.put(r);
    float out[2]={0,0};
    for(int i=0;i<8;++i){auto& line=comb[i];float v=line.read(line.data.size());float damp=.9f*c.p[1];damping[i]=v*(1-damp)+damping[i]*damp;line.put(in[i/4]+c.p[0]*damping[i]);out[i/4]+=.25f*v;}
    for(int i=0;i<4;++i){int ch=i/2;auto& line=allpass[i];float v=line.read(line.data.size())-.5f*out[ch];line.put(out[ch]+.5f*v);out[ch]=v;}
    wl=out[0];wr=out[1];
}
void EffectsRack::State::reset(){for(auto& f:filters)f.reset();gain=1;previous=0;enabled=config.bypass?0:1;ready=false;}
EffectsRack::EffectsRack(int rate):rate_(rate),states_(75*8){wet_.reserve(kWetInserts);for(int i=0;i<kWetInserts;++i)wet_.emplace_back(rate);}
void EffectsRack::configure(const float* data){
    uint32_t needed[kWetInserts]{};int n=0;
    for(int strip=0;strip<75;++strip){const float* s=data+2+strip*kMixerStride;for(int k=0;k<(int)s[7];++k){auto c=EffectConfig::read(s+8+k*kEffectStride);if(c.kind==7||c.kind==8)needed[n++]=c.id;}}
    for(auto& w:wet_){bool keep=false;for(int i=0;i<n;++i)keep|=needed[i]==w.id;if(!keep)w.id=0;}
    for(int strip=0;strip<75;++strip){const float* s=data+2+strip*kMixerStride;counts_[strip]=(int)s[7];
        for(int k=0;k<8;++k){State& state=states_[strip*8+k];EffectConfig c=k<counts_[strip]?EffectConfig::read(s+8+k*kEffectStride):EffectConfig{};
            bool changed=state.config.kind!=c.kind||state.config.id!=c.id;state.config=c;if(changed)state.reset();state.wet=-1;
            if(c.kind==7||c.kind==8){for(int i=0;i<kWetInserts;++i)if(wet_[i].id==c.id){state.wet=i;break;}
                if(state.wet<0)for(int i=0;i<kWetInserts;++i)if(wet_[i].id==0){wet_[i].id=c.id;wet_[i].kind=c.kind;wet_[i].reset();state.wet=i;break;}
                if(state.wet>=0 && wet_[state.wet].kind!=c.kind){wet_[state.wet].kind=c.kind;wet_[state.wet].reset();}
            }
        }
    }
}
void EffectsRack::reset(){for(auto& state:states_)state.reset();for(auto& w:wet_)w.reset();}
void EffectsRack::process(int strip,float* audio,int frames){
    for(int k=0;k<counts_[strip];++k){State& state=states_[strip*8+k];const auto& c=state.config;const auto& p=c.p;
        float target=c.kind==1?dbToGain(p[0]):c.kind==2?p[0]:c.kind==3?-1:c.kind==8?p[0]:1;
        if(!state.ready){state.previous=target;state.ready=true;}
        if(c.kind==4){state.filters[0].prepare(4,120,.7071,p[0],rate_,frames);state.filters[1].prepare(3,p[3],p[4],p[1],rate_,frames);state.filters[2].prepare(5,8000,.7071,p[2],rate_,frames);state.filters[3].prepare(1,p[5],.7071,0,rate_,frames);state.filters[4].prepare(0,p[6],.7071,0,rate_,frames);}
        if(c.kind==9)state.filters[0].prepare(0,p[1],.7071,0,rate_,frames);
        if(c.kind==10)state.filters[0].prepare((int)p[0],p[1],p[2],0,rate_,frames);
        const float attack=c.kind==5?1-std::exp(-1.f/(rate_*p[2]*.001f)):1;
        const float release=(c.kind==5||c.kind==6)?1-std::exp(-1.f/(rate_*(c.kind==5?p[3]:p[1])*.001f)):1;
        const float threshold=dbToGain(p[0]), makeup=c.kind==5?dbToGain(p[4]):1;
        const float drive=c.kind==9?dbToGain(p[0]):1, output=c.kind==9?dbToGain(p[3]):1, boost=c.kind==6?dbToGain(p[2]):1;
        const float enabled=c.bypass?0:1;
        for(int i=0;i<frames;++i){float dryL=audio[2*i],dryR=audio[2*i+1],l=dryL,r=dryR;float f=(float)(i+1)/frames, v=state.previous+(target-state.previous)*f;
            if(c.kind==1||c.kind==3){l*=v;r*=v;}
            else if(c.kind==2){float mid=.5f*(l+r),side=.5f*(l-r)*v;l=mid+side;r=mid-side;}
            else if(c.kind==4){for(auto& filter:state.filters)filter.tick(l,r);}
            else if(c.kind==5){float peak=std::max(std::fabs(l),std::fabs(r));float wanted=peak>threshold?std::pow(peak/threshold,1/p[1]-1):1;state.gain+=(wanted-state.gain)*(wanted<state.gain?attack:release);float g=state.gain*makeup;l*=g;r*=g;l=dryL+(l-dryL)*p[5];r=dryR+(r-dryR)*p[5];}
            else if(c.kind==6){l*=boost;r*=boost;float peak=std::max(std::fabs(l),std::fabs(r));float needed=peak>threshold?threshold/peak:1;state.gain=needed<state.gain?needed:std::min(needed,state.gain+(1-state.gain)*release);l*=state.gain;r*=state.gain;}
            else if((c.kind==7||c.kind==8)&&state.wet>=0){float wl,wr;wet_[state.wet].tick(c,rate_,l,r,wl,wr,v);l=l*(1-p[2])+wl*p[2];r=r*(1-p[2])+wr*p[2];}
            else if(c.kind==9){float norm=std::tanh(drive);l=std::tanh(l*drive)/norm;r=std::tanh(r*drive)/norm;state.filters[0].tick(l,r);l=(dryL+(l-dryL)*p[2])*output;r=(dryR+(r-dryR)*p[2])*output;}
            else if(c.kind==10){state.filters[0].tick(l,r);l=dryL+(l-dryL)*p[3];r=dryR+(r-dryR)*p[3];}
            float blend=state.enabled+(enabled-state.enabled)*f;
            audio[2*i]=dryL+(l-dryL)*blend;audio[2*i+1]=dryR+(r-dryR)*blend;
        }
        state.previous=target;state.enabled=enabled;
    }
}
}

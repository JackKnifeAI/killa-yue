#include "studio/mixer.h"
#include "studio/mix.h"
#include "test.h"
#include <algorithm>
#include <vector>
using namespace studio;
static float* effect(MixerConfig& c,int kind,std::initializer_list<float> p,int strip=0,int slot=0,int id=1) {
    float* s=c.data.data()+2+strip*kMixerStride; s[7]=std::max(s[7],(float)(slot+1));
    float* f=s+8+slot*kEffectStride; std::fill_n(f,kEffectStride,0);f[0]=kind;f[10]=id;
    int n=0;for(float v:p){f[n==0?1:n+2]=v;++n;}return f;
}
static double response(int kind,std::initializer_list<float> params,double hz) {
    MixerConfig c;effect(c,kind,params);EffectsRack rack(48000);rack.configure(c.data.data());
    float buf[512];double power=0;
    for(int block=0;block<200;++block){for(int i=0;i<256;++i)buf[2*i]=buf[2*i+1]=.1f*std::sin(6.283185307179586*hz*(block*256+i)/48000);
        rack.process(0,buf,256);if(block>=100)for(float v:buf)power+=v*v;}
    return std::sqrt(power/(100*512))/.070710678;
}
int main(){
    NEAR(response(4,{0,0,0,1000,1,0,0},1000),1,.003,"flat EQ passes unchanged");
    CHECK(response(4,{-12,0,0,1000,1,0,0},30)<.27,"low shelf attenuates low frequencies");
    CHECK(response(4,{0,0,12,1000,1,0,0},16000)>3.6,"high shelf boosts high frequencies");
    NEAR(response(4,{0,6,0,1000,1,0,0},1000),1.995,.01,"bell reaches its specified centre gain");
    CHECK(response(4,{0,0,0,1000,1,200,0},20)<.011,"low cut removes sub bass");
    CHECK(response(4,{0,0,0,1000,1,0,1000},10000)<.009,"high cut attenuates highs");
    CHECK(response(10,{0,1000,.7071f,1},10000)<.009,"low pass");
    CHECK(response(10,{1,1000,.7071f,1},100)<.011,"high pass");
    NEAR(response(10,{2,1000,1,1},1000),1,.003,"band pass centre");
    {
        MixerConfig c;auto* f=effect(c,5,{-20,4,.1f,100,0,1});EffectsRack rack(48000);rack.configure(c.data.data());
        float buf[512];for(int b=0;b<200;++b){std::fill_n(buf,512,1);rack.process(0,buf,256);}
        NEAR(buf[511],std::pow(10.,-15./20),.0001,"compressor converges to 4:1 above -20 dB");
        f[2]=1;rack.configure(c.data.data());std::fill_n(buf,512,1);rack.process(0,buf,256);
        CHECK(buf[0]<.3 && buf[510]>.999,"bypass fades to dry within one block");
        std::fill_n(buf,512,1);rack.process(0,buf,256);NEAR(buf[0],1,1e-6,"bypassed compressor is transparent");
    }
    {
        MixerConfig c;effect(c,6,{-3,80,6});EffectsRack rack(48000);rack.configure(c.data.data());
        float buf[512];for(int b=0;b<20;++b){for(int i=0;i<512;++i)buf[i]=2*std::sin((b*512+i)*.13f);rack.process(0,buf,256);
            bool limited=true;for(float v:buf)limited&=std::isfinite(v)&&std::fabs(v)<=dbToGain(-3)+1e-6;CHECK(limited,"limiter holds sample ceiling including transients");}
    }
    {
        MixerConfig c;auto* f=effect(c,8,{10,.5f,1,1});CHECK(c.valid(),"valid delay layout");EffectsRack rack(48000);rack.configure(c.data.data());
        std::vector<float> buf(2*1921);buf[0]=1;rack.process(0,buf.data(),1921);
        NEAR(buf[0],0,1e-8,"fully wet delay removes dry impulse");NEAR(buf[2*480],1,1e-8,"delay time matches milliseconds");
        NEAR(buf[2*960+1],.5,1e-8,"ping pong crosses feedback channels");NEAR(buf[2*1440],.25,1e-8,"feedback decays");
        rack.reset();std::vector<float> again(buf.size());again[0]=1;rack.process(0,again.data(),1921);CHECK(buf==again,"delay reset is deterministic");
        f[2]=1;rack.configure(c.data.data());std::fill(again.begin(),again.end(),0);rack.process(0,again.data(),1921);std::fill(again.begin(),again.end(),.2f);rack.process(0,again.data(),1921);NEAR(again[100],.2,1e-7,"bypassed wet insert passes dry");
    }
    {
        MixerConfig c;effect(c,7,{.85f,.4f,1,100});EffectsRack rack(48000);rack.configure(c.data.data());
        std::vector<float> audio(2*196800);audio[0]=1;rack.process(0,audio.data(),196800);double power=0,end=0;bool finite=true;
        for(size_t i=0;i<audio.size();++i){finite&=std::isfinite(audio[i]);power+=audio[i]*audio[i];if(i>audio.size()-4800)end+=audio[i]*audio[i];}
        CHECK(finite && power>.01,"reverb generates finite tail");CHECK(end<1e-12,"longest room decays inside tail allowance");
        rack.reset();std::vector<float> again(audio.size());again[0]=1;rack.process(0,again.data(),196800);CHECK(audio==again,"reverb reset deterministic");
    }
    {
        MixerConfig c;effect(c,9,{30,20000,1,0});EffectsRack rack(48000);rack.configure(c.data.data());float audio[512];
        std::fill_n(audio,512,10);rack.process(0,audio,256);NEAR(audio[511],1,.001,"saturation bounds steady output");
    }
    {
        MixerConfig c;auto* f=effect(c,10,{0,1000,.7f,1});CHECK(c.valid(),"filter config valid");f[1]=.5f;CHECK(!c.valid(),"fractional filter mode rejected");f[1]=0;f[3]=NAN;CHECK(!c.valid(),"NaN parameter rejected");
        f[3]=1000;f[10]=NAN;CHECK(!c.valid(),"NaN ID rejected before conversion");f[10]=0;CHECK(!c.valid(),"stateful insert requires identity");f[10]=1;
        effect(c,1,{0},0,1,1);CHECK(!c.valid(),"duplicate insert identity rejected");
        c=MixerConfig();for(int i=0;i<16;++i)effect(c,8,{250,.3f,.2f,0},i/8,i%8,i+1);CHECK(c.valid(),"16 wet inserts allowed");
        effect(c,7,{.65f,.4f,.2f,12},2,0,17);CHECK(!c.valid(),"17th wet insert refused before callback");
    }
    for (int location : {0, 64}) {
        MixerConfig c; c.data[0]=1; c.data[1]=location==64?1:0;
        if (location==64) c.data[2+6]=0;
        effect(c,8,{10,.5f,1,0},location); MixerGraph graph(48000,64); graph.configure(c);
        float in[128]{}, out[128]{}; in[0]=1;
        graph.begin(64);graph.track(0,in,out,64);graph.finish(out,64);
        if(location==64){c.data[2+64*kMixerStride+2]=1;graph.configure(c);}
        bool silent=true;
        for(int block=1;block<30;++block){std::fill_n(in,128,0);std::fill_n(out,128,0);graph.begin(64);
            graph.track(0,in,out,64,location!=0);graph.finish(out,64);
            for(float v:out)silent&=std::fabs(v)<1e-8;}
        CHECK(silent,"muting track or group silences its pending insert tail");
    }
    DONE("effects_test");
}

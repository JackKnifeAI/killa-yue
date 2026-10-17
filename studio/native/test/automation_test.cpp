#include "studio/engine.h"
#include "studio/automation.h"
#include "test.h"
#include <vector>
using namespace studio;
static void lane(MixerConfig& c,int strip,int slot,int param,std::initializer_list<std::pair<long,float>> points){
    float* a=c.data.data()+kAutomationOffset;int index=(int)a[0],first=(int)a[1];float* h=a+2+index*5;
    h[0]=strip;h[1]=slot;h[2]=param;h[3]=first;h[4]=points.size();int count=first;
    for(auto point:points){float* p=a+2+kAutomationLanes*5+count++*3;p[0]=point.first&65535;p[1]=point.first>>16;p[2]=point.second;}a[0]=index+1;a[1]=count;
}
struct Constant : Source {
    int read(float* out,int n)override{std::fill_n(out,2*n,.2f);return n;}int channels()const override{return 2;}void seek(long)override{}
};
static std::vector<float> render(MixerConfig c,int frames){Constant source;Engine engine(48000,512);auto* s=new Session{48000,{{&source,0,0,false,false}}};s->hasMixer=true;s->mixer=c;
    engine.post({Msg::Swap,0,0,0,s});engine.post({Msg::Limiter,0,0,0,nullptr});engine.post({Msg::Play,0,0,0,nullptr});std::vector<float> out(2*frames);engine.process(out.data(),frames);return out;}
int main(){
    MixerConfig c;c.data[0]=1;lane(c,0,-1,0,{{0,-20},{48000,0}});CHECK(c.valid(),"valid gain curve");
    NEAR(AutomationData::value(c.data.data(),0,24000),-10,1e-6,"linear interpolation");NEAR(AutomationData::value(c.data.data(),0,-1),-20,1e-6,"hold before first");NEAR(AutomationData::value(c.data.data(),0,96000),0,1e-6,"hold after last");
    CHECK(AutomationData::next(c.data.data(),0,96000)==48000,"next point boundary");auto audio=render(c,48001);
    NEAR(audio[2*24000],.2*dbToGain(-10),.00003,"track volume rendered through real engine");NEAR(audio[2*48000],.2,.00001,"track reaches endpoint");
    c=MixerConfig();c.data[0]=1;lane(c,0,-1,1,{{0,-1},{48000,1}});audio=render(c,48001);
    NEAR(audio[0],.2,.0001,"pan starts left");CHECK(std::fabs(audio[1])<.0001,"pan starts with right silent");NEAR(audio[2*48000+1],.2,.0001,"pan ends right");CHECK(std::fabs(audio[2*48000])<.0001,"pan ends with left silent");
    c=MixerConfig();c.data[0]=1;c.data[1]=1;c.data[8]=0;lane(c,64,-1,0,{{0,-20},{48000,0}});audio=render(c,48001);NEAR(audio[2*24000],.2*dbToGain(-10),.00003,"group volume follows lane");
    c=MixerConfig();c.data[0]=1;lane(c,74,-1,0,{{0,-20},{48000,0}});audio=render(c,48001);NEAR(audio[2*24000],.2*dbToGain(-10),.00003,"master volume follows lane");
    c=MixerConfig();c.data[0]=1;float* strip=c.data.data()+2;strip[7]=1;strip[8]=1;strip[18]=1;lane(c,0,0,0,{{0,-20},{48000,0}});CHECK(c.valid(),"FX lane valid");audio=render(c,48001);NEAR(audio[2*24000],.2*dbToGain(-10),.00004,"FX parameter follows lane");
    c=MixerConfig();c.data[0]=1;strip=c.data.data()+2;strip[7]=1;strip[8]=3;strip[18]=1;lane(c,0,0,8,{{0,0},{24000,1}});CHECK(c.valid(),"bypass lane valid");audio=render(c,25000);NEAR(audio[2*23000],-.2,1e-6,"polarity enabled before bypass point");NEAR(audio[2*24500],.2,1e-6,"automated bypass fades to dry");
    c=MixerConfig();c.data[0]=1;lane(c,0,-1,3,{{0,1}});audio=render(c,24001);NEAR(audio[2*12001],.4,1e-6,"automated delay send enters shared return");
    lane(c,73,-1,0,{{0,-6.0206f}});audio=render(c,24001);NEAR(audio[2*12001],.3,1e-6,"automated return gain");
    {
        Constant source;Engine e(48000,512);auto* s=new Session{48000,{{&source,0,0,false,false}}};s->hasMixer=true;s->mixer=MixerConfig();s->mixer.data[0]=1;
        lane(s->mixer,0,-1,0,{{0,-20},{48000,0}});e.post({Msg::Swap,0,0,0,s});e.post({Msg::Limiter,0,0,0,nullptr});e.post({Msg::Play,0,0,0,nullptr});
        std::vector<float> first(96000),again(96000);e.process(first.data(),48000);e.post({Msg::Seek,0,0,0,nullptr});e.post({Msg::Reset,0,0,0,nullptr});e.process(again.data(),48000);
        CHECK(first==again,"seek/reset makes automated rendering deterministic");
    }
    c=MixerConfig();c.data[0]=1;lane(c,0,-1,0,{{0,0},{100,1}});float* point=c.data.data()+kAutomationOffset+2+kAutomationLanes*5;
    point[3]=0;CHECK(!c.valid(),"duplicate native frame refused");point[3]=100;point[2]=NAN;CHECK(!c.valid(),"NaN value refused");point[2]=0;point[1]=NAN;CHECK(!c.valid(),"NaN frame refused before conversion");point[1]=0;
    lane(c,0,-1,0,{{0,0}});CHECK(!c.valid(),"duplicate native target refused");
    DONE("automation_test");
}

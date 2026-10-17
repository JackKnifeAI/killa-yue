#include "studio/engine.h"
#include "test.h"
#include <vector>
using namespace studio;
struct Pulse : Source {
    long at=0;
    int read(float* out,int n) override {for(int i=0;i<n;++i,++at)out[2*i]=out[2*i+1]=at==0?.5f:0;return n;}
    int channels() const override{return 2;}
    void seek(long frame) override{at=frame;}
};
int main(){
    for(int location:{0,64}){
        Pulse source;Engine engine(48000,64);
        auto* session=new Session{48000,{{&source,0,0,false,false}}};session->hasMixer=true;
        auto& c=session->mixer;c.data[0]=1;c.data[1]=location==64?1:0;if(location==64)c.data[8]=0;
        float* s=c.data.data()+2+location*kMixerStride;s[7]=1;float* f=s+8;f[0]=8;f[1]=1;f[3]=.5f;f[4]=1;f[10]=1;
        CHECK(c.valid(),"tail fixture config valid");engine.post({Msg::Swap,0,0,0,session});engine.post({Msg::Limiter,0,0,0,nullptr});engine.post({Msg::Play,0,0,0,nullptr});
        float first[32]{};engine.process(first,16);engine.post({Msg::Stop,0,0,0,nullptr});
        std::vector<float> tail(2*4800);engine.process(tail.data(),4800);
        NEAR(tail[2*32],.5,1e-8,"track/group delay drains after stopping");CHECK(source.at==16&&engine.status().position==16,"draining effects does not advance clips or playhead");
        engine.post({Msg::Play,0,0,0,nullptr});float resumed[128];engine.process(resumed,64);bool quiet=true;for(float v:resumed)quiet&=std::fabs(v)<1e-8;
        CHECK(quiet,"resuming does not replay a frozen tail");
    }
    DONE("effects_engine_test");
}

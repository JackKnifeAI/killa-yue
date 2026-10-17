#include "studio/automation.h"
#include <cmath>
namespace studio {
static bool integer(float v,int lo,int hi){return std::isfinite(v)&&v>=lo&&v<=hi&&v==std::floor(v);}
long AutomationData::frame(const float* p){return (long)p[0]+((long)p[1]<<16);}
bool AutomationData::valid(const float* data){
    const float* a=data+kAutomationOffset;
    if(!integer(a[0],0,kAutomationLanes)||!integer(a[1],0,kAutomationPoints))return false;
    int used=0,keys[kAutomationLanes]{};
    for(int i=0;i<(int)a[0];++i){
        const float* h=lane(data,i);
        if(!integer(h[0],0,74)||!integer(h[1],-1,7)||!integer(h[2],0,8)||!integer(h[3],0,kAutomationPoints-1)||!integer(h[4],1,kAutomationPoints))return false;
        int strip=(int)h[0],slot=(int)h[1],param=(int)h[2],count=(int)h[4];
        if(h[3]!=used||used+count>a[1]||(strip<64&&strip>=data[0])||(strip>=64&&strip<72&&strip>=64+data[1]))return false;
        int key=(strip*9+slot+1)*9+param;for(int j=0;j<i;++j)if(keys[j]==key)return false;keys[i]=key;
        const float* s=data+2+strip*kMixerStride;EffectConfig effect;
        if(slot>=0){
            if(slot>=s[7])return false;
            effect=EffectConfig::read(s+8+slot*kEffectStride);
            const int parameters[11]={0,1,1,0,7,6,3,4,4,4,4};
            if(effect.kind<1||effect.kind>10||(param!=8&&param>=parameters[effect.kind]))return false;
        }else if(param>3||(strip==74&&param!=0)||(strip>=72&&param>=2))return false;
        long previous=-1;
        for(int j=0;j<count;++j){const float* p=point(data,used+j);
            if(!integer(p[0],0,65535)||!integer(p[1],0,16777215)||!std::isfinite(p[2]))return false;
            long at=frame(p);if(at<=previous)return false;previous=at;
            if(slot>=0){if(param==8){if(!integer(p[2],0,1))return false;}else{effect.p[param]=p[2];if(!effect.valid())return false;}}
            else{float lo=param==0?-60:param==1?-1:0,hi=param==0?12:1;if(p[2]<lo||p[2]>hi)return false;}
        }
        used+=count;
    }
    return used==(int)a[1];
}
float AutomationData::value(const float* data,int index,long at,bool stepped){
    const float* h=lane(data,index);int first=(int)h[3],count=(int)h[4];
    int lo=0,hi=count;while(lo<hi){int mid=(lo+hi)/2;if(frame(point(data,first+mid))<=at)lo=mid+1;else hi=mid;}
    if(lo==0)return point(data,first)[2];
    const float* left=point(data,first+lo-1);
    if(lo==count||stepped)return left[2];
    const float* right=point(data,first+lo);long from=frame(left),to=frame(right);
    return left[2]+(right[2]-left[2])*(double)(at-from)/(to-from);
}
long AutomationData::next(const float* data,long at,long end){
    for(int i=0;i<(int)data[kAutomationOffset];++i){const float* h=lane(data,i);int first=(int)h[3],lo=0,hi=(int)h[4];
        while(lo<hi){int mid=(lo+hi)/2;if(frame(point(data,first+mid))<=at)lo=mid+1;else hi=mid;}
        if(lo<(int)h[4]){long next=frame(point(data,first+lo));if(next<end)end=next;}}
    return end;
}
}

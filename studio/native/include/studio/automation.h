#pragma once
#include "studio/effects.h"
namespace studio {
constexpr int kAutomationOffset=2+75*kMixerStride;
constexpr int kAutomationLanes=128, kAutomationPoints=4096;
constexpr int kAutomationSize=2+kAutomationLanes*5+kAutomationPoints*3;
// Immutable numeric envelopes travel in the mixer configuration's bounded SPSC queue.
struct AutomationData {
    static bool valid(const float* mixer);
    static const float* lane(const float* mixer,int index){return mixer+kAutomationOffset+2+index*5;}
    static const float* point(const float* mixer,int index){return mixer+kAutomationOffset+2+kAutomationLanes*5+index*3;}
    static long frame(const float* point);
    static float value(const float* mixer,int lane,long at,bool stepped=false);
    static long next(const float* mixer,long at,long end);
};
}

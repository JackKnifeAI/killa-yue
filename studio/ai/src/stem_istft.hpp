#pragma once
#include <algorithm>
#include <cmath>
#include <complex>
#include <stdexcept>
#include <vector>

namespace killa_stems {
// Demucs htdemucs CAC decoder: periodic Hann, normalized transform,
// Nyquist zero-padding, two zero time frames on each side, hybrid crop.
// Adapted from Meta's MIT demucs/hdemucs.py and demucs/spec.py.
constexpr int fftSize=4096,hop=1024,frames=343980,bins=2048;
inline void inverse(std::vector<std::complex<float>>&values){
 const int n=values.size();
 for(int i=1,j=0;i<n;i++){
  int bit=n>>1;for(;j&bit;bit>>=1)j^=bit;j^=bit;if(i<j)std::swap(values[i],values[j]);
 }
 for(int length=2;length<=n;length<<=1){
  const double angle=2*3.14159265358979323846/length;
  std::complex<float> root(std::cos(angle),std::sin(angle));
  for(int start=0;start<n;start+=length){std::complex<float> weight(1,0);for(int k=0;k<length/2;k++){
   auto a=values[start+k],b=values[start+k+length/2]*weight;
   values[start+k]=a+b;values[start+k+length/2]=a-b;weight*=root;
  }}
 }
 const float normalized=1/std::sqrt(float(n));for(auto&value:values)value*=normalized;
}
inline std::vector<float> reconstruct(const float*spectrum,const float*time,int timeFrames,int sampleFrames=frames){
 if(!spectrum||!time||timeFrames!=(sampleFrames+hop-1)/hop)throw std::runtime_error("Unsupported Demucs spectral layout");
 const int paddedFrames=timeFrames+4,total=fftSize+(paddedFrames-1)*hop,crop=fftSize/2+hop*3/2;
 if(crop+sampleFrames>total)throw std::runtime_error("Invalid Demucs hybrid crop");
 std::vector<float> window(fftSize),denominator(total,0),sum(total),result(size_t(8)*sampleFrames);
 for(int i=0;i<fftSize;i++)window[i]=.5f-.5f*std::cos(2*3.14159265358979323846*i/fftSize);
 for(int t=0;t<paddedFrames;t++)for(int i=0;i<fftSize;i++)denominator[t*hop+i]+=window[i]*window[i];
 std::vector<std::complex<float>>values(fftSize);
 for(int channel=0;channel<8;channel++){
  std::fill(sum.begin(),sum.end(),0);
  int stem=channel/2,side=channel%2;
  const float*real=spectrum+size_t(stem*4+side*2)*bins*timeFrames;
  const float*imag=real+size_t(bins)*timeFrames;
  for(int t=0;t<timeFrames;t++){
   std::fill(values.begin(),values.end(),std::complex<float>(0,0));
   values[0]={real[t],0};
   for(int frequency=1;frequency<bins;frequency++){
    size_t index=size_t(frequency)*timeFrames+t;
    values[frequency]={real[index],imag[index]};values[fftSize-frequency]=std::conj(values[frequency]);
   }
   inverse(values);int offset=(t+2)*hop;
   for(int i=0;i<fftSize;i++)sum[offset+i]+=values[i].real()*window[i];
  }
  for(int i=0;i<sampleFrames;i++){
   float value=sum[crop+i]/denominator[crop+i]+time[size_t(channel)*sampleFrames+i];
   if(!std::isfinite(value))throw std::runtime_error("Demucs returned non-finite audio");
   result[size_t(channel)*sampleFrames+i]=value;
  }
 }
 return result;
}
}

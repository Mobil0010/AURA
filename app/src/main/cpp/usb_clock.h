#pragma once
#include <cstdint>
#include <functional>
#include <string>

// Transport-independent UAC2 clock negotiation. Never accept a rate that wasn't read back.
namespace aura {
using ClockTransfer=std::function<int(bool,unsigned char*,int)>;
inline unsigned clockRate(const unsigned char* p){return unsigned(p[0])|unsigned(p[1])<<8|unsigned(p[2])<<16|unsigned(p[3])<<24;}
inline int configureClock(unsigned wanted,int controls,const ClockTransfer& transfer,const std::function<void()>& settle,std::string& stage){
    unsigned char current[4]={};stage="clock GET_CUR";int read=transfer(true,current,4);
    if(read==4&&clockRate(current)==wanted)return 0;
    if((controls&3)!=3){stage="clock is read-only; requested "+std::to_string(wanted)+", current "+(read==4?std::to_string(clockRate(current)):"unavailable");return -12;}
    unsigned char bytes[4]={(unsigned char)wanted,(unsigned char)(wanted>>8),(unsigned char)(wanted>>16),(unsigned char)(wanted>>24)};
    stage="clock SET_CUR";int sent=transfer(false,bytes,4);
    // Some firmware reports a failed SET although it applied it: require verified readback.
    for(int i=0;i<6;i++){if(i)settle();read=transfer(true,current,4);if(read==4&&clockRate(current)==wanted)return 0;}
    stage="clock readback requested "+std::to_string(wanted)+", actual "+(read==4?std::to_string(clockRate(current)):"unavailable");return sent<0?sent:read<0?read:-1;
}
}

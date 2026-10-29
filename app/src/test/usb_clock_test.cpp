#include "../main/cpp/usb_clock.h"
#include <cassert>
#include <iostream>
static void put(unsigned char* b,unsigned r){for(int i=0;i<4;i++)b[i]=(r>>(8*i))&255;}
int main(){std::string stage;int sets=0,reads=0;
 assert(aura::configureClock(44100,1,[&](bool read,unsigned char* b,int){assert(read);reads++;put(b,44100);return 4;},[]{},stage)==0);assert(reads==1);
 assert(aura::configureClock(44100,1,[&](bool read,unsigned char* b,int){assert(read);put(b,48000);return 4;},[]{},stage)!=0);
 unsigned actual=48000;assert(aura::configureClock(44100,3,[&](bool read,unsigned char* b,int){if(read)put(b,actual);else{actual=aura::clockRate(b);sets++;}return 4;},[]{},stage)==0);assert(sets==1&&actual==44100);
 assert(aura::configureClock(96000,3,[&](bool read,unsigned char* b,int){if(read)put(b,48000);return 4;},[]{},stage)!=0);assert(stage.find("48000")!=std::string::npos);
 actual=48000;assert(aura::configureClock(44100,3,[&](bool read,unsigned char* b,int){if(read){put(b,actual);return 4;}actual=44100;return -1;},[]{},stage)==0);
 assert(aura::configureClock(44100,3,[](bool,unsigned char*,int){return -4;},[]{},stage)==-4);
 std::cout<<"USB clock negotiation: 6 scenarios passed\n";
}

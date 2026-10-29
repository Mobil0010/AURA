#pragma once
#include <algorithm>
#include <cstddef>
namespace aura {
// USB PCM is left justified. Only add zero LSBs; never discard source bits.
inline void padPcm(unsigned char* bytes,size_t samples,int inputBytes,int wireBytes){if(inputBytes==wireBytes)return;while(samples--){size_t in=samples*inputBytes,out=samples*wireBytes;for(int b=inputBytes-1;b>=0;b--)bytes[out+wireBytes-inputBytes+b]=bytes[in+b];std::fill_n(bytes+out,wireBytes-inputBytes,0);}}
}

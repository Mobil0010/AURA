package dev.aura.player;

import androidx.media3.common.*;
import java.nio.*;

/** Provenance for lossless integer PCM decoded through a float-only codec output. */
@androidx.media3.common.util.UnstableApi
record PcmOrigin(int encoding,int rate,int channels,boolean lossless) {
    static int bits(Format format){if(format.pcmEncoding!=C.ENCODING_PCM_FLOAT||!(format.customData instanceof PcmOrigin source)||!source.lossless||source.rate!=format.sampleRate||source.channels!=format.channelCount)return 0;return source.encoding==C.ENCODING_PCM_24BIT?24:source.encoding==C.ENCODING_PCM_16BIT?16:0;}
    static ByteBuffer restore(ByteBuffer input,int bits,ByteBuffer output){int count=input.remaining()/4,bytes=bits/8;if(input.remaining()%4!=0||bits!=16&&bits!=24)throw new IllegalArgumentException("무손실 PCM 복원 형식이 올바르지 않습니다.");int needed=count*bytes;if(output==null||output.capacity()<needed)output=ByteBuffer.allocateDirect(needed);output.clear();ByteBuffer source=input.duplicate().order(ByteOrder.LITTLE_ENDIAN);double scale=Math.scalb(1.,bits-1);for(int i=0;i<count;i++){double sample=source.getFloat()*scale;if(!Double.isFinite(sample)||sample!=Math.rint(sample)||sample< -scale||sample>scale-1)throw new IllegalArgumentException("디코더 출력에서 원본 정수 PCM을 무손실로 복원할 수 없습니다.");int value=(int)sample;for(int b=0;b<bytes;b++)output.put((byte)(value>>(8*b)));}output.flip();return output;}
}

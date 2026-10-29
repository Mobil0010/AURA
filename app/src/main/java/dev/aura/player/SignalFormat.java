package dev.aura.player;

import java.util.Locale;

/** Immutable successful sink configuration, not a guess based on selected settings. */
record SignalFormat(int inputRate,int inputBits,boolean inputFloat,int outputRate,int outputBits,boolean outputFloat,int channels,boolean perfect,boolean sox) {
    private static String rate(int hz){return (hz%1000==0?String.format(Locale.ROOT,"%d",hz/1000):String.format(Locale.ROOT,"%.3f",hz/1000.0).replaceAll("0+$","").replaceAll("\\.$",""))+" kHz";}
    private static String pcm(int bits,boolean floating,int hz){return bits+" bit"+(floating?" float":"")+" / "+rate(hz);}
    String input(){return pcm(inputBits,inputFloat,inputRate);}
    String output(){return pcm(outputBits,outputFloat,outputRate);}
    String compact(){return inputRate==outputRate?input().replace(" / "," · "):input()+" → "+output()+(sox?" · SoX":" · 리샘플링");}
    String processing(){String rate=inputRate!=outputRate?(sox?"SoX 리샘플링":"호환 리샘플링"):"샘플레이트 유지";return rate+(inputBits!=outputBits||inputFloat!=outputFloat?" · 비트 형식 변환":"")+(perfect?" · 비트퍼펙트 요청":"");}
}

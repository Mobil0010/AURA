package dev.aura.player;

import android.content.Context;
import android.media.MediaFormat;
import android.os.Handler;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.audio.*;
import androidx.media3.exoplayer.mediacodec.*;
import java.util.ArrayList;

@androidx.media3.common.util.UnstableApi
class AuraRenderersFactory extends DefaultRenderersFactory {
    AuraRenderersFactory(Context c){super(c);setEnableDecoderFallback(true);}
    @Override protected AudioSink buildAudioSink(Context c,boolean floating,boolean playbackParams){return new PcmSink(c);}
    @Override protected void buildAudioRenderers(Context c,int mode,MediaCodecSelector selector,boolean fallback,AudioSink sink,Handler handler,AudioRendererEventListener listener,ArrayList<Renderer> out){out.add(new MediaCodecAudioRenderer(c,getCodecAdapterFactory(),selector,fallback,handler,listener,sink){
        @Override protected androidx.media3.exoplayer.DecoderReuseEvaluation onInputFormatChanged(androidx.media3.exoplayer.FormatHolder holder)throws ExoPlaybackException{Format f=holder.format;if(f!=null&&!MimeTypes.AUDIO_RAW.equals(f.sampleMimeType))holder.format=f.buildUpon().setCustomData(new PcmOrigin(f.pcmEncoding,f.sampleRate,f.channelCount,MimeTypes.AUDIO_FLAC.equals(f.sampleMimeType)||MimeTypes.AUDIO_ALAC.equals(f.sampleMimeType))).build();return super.onInputFormatChanged(holder);}
        @Override protected MediaFormat getMediaFormat(Format format,String mime,int maxSize,float rate){MediaFormat result=super.getMediaFormat(format,mime,maxSize,rate);if(!MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)){int encoding=integerEncoding(format);if(AudioSettings.current.perfect()||encoding!=C.ENCODING_INVALID)result.setInteger(MediaFormat.KEY_PCM_ENCODING,encoding==C.ENCODING_INVALID?C.ENCODING_PCM_16BIT:encoding==C.ENCODING_PCM_24BIT?C.ENCODING_PCM_FLOAT:encoding);}return result;}
        @Override protected void onOutputFormatChanged(Format format,MediaFormat output)throws ExoPlaybackException{int expected=integerEncoding(format);if(AudioSettings.current.perfect()&&!MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)&&expected!=C.ENCODING_INVALID&&output!=null&&(output.containsKey(MediaFormat.KEY_PCM_ENCODING)?output.getInteger(MediaFormat.KEY_PCM_ENCODING):C.ENCODING_PCM_16BIT)!=expected&&!(expected==C.ENCODING_PCM_24BIT&&output.containsKey(MediaFormat.KEY_PCM_ENCODING)&&output.getInteger(MediaFormat.KEY_PCM_ENCODING)==C.ENCODING_PCM_FLOAT&&format.customData instanceof PcmOrigin origin&&origin.lossless()))throw createRendererException(new IllegalStateException("디코더가 원본 PCM 비트 수를 유지하지 못했습니다. 다른 출력 모드나 지원되는 음원을 선택해 주세요."),format,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED);super.onOutputFormatChanged(format,output);}
    });}
    static int integerEncoding(Format f){return switch(f.pcmEncoding){case C.ENCODING_PCM_16BIT,C.ENCODING_PCM_24BIT,C.ENCODING_PCM_32BIT->f.pcmEncoding;default->C.ENCODING_INVALID;};}
}

package dev.aura.player;
import androidx.media3.common.Metadata;import java.util.*;import java.util.regex.*;
/** ReplayGain tags only; never invents loudness values or analyzes a whole library. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class ReplayGain {
 static volatile double gainDb;static volatile String status="ReplayGain 태그 없음";
 static final Map<String,Double> tags=new HashMap<>();
 static String trackUri="";
 static void begin(androidx.media3.common.MediaItem item){String next=item==null?"":!item.mediaId.isEmpty()?item.mediaId:item.localConfiguration==null?"":item.localConfiguration.uri.toString();if(!next.equals(trackUri)){reset();trackUri=next;}}
 static void reset(){trackUri="";tags.clear();gainDb=0;status="ReplayGain 태그 없음";}
 static void read(Metadata m){if(m==null)return;for(int i=0;i<m.length();i++){String text=m.get(i).toString().toUpperCase(Locale.ROOT);for(String key:List.of("REPLAYGAIN_TRACK_GAIN","REPLAYGAIN_ALBUM_GAIN","REPLAYGAIN_TRACK_PEAK","REPLAYGAIN_ALBUM_PEAK")){int at=text.indexOf(key);if(at<0)continue;Matcher value=Pattern.compile("[-+]?\\d+(?:\\.\\d+)?").matcher(text.substring(at+key.length()));if(value.find())try{double n=Double.parseDouble(value.group());if(Double.isFinite(n))tags.put(key,n);}catch(NumberFormatException ignored){}}}update();}
 static void update(){var extra=AudioSettings.extra;if(extra==null||extra.replayGain().equals("off")){gainDb=0;status="ReplayGain 꺼짐";return;}boolean album=extra.replayGain().equals("album");String base=album&&tags.containsKey("REPLAYGAIN_ALBUM_GAIN")?"REPLAYGAIN_ALBUM_":"REPLAYGAIN_TRACK_";Double db=tags.get(base+"GAIN");if(db==null){gainDb=0;status="ReplayGain 태그 없음 · 음량 유지";return;}double gain=Math.max(-30,Math.min(18,db));Double peak=tags.get(base+"PEAK");if(peak!=null&&peak>0)gain=Math.min(gain,-20*Math.log10(peak));else gain=Math.min(0,gain);gainDb=gain;status=(base.contains("ALBUM")?"앨범":"곡")+" 기준 · "+String.format(Locale.ROOT,"%+.1f dB",gain);}
}

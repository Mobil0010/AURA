package dev.aura.player;
import android.net.Uri;import java.util.*;
final class MediaTypes {
 static String of(Uri uri,String... hints){for(String hint:hints)if(hint!=null&&hint.startsWith("audio/")&&!hint.equals("audio/*"))return switch(hint){case "audio/x-flac"->"audio/flac";case "audio/mp3"->"audio/mpeg";case "audio/x-wav","audio/wave"->"audio/wav";default->hint;};String name=Objects.toString(uri.getLastPathSegment(),"").toLowerCase(Locale.ROOT);return name.endsWith(".mp3")?"audio/mpeg":name.endsWith(".m4a")||name.endsWith(".mp4")||name.endsWith(".alac")?"audio/mp4":name.endsWith(".aac")?"audio/aac":name.endsWith(".wav")?"audio/wav":name.endsWith(".ogg")||name.endsWith(".opus")?"audio/ogg":name.endsWith(".aif")||name.endsWith(".aiff")?"audio/aiff":"audio/flac";}
}

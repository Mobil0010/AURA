package dev.aura.player;

import android.content.Context;
import android.os.*;
import androidx.media3.common.*;
import java.util.*;

/** Service-owned tracker: continues with the screen off and never writes on the player thread. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class ListeningMonitor implements Player.Listener {
    interface Source {Track track();long position();boolean playing();default float speed(){return 1;}}
    private static final Set<ListeningMonitor> live=new HashSet<>();
    private final Context context;private final ListeningStore store;private final Source source;private final Handler handler=new Handler(Looper.getMainLooper());private final ListeningClock clock=new ListeningClock();
    private Track track;private String visit="";private long listened;private boolean counted;private float speed=1;
    private final Runnable poll=new Runnable(){public void run(){sample();handler.postDelayed(this,5000);}};
    ListeningMonitor(Context c,Source source){context=c.getApplicationContext();store=ListeningStore.get(c);this.source=source;live.add(this);sample();handler.postDelayed(poll,5000);}
    static ListeningMonitor local(Context c,Player p){ListeningMonitor m=new ListeningMonitor(c,new Source(){public Track track(){MediaItem i=p.getCurrentMediaItem();if(i==null)return null;MediaMetadata t=p.getMediaMetadata();String uri=i.mediaId.isEmpty()&&i.localConfiguration!=null?i.localConfiguration.uri.toString():i.mediaId;return new Track(uri,t.title==null?"제목 없는 곡":t.title.toString(),t.artist==null?"알 수 없는 아티스트":t.artist.toString(),t.albumTitle==null?"알 수 없는 앨범":t.albumTitle.toString(),"",Math.max(0,p.getDuration()),"").withGenre(t.genre==null?"":t.genre.toString());}public long position(){return p.getCurrentPosition();}public boolean playing(){NetworkPlayback n=NetworkPlayback.instance;return p.isPlaying()&&(n==null||!n.active());}public float speed(){return p.getPlaybackParameters().speed;}});p.addListener(m);return m;}
    static void checkpoint(){for(ListeningMonitor m:new ArrayList<>(live))m.sample();}
    static void resetAll(){for(ListeningMonitor m:new ArrayList<>(live)){m.clock.reset();m.track=null;m.listened=0;m.counted=false;}}
    static boolean enabled(Context c){return c.getSharedPreferences("listening",0).getBoolean("enabled",true);}
    static void enabled(Context c,boolean enabled){checkpoint();c.getSharedPreferences("listening",0).edit().putBoolean("enabled",enabled).apply();resetAll();}
    void sample(){Track next=source.track();if(next==null||!enabled(context)){clock.reset();track=null;return;}if(track==null||!track.uri.equals(next.uri)){begin(next);}else track=next;credit(source.position(),source.playing());}
    private void begin(Track t){track=t;visit=UUID.randomUUID().toString();listened=0;counted=false;speed=source.speed();clock.reset();}
    private void credit(long position,boolean active){long ms=clock.sample(SystemClock.elapsedRealtime(),Math.max(0,position),active,speed);speed=source.speed();if(ms<=0)return;listened+=ms;long threshold=track.duration>0?Math.max(1,Math.min(30000,track.duration/2)):30000;int plays=!counted&&listened>=threshold?1:0;if(plays>0)counted=true;store.append(visit,track,System.currentTimeMillis(),ms,plays);}
    @Override public void onIsPlayingChanged(boolean playing){sample();}
    @Override public void onPositionDiscontinuity(Player.PositionInfo old,Player.PositionInfo next,int reason){if(track!=null&&enabled(context))credit(old.positionMs,false);clock.reset();boolean newVisit=reason==Player.DISCONTINUITY_REASON_AUTO_TRANSITION||old.mediaItemIndex!=next.mediaItemIndex;if(newVisit)track=null;sample();}
    @Override public void onMediaItemTransition(MediaItem item,int reason){sample();}
    @Override public void onPlaybackParametersChanged(PlaybackParameters parameters){sample();}
    void close(){sample();handler.removeCallbacksAndMessages(null);live.remove(this);}
}

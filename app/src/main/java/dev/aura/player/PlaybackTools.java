package dev.aura.player;
import android.content.*;import android.os.*;import androidx.media3.common.*;import androidx.media3.exoplayer.ExoPlayer;
/** Sleep timer and A–B looping live with playback, even with the activity closed. */
@androidx.media3.common.util.UnstableApi
final class PlaybackTools implements AutoCloseable {
 static volatile PlaybackTools instance;static volatile String activeUri="";final Context c;final ExoPlayer player;final Handler handler=new Handler(Looper.getMainLooper());
 long deadline,a=-1,b=-1;String boundary="",loopTrack="";boolean fading;float fadeStart;
 String albumKey(MediaItem item){return item==null?"":String.valueOf(item.mediaMetadata.albumTitle)+"\u0000"+String.valueOf(item.mediaMetadata.albumArtist==null?item.mediaMetadata.artist:item.mediaMetadata.albumArtist);}
 void updateBoundary(){if(boundary.isEmpty())return;int next=player.getNextMediaItemIndex();boolean last=boundary.equals("track")||next<0||!albumKey(player.getMediaItemAt(next)).equals(boundary.substring(6));player.setPauseAtEndOfMediaItems(last);}
 final Player.Listener listener=new Player.Listener(){@Override public void onMediaItemTransition(MediaItem item,int reason){activeUri=item==null?"":item.mediaId;clearLoop();if(!boundary.isEmpty())updateBoundary();}@Override public void onPlayWhenReadyChanged(boolean play,int reason){if(!play&&reason==Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM&&!boundary.isEmpty())cancelSleep();}@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_ENDED&&!boundary.isEmpty()){player.pause();cancelSleep();}}};
 final Runnable tick=new Runnable(){public void run(){if(a>=0&&b>a&&player.isPlaying()&&player.getCurrentMediaItem()!=null&&loopTrack.equals(player.getCurrentMediaItem().mediaId)&&player.getCurrentPosition()>=b)player.seekTo(a);if(deadline>0){long left=deadline-SystemClock.elapsedRealtime();if(left<=0){player.pause();cancelSleep();}else if(left<=5000&&MusicCollections.prefs(c).getBoolean("sleep-fade",true)&&!AudioSettings.current.perfect()){if(!fading){fading=true;fadeStart=player.getVolume();}player.setVolume(fadeStart*(left/5000f));}}handler.postDelayed(this,a>=0?80:500);}};
 PlaybackTools(Context c,ExoPlayer p){this.c=c;player=p;instance=this;p.addListener(listener);handler.post(tick);}
 void sleepMinutes(int min){cancelSleep();deadline=SystemClock.elapsedRealtime()+min*60000L;}
 void sleepBoundary(boolean album){cancelSleep();boundary=album?"album:"+albumKey(player.getCurrentMediaItem()):"track";updateBoundary();}
 void cancelSleep(){deadline=0;boundary="";player.setPauseAtEndOfMediaItems(false);if(fading){player.setVolume(fadeStart);fading=false;}}
 String sleepLabel(){return deadline>0?"남은 시간 "+Math.max(0,(deadline-SystemClock.elapsedRealtime()+59999)/60000)+"분":boundary.equals("track")?"현재 곡 끝에 정지":boundary.isEmpty()?"꺼짐":"현재 앨범 끝에 정지";}
 void pointA(){a=Math.max(0,player.getCurrentPosition());b=-1;loopTrack=player.getCurrentMediaItem()==null?"":player.getCurrentMediaItem().mediaId;}
 boolean pointB(){long pos=player.getCurrentPosition();if(a<0||pos-a<500)return false;b=pos;return true;}
 void clearLoop(){a=b=-1;loopTrack="";}
 @Override public void close(){cancelSleep();handler.removeCallbacksAndMessages(null);player.removeListener(listener);if(instance==this){instance=null;activeUri="";}}
}

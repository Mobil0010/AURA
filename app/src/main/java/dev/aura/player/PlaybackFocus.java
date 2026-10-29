package dev.aura.player;

import android.content.Context;
import android.media.*;
import android.os.*;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlaybackFocus implements Player.Listener,AutoCloseable {
    private final ExoPlayer player;private final AudioManager manager;private final android.content.SharedPreferences prefs;private final AudioFocusRequest request;
    private boolean owned,waiting,internal,ducked;
    PlaybackFocus(Context c,ExoPlayer p){player=p;manager=c.getSystemService(AudioManager.class);prefs=c.getSharedPreferences("playback",0);request=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(this::change,new Handler(Looper.getMainLooper())).build();player.addListener(this);}
    @Override public void onPlayWhenReadyChanged(boolean play,int reason){if(internal)return;waiting=false;if(play){if(!owned){owned=manager.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;if(!owned){internal=true;player.pause();internal=false;PcmSink.status="다른 앱이 오디오를 사용하고 있습니다. 다시 재생해 주세요.";}}}else{restoreVolume();manager.abandonAudioFocusRequest(request);owned=false;}}
    private void restoreVolume(){if(ducked){player.setVolume(1);ducked=false;}}
    void change(int change){if(change==AudioManager.AUDIOFOCUS_GAIN){owned=true;restoreVolume();boolean wasWaiting=waiting;boolean resume=waiting&&prefs.getBoolean("resumeFocus",true);waiting=false;if(resume){internal=true;player.play();internal=false;}else if(wasWaiting||!player.getPlayWhenReady()){owned=false;manager.abandonAudioFocusRequest(request);}return;}
        boolean duck=change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;var c=AudioSettings.current;
        if(duck&&!PlaybackPolicy.pauseForDuck(prefs.getBoolean("pauseOnDuck",false),c.perfect(),c.mode().equals("usb"))){if(player.getPlayWhenReady()){player.setVolume(.2f);ducked=true;}return;}
        if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT||duck){waiting=change!=AudioManager.AUDIOFOCUS_LOSS&&(waiting||player.getPlayWhenReady());owned=false;internal=true;player.pause();internal=false;restoreVolume();if(change==AudioManager.AUDIOFOCUS_LOSS){owned=false;manager.abandonAudioFocusRequest(request);}}
    }
    void cancelResume(){waiting=false;manager.abandonAudioFocusRequest(request);owned=false;restoreVolume();}
    @Override public void close(){player.removeListener(this);manager.abandonAudioFocusRequest(request);}
}

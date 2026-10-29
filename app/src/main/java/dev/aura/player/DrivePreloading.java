package dev.aura.player;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/** Reuses Media3's queued samples; no second player, disk cache, or bearer-token URLs. */
@androidx.media3.common.util.UnstableApi
final class DrivePreloading implements Player.Listener {
    static final long DURATION_US=5_000_000L;
    private final ExoPlayer player;
    private final long durationUs;private boolean enabled;
    private DrivePreloading(ExoPlayer player,long durationUs){this.player=player;this.durationUs=durationUs;player.addListener(this);update();}
    static void attach(ExoPlayer player){attach(player,DURATION_US);}
    static void attach(ExoPlayer player,long durationUs){new DrivePreloading(player,durationUs);}
    @Override public void onEvents(Player player,Player.Events events){
        if(events.containsAny(Player.EVENT_TIMELINE_CHANGED,Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_POSITION_DISCONTINUITY,Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED))update();
    }
    private void update(){
        var timeline=player.getCurrentTimeline();
        int next=timeline.isEmpty()?C.INDEX_UNSET:timeline.getNextWindowIndex(player.getCurrentMediaItemIndex(),player.getRepeatMode(),player.getShuffleModeEnabled());
        boolean preload=false;
        if(next!=C.INDEX_UNSET){var item=player.getMediaItemAt(next);preload=item.localConfiguration!=null&&DriveUris.is(item.localConfiguration.uri);}
        // A seek masks the new current index before the playback thread adopts its preloaded
        // period. Keep the configuration through the final Drive item so that disabling it
        // cannot release that period during the handoff. An empty queue still disables it.
        if(next==C.INDEX_UNSET&&player.getCurrentMediaItem()!=null){
            var current=player.getCurrentMediaItem().localConfiguration;
            preload=current!=null&&DriveUris.is(current.uri);
        }
        if(enabled==preload)return;
        enabled=preload;
        player.setPreloadConfiguration(preload?new ExoPlayer.PreloadConfiguration(durationUs):ExoPlayer.PreloadConfiguration.DEFAULT);
    }
}

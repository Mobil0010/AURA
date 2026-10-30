package dev.aura.player;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackService extends MediaSessionService {
    private final java.util.concurrent.ExecutorService metadata =
            new java.util.concurrent.ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>());
    private java.util.concurrent.Future<?> metadataTask;
    private int metadataGeneration;
    private PlaybackTools tools;
    private ListeningMonitor listening;
    private ExoPlayer player;
    private PlaybackFocus focus;
    private UsbAutoRouting routing;
    private UsbVolumePlayer volumePlayer;
    private MediaSession session;
    static volatile PlaybackService instance;
    private RemoteServer remote;
    private final java.util.concurrent.ExecutorService networkCommands =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        var notificationProvider =
                new androidx.media3.session.DefaultMediaNotificationProvider(this);
        notificationProvider.setSmallIcon(R.drawable.ic_stat_aura);
        setMediaNotificationProvider(notificationProvider);
        AudioSettings.init(this);
        if (AudioSettings.current.mode().equals("usb") && !EngineMemory.usbPresent(this))
            EngineMemory.detached(this);
        UsbVolume.init(this);
        PlaybackBudget.Limits budget = PlaybackBudget.get(this);
        AuraRenderersFactory factory = new AuraRenderersFactory(this);
        player =
                new ExoPlayer.Builder(this, factory)
                        .setMediaSourceFactory(
                                new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                                        () -> new DsdDataSource(this)))
                        .setLoadControl(
                                new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                                        .setBufferDurationsMs(5000, 15000, 500, 1500)
                                        .setTargetBufferBytes(budget.audioBytes())
                                        .setPrioritizeTimeOverSizeThresholds(false)
                                        .build())
                        .setWakeMode(C.WAKE_MODE_LOCAL)
                        .build();
        DrivePreloading.attach(player, budget.preloadUs());
        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                false);
        player.addListener(
                new Player.Listener() {
                    @Override
                    public void onMediaItemTransition(
                            androidx.media3.common.MediaItem item, int reason) {
                        refreshDriveMetadata(item);
                    }
                });

        tools = new PlaybackTools(this, player);
        player.addListener(
                new Player.Listener() {
                    @Override
                    public void onMediaItemTransition(
                            androidx.media3.common.MediaItem item, int reason) {
                        ReplayGain.begin(item);
                    }

                    @Override
                    public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                        for (var group : tracks.getGroups())
                            for (int n = 0; n < group.length; n++)
                                if (group.isTrackSelected(n))
                                    ReplayGain.read(group.getTrackFormat(n).metadata);
                    }

                    @Override
                    public void onMetadata(androidx.media3.common.Metadata m) {
                        ReplayGain.read(m);
                    }

                    @Override
                    public void onEvents(Player p, Player.Events e) {
                        if (e.containsAny(
                                Player.EVENT_MEDIA_ITEM_TRANSITION,
                                Player.EVENT_IS_PLAYING_CHANGED,
                                Player.EVENT_MEDIA_METADATA_CHANGED))
                            MusicWidget.state(
                                    PlaybackService.this,
                                    player.getCurrentMediaItem(),
                                    player.isPlaying());
                    }
                });
        listening = ListeningMonitor.local(this, player);
        player.setHandleAudioBecomingNoisy(false);
        focus = new PlaybackFocus(this, player);
        routing = new UsbAutoRouting(this);
        Intent intent = new Intent(this, MainActivity.class);
        volumePlayer = new UsbVolumePlayer(player);
        UsbVolume.listener(volumePlayer::signal);
        volumePlayer.signal();
        session =
                new MediaSession.Builder(this, volumePlayer)
                        .setSessionActivity(
                                PendingIntent.getActivity(
                                        this,
                                        0,
                                        intent,
                                        PendingIntent.FLAG_IMMUTABLE
                                                | PendingIntent.FLAG_UPDATE_CURRENT))
                        .build();
        instance = this;
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controller) {
        return controller.getUid() == android.os.Process.myUid() || controller.isTrusted()
                ? session
                : null;
    }

    private void refreshDriveMetadata(androidx.media3.common.MediaItem item) {
        int generation = ++metadataGeneration;
        if (metadataTask != null) {
            metadataTask.cancel(true);
            ((java.util.concurrent.ThreadPoolExecutor) metadata).purge();
        }
        if (PlaybackBudget.get(this).constrained()
                || LibraryScanService.state.running()
                || item == null) {
            return;
        }
        boolean tagsReady =
                item.mediaMetadata.extras != null
                        && item.mediaMetadata.extras.getBoolean("auraTagsReady", false);
        if (!DriveUris.is(android.net.Uri.parse(item.mediaId)) || tagsReady) {
            return;
        }
        metadataTask = metadata.submit(() -> readDriveMetadata(item, generation));
    }

    private void readDriveMetadata(androidx.media3.common.MediaItem item, int generation) {
        try {
            Track track = new Track(item.mediaId, "", "", "", "", 0, "");
            Track updated = DriveLibrary.enrich(this, track);
            postToMain(() -> applyDriveMetadata(updated, generation));
        } catch (Exception ignored) {
            // Optional metadata must not interrupt playback.
        }
    }

    private void applyDriveMetadata(Track updated, int generation) {
        if (instance != this || generation != metadataGeneration) {
            return;
        }
        int index = player.getCurrentMediaItemIndex();
        var current = player.getCurrentMediaItem();
        if (index < 0 || current == null || !updated.uri.equals(current.mediaId)) {
            return;
        }
        var replacement = updated.item();
        if (!replacement.mediaMetadata.equals(player.getMediaMetadata())) {
            player.replaceMediaItem(index, replacement);
        }
    }

    private void postToMain(Runnable task) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
    }

    void widgetCommand(String action) {
        if (player.getMediaItemCount() == 0) {
            metadata.execute(
                    () -> {
                        java.util.List<Track> tracks = new Library(this).load();
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(
                                        () -> {
                                            if (instance != this) return;
                                            String last =
                                                    getSharedPreferences("aura", 0)
                                                            .getString("lastTrack", "");
                                            for (Track t : tracks)
                                                if (t.uri.equals(last)) {
                                                    player.setMediaItem(
                                                            t.item(),
                                                            getSharedPreferences("aura", 0)
                                                                    .getLong("lastPosition", 0));
                                                    break;
                                                }
                                            widgetAction(action);
                                        });
                    });
        } else widgetAction(action);
    }

    private void widgetAction(String action) {
        if (player.getMediaItemCount() == 0) {
            stopSelf();
            return;
        }
        if (action.equals("next")) player.seekToNextMediaItem();
        else if (action.equals("previous")) player.seekToPreviousMediaItem();
        else if (player.isPlaying()) player.pause();
        else if (OutputRouting.current(this).allowed()) {
            player.prepare();
            player.play();
        }
    }

    public void applyAudioSettings() {
        UsbVolume.settingsChanged();
        OutputRouting.requireReady(this);
        long position = Math.max(0, player.getCurrentPosition());
        boolean play = player.getPlayWhenReady();
        player.stop();
        if (!AudioSettings.current.mode().equals("usb")) UsbRoute.close();
        player.seekTo(position);
        player.prepare();
        player.setPlayWhenReady(play);
    }

    public void removeDriveQueue() {
        if (player.getCurrentMediaItem() != null
                && DriveUris.is(android.net.Uri.parse(player.getCurrentMediaItem().mediaId)))
            player.pause();
        for (int i = player.getMediaItemCount() - 1; i >= 0; i--)
            if (DriveUris.is(android.net.Uri.parse(player.getMediaItemAt(i).mediaId)))
                player.removeMediaItem(i);
    }

    public void pauseForDisconnect() {
        focus.cancelResume();
        player.pause();
    }

    public void actualOutput(android.media.AudioDeviceInfo device) {
        if (OutputDevices.bluetooth(device.getType())) BluetoothCodecMonitor.refresh(this);
        if (routing != null) routing.actualSelected(device);
        if (DeviceProfiles.apply(this, device, false))
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(this::applyAudioSettings);
    }

    public void userSelectedOutput(android.media.AudioDeviceInfo device, boolean perfect) {
        routing.systemSelected(device, perfect);
    }

    public void automaticRoute(int id, boolean pause) {
        boolean resume = PlaybackPolicy.resumeAfterDetach(player.getPlayWhenReady(), pause);
        if (pause) focus.cancelResume();
        long position = Math.max(0, player.getCurrentPosition());
        player.stop();
        if (!AudioSettings.current.mode().equals("usb")) AudioSettings.put("device", id);
        player.seekTo(position);
        if (player.getMediaItemCount() > 0 && OutputRouting.current(this).allowed())
            player.prepare();
        else if (!OutputRouting.current(this).allowed())
            PcmSink.status = "선택한 출력 엔진 유지 · DAC 연결 대기";
        player.setPlayWhenReady(resume);
    }

    public void switchUsb(android.hardware.usb.UsbDevice device, boolean perfect) {
        long position = Math.max(0, player.getCurrentPosition());
        boolean resume = player.getPlayWhenReady();
        var old = AudioSettings.current;
        player.stop();
        try {
            UsbRoute.select(this, device);
            EngineMemory.enteringUsb(this);
            getSharedPreferences("playback", 0)
                    .edit()
                    .putBoolean("preferDirectUsb", true)
                    .putBoolean("preferPerfectUsb", perfect)
                    .apply();
            AudioSettings.put("mode", "usb");
            AudioSettings.put("perfect", perfect);
            UsbVolume.settingsChanged();
            routing.directSelected();
            DeviceProfiles.apply(this, null, false);
            player.seekTo(position);
            player.prepare();
            player.setPlayWhenReady(resume);
        } catch (RuntimeException e) {
            AudioSettings.put("mode", old.mode());
            AudioSettings.put("perfect", old.perfect());
            player.seekTo(position);
            if (OutputRouting.current(this).allowed()) {
                player.prepare();
                player.setPlayWhenReady(resume);
            }
            throw e;
        }
    }

    public String enableRemote() throws Exception {
        if (remote == null) {
            RemoteServer.Endpoint endpoint =
                    new RemoteServer.Endpoint() {
                        public String status() throws Exception {
                            return remoteStatus();
                        }

                        public void command(String command) {
                            handleRemoteCommand(command);
                        }
                    };
            remote = new RemoteServer(endpoint, this::postToMain);
            remote.start();
        }
        return remote.link();
    }

    private String remoteStatus() throws Exception {
        NetworkPlayback target = NetworkPlayback.instance;
        if (target != null && target.active()) {
            Track track = target.track();
            return new org.json.JSONObject()
                    .put("title", track == null ? "" : track.title)
                    .put("artist", track == null ? "" : track.artist)
                    .put("playing", target.playing())
                    .put("position", target.position())
                    .put("duration", track == null ? 0 : track.duration)
                    .toString();
        }
        var metadata = player.getMediaMetadata();
        String title = String.valueOf(metadata.title == null ? "재생할 곡을 선택해 주세요." : metadata.title);
        String artist = String.valueOf(metadata.artist == null ? "" : metadata.artist);
        return new org.json.JSONObject()
                .put("title", title)
                .put("artist", artist)
                .put("playing", player.isPlaying())
                .put("position", player.getCurrentPosition())
                .put("duration", Math.max(0, player.getDuration()))
                .toString();
    }

    private void handleRemoteCommand(String command) {
        NetworkPlayback target = NetworkPlayback.instance;
        if (target != null && target.active()) {
            Runnable task = () -> sendNetworkCommand(target, command);
            if (target.isCast()) {
                task.run();
            } else {
                networkCommands.execute(task);
            }
            return;
        }
        if (command.startsWith("seek:")) {
            player.seekTo(Long.parseLong(command.substring(5)));
            return;
        }
        switch (command) {
            case "play" -> player.play();
            case "pause" -> player.pause();
            case "next" -> player.seekToNextMediaItem();
            case "previous" -> player.seekToPreviousMediaItem();
        }
    }

    private void sendNetworkCommand(NetworkPlayback target, String command) {
        try {
            if (command.startsWith("seek:")) {
                target.seek(Long.parseLong(command.substring(5)));
            } else {
                target.command(command);
            }
        } catch (Exception e) {
            target.lastError = e.getMessage();
        }
    }

    public String remoteLink() {
        return remote == null ? null : remote.link();
    }

    public void disableRemote() {
        if (remote != null) {
            remote.close();
            remote = null;
        }
    }

    @Override
    public void onDestroy() {
        UsbVolumePopup.hide(false);
        if (tools != null) tools.close();
        if (listening != null) {
            player.removeListener(listening);
            listening.close();
        }
        instance = null;
        UsbVolume.listener(() -> {});
        metadataGeneration++;
        if (routing != null) routing.close();
        if (focus != null) focus.close();
        disableRemote();
        networkCommands.shutdownNow();
        metadata.shutdownNow();
        if (session != null) session.release();
        if (player != null) player.release();
        UsbRoute.close();
        if (NetworkPlayback.instance != null && !NetworkPlayback.instance.relaying())
            NetworkPlayback.instance.close();
        super.onDestroy();
    }
}

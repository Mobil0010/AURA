package dev.aura.player;

import android.content.Context;
import android.net.Uri;
import com.google.android.gms.cast.*;
import com.google.android.gms.cast.framework.*;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import java.util.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class NetworkPlayback implements AutoCloseable {
    private final Context context;
    private final MediaFileServer server;
    private volatile Dlna.Device dlna;
    private volatile long dlnaPosition;
    private final java.util.concurrent.ScheduledExecutorService poll =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private RemoteMediaClient cast;
    private CastSession activeSession;
    private Track track;
    private volatile boolean rendered, relaying, closed;
    private List<Track> queue = List.of();
    private final Map<String, Uri> prepared = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService render =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler main =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int index, windowStart, windowEnd;
    private final Map<String, Integer> mediaIndices = new HashMap<>();
    public volatile String lastError = "";
    private boolean advancing;
    private final RemoteMediaClient.Callback callback =
            new RemoteMediaClient.Callback() {
                @Override
                public void onStatusUpdated() {
                    if (cast == null || cast.getMediaStatus() == null) return;
                    if (cast.getMediaStatus().getIdleReason() == MediaStatus.IDLE_REASON_ERROR) {
                        lastError = "캐스트 음원을 읽지 못했습니다. Drive 연결과 수신 기기의 지원 형식을 확인해 주세요.";
                        disconnect();
                        return;
                    }
                    syncCastTrack();
                    if (cast.isPlaying()) advancing = false;
                    if (relaying
                            && !advancing
                            && cast.getMediaStatus().getPlayerState()
                                    == MediaStatus.PLAYER_STATE_IDLE
                            && cast.getMediaStatus().getIdleReason()
                                    == MediaStatus.IDLE_REASON_FINISHED
                            && index + 1 < queue.size()) loadAdjacent(1);
                }
            };
    private volatile boolean playing;
    public static volatile NetworkPlayback instance;

    public NetworkPlayback(Context c) throws Exception {
        context = c.getApplicationContext();
        server = new MediaFileServer(c);
        instance = this;
        try {
            castManager = CastContext.getSharedInstance(context).getSessionManager();
            castManager.addSessionManagerListener(sessions, CastSession.class);
        } catch (Exception unavailable) {
        }
        poll.scheduleWithFixedDelay(
                () -> {
                    Dlna.Device device = dlna;
                    if (device != null)
                        try {
                            var state =
                                    Dlna.xml(Dlna.command(device, "GetTransportInfo", Map.of()));
                            String transport =
                                    Dlna.tag(state.getDocumentElement(), "CurrentTransportState");
                            playing = transport.equals("PLAYING");
                            var position =
                                    Dlna.xml(Dlna.command(device, "GetPositionInfo", Map.of()));
                            String[] parts =
                                    Dlna.tag(position.getDocumentElement(), "RelTime").split(":");
                            if (parts.length == 3)
                                dlnaPosition =
                                        (long)
                                                ((Double.parseDouble(parts[0]) * 3600
                                                                + Double.parseDouble(parts[1]) * 60
                                                                + Double.parseDouble(parts[2]))
                                                        * 1000);
                        } catch (Exception ignored) {
                        }
                },
                1,
                2,
                java.util.concurrent.TimeUnit.SECONDS);
    }

    private SessionManager castManager;
    private final SessionManagerListener<CastSession> sessions =
            new SessionManagerListener<>() {
                public void onSessionStarting(CastSession s) {}

                public void onSessionStarted(CastSession s, String id) {}

                public void onSessionStartFailed(CastSession s, int error) {}

                public void onSessionEnding(CastSession s) {}

                public void onSessionEnded(CastSession s, int error) {
                    if (cast != null && s == activeSession) disconnect();
                }

                public void onSessionResuming(CastSession s, String id) {}

                public void onSessionResumed(CastSession s, boolean suspended) {}

                public void onSessionResumeFailed(CastSession s, int error) {
                    if (cast != null) disconnect();
                }

                public void onSessionSuspended(CastSession s, int reason) {
                    if (cast != null && s == activeSession) {
                        lastError = "Cast 연결이 중단되었습니다. 다시 연결해 주세요.";
                        disconnect();
                    }
                }
            };

    public void setCast(CastSession session) {
        if (session == null) {
            disconnect();
            return;
        }
        activeSession = session;
        RemoteMediaClient next = session.getRemoteMediaClient();
        if (next != cast) {
            if (cast != null) cast.unregisterCallback(callback);
            cast = next;
            if (cast != null) cast.registerCallback(callback);
        }
        if (cast != null) dlna = null;
    }

    public boolean active() {
        return cast != null && cast.hasMediaSession() || dlna != null;
    }

    public void prepare(Track t) throws Exception {
        Uri result = t.drive() ? Uri.parse(t.uri) : OfflineRender.prepare(context, t);
        prepared.put(t.uri, result);
        rendered = !result.equals(Uri.parse(t.uri));
    }

    private Uri source(Track t) {
        return prepared.getOrDefault(t.uri, Uri.parse(t.uri));
    }

    private boolean processed() {
        return rendered
                || queue.stream()
                        .anyMatch(
                                t ->
                                        !t.drive()
                                                && !(AudioSettings.current.perfect()
                                                        || !AudioSettings.dsp(AudioSettings.current)
                                                                && AudioSettings.current.bits()
                                                                        == 0));
    }

    private String mime(Track t) {
        return source(t).equals(Uri.parse(t.uri))
                ? MediaTypes.of(Uri.parse(t.uri), t.mime)
                : "audio/wav";
    }

    public boolean relaying() {
        return relaying;
    }

    public boolean closed() {
        return closed;
    }

    private void relay() {
        relaying = true;
        CastRelayService.start(context);
    }

    public void queue(List<Track> tracks, Track current) {
        server.clear();
        prepared.clear();
        List<Track> copy = new ArrayList<>(tracks);
        int at = -1;
        for (int i = 0; i < copy.size(); i++)
            if (copy.get(i).uri.equals(current.uri)) {
                at = i;
                break;
            }
        if (at < 0) {
            copy.add(0, current);
            at = 0;
        }
        queue = List.copyOf(copy);
        index = at;
    }

    MediaQueueItem[] castItems(Track current) throws Exception {
        windowStart = processed() ? index : Math.max(0, index - 3);
        windowEnd = processed() ? index + 1 : Math.min(queue.size(), index + 17);
        List<Track> items = processed() ? List.of(current) : queue.subList(windowStart, windowEnd);
        mediaIndices.clear();
        List<MediaQueueItem> out = new ArrayList<>();
        for (Track t : items) {
            MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
            md.putString(MediaMetadata.KEY_TITLE, t.title);
            md.putString(MediaMetadata.KEY_ARTIST, t.artist);
            md.putString(MediaMetadata.KEY_ALBUM_TITLE, t.album);
            String address = server.url(source(t), mime(t));
            mediaIndices.put(address, windowStart + out.size());
            MediaInfo.Builder info =
                    new MediaInfo.Builder(address)
                            .setContentType(mime(t))
                            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                            .setMetadata(md);
            if (t.duration > 0) info.setStreamDuration(t.duration);
            out.add(new MediaQueueItem.Builder(info.build()).setAutoplay(true).build());
        }
        return out.toArray(new MediaQueueItem[0]);
    }

    public void cast(Track t) throws Exception {
        CastSession session =
                CastContext.getSharedInstance(context).getSessionManager().getCurrentCastSession();
        if (session == null) throw new IllegalStateException("먼저 Cast 기기를 연결해 주세요.");
        setCast(session);
        track = t;
        if (queue.isEmpty()) queue = List.of(t);
        MediaQueueItem[] items = castItems(t);
        relay();
        cast.queueLoad(
                        items,
                        processed() ? 0 : index - windowStart,
                        MediaStatus.REPEAT_MODE_REPEAT_OFF,
                        null)
                .setResultCallback(
                        result -> {
                            if (!result.getStatus().isSuccess()) {
                                lastError = "Cast 응답: " + result.getStatus().getStatusCode();
                                relaying = false;
                                CastRelayService.finish(context);
                            } else CastRelayService.refresh();
                        });
        playing = true;
    }

    public void dlna(Dlna.Device device, Track t) throws Exception {
        Dlna.load(device, server.url(source(t), mime(t)), mime(t), t.title);
        dlna = device;
        cast = null;
        track = t;
        playing = true;
        relay();
    }

    public void command(String action) throws Exception {
        if (cast != null) {
            switch (action) {
                case "play" -> cast.play();
                case "pause" -> cast.pause();
                case "stop" -> cast.stop();
                case "next", "previous" -> {
                    if (processed()
                            || action.equals("next") && index + 1 >= windowEnd
                            || action.equals("previous") && index <= windowStart)
                        loadAdjacent(action.equals("next") ? 1 : -1);
                    else if (action.equals("next")) cast.queueNext(null);
                    else cast.queuePrev(null);
                }
                default -> throw new IllegalArgumentException("지원하지 않는 명령");
            }
        } else if (dlna != null) {
            switch (action) {
                case "play" -> Dlna.command(dlna, "Play", Map.of("Speed", "1"));
                case "pause" -> Dlna.command(dlna, "Pause", Map.of());
                case "stop" -> Dlna.command(dlna, "Stop", Map.of());
                case "next", "previous" -> {
                    if (!queue.isEmpty()) {
                        index =
                                Math.max(
                                        0,
                                        Math.min(
                                                queue.size() - 1,
                                                index + (action.equals("next") ? 1 : -1)));
                        track = queue.get(index);
                        prepare(track);
                        Dlna.load(
                                dlna,
                                server.url(source(track), mime(track)),
                                mime(track),
                                track.title);
                    }
                }
            }
        }
        playing = action.equals("play") || action.equals("next") || action.equals("previous");
        CastRelayService.refresh();
    }

    private void loadAdjacent(int step) {
        if (queue.isEmpty()) return;
        advancing = true;
        index = Math.max(0, Math.min(queue.size() - 1, index + step));
        Track next = queue.get(index);
        render.execute(
                () -> {
                    try {
                        prepare(next);
                        main.post(
                                () -> {
                                    try {
                                        cast(next);
                                    } catch (Exception e) {
                                        lastError = e.getMessage();
                                    }
                                });
                    } catch (Exception e) {
                        lastError = e.getMessage();
                    }
                });
    }

    public void seek(long ms) throws Exception {
        if (cast != null) cast.seek(new MediaSeekOptions.Builder().setPosition(ms).build());
        else if (dlna != null)
            Dlna.command(
                    dlna,
                    "Seek",
                    Map.of(
                            "Unit",
                            "REL_TIME",
                            "Target",
                            String.format(
                                    Locale.ROOT,
                                    "%02d:%02d:%02d",
                                    ms / 3600000,
                                    ms / 60000 % 60,
                                    ms / 1000 % 60)));
    }

    public boolean playing() {
        return cast != null ? cast.isPlaying() : playing;
    }

    public String label() {
        return cast != null ? "Google Cast" : dlna == null ? "" : dlna.name();
    }

    public boolean isCast() {
        return cast != null;
    }

    public long position() {
        return cast == null ? dlnaPosition : cast.getApproximateStreamPosition();
    }

    private void syncCastTrack() {
        if (cast != null && cast.getMediaInfo() != null) {
            Integer at = mediaIndices.get(cast.getMediaInfo().getContentId());
            if (at != null && at >= 0 && at < queue.size()) {
                index = at;
                track = queue.get(at);
            }
        }
    }

    public Track track() {
        syncCastTrack();
        return track;
    }

    public void disconnect() {
        relaying = false;
        CastRelayService.finish(context);
        server.clear();
        if (cast != null) {
            try {
                if (cast.hasMediaSession()) cast.stop();
            } catch (RuntimeException ignored) {
            }
            cast.unregisterCallback(callback);
        }
        Dlna.Device previous = dlna;
        if (previous != null) {
            Thread stop =
                    new Thread(
                            () -> {
                                try {
                                    Dlna.command(previous, "Stop", Map.of());
                                } catch (Exception ignored) {
                                }
                            },
                            "AURA renderer stop");
            stop.setDaemon(true);
            stop.start();
        }
        advancing = false;
        cast = null;
        activeSession = null;
        dlna = null;
        track = null;
        playing = false;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (castManager != null)
            main.post(() -> castManager.removeSessionManagerListener(sessions, CastSession.class));
        poll.shutdownNow();
        render.shutdownNow();
        server.close();
        disconnect();
        if (instance == this) instance = null;
    }
}

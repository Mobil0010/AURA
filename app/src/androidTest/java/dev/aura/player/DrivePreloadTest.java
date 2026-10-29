package dev.aura.player;

import static org.junit.Assert.*;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class DrivePreloadTest {
    private final Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void settings() {
        AudioSettings.init(c);
        for (String k : List.of("perfect", "sox", "eq", "limiter")) AudioSettings.put(k, false);
        AudioSettings.put("mode", "android");
        AudioSettings.put("rate", 0);
        AudioSettings.put("bits", 0);
        AudioSettings.put("device", 0);
    }

    @Test
    public void nextDriveTrackIsBufferedBeforeTransitionAndReusesSamples() throws Exception {
        try (DriveTest.FakeDrive server = new DriveTest.FakeDrive()) {
            var bytes = new ConcurrentHashMap<String, AtomicLong>();
            ExoPlayer player = player(server, bytes);
            try {
                main(
                        () -> {
                            player.setMediaItems(List.of(item("current"), item("next")));
                            player.prepare();
                            player.play();
                            return null;
                        });
                await(
                        () ->
                                bytes.getOrDefault("next", new AtomicLong()).get() > 700000
                                        && main(player::getCurrentPosition) > 200,
                        15000);
                assertEquals(
                        "Next track must load while the first is still playing",
                        0,
                        main(player::getCurrentMediaItemIndex).intValue());
                assertEquals(
                        DrivePreloading.DURATION_US,
                        main(() -> player.getPreloadConfiguration().targetPreloadDurationUs)
                                .longValue());
                Thread.sleep(350);
                int before = starts(server, "next");
                assertEquals(1, before);
                main(
                        () -> {
                            player.seekToNextMediaItem();
                            return null;
                        });
                await(
                        () ->
                                main(player::getCurrentMediaItemIndex) == 1
                                        && main(player::getCurrentPosition) > 300,
                        10000);
                assertNull(main(player::getPlayerError));
                assertEquals(
                        "Preloaded beginning must not be downloaded again",
                        before,
                        starts(server, "next"));
                assertEquals(
                        DrivePreloading.DURATION_US,
                        main(() -> player.getPreloadConfiguration().targetPreloadDurationUs)
                                .longValue());
                for (var request : server.requests)
                    assertTrue(DriveTest.FakeDrive.rangeLength(request) <= DriveDataSource.WINDOW);
            } finally {
                main(
                        () -> {
                            player.release();
                            return null;
                        });
            }
        }
    }

    @Test
    public void drivePlaylistAdvancesAutomaticallyUsingAlreadyLoadedNextTrack() throws Exception {
        try (DriveTest.FakeDrive server = new DriveTest.FakeDrive(176400 * 3 + 44)) {
            var bytes = new ConcurrentHashMap<String, AtomicLong>();
            ExoPlayer player = player(server, bytes);
            try {
                main(
                        () -> {
                            player.setMediaItems(List.of(item("current"), item("next")));
                            player.prepare();
                            player.play();
                            return null;
                        });
                await(
                        () ->
                                bytes.getOrDefault("next", new AtomicLong()).get() > 100000
                                        && main(player::getCurrentPosition) > 100,
                        10000);
                assertEquals(0, main(player::getCurrentMediaItemIndex).intValue());
                int before = starts(server, "next");
                assertEquals(1, before);
                await(
                        () ->
                                main(player::getCurrentMediaItemIndex) == 1
                                        && main(player::getCurrentPosition) > 250,
                        10000);
                assertNull(main(player::getPlayerError));
                assertEquals(before, starts(server, "next"));
            } finally {
                main(
                        () -> {
                            player.release();
                            return null;
                        });
            }
        }
    }

    @Test
    public void queueReplacementDropsOldPreloadAndSelectsNewNextTrack() throws Exception {
        try (DriveTest.FakeDrive server = new DriveTest.FakeDrive()) {
            var bytes = new ConcurrentHashMap<String, AtomicLong>();
            ExoPlayer player = player(server, bytes);
            try {
                main(
                        () -> {
                            player.setMediaItems(List.of(item("current"), item("removed")));
                            player.prepare();
                            player.play();
                            return null;
                        });
                await(() -> bytes.getOrDefault("removed", new AtomicLong()).get() > 700000, 15000);
                main(
                        () -> {
                            player.replaceMediaItem(1, item("replacement"));
                            return null;
                        });
                await(
                        () -> bytes.getOrDefault("replacement", new AtomicLong()).get() > 700000,
                        10000);
                main(
                        () -> {
                            player.seekToNextMediaItem();
                            return null;
                        });
                await(() -> main(player::getCurrentPosition) > 200, 10000);
                assertEquals(
                        "replacement",
                        DriveUris.fileId(
                                main(() -> player.getCurrentMediaItem().localConfiguration.uri)));
                assertNull(main(player::getPlayerError));
                main(
                        () -> {
                            player.clearMediaItems();
                            return null;
                        });
                await(
                        () ->
                                main(() -> player.getPreloadConfiguration().targetPreloadDurationUs)
                                        == C.TIME_UNSET,
                        3000);
            } finally {
                main(
                        () -> {
                            player.release();
                            return null;
                        });
            }
        }
    }

    private ExoPlayer player(
            DriveTest.FakeDrive server, ConcurrentHashMap<String, AtomicLong> bytes)
            throws Exception {
        return main(
                () -> {
                    var http =
                            new DriveHttp(
                                    server.base(),
                                    new DriveHttp.Tokens() {
                                        public String token() {
                                            return "fake-access";
                                        }

                                        public void invalidate(String t) {}
                                    });
                    DataSource.Factory sources =
                            () -> {
                                var source = new DriveDataSource(http, u -> true);
                                source.addTransferListener(
                                        new TransferListener() {
                                            public void onTransferInitializing(
                                                    DataSource s, DataSpec d, boolean n) {}

                                            public void onTransferStart(
                                                    DataSource s, DataSpec d, boolean n) {}

                                            public void onTransferEnd(
                                                    DataSource s, DataSpec d, boolean n) {}

                                            public void onBytesTransferred(
                                                    DataSource s,
                                                    DataSpec d,
                                                    boolean n,
                                                    int count) {
                                                bytes.computeIfAbsent(
                                                                d.uri.getLastPathSegment(),
                                                                k -> new AtomicLong())
                                                        .addAndGet(count);
                                            }
                                        });
                                return source;
                            };
                    var renderers =
                            new DefaultRenderersFactory(c) {
                                @Override
                                protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                                        Context context, boolean f, boolean p) {
                                    return new PcmSink(context);
                                }
                            };
                    var player =
                            new ExoPlayer.Builder(c, renderers)
                                    .setLoadControl(
                                            new DefaultLoadControl.Builder()
                                                    .setBufferDurationsMs(1000, 3000, 250, 500)
                                                    .build())
                                    .setMediaSourceFactory(new DefaultMediaSourceFactory(sources))
                                    .build();
                    DrivePreloading.attach(player);
                    return player;
                });
    }

    private MediaItem item(String id) throws Exception {
        return new MediaItem.Builder()
                .setUri(DriveUris.file("account", id, id + ".wav"))
                .setMimeType("audio/wav")
                .build();
    }

    private int starts(DriveTest.FakeDrive server, String id) {
        return (int)
                server.requests.stream()
                        .filter(
                                r ->
                                        r.path().contains("/files/" + id + "?")
                                                && DriveTest.FakeDrive.rangeStart(r) == 0)
                        .count();
    }

    private void await(Callable<Boolean> condition, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!condition.call() && SystemClock.elapsedRealtime() < end) Thread.sleep(40);
        assertTrue("Playback/preloading timed out", condition.call());
    }

    private <T> T main(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(future);
        return future.get(20, TimeUnit.SECONDS);
    }
}

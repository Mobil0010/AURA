package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class ListeningStatsTest {
    final android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    final Context c = inst.getTargetContext();
    ListeningStore store;
    MainActivity activity;
    String theme;
    boolean scan;

    @Before
    public void setup() {
        theme = c.getSharedPreferences("aura", 0).getString("appearance", "dark");
        scan = c.getSharedPreferences("aura", 0).getBoolean("scanOnLaunch", false);
        c.getSharedPreferences("aura", 0).edit().putBoolean("scanOnLaunch", false).commit();
        c.deleteDatabase("stats-tests.db");
        store = new ListeningStore(c, "stats-tests.db");
    }

    @After
    public void cleanup() throws Exception {
        if (activity != null)
            main(
                    () -> {
                        activity.finish();
                        return null;
                    });
        store.io.shutdown();
        store.io.awaitTermination(10, TimeUnit.SECONDS);
        store.close();
        c.deleteDatabase("stats-tests.db");
        c.getSharedPreferences("aura", 0)
                .edit()
                .putString("appearance", theme)
                .putBoolean("scanOnLaunch", scan)
                .commit();
    }

    @Test
    public void monotonicClockRejectsPauseBufferSeekAndDelayedCallbacks() {
        ListeningClock clock = new ListeningClock();
        assertEquals(0, clock.sample(0, 0, true, 1));
        assertEquals(5000, clock.sample(5000, 5000, true, 1));
        assertEquals(1000, clock.sample(6000, 6000, false, 1));
        assertEquals(0, clock.sample(11000, 6000, false, 1));
        assertEquals(0, clock.sample(12000, 6000, true, 1));
        assertEquals(0, clock.sample(17000, 90000, true, 1));
        assertEquals(5000, clock.sample(22000, 95000, true, 1));
        assertEquals(0, clock.sample(27000, 95000, true, 1));
        assertEquals(0, clock.sample(60000, 128000, true, 1));
        clock.reset();
        assertEquals(0, clock.sample(61000, 0, true, 2));
        assertEquals(5000, clock.sample(66000, 10000, true, 2));
    }

    Track track(int n, String genre) {
        return new Track(
                        "file:///stats-" + n + ".flac",
                        new String[] {
                                    "Blue Hour",
                                    "Soft Landing",
                                    "Afterglow",
                                    "Moonlit Walk",
                                    "Slow Motion",
                                    "City Lights"
                                }
                                [n % 6],
                        n % 2 == 0 ? "AURA Sessions" : "Night Archive",
                        n % 3 == 0 ? "Blue Hour" : "Nightfall",
                        "",
                        210000,
                        "")
                .withGenre(genre);
    }

    @Test
    public void minuteBucketsSplitMidnightAndFilterAndSortAccurately() {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        LocalDate date = LocalDate.of(2026, 10, 27);
        long midnight = date.atStartOfDay(zone).toInstant().toEpochMilli();
        store.write("visit", track(0, "Jazz"), midnight + 15000, 30000, 1);
        var before =
                store.query(
                        ListeningStore.Range.of(0, date.minusDays(1), zone),
                        0,
                        false,
                        "",
                        20,
                        zone);
        var after = store.query(ListeningStore.Range.of(0, date, zone), 0, false, "", 20, zone);
        assertEquals(15000, before.ms());
        assertEquals(0, before.plays());
        assertEquals(15000, after.ms());
        assertEquals(1, after.plays());
        store.write("visit2", track(1, "Rock"), midnight + 90000, 60000, 1);
        store.write("visit3", track(2, "Jazz"), midnight + 180000, 45000, 1);
        var jazz = store.query(ListeningStore.Range.of(0, date, zone), 0, false, "Jazz", 20, zone);
        assertEquals(60000, jazz.ms());
        assertEquals(2, jazz.songs());
        assertEquals("Afterglow", jazz.ranks().get(0).title());
        var artists = store.query(ListeningStore.Range.of(0, date, zone), 1, false, "", 20, zone);
        assertEquals(2, artists.ranks().size());
        assertEquals(120000, artists.ms());
        assertEquals(120000, Arrays.stream(artists.bars()).sum());
        assertEquals(
                1,
                store.query(ListeningStore.Range.of(0, date, zone), 0, false, "", 1, 1, zone)
                        .ranks()
                        .size());
        var selected =
                store.query(ListeningStore.Range.of(0, date, zone), 0, false, "Jazz", 20, zone)
                        .ranks()
                        .get(0);
        var detail =
                store.detail(ListeningStore.Range.of(0, date, zone), 0, selected, "Jazz", zone);
        assertEquals(45000, detail.ms());
        assertEquals(1, detail.days());
        assertEquals(midnight + 135000, detail.first());
        assertEquals(midnight + 180000, detail.last());
        assertEquals("0시간 00분 45초", StatsUi.exact(detail.ms()));
    }

    @Test
    public void calendarRangesHandleDstMondayAndMonthBoundaries() {
        ZoneId ny = ZoneId.of("America/New_York");
        var spring = ListeningStore.Range.of(0, LocalDate.of(2026, 3, 8), ny);
        assertEquals(23 * 3600000L, spring.end() - spring.start());
        var autumn = ListeningStore.Range.of(0, LocalDate.of(2026, 11, 1), ny);
        assertEquals(25 * 3600000L, autumn.end() - autumn.start());
        assertEquals(
                LocalDate.of(2026, 10, 26),
                ListeningStore.Range.of(1, LocalDate.of(2026, 10, 27), ny).date());
        assertEquals(
                LocalDate.of(2026, 2, 1),
                ListeningStore.Range.of(2, LocalDate.of(2026, 2, 28), ny).date());
    }

    @Test
    public void genreSurvivesTrackJsonAndQueueMetadata() throws Exception {
        Track a =
                track(1, "Jazz")
                        .withNumbers(2, 1)
                        .withPath(List.of(new FolderTree.Part("r", "Music")));
        Track b = Track.from(a.json());
        assertEquals("Jazz", b.genre);
        assertEquals("Jazz", b.item().mediaMetadata.genre);
        assertEquals("Jazz", b.withNumbers(3, 1).genre);
        assertEquals("Jazz", b.withPath(List.of()).genre);
    }

    @Test
    public void realBackgroundPlaybackCountsRepeatButNotPauseOrSeek() throws Exception {
        boolean previous = ListeningMonitor.enabled(c);
        File tone = new File(c.getCacheDir(), "stats-real-tone.wav");
        try (var in = inst.getContext().getAssets().open("tone.wav");
                var out = new FileOutputStream(tone)) {
            out.write(Io.read(in));
        }
        androidx.media3.exoplayer.ExoPlayer[] player = {null};
        ListeningMonitor[] monitor = {null};
        try {
            main(
                    () -> {
                        if (PlaybackService.instance != null) {
                            var f = PlaybackService.class.getDeclaredField("player");
                            f.setAccessible(true);
                            ((androidx.media3.common.Player) f.get(PlaybackService.instance))
                                    .pause();
                        }
                        ListeningMonitor.enabled(c, true);
                        player[0] = new androidx.media3.exoplayer.ExoPlayer.Builder(c).build();
                        monitor[0] = ListeningMonitor.local(c, player[0]);
                        field(monitor[0], "store", store);
                        var item =
                                new androidx.media3.common.MediaItem.Builder()
                                        .setMediaId(tone.toURI().toString())
                                        .setUri(android.net.Uri.fromFile(tone))
                                        .setMediaMetadata(
                                                new androidx.media3.common.MediaMetadata.Builder()
                                                        .setTitle("Actual audio")
                                                        .setArtist("AURA")
                                                        .setAlbumTitle("Test")
                                                        .setGenre("Test")
                                                        .build())
                                        .build();
                        player[0].setMediaItem(item);
                        player[0].setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
                        player[0].prepare();
                        player[0].play();
                        return null;
                    });
            await(() -> main(() -> player[0].isPlaying()), 8000);
            Thread.sleep(7600);
            main(
                    () -> {
                        player[0].pause();
                        ListeningMonitor.checkpoint();
                        return null;
                    });
            store.io.submit(() -> {}).get(10, TimeUnit.SECONDS);
            var all = ListeningStore.Range.of(3, LocalDate.now(), ZoneId.systemDefault());
            var before = store.query(all, 0, false, "Test", 20, ZoneId.systemDefault());
            assertTrue("Actual decoded audio must be counted", before.ms() > 5000);
            assertTrue("Natural repeats must count separately", before.plays() >= 2);
            Thread.sleep(1500);
            main(
                    () -> {
                        player[0].seekTo(500);
                        ListeningMonitor.checkpoint();
                        return null;
                    });
            store.io.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertEquals(
                    before.ms(),
                    store.query(all, 0, false, "Test", 20, ZoneId.systemDefault()).ms());
            main(
                    () -> {
                        ListeningMonitor.enabled(c, false);
                        player[0].play();
                        return null;
                    });
            Thread.sleep(1800);
            main(
                    () -> {
                        ListeningMonitor.checkpoint();
                        player[0].pause();
                        return null;
                    });
            store.io.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertEquals(
                    before.ms(),
                    store.query(all, 0, false, "Test", 20, ZoneId.systemDefault()).ms());
        } finally {
            main(
                    () -> {
                        if (monitor[0] != null) {
                            player[0].removeListener(monitor[0]);
                            monitor[0].close();
                        }
                        if (player[0] != null) player[0].release();
                        ListeningMonitor.enabled(c, previous);
                        return null;
                    });
        }
    }

    void fixture() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        for (int day = 0; day < 40; day++)
            for (int n = 0; n < 6; n++) {
                long time =
                        today.minusDays(day)
                                .atTime(12 + n, 0)
                                .atZone(zone)
                                .toInstant()
                                .toEpochMilli();
                for (int play = 0; play < (6 - n) + (day % 3); play++)
                    store.write(
                            "design-" + day + "-" + n + "-" + play,
                            track(n, n % 3 == 0 ? "Jazz" : n % 3 == 1 ? "Indie Pop" : "Electronic"),
                            time + play * 240000L,
                            180000 + n * 5000L,
                            1);
            }
    }

    @Test
    public void statisticsScreensUseActualActivityBothThemesAndReachableNavigation()
            throws Exception {
        fixture();
        for (String mode : List.of("dark", "light")) {
            c.getSharedPreferences("aura", 0).edit().putString("appearance", mode).commit();
            activity =
                    (MainActivity)
                            inst.startActivitySync(
                                    new Intent(c, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(
                    () ->
                            main(
                                    () ->
                                            get(activity, "player") != null
                                                    && !(Boolean) get(activity, "libraryLoading")),
                    10000);
            StatsUi ui = (StatsUi) main(() -> get(activity, "statsUi"));
            main(
                    () -> {
                        field(ui, "store", store);
                        go("stats");
                        return null;
                    });
            await(
                    () ->
                            main(
                                    () ->
                                            activity.findViewById(android.R.id.content)
                                                            .findViewWithTag("stats-total")
                                                    != null),
                    5000);
            Thread.sleep(400);
            capture("stats-week-" + mode);
            var detailRank =
                    store.query(
                                    ListeningStore.Range.of(
                                            1, LocalDate.now(), ZoneId.systemDefault()),
                                    0,
                                    false,
                                    "",
                                    20,
                                    ZoneId.systemDefault())
                            .ranks()
                            .get(0);
            main(
                    () -> {
                        var method =
                                StatsUi.class.getDeclaredMethod(
                                        "details", ListeningStore.Rank.class, int.class);
                        method.setAccessible(true);
                        method.invoke(ui, detailRank, 0);
                        return null;
                    });
            Thread.sleep(500);
            capture("stats-detail-" + mode);
            inst.getUiAutomation()
                    .performGlobalAction(
                            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            Thread.sleep(200);
            main(
                    () -> {
                        field(ui, "period", 2);
                        field(ui, "category", 1);
                        go("stats");
                        return null;
                    });
            await(
                    () ->
                            main(
                                    () ->
                                            activity.findViewById(android.R.id.content)
                                                            .findViewWithTag("stats-total")
                                                    != null),
                    5000);
            Thread.sleep(400);
            main(
                    () -> {
                        View value =
                                activity.findViewById(android.R.id.content)
                                        .findViewWithTag("stats-rank-duration");
                        assertNotNull(value);
                        assertTrue(
                                "Ranking durations must remain readable",
                                value.getWidth() > Ui.dp(activity, 50));
                        return null;
                    });
            capture("stats-month-" + mode);
            main(
                    () -> {
                        PlayerScrollView scroll = (PlayerScrollView) get(activity, "scroll");
                        scroll.scrollTo(0, Ui.dp(activity, 480));
                        return null;
                    });
            Thread.sleep(350);
            capture("stats-artists-" + mode);
            main(
                    () -> {
                        field(ui, "period", 3);
                        field(ui, "category", 3);
                        go("stats");
                        return null;
                    });
            await(
                    () ->
                            main(
                                    () ->
                                            activity.findViewById(android.R.id.content)
                                                            .findViewWithTag("stats-total")
                                                    != null),
                    5000);
            main(
                    () -> {
                        ((PlayerScrollView) get(activity, "scroll")).fullScroll(View.FOCUS_DOWN);
                        return null;
                    });
            Thread.sleep(350);
            capture("stats-genres-" + mode);
            main(
                    () -> {
                        LinearLayout nav = (LinearLayout) get(activity, "nav");
                        assertEquals(5, nav.getChildCount());
                        assertTrue(nav.getChildAt(3).isSelected());
                        assertTrue(nav.getChildAt(4).getWidth() > 0);
                        nav.getChildAt(4).performClick();
                        assertEquals("settings", get(activity, "page"));
                        activity.finish();
                        return null;
                    });
            activity = null;
        }
    }

    @Test
    public void emptyStatisticsNeverInventsListeningHistory() throws Exception {
        var d =
                store.query(
                        ListeningStore.Range.of(3, LocalDate.now(), ZoneId.systemDefault()),
                        0,
                        false,
                        "",
                        20,
                        ZoneId.systemDefault());
        assertEquals(0, d.ms());
        assertEquals(0, d.plays());
        assertTrue(d.ranks().isEmpty());
    }

    void capture(String name) throws Exception {
        Bitmap image = inst.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (var out = new FileOutputStream(new File(c.getExternalFilesDir(null), name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        image.recycle();
    }

    void go(String page) throws Exception {
        var m = MainActivity.class.getDeclaredMethod("go", String.class);
        m.setAccessible(true);
        m.invoke(activity, page);
    }

    Object get(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    void field(Object o, String name, Object value) throws Exception {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        inst.runOnMainSync(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    void await(Callable<Boolean> condition, long timeout) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.call() && android.os.SystemClock.elapsedRealtime() < end)
            Thread.sleep(50);
        assertTrue(condition.call());
    }
}

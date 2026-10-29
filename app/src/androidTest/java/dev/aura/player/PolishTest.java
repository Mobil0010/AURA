package dev.aura.player;

import static org.junit.Assert.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import androidx.media3.common.*;
import androidx.media3.session.MediaController;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

@RunWith(AndroidJUnit4.class)
public class PolishTest {
    private final android.app.Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private MainActivity activity;
    private LibraryScanService.State scan;

    @Before
    public void launch() throws Exception {
        scan = LibraryScanService.state;
        AudioSettings.init(context);
        AudioSettings.put("mode", "android");
        AudioSettings.put("perfect", false);
        AudioSettings.put("device", 0);
        activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        idle();
        long end = SystemClock.elapsedRealtime() + 10000;
        while (main(() -> get("player")) == null && SystemClock.elapsedRealtime() < end)
            Thread.sleep(40);
        main(
                () -> {
                    set("tracks", tracks());
                    set("libraryLoading", false);
                    set("query", "");
                    set("source", "all");
                    set("tab", "곡");
                    return null;
                });
    }

    @After
    public void close() throws Exception {
        LibraryScanService.state = scan;
        main(
                () -> {
                    activity.finish();
                    return null;
                });
    }

    private List<Track> tracks() {
        List<Track> result = new ArrayList<>();
        for (int i = 0; i < 80; i++)
            result.add(
                    new Track(
                            "content://polish/" + i,
                            String.format(Locale.ROOT, "Song %02d", i),
                            "Artist",
                            "Album",
                            "Music",
                            1000,
                            ""));
        return result;
    }

    @Test
    public void browsingRestoresListPositionAcrossGroupsAndTabs() throws Exception {
        main(
                () -> {
                    go("library");
                    return null;
                });
        idle();
        RecyclerView first = main(this::list);
        main(
                () -> {
                    first.scrollToPosition(45);
                    return null;
                });
        idle();
        int before =
                main(
                        () ->
                                ((LinearLayoutManager) first.getLayoutManager())
                                        .findFirstVisibleItemPosition());
        assertTrue("List did not scroll", before > 0);
        main(
                () -> {
                    set("group", "Album");
                    set("groupTracks", tracks());
                    go("group");
                    return null;
                });
        idle();
        main(
                () -> {
                    activity.getOnBackPressedDispatcher().onBackPressed();
                    return null;
                });
        idle();
        assertEquals(
                before,
                main(() ->
                                ((LinearLayoutManager) list().getLayoutManager())
                                        .findFirstVisibleItemPosition())
                        .intValue());
        main(
                () -> {
                    set("tab", "폴더");
                    go("library");
                    return null;
                });
        idle();
        main(
                () -> {
                    set("tab", "곡");
                    go("library");
                    return null;
                });
        idle();
        assertEquals(
                before,
                main(() ->
                                ((LinearLayoutManager) list().getLayoutManager())
                                        .findFirstVisibleItemPosition())
                        .intValue());
    }

    @Test
    public void searchTrimsSpacesClearsAndExplainsMissingDriveSource() throws Exception {
        main(
                () -> {
                    set("query", "  Song 00  ");
                    go("library");
                    return null;
                });
        idle();
        assertEquals(1, main(() -> list().getAdapter().getItemCount()).intValue());
        main(
                () -> {
                    ((View) activity.getWindow().getDecorView().findViewWithTag("clearSearch"))
                            .performClick();
                    return null;
                });
        idle();
        assertEquals(80, main(() -> list().getAdapter().getItemCount()).intValue());
        main(
                () -> {
                    set("source", "drive");
                    go("library");
                    return null;
                });
        idle();
        main(
                () -> {
                    assertNotNull(text(getBody(), "Google Drive 음악이 없습니다."));
                    assertNotNull(text(getBody(), "Drive 폴더 연결"));
                    return null;
                });
        capture("polish-empty-drive.png");
    }

    @Test
    public void recycledCardsKeepTheirSpacingAndOverflowDoesNotPlay() throws Exception {
        main(
                () -> {
                    set("tab", "아티스트");
                    go("library");
                    return null;
                });
        idle();
        main(
                () -> {
                    ViewGroup holder = (ViewGroup) list().getChildAt(0);
                    assertTrue(holder.getChildAt(0).getTop() >= Ui.dp(activity, 8));
                    set("tab", "곡");
                    go("library");
                    return null;
                });
        idle();
        int before = main(() -> player().getMediaItemCount());
        main(
                () -> {
                    ViewGroup row = (ViewGroup) ((ViewGroup) list().getChildAt(0)).getChildAt(0);
                    row.getChildAt(row.getChildCount() - 1).performClick();
                    return null;
                });
        idle();
        assertEquals(before, main(() -> player().getMediaItemCount()).intValue());
        if (Build.VERSION.SDK_INT >= 29)
            assertTrue(
                    main(
                            () ->
                                    android.view.inspector.WindowInspector.getGlobalWindowViews()
                                            .stream()
                                            .anyMatch(root -> text(root, "플레이리스트에 추가") != null)));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    }

    @Test
    public void queueSelectionKeepsQueueAndUpdatesAfterRemoval() throws Exception {
        File tone = new File(context.getCacheDir(), "polish-tone.wav");
        try (var in = instrumentation.getContext().getAssets().open("tone.wav");
                var out = new FileOutputStream(tone)) {
            out.write(Io.read(in));
        }
        main(
                () -> {
                    var songs = new ArrayList<Track>();
                    for (int i = 0; i < 3; i++)
                        songs.add(
                                new Track(
                                        Uri.fromFile(tone)
                                                .buildUpon()
                                                .fragment("track" + i)
                                                .build()
                                                .toString(),
                                        "Queue " + i,
                                        "Artist",
                                        "Album",
                                        "Music",
                                        3000,
                                        ""));
                    set("tracks", songs);
                    player().setMediaItems(
                                    songs.stream()
                                            .map(Track::item)
                                            .collect(java.util.stream.Collectors.toList()));
                    player().prepare();
                    player().pause();
                    go("player");
                    go("queue");
                    return null;
                });
        idle();
        capture("polish-queue.png");
        main(
                () -> {
                    ((ViewGroup) list().findViewHolderForAdapterPosition(1).itemView)
                            .getChildAt(0)
                            .performClick();
                    return null;
                });
        idle();
        assertEquals(3, main(() -> player().getMediaItemCount()).intValue());
        assertEquals(1, main(() -> player().getCurrentMediaItemIndex()).intValue());
        main(
                () -> {
                    player().pause();
                    go("queue");
                    player().removeMediaItem(2);
                    return null;
                });
        idle();
        assertEquals(2, main(() -> list().getAdapter().getItemCount()).intValue());
        main(
                () -> {
                    player().stop();
                    player().clearMediaItems();
                    return null;
                });
    }

    @Test
    public void completedScanCanBeDismissedAndSuccessExpires() throws Exception {
        main(
                () -> {
                    set("scanRevision", 900L);
                    LibraryScanService.state =
                            new LibraryScanService.State(false, "80곡을 검색했습니다.", 80, 80, 900);
                    invoke("updateScan");
                    assertEquals(View.VISIBLE, ((View) get("scanPanel")).getVisibility());
                    ((View) get("scanDismiss")).performClick();
                    invoke("updateScan");
                    assertEquals(View.GONE, ((View) get("scanPanel")).getVisibility());
                    LibraryScanService.state =
                            new LibraryScanService.State(true, "검색 중…", 1, 80, 900);
                    invoke("updateScan");
                    assertEquals(View.VISIBLE, ((View) get("scanPanel")).getVisibility());
                    assertEquals(View.GONE, ((View) get("scanDismiss")).getVisibility());
                    LibraryScanService.state =
                            new LibraryScanService.State(false, "80곡을 검색했습니다.", 80, 80, 900);
                    invoke("updateScan");
                    set("scanFinishedAt", SystemClock.elapsedRealtime() - 9000);
                    invoke("updateScan");
                    assertEquals(View.GONE, ((View) get("scanPanel")).getVisibility());
                    return null;
                });
    }

    @Test
    public void playbackSettingsHaveParentNavigationAndConsistentSwitches() throws Exception {
        main(
                () -> {
                    go("settings");
                    go("playback");
                    return null;
                });
        idle();
        main(
                () -> {
                    assertNotNull(text(getBody(), "재생과 알림"));
                    assertTrue(switches(getBody()) >= 3);
                    activity.getOnBackPressedDispatcher().onBackPressed();
                    assertEquals("settings", get("page"));
                    return null;
                });
        capture("polish-settings.png");
    }

    @Test
    public void systemBarInsetsAndSearchCaretSurviveScreenRefresh() throws Exception {
        for (String screen : List.of("home", "library", "settings", "playback")) {
            main(
                    () -> {
                        go(screen);
                        return null;
                    });
            idle();
            main(
                    () -> {
                        if (Build.VERSION.SDK_INT >= 30) {
                            View root = (View) get("root");
                            int top =
                                    root.getRootWindowInsets()
                                            .getInsets(WindowInsets.Type.systemBars())
                                            .top;
                            assertTrue(
                                    "Content overlaps status bar on " + screen,
                                    root.getPaddingTop() >= top);
                        }
                        return null;
                    });
        }
        main(
                () -> {
                    go("library");
                    return null;
                });
        idle();
        main(
                () -> {
                    EditText input =
                            activity.getWindow().getDecorView().findViewWithTag("librarySearch");
                    input.requestFocus();
                    input.setText("Song");
                    input.setSelection(2);
                    invoke("render");
                    return null;
                });
        idle();
        main(
                () -> {
                    EditText input =
                            activity.getWindow().getDecorView().findViewWithTag("librarySearch");
                    assertTrue(input.hasFocus());
                    assertEquals(2, input.getSelectionStart());
                    return null;
                });
    }

    @Test
    public void libraryHeaderBecomesThinAndReappearsOnUpwardScroll() throws Exception {
        main(
                () -> {
                    go("library");
                    return null;
                });
        idle();
        RecyclerView list = main(this::list);
        for (int i = 0; i < 3 && !main(() -> (Boolean) get("libraryCollapsed")); i++) {
            finger(list, true);
            idle();
        }
        assertTrue(
                "Header progress=" + main(() -> ((LibraryHeader) get("libraryChrome")).progress()),
                main(() -> (Boolean) get("libraryCollapsed")));
        main(
                () -> {
                    View thin = (View) get("libraryThin"), expanded = (View) get("libraryExpanded");
                    assertEquals(View.VISIBLE, thin.getVisibility());
                    assertEquals(0, expanded.getAlpha(), .01);
                    assertTrue(thin.getHeight() <= Ui.dp(activity, 56));
                    return null;
                });
        capture("polish-library-thin.png");
        finger(list, false);
        idle();
        assertFalse(main(() -> (Boolean) get("libraryCollapsed")));
        capture("polish-library-expanded.png");
    }

    @Test
    public void enrichedMetadataRetriesLyricsForSameTrackUri() throws Exception {
        File file = new File(context.getCacheDir(), "polish-refresh.lrc");
        java.nio.file.Files.write(
                file.toPath(),
                "[00:00.00]First synthetic line".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Track first =
                new Track(
                        "content://polish/same",
                        "Before",
                        "Artist",
                        "Album",
                        "",
                        0,
                        Uri.fromFile(file).toString());
        main(
                () -> {
                    var method = MainActivity.class.getDeclaredMethod("ensureLyrics", Track.class);
                    method.setAccessible(true);
                    method.invoke(activity, first);
                    return null;
                });
        idle();
        assertTrue(main(() -> get("lyricsText").toString().contains("First synthetic")));
        java.nio.file.Files.write(
                file.toPath(),
                "[00:00.00]Enriched synthetic line"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Track enriched = new Track(first.uri, "After", "Artist", "Album", "", 180000, first.lrcUri);
        main(
                () -> {
                    var method = MainActivity.class.getDeclaredMethod("ensureLyrics", Track.class);
                    method.setAccessible(true);
                    method.invoke(activity, enriched);
                    return null;
                });
        idle();
        assertTrue(main(() -> get("lyricsText").toString().contains("Enriched synthetic")));
        file.delete();
    }

    private void finger(View view, boolean down) throws Exception {
        android.graphics.Rect rect =
                main(
                        () -> {
                            var r = new android.graphics.Rect();
                            assertTrue(view.getGlobalVisibleRect(r));
                            return r;
                        });
        float x = rect.centerX(),
                from = rect.top + rect.height() * (down ? .8f : .2f),
                to = rect.top + rect.height() * (down ? .2f : .8f);
        long at = SystemClock.uptimeMillis();
        for (int i = 0; i <= 10; i++) {
            MotionEvent event =
                    MotionEvent.obtain(
                            at,
                            SystemClock.uptimeMillis(),
                            i == 0
                                    ? MotionEvent.ACTION_DOWN
                                    : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE,
                            x,
                            from + (to - from) * i / 10,
                            0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            instrumentation.getUiAutomation().injectInputEvent(event, true);
            event.recycle();
            Thread.sleep(18);
        }
        instrumentation.waitForIdleSync();
    }

    private int switches(View v) {
        int n = v instanceof androidx.appcompat.widget.SwitchCompat ? 1 : 0;
        if (v instanceof ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) n += switches(g.getChildAt(i));
        return n;
    }

    private TextView text(View v, String value) {
        if (v instanceof TextView t && value.contentEquals(t.getText())) return t;
        if (v instanceof ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) {
                var found = text(g.getChildAt(i), value);
                if (found != null) return found;
            }
        return null;
    }

    private MediaController player() throws Exception {
        return (MediaController) get("player");
    }

    private LinearLayout getBody() throws Exception {
        return (LinearLayout) get("body");
    }

    private RecyclerView list() throws Exception {
        return (RecyclerView) get("activeList");
    }

    private Object get(String key) throws Exception {
        var f = MainActivity.class.getDeclaredField(key);
        f.setAccessible(true);
        return f.get(activity);
    }

    private void set(String key, Object v) throws Exception {
        var f = MainActivity.class.getDeclaredField(key);
        f.setAccessible(true);
        f.set(activity, v);
    }

    private void invoke(String key) throws Exception {
        var m = MainActivity.class.getDeclaredMethod(key);
        m.setAccessible(true);
        m.invoke(activity);
    }

    private void go(String page) throws Exception {
        var m = MainActivity.class.getDeclaredMethod("go", String.class);
        m.setAccessible(true);
        m.invoke(activity, page);
    }

    private void idle() throws Exception {
        instrumentation.waitForIdleSync();
        Thread.sleep(400);
    }

    private void capture(String name) throws Exception {
        var shot = instrumentation.getUiAutomation().takeScreenshot();
        try (var out = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private <T> T main(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        instrumentation.runOnMainSync(future);
        return future.get(20, TimeUnit.SECONDS);
    }
}

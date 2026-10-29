package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
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
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

@RunWith(AndroidJUnit4.class)
public class MotionFoldersLyricsTest {
    private final android.app.Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();
    private final Context c = instrumentation.getTargetContext();
    private MainActivity activity;

    @Before
    public void start() throws Exception {
        AudioSettings.init(c);
        for (String key : List.of("sox", "perfect", "eq")) AudioSettings.put(key, false);
        AudioSettings.put("mode", "android");
        AudioSettings.put("device", 0);
        activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(c, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        idle();
        await(() -> main(() -> get("player")) != null, 8000);
        main(
                () -> {
                    player().stop();
                    player().clearMediaItems();
                    set("libraryLoading", false);
                    set("tab", "곡");
                    set("query", "");
                    set("source", "all");
                    return null;
                });
    }

    @After
    public void finish() throws Exception {
        main(
                () -> {
                    player().stop();
                    player().clearMediaItems();
                    activity.finish();
                    return null;
                });
    }

    private Track song(String id, String title, List<FolderTree.Part> path) {
        return new Track(
                        "content://motion/" + id,
                        title,
                        "Artist",
                        "Album",
                        "Music/Albums",
                        3000,
                        "")
                .withPath(path);
    }

    @Test
    public void slowHeaderDragHasIntermediateHeightAndNoFlingToggle() throws Exception {
        main(
                () -> {
                    List<Track> songs = new ArrayList<>();
                    for (int i = 0; i < 60; i++)
                        songs.add(
                                song(
                                        "s" + i,
                                        "Song " + i,
                                        List.of(new FolderTree.Part("root", "Music"))));
                    set("tracks", songs);
                    go("library");
                    return null;
                });
        idle();
        int expanded = main(() -> ((LibraryHeader) get("libraryChrome")).visibleHeight());
        main(
                () -> {
                    LibraryHeader header = (LibraryHeader) get("libraryChrome");
                    touch(header, MotionEvent.ACTION_DOWN, 500);
                    touch(header, MotionEvent.ACTION_MOVE, 492);
                    assertEquals(0, header.progress(), .0001);
                    touch(header, MotionEvent.ACTION_MOVE, 350);
                    assertTrue(header.progress() > 0 && header.progress() < .8f);
                    return null;
                });
        idle();
        int middle = main(() -> ((LibraryHeader) get("libraryChrome")).visibleHeight());
        assertTrue(middle < expanded && middle > Ui.dp(c, 48));
        float progress = main(() -> ((LibraryHeader) get("libraryChrome")).progress());
        Thread.sleep(350);
        assertEquals(progress, main(() -> ((LibraryHeader) get("libraryChrome")).progress()), .001);
        capture("header-partial.png");
        main(
                () -> {
                    touch((LibraryHeader) get("libraryChrome"), MotionEvent.ACTION_UP, 350);
                    return null;
                });
        idle();
        assertEquals(progress, main(() -> ((LibraryHeader) get("libraryChrome")).progress()), .001);
        main(
                () -> {
                    ((RecyclerView) get("activeList")).fling(0, 20000);
                    return null;
                });
        idle();
        assertEquals(progress, main(() -> ((LibraryHeader) get("libraryChrome")).progress()), .001);
    }

    @Test
    public void folderIdentityAndDirectSongsSurviveSerialization() throws Exception {
        var rootA = new FolderTree.Part("a", "Music");
        var rootB = new FolderTree.Part("b", "Music");
        var album = new FolderTree.Part("album", "Jazz / live");
        Track direct = song("direct", "Direct", List.of(rootA));
        Track nested = song("nested", "Nested", List.of(rootA, album));
        Track other = song("other", "Other", List.of(rootB, album));
        Track restored = Track.from(nested.json());
        assertEquals(nested.folderPath, restored.folderPath);
        FolderTree tree = new FolderTree(List.of(direct, restored, other));
        var local = tree.root.children.get("local");
        assertEquals(2, local.children.size());
        var first =
                local.children.values().stream()
                        .filter(n -> n.count() == 2)
                        .findFirst()
                        .orElseThrow();
        assertEquals(1, first.songs.size());
        assertEquals("Direct", first.songs.get(0).title);
        assertEquals("Jazz / live", first.children.values().iterator().next().name);
        assertEquals(1, first.children.values().iterator().next().songs.size());
        assertFalse(tree.legacy);
    }

    @Test
    public void folderUiWalksParentsInsteadOfPlayingAllDescendants() throws Exception {
        var path =
                List.of(
                        new FolderTree.Part("music", "Music"),
                        new FolderTree.Part("albums", "Albums"),
                        new FolderTree.Part("jazz", "Jazz"));
        main(
                () -> {
                    set(
                            "tracks",
                            List.of(
                                    song("one", "Inside Jazz", path),
                                    song("two", "At root", path.subList(0, 1))));
                    set("tab", "폴더");
                    go("library");
                    return null;
                });
        idle();
        assertEquals(1, main(() -> list().getAdapter().getItemCount()).intValue());
        clickRow(0);
        clickRow(0);
        assertEquals(2, main(() -> list().getAdapter().getItemCount()).intValue());
        clickRow(0);
        clickRow(0);
        assertEquals(1, main(() -> list().getAdapter().getItemCount()).intValue());
        assertEquals(0, main(() -> player().getMediaItemCount()).intValue());
        capture("folder-path.png");
        String leaf = main(() -> get("folderCursor").toString());
        main(
                () -> {
                    activity.getOnBackPressedDispatcher().onBackPressed();
                    return null;
                });
        idle();
        assertEquals("library", main(() -> get("page")));
        assertNotEquals(leaf, main(() -> get("folderCursor")));
    }

    @Test
    public void realPlaybackUpdatesOnlyCurrentPreviewAndFollowsSeek() throws Exception {
        File tone = new File(c.getCacheDir(), "motion-tone.wav"),
                lrc = new File(c.getCacheDir(), "motion-tone.lrc");
        try (var in = instrumentation.getContext().getAssets().open("tone.wav");
                var out = new FileOutputStream(tone)) {
            out.write(Io.read(in));
        }
        java.nio.file.Files.write(
                lrc.toPath(),
                "[00:00.00]First synthetic line\n[00:00.70]Second synthetic line\n[00:01.40]Third synthetic line"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Track track =
                new Track(
                        Uri.fromFile(tone).toString(),
                        "Quiet Moments",
                        "AURA Test",
                        "Evening",
                        "Music",
                        3000,
                        Uri.fromFile(lrc).toString());
        main(
                () -> {
                    set("tracks", List.of(track));
                    player().setMediaItem(track.item());
                    player().prepare();
                    go("player");
                    player().play();
                    return null;
                });
        await(
                () ->
                        main(
                                () ->
                                        ((TextView) get("lyricPreview"))
                                                .getText()
                                                .toString()
                                                .contains("Second synthetic")),
                6000);
        main(
                () -> {
                    TextView preview = (TextView) get("lyricPreview");
                    assertEquals(1, preview.getMaxLines());
                    assertFalse(preview.getText().toString().contains("Third"));
                    return null;
                });
        main(
                () -> {
                    player().pause();
                    player().seekTo(1600);
                    return null;
                });
        await(
                () ->
                        main(
                                () ->
                                        ((TextView) get("lyricPreview"))
                                                .getText()
                                                .toString()
                                                .contains("Third synthetic")),
                3000);
        main(
                () -> {
                    go("lyrics");
                    return null;
                });
        idle();
        assertEquals(2, main(() -> (Integer) get("activeLine")).intValue());
        main(
                () -> {
                    player().seekTo(100);
                    return null;
                });
        await(() -> main(() -> get("activeLine").equals(0)), 3000);
        main(
                () -> {
                    go("player");
                    return null;
                });
        idle();
        capture("player-refined.png");
        main(
                () -> {
                    set("lyricLines", List.of());
                    set("lyricsText", "Long plain lyric\\n".repeat(200));
                    set("lyricsMessage", "Long plain lyric\\n".repeat(200));
                    var update = MainActivity.class.getDeclaredMethod("updateLyrics", long.class);
                    update.setAccessible(true);
                    update.invoke(activity, 0L);
                    assertTrue(
                            ((TextView) get("lyricPreview")).getText().toString().length() < 100);
                    return null;
                });
    }

    @Test
    public void playerEntryResetsScrollAndDismissSkipsLyricsHistory() throws Exception {
        Track track = song("screen", "Screen regression", List.of());
        main(
                () -> {
                    set("tracks", List.of(track));
                    player().setMediaItem(track.item());
                    go("library");
                    go("player");
                    return null;
                });
        idle();
        main(
                () -> {
                    View pane = (View) ((View) get("scroll")).getParent();
                    LinearLayout.LayoutParams params =
                            (LinearLayout.LayoutParams) pane.getLayoutParams();
                    params.height = Ui.dp(c, 400);
                    params.weight = 0;
                    pane.setLayoutParams(params);
                    return null;
                });
        idle();
        main(
                () -> {
                    PlayerScrollView scroll = (PlayerScrollView) get("scroll");
                    scroll.scrollTo(0, 10000);
                    assertTrue(scroll.getScrollY() > 0);
                    go("library");
                    go("player");
                    return null;
                });
        idle();
        assertEquals(0, main(() -> ((View) get("scroll")).getScrollY()).intValue());
        main(
                () -> {
                    go("lyrics");
                    go("player");
                    return null;
                });
        idle();
        main(
                () -> {
                    var method = MainActivity.class.getDeclaredMethod("dismissPlayer");
                    method.setAccessible(true);
                    method.invoke(activity);
                    return null;
                });
        idle();
        assertEquals("library", main(() -> get("page")));
        main(
                () -> {
                    go("player");
                    return null;
                });
        idle();
        assertEquals(0, main(() -> ((View) get("scroll")).getScrollY()).intValue());
    }

    @Test
    public void compactHeaderKeepsListViewportStableDuringDrag() throws Exception {
        main(
                () -> {
                    List<Track> songs = new ArrayList<>();
                    for (int i = 0; i < 100; i++)
                        songs.add(song("stable" + i, "Song " + i, List.of()));
                    set("tracks", songs);
                    go("library");
                    return null;
                });
        idle();
        int height = main(() -> list().getHeight());
        int header = main(() -> ((LibraryHeader) get("libraryChrome")).visibleHeight());
        assertTrue(header <= Ui.dp(c, 104));
        main(
                () -> {
                    LibraryHeader chrome = (LibraryHeader) get("libraryChrome");
                    touch(chrome, MotionEvent.ACTION_DOWN, 500);
                    touch(chrome, MotionEvent.ACTION_MOVE, 440);
                    return null;
                });
        idle();
        assertEquals(height, main(() -> list().getHeight()).intValue());
        assertTrue(main(() -> ((LibraryHeader) get("libraryChrome")).visibleHeight()) < header);
        main(
                () -> {
                    touch((LibraryHeader) get("libraryChrome"), MotionEvent.ACTION_UP, 440);
                    return null;
                });
    }

    @Test
    public void largeLibrarySearchAppliesOnlyLatestQuery() throws Exception {
        List<Track> songs = new ArrayList<>();
        for (int i = 0; i < 12000; i++) songs.add(song("large" + i, "Song " + i, List.of()));
        main(
                () -> {
                    set("tracks", songs);
                    set("searchOpen", true);
                    go("library");
                    EditText input =
                            activity.getWindow().getDecorView().findViewWithTag("librarySearch");
                    input.setText("Song 1");
                    input.setText("Song 11999");
                    return null;
                });
        await(() -> main(() -> list() != null && list().getAdapter().getItemCount() == 1), 6000);
        main(
                () -> {
                    assertEquals("Song 11999", get("query"));
                    return null;
                });
    }

    private void touch(LibraryHeader header, int action, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 100, y, 0);
        header.touch(event);
        event.recycle();
    }

    private void clickRow(int at) throws Exception {
        main(
                () -> {
                    ((ViewGroup) list().findViewHolderForAdapterPosition(at).itemView)
                            .getChildAt(0)
                            .performClick();
                    return null;
                });
        idle();
    }

    private RecyclerView list() throws Exception {
        return (RecyclerView) get("activeList");
    }

    private MediaController player() throws Exception {
        return (MediaController) get("player");
    }

    private Object get(String key) throws Exception {
        var f = MainActivity.class.getDeclaredField(key);
        f.setAccessible(true);
        return f.get(activity);
    }

    private void set(String key, Object value) throws Exception {
        var f = MainActivity.class.getDeclaredField(key);
        f.setAccessible(true);
        f.set(activity, value);
    }

    private void go(String page) throws Exception {
        var m = MainActivity.class.getDeclaredMethod("go", String.class);
        m.setAccessible(true);
        m.invoke(activity, page);
    }

    private void capture(String name) throws Exception {
        var shot = instrumentation.getUiAutomation().takeScreenshot();
        try (var out = new FileOutputStream(new File(c.getExternalFilesDir(null), name))) {
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private void await(Callable<Boolean> condition, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.call() && SystemClock.elapsedRealtime() < until) Thread.sleep(35);
        assertTrue("Playback UI did not update", condition.call());
    }

    private void idle() throws Exception {
        instrumentation.waitForIdleSync();
        Thread.sleep(400);
    }

    private <T> T main(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        instrumentation.runOnMainSync(future);
        return future.get(20, TimeUnit.SECONDS);
    }
}

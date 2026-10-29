package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.*;
import android.net.Uri;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class FolderDialogDesignTest {
    final android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    final Context c = inst.getTargetContext();
    MainActivity activity;
    String savedTheme, savedSort;
    boolean savedScan, savedDescending;

    @Before
    public void setup() {
        var p = c.getSharedPreferences("aura", 0);
        savedTheme = p.getString("appearance", "dark");
        savedSort = p.getString("trackSort", "track");
        savedScan = p.getBoolean("scanOnLaunch", false);
        savedDescending = p.getBoolean("trackSortDescending", false);
        p.edit()
                .putBoolean("scanOnLaunch", false)
                .putString("trackSort", "track")
                .putBoolean("trackSortDescending", false)
                .commit();
        AudioSettings.init(c);
        AudioSettings.put("mode", "android");
        AudioSettings.put("perfect", false);
        AudioSettings.put("eq", false);
    }

    @After
    public void cleanup() throws Exception {
        if (activity != null)
            main(
                    () -> {
                        player().stop();
                        player().clearMediaItems();
                        activity.finish();
                        return null;
                    });
        c.getSharedPreferences("aura", 0)
                .edit()
                .putString("appearance", savedTheme)
                .putString("trackSort", savedSort)
                .putBoolean("trackSortDescending", savedDescending)
                .putBoolean("scanOnLaunch", savedScan)
                .commit();
    }

    void launch() throws Exception {
        activity =
                (MainActivity)
                        inst.startActivitySync(
                                new Intent(c, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> main(() -> get("player") != null && !(Boolean) get("libraryLoading")), 10000);
    }

    @Test
    public void folderPlayButtonBuildsSortedRecursiveQueueWithoutEnteringFolder() throws Exception {
        launch();
        List<Track> songs = new ArrayList<>();
        for (int n : new int[] {10, 2}) {
            File tone = new File(c.getCacheDir(), "folder-play-" + n + ".wav");
            try (var in = inst.getContext().getAssets().open("tone.wav");
                    var out = new FileOutputStream(tone)) {
                out.write(Io.read(in));
            }
            var path =
                    new ArrayList<>(
                            List.of(
                                    new FolderTree.Part("r", "Music"),
                                    new FolderTree.Part("sub", "Album")));
            if (n == 10) path.add(new FolderTree.Part("deeper", "Extras"));
            songs.add(
                    new Track(
                                    Uri.fromFile(tone).toString(),
                                    "Track " + n,
                                    "AURA",
                                    "Album",
                                    "",
                                    3000,
                                    "")
                            .withNumbers(n, 1)
                            .withPath(path));
        }
        main(
                () -> {
                    set("tracks", songs);
                    set("tab", "폴더");
                    set("source", "local");
                    set("folderCursor", "local/r");
                    go("library");
                    return null;
                });
        await(
                () ->
                        main(
                                () ->
                                        find(activity.getWindow().getDecorView(), "Album 폴더 전체 재생")
                                                != null),
                5000);
        main(
                () -> {
                    find(activity.getWindow().getDecorView(), "Album 폴더 전체 재생").performClick();
                    return null;
                });
        await(
                () -> main(() -> player().getMediaItemCount() == 2 && get("page").equals("player")),
                5000);
        assertTrue(main(() -> player().getMediaItemAt(0).mediaId).contains("folder-play-2.wav"));
        assertEquals("local/r", main(() -> get("folderCursor")));
    }

    @Test
    public void commonChoiceAndConfirmationDialogsUseBothAuraThemes() throws Exception {
        for (String theme : List.of("dark", "light")) {
            c.getSharedPreferences("aura", 0).edit().putString("appearance", theme).commit();
            launch();
            int[] selected = {-1};
            androidx.appcompat.app.AlertDialog choice =
                    main(
                            () ->
                                    new androidx.appcompat.app.AlertDialog.Builder(activity)
                                            .setTitle("출력 엔진")
                                            .setSingleChoiceItems(
                                                    new String[] {
                                                        "Android 기본",
                                                        "Hi-Res AudioTrack",
                                                        "USB DAC 직접 출력"
                                                    },
                                                    1,
                                                    (d, i) -> {
                                                        selected[0] = i;
                                                        d.dismiss();
                                                    })
                                            .setNegativeButton("취소", null)
                                            .show());
            inst.waitForIdleSync();
            Thread.sleep(300);
            main(
                    () -> {
                        Bitmap b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
                        var background = choice.getWindow().getDecorView().getBackground();
                        assertNotNull(background);
                        background.setBounds(0, 0, 160, 160);
                        background.draw(new Canvas(b));
                        assertEquals(0, Color.alpha(b.getPixel(0, 0)));
                        assertTrue(Color.alpha(b.getPixel(80, 80)) > 0);
                        b.recycle();
                        assertEquals(1, choice.getListView().getCheckedItemPosition());
                        return null;
                    });
            capture("dialog-choice-" + theme);
            main(
                    () -> {
                        choice.getListView()
                                .performItemClick(choice.getListView().getChildAt(2), 2, 2);
                        return null;
                    });
            assertEquals(2, selected[0]);
            androidx.appcompat.app.AlertDialog confirm =
                    main(
                            () ->
                                    new androidx.appcompat.app.AlertDialog.Builder(activity)
                                            .setTitle("폴더 다시 검색")
                                            .setMessage("선택한 폴더와 하위 폴더만 다시 검색합니다. 다른 폴더의 곡은 유지됩니다.")
                                            .setPositiveButton("검색", null)
                                            .setNegativeButton("취소", null)
                                            .show());
            inst.waitForIdleSync();
            Thread.sleep(300);
            capture("dialog-confirm-" + theme);
            main(
                    () -> {
                        confirm.dismiss();
                        activity.finish();
                        return null;
                    });
            activity = null;
        }
    }

    @Test
    public void selectedFolderActionStartsScopedBackgroundScan() throws Exception {
        Uri provider = Uri.parse("content://dev.aura.player.test.documents");
        var previous = Library.roots(c);
        c.getContentResolver().call(provider, "test.reset", null, null);
        Uri
                a =
                        android.provider.DocumentsContract.buildTreeDocumentUri(
                                provider.getAuthority(), "A"),
                b =
                        android.provider.DocumentsContract.buildTreeDocumentUri(
                                provider.getAuthority(), "B");
        try {
            Library.saveRoots(c, List.of(a, b));
            var tracks = new Library(c).scan(List.of(a, b), p -> {});
            var tree = new FolderTree(tracks);
            var node =
                    tree.nodes.values().stream()
                            .filter(n -> n.name.equals("sub"))
                            .findFirst()
                            .orElseThrow();
            launch();
            var before = c.getContentResolver().call(provider, "test.stats", null, null);
            long revision = LibraryScanService.state.revision();
            main(
                    () -> {
                        var method =
                                MainActivity.class.getDeclaredMethod(
                                        "rescanFolder", FolderTree.Node.class);
                        method.setAccessible(true);
                        method.invoke(activity, node);
                        return null;
                    });
            await(
                    () ->
                            !LibraryScanService.state.running()
                                    && LibraryScanService.state.revision() > revision,
                    8000);
            var after = c.getContentResolver().call(provider, "test.stats", null, null);
            assertEquals(before.getInt("open:A/a.wav"), after.getInt("open:A/a.wav"));
            assertEquals(before.getInt("open:B/c.wav"), after.getInt("open:B/c.wav"));
            assertTrue(after.getInt("open:A/sub/b.wav") > before.getInt("open:A/sub/b.wav"));
            assertEquals(3, new Library(c).loadLocal().size());
        } finally {
            Library.saveRoots(c, previous);
            c.getContentResolver().call(provider, "test.reset", null, null);
        }
    }

    View find(View view, String description) {
        if (description.contentEquals(
                view.getContentDescription() == null ? "" : view.getContentDescription()))
            return view;
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        return null;
    }

    void capture(String name) throws Exception {
        Bitmap bitmap = inst.getUiAutomation().takeScreenshot();
        try (var out = new FileOutputStream(new File(c.getExternalFilesDir(null), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    androidx.media3.session.MediaController player() throws Exception {
        return (androidx.media3.session.MediaController) get("player");
    }

    void go(String page) throws Exception {
        var m = MainActivity.class.getDeclaredMethod("go", String.class);
        m.setAccessible(true);
        m.invoke(activity, page);
    }

    Object get(String key) throws Exception {
        var f = MainActivity.class.getDeclaredField(key);
        f.setAccessible(true);
        return f.get(activity);
    }

    void set(String key, Object value) throws Exception {
        var f = MainActivity.class.getDeclaredField(key);
        f.setAccessible(true);
        f.set(activity, value);
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

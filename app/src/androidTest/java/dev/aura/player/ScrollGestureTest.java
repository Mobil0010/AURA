package dev.aura.player;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;

/** Exercise the complete touch dispatch chain, rather than scrollToPosition(). */
@RunWith(AndroidJUnit4.class)
public class ScrollGestureTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private MainActivity activity;

    @Before
    public void start() throws Exception {
        DriveAuth.get(context).clear();
        AudioSettings.init(context);
        AudioSettings.put("mode", "android");
        AudioSettings.put("perfect", false);
        activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        idle();
    }

    @After
    public void finish() throws Exception {
        main(
                () -> {
                    activity.finish();
                    return null;
                });
        DriveAuth.get(context).clear();
    }

    @Test
    public void libraryTabsReachBothEndsWithFingerDrags() throws Exception {
        main(
                () -> {
                    set(activity, "tracks", tracks());
                    set(activity, "source", "all");
                    set(activity, "query", "");
                    return null;
                });
        for (String tab : List.of("곡", "앨범", "아티스트", "폴더")) {
            main(
                    () -> {
                        set(activity, "tab", tab);
                        go("library");
                        return null;
                    });
            idle();
            RecyclerView list = main(() -> findList(activity.getWindow().getDecorView()));
            assertNotNull(list);
            reach(list, true);
            assertEdgeVisible(list, true);
            reach(list, false);
            assertEdgeVisible(list, false);
        }
        capture("scroll-library.png");
    }

    @Test
    public void groupListAndShortViewportReachLastRow() throws Exception {
        main(
                () -> {
                    set(activity, "group", "전체 곡");
                    set(activity, "groupTracks", tracks());
                    go("group");
                    View pane = (View) page().getParent();
                    var lp = (LinearLayout.LayoutParams) pane.getLayoutParams();
                    lp.height = Ui.dp(activity, 240);
                    lp.weight = 0;
                    pane.setLayoutParams(lp);
                    return null;
                });
        idle();
        RecyclerView list = main(() -> findList(activity.getWindow().getDecorView()));
        reach(list, true);
        assertEdgeVisible(list, true);
        reach(list, false);
        assertEdgeVisible(list, false);
    }

    @Test
    public void driveFoldersScrollToLastAndContinueToPageFooter() throws Exception {
        main(
                () -> {
                    DriveAuth.get(context)
                            .accept(
                                    "scroll-test",
                                    "scroll@example.invalid",
                                    "테스트 계정",
                                    "fake-token");
                    var roots = new ArrayList<DriveLibrary.Folder>();
                    for (int i = 0; i < 8; i++)
                        roots.add(new DriveLibrary.Folder("root" + i, "등록 폴더 " + i));
                    DriveLibrary.saveRoots(context, roots);
                    Object ui = get(activity, "driveUi");
                    var folders = new ArrayList<DriveApi.Item>();
                    for (int i = 0; i < 36; i++)
                        folders.add(
                                new DriveApi.Item(
                                        "folder" + i,
                                        String.format(Locale.ROOT, "폴더 %02d", i)
                                                + (i % 4 == 0 ? " · 긴 이름의 음악 모음과 하위 앨범 폴더" : ""),
                                        DriveApi.FOLDER,
                                        0,
                                        "",
                                        true));
                    set(ui, "folders", folders);
                    set(ui, "loading", false);
                    go("drive");
                    return null;
                });
        idle();
        RecyclerView list = main(() -> findList(activity.getWindow().getDecorView()));
        assertNotNull(list);
        // Bring the picker into view by dragging the page header first.
        for (int i = 0; i < 35 && !visibleEnough(list); i++) drag(page(), true);
        assertTrue("Drive folder picker never entered the viewport", visibleEnough(list));
        reach(list, true);
        assertEdgeVisible(list, true);
        for (int i = 0; i < 8 && main(() -> page().canScrollVertically(1)); i++) drag(list, true);
        assertFalse(
                "Folder list trapped the page before its footer",
                main(() -> page().canScrollVertically(1)));
        capture("scroll-drive-bottom.png");
        reach(list, false);
        assertEdgeVisible(list, false);
        for (int i = 0; i < 15 && main(() -> page().canScrollVertically(-1)); i++)
            drag(page(), false);
        capture("scroll-drive-return.png");
        assertEquals(
                "Upward handoff must return to the page heading",
                0,
                main(() -> page().getScrollY()).intValue());
    }

    private ArrayList<Track> tracks() {
        var tracks = new ArrayList<Track>();
        for (int i = 0; i < 24; i++)
            tracks.add(
                    new Track(
                            "content://scroll/" + i,
                            String.format(Locale.ROOT, "곡 %02d", i),
                            String.format(Locale.ROOT, "아티스트 %02d", i),
                            String.format(Locale.ROOT, "앨범 %02d", i),
                            String.format(Locale.ROOT, "폴더 %02d", i),
                            1000,
                            ""));
        return tracks;
    }

    private void reach(RecyclerView list, boolean down) throws Exception {
        for (int i = 0; i < 90; i++) {
            if (!visibleEnough(list)) {
                drag(page(), down);
                continue;
            }
            if (!main(() -> list.canScrollVertically(down ? 1 : -1))) {
                if (edgeVisible(list, down)) return;
                boolean revealBelow =
                        main(
                                () -> {
                                    var item =
                                            list.findViewHolderForAdapterPosition(
                                                            down
                                                                    ? list.getAdapter()
                                                                                    .getItemCount()
                                                                            - 1
                                                                    : 0)
                                                    .itemView;
                                    int[] at = new int[2];
                                    item.getLocationOnScreen(at);
                                    Rect viewport = bounds(page());
                                    return at[1] + item.getHeight() > viewport.bottom;
                                });
                drag(page(), revealBelow);
                continue;
            }
            drag(list, down);
        }
        capture("scroll-unreachable.png");
        System.out.println(
                main(
                        () ->
                                "outer="
                                        + page().getScrollY()
                                        + ", listHeight="
                                        + list.getHeight()
                                        + ", first="
                                        + ((LinearLayoutManager) list.getLayoutManager())
                                                .findFirstVisibleItemPosition()
                                        + ", last="
                                        + ((LinearLayoutManager) list.getLayoutManager())
                                                .findLastVisibleItemPosition()
                                        + ", firstRect="
                                        + bounds(
                                                list.findViewHolderForAdapterPosition(0) == null
                                                        ? list
                                                        : list.findViewHolderForAdapterPosition(0)
                                                                .itemView)));
        fail("Finger drags did not reach " + (down ? "last" : "first") + " row");
    }

    private boolean edgeVisible(RecyclerView list, boolean last) throws Exception {
        return main(
                () -> {
                    int position = last ? list.getAdapter().getItemCount() - 1 : 0;
                    var holder = list.findViewHolderForAdapterPosition(position);
                    if (holder == null) return false;
                    Rect visible = new Rect();
                    return holder.itemView.getGlobalVisibleRect(visible)
                            && visible.height() >= holder.itemView.getHeight() - 2;
                });
    }

    private void assertEdgeVisible(RecyclerView list, boolean last) throws Exception {
        main(
                () -> {
                    int position = last ? list.getAdapter().getItemCount() - 1 : 0;
                    var holder = list.findViewHolderForAdapterPosition(position);
                    assertNotNull("Edge item missing", holder);
                    Rect visible = new Rect();
                    assertTrue(holder.itemView.getGlobalVisibleRect(visible));
                    assertTrue(
                            "Edge item was clipped",
                            visible.height() >= holder.itemView.getHeight() - 2);
                    return null;
                });
    }

    private boolean visibleEnough(View v) throws Exception {
        return main(
                () -> {
                    Rect r = new Rect();
                    return v.getGlobalVisibleRect(r) && r.height() > Ui.dp(context, 72);
                });
    }

    private void drag(View target, boolean down) throws Exception {
        Rect rect =
                main(
                        () -> {
                            Rect r = new Rect();
                            assertTrue("No visible drag surface", target.getGlobalVisibleRect(r));
                            return r;
                        });
        float margin = Math.max(16, rect.height() * .2f);
        float x = rect.centerX(),
                start = down ? rect.bottom - margin : rect.top + margin,
                end = down ? rect.top + margin : rect.bottom - margin;
        long at = SystemClock.uptimeMillis();
        input(at, MotionEvent.ACTION_DOWN, x, start);
        for (int i = 1; i <= 10; i++) {
            SystemClock.sleep(14);
            input(at, MotionEvent.ACTION_MOVE, x, start + (end - start) * i / 10);
        }
        SystemClock.sleep(70);
        input(at, MotionEvent.ACTION_MOVE, x, end);
        input(at, MotionEvent.ACTION_UP, x, end);
        instrumentation.waitForIdleSync();
    }

    private Rect bounds(View v) {
        Rect r = new Rect();
        v.getGlobalVisibleRect(r);
        return r;
    }

    private void input(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true));
        } finally {
            event.recycle();
        }
    }

    private PlayerScrollView page() throws Exception {
        return (PlayerScrollView) get(activity, "scroll");
    }

    private void go(String page) throws Exception {
        var m = MainActivity.class.getDeclaredMethod("go", String.class);
        m.setAccessible(true);
        m.invoke(activity, page);
    }

    private Object get(Object o, String name) throws Exception {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private void set(Object o, String name, Object value) throws Exception {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private RecyclerView findList(View v) {
        if (v instanceof RecyclerView r) return r;
        if (v instanceof ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) {
                var r = findList(g.getChildAt(i));
                if (r != null) return r;
            }
        return null;
    }

    private void idle() throws Exception {
        instrumentation.waitForIdleSync();
        Thread.sleep(350);
    }

    private void capture(String name) throws Exception {
        var bitmap = instrumentation.getUiAutomation().takeScreenshot();
        try (var out =
                new java.io.FileOutputStream(
                        new java.io.File(context.getExternalFilesDir(null), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private <T> T main(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        instrumentation.runOnMainSync(future);
        return future.get(20, TimeUnit.SECONDS);
    }
}

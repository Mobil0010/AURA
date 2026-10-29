package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class RoutingAndUiTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void defaults() {
        AudioSettings.init(context);
        AudioSettings.put("mode", "android");
        AudioSettings.put("perfect", false);
        AudioSettings.put("device", 0);
    }

    @Test
    public void legacyAndroidUsesDirectUsbWithoutOfficialMixer() {
        var cap = new OutputRouting.Capabilities(33, true, List.of());
        assertFalse(OutputRouting.assess(cap, "android", true, 0).allowed());
        assertTrue(OutputRouting.assess(cap, "usb", true, 0).allowed());
    }

    @Test
    public void directUsbRequiresPermissionBeforeModeSwitch() {
        var cap = new OutputRouting.Capabilities(36, false, List.of());
        var result = OutputRouting.assess(cap, "usb", true, 0);
        assertFalse(result.allowed());
        assertTrue(result.message().contains("권한"));
        assertFalse(AudioSettings.current.perfect());
        assertEquals("android", AudioSettings.current.mode());
    }

    @Test
    public void officialPerfectAutoSelectsSingleCapableUsb() {
        var cap =
                new OutputRouting.Capabilities(
                        36,
                        false,
                        List.of(
                                new OutputRouting.Device(1, false, false),
                                new OutputRouting.Device(9, true, true)));
        var result = OutputRouting.assess(cap, "android", true, 0);
        assertTrue(result.allowed());
        assertEquals(9, result.deviceId());
    }

    @Test
    public void multipleUsbRequiresExplicitSelection() {
        var cap =
                new OutputRouting.Capabilities(
                        36,
                        false,
                        List.of(
                                new OutputRouting.Device(9, true, true),
                                new OutputRouting.Device(10, true, true)));
        assertFalse(OutputRouting.assess(cap, "android", true, 0).allowed());
        assertEquals(10, OutputRouting.assess(cap, "android", true, 10).deviceId());
        assertTrue(OutputRouting.assess(cap, "android", true, 10).allowed());
    }

    @Test
    public void unsupportedUsbDoesNotPretendToBeBitPerfect() {
        var cap =
                new OutputRouting.Capabilities(
                        36, false, List.of(new OutputRouting.Device(9, true, false)));
        assertFalse(OutputRouting.assess(cap, "android", true, 9).allowed());
        assertTrue(OutputRouting.assess(cap, "android", false, 9).allowed());
    }

    @Test
    public void deniedToggleLeavesSettingsUnchanged() throws Exception {
        var result = OutputRouting.assess(context, "android", true, 0);
        org.junit.Assume.assumeFalse(result.allowed());
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        Thread.sleep(350);
        try {
            main(
                    () -> {
                        LinearLayout root = new LinearLayout(activity);
                        AudioUi ui = new AudioUi(activity, root, x -> {});
                        var perfect = AudioUi.class.getDeclaredMethod("perfect", boolean.class);
                        perfect.setAccessible(true);
                        perfect.invoke(ui, true);
                        assertFalse(AudioSettings.current.perfect());
                        assertEquals("android", AudioSettings.current.mode());
                        return null;
                    });
        } finally {
            main(
                    () -> {
                        activity.finish();
                        return null;
                    });
        }
    }

    @Test
    public void rectangularArtworkFitsWithinExistingBoundsWithoutStretch() throws Exception {
        Track track =
                new Track(
                        "content://test/rectangular-cover",
                        "test",
                        "artist",
                        "album",
                        "",
                        1000,
                        "");
        File file = Library.artFile(context, track.uri);
        file.getParentFile().mkdirs();
        Bitmap bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.RED);
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        bitmap.recycle();
        try {
            ArtworkView art =
                    main(
                            () -> {
                                ArtworkView v = new ArtworkView(context, track, 120);
                                v.measure(
                                        View.MeasureSpec.makeMeasureSpec(
                                                200, View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(
                                                500, View.MeasureSpec.AT_MOST));
                                v.layout(0, 0, 200, 200);
                                return v;
                            });
            long end = android.os.SystemClock.elapsedRealtime() + 5000;
            while (main(() -> art.image().getDrawable() == null)
                    && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(20);
            main(
                    () -> {
                        assertEquals(200, art.getMeasuredWidth());
                        assertEquals(200, art.getMeasuredHeight());
                        assertEquals(ImageView.ScaleType.FIT_CENTER, art.image().getScaleType());
                        assertEquals(200, art.image().getWidth());
                        assertEquals(100, art.image().getHeight());
                        float[] matrix = new float[9];
                        art.image().getImageMatrix().getValues(matrix);
                        assertEquals(matrix[Matrix.MSCALE_X], matrix[Matrix.MSCALE_Y], .00001f);
                        assertNotNull(art.image().getDrawable());
                        return null;
                    });
        } finally {
            file.delete();
        }
    }

    @Test
    public void playGlyphIsCenteredInsideLargeButton() throws Exception {
        main(
                () -> {
                    Ui.IconButton button = new Ui.IconButton(context, "▶");
                    int size = Ui.dp(context, 76);
                    button.measure(
                            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
                    button.layout(0, 0, size, size);
                    Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    button.draw(new Canvas(bitmap));
                    var field = Ui.IconButton.class.getDeclaredField("icon");
                    field.setAccessible(true);
                    android.graphics.drawable.Drawable icon =
                            (android.graphics.drawable.Drawable) field.get(button);
                    assertEquals(size / 2f, icon.getBounds().exactCenterY(), .5f);
                    assertEquals(size / 2f, icon.getBounds().exactCenterX(), .5f);
                    assertNull(button.getCompoundDrawables()[1]);
                    bitmap.recycle();
                    return null;
                });
    }

    @Test
    public void downwardSwipeDismissesAndShortPullReturns() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        Thread.sleep(350);
        AtomicBoolean closed = new AtomicBoolean();
        PlayerScrollView scroll =
                main(
                        () -> {
                            PlayerScrollView view = new PlayerScrollView(activity);
                            FrameLayout host = new FrameLayout(activity);
                            TextView content = new TextView(activity);
                            content.setText("Cover gesture test");
                            content.setMinHeight(Ui.dp(activity, 1400));
                            view.addView(content);
                            host.addView(view, new FrameLayout.LayoutParams(-1, -1));
                            activity.setContentView(host);
                            view.dismissHandler(() -> closed.set(true));
                            view.dismissRegion(Ui.dp(activity, 550));
                            return view;
                        });
        instrumentation.waitForIdleSync();
        try {
            main(
                    () -> {
                        swipe(scroll, Ui.dp(activity, 100), Ui.dp(activity, 125), 180);
                        return null;
                    });
            Thread.sleep(320);
            assertFalse(closed.get());
            assertEquals(0, main(scroll::getTranslationY), .1);
            main(
                    () -> {
                        swipe(scroll, Ui.dp(activity, 100), Ui.dp(activity, 500), 250);
                        return null;
                    });
            Thread.sleep(300);
            assertTrue(closed.get());
            closed.set(false);
            main(
                    () -> {
                        scroll.dismissHandler(() -> closed.set(true));
                        scroll.dismissRegion(Ui.dp(activity, 550));
                        scroll.scrollTo(0, Ui.dp(activity, 150));
                        swipe(scroll, Ui.dp(activity, 100), Ui.dp(activity, 500), 250);
                        return null;
                    });
            Thread.sleep(300);
            assertFalse("Scrolling content must not dismiss", closed.get());
        } finally {
            main(
                    () -> {
                        activity.finish();
                        return null;
                    });
        }
    }

    private void swipe(View view, int start, int end, int duration) {
        long time = SystemClock.uptimeMillis();
        for (int i = 0; i <= 4; i++) {
            int action =
                    i == 0
                            ? MotionEvent.ACTION_DOWN
                            : i == 4 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event =
                    MotionEvent.obtain(
                            time,
                            time + duration * i / 4,
                            action,
                            100,
                            start + (end - start) * i / 4f,
                            0);
            view.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    private <T> T main(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}

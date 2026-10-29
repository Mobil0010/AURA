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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(AndroidJUnit4.class)
public class SettingsEqDesignTest {
    final android.app.Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();
    final Context c = instrumentation.getTargetContext();
    MainActivity activity;

    @Before
    public void setup() {
        AudioSettings.init(c);
        for (String key : List.of("perfect", "sox", "limiter")) AudioSettings.put(key, false);
        AudioSettings.put("mode", "android");
        AudioSettings.put("eq", true);
        AudioSettings.put("preamp", 0);
        AudioSettings.put("autoEqPreamp", true);
        AudioSettings.put("eqMode", "parametric");
        for (int i = 0; i < 7; i++)
            AudioSettings.band(
                    i,
                    new AudioSettings.Band(
                            AudioSettings.GRAPHIC_FREQUENCIES[i], i == 0 ? 6 : 0, 1, "peak"));
        AudioSettings.put("eqMode", "graphic");
        for (int i = 0; i < 7; i++)
            AudioSettings.band(
                    i,
                    new AudioSettings.Band(
                            AudioSettings.GRAPHIC_FREQUENCIES[i],
                            new double[] {3, 1, -1, -2, 0, 1, 2}[i],
                            1,
                            "peak"));
    }

    @After
    public void finish() throws Exception {
        if (activity != null)
            main(
                    () -> {
                        activity.finish();
                        return null;
                    });
        AudioSettings.put("eq", false);
        AudioSettings.put("eqMode", "parametric");
        AudioSettings.put("preamp", 0);
        c.getSharedPreferences("aura", 0).edit().putString("appearance", "dark").commit();
        Ui.configure(c);
    }

    @Test
    public void settingsEqAndAdvancedPagesInBothThemes() throws Exception {
        for (String mode : List.of("light", "dark")) {
            c.getSharedPreferences("aura", 0).edit().putString("appearance", mode).commit();
            launch();
            go("settings");
            idle();
            assertNotNull(main(() -> text(body(), "이퀄라이저")));
            capture("settings-" + mode);
            click("이퀄라이저");
            idle();
            assertNotNull(main(() -> body().findViewWithTag("eqPanel")));
            assertNotNull(main(() -> body().findViewWithTag("graphicBand6")));
            capture("eq-graphic-" + mode);
            click("파라메트릭");
            idle();
            assertEquals("parametric", AudioSettings.current.eqMode());
            capture("eq-parametric-" + mode);
            go("audio");
            idle();
            capture("audio-" + mode);
            go("sox");
            idle();
            capture("sox-" + mode);
            go("settings");
            click("라이브러리 관리");
            idle();
            assertEquals("library-settings", main(() -> get(activity, "page")));
            main(
                    () -> {
                        var toggle = findSwitch(body(), "앱 시작 시 음악 변경 확인");
                        assertNotNull(toggle);
                        boolean before =
                                c.getSharedPreferences("aura", 0).getBoolean("scanOnLaunch", false);
                        toggle.performClick();
                        assertEquals(
                                !before,
                                c.getSharedPreferences("aura", 0)
                                        .getBoolean("scanOnLaunch", false));
                        toggle.performClick();
                        assertEquals(
                                before,
                                c.getSharedPreferences("aura", 0)
                                        .getBoolean("scanOnLaunch", false));
                        activity.getOnBackPressedDispatcher().onBackPressed();
                        assertEquals("settings", get(activity, "page"));
                        return null;
                    });
            click("가사 설정");
            idle();
            assertEquals("lyrics-settings", main(() -> get(activity, "page")));
            assertNotNull(main(() -> findSwitch(body(), "LRCLIB 온라인 검색")));
            main(
                    () -> {
                        activity.getOnBackPressedDispatcher().onBackPressed();
                        assertEquals("settings", get(activity, "page"));
                        activity.finish();
                        return null;
                    });
            activity = null;
            AudioSettings.put("eqMode", "graphic");
        }
    }

    @Test
    public void verticalFaderHandlesFingerAndAccessibility() throws Exception {
        launch();
        go("eq");
        idle();
        SeekBar bar = main(() -> body().findViewWithTag("graphicBand0"));
        assertNotNull(bar);
        Rect bounds = new Rect();
        main(
                () -> {
                    assertEquals(-90, bar.getRotation(), .01);
                    assertTrue(((View) bar.getParent()).getGlobalVisibleRect(bounds));
                    return null;
                });
        List<AudioSettings.Band> before = AudioSettings.current.bands();
        float x = bounds.centerX(),
                from = bounds.top + bounds.height() * .5f,
                to = bounds.top + bounds.height() * .16f;
        long down = SystemClock.uptimeMillis();
        send(down, MotionEvent.ACTION_DOWN, x, from);
        for (int i = 1; i <= 10; i++) {
            send(down, MotionEvent.ACTION_MOVE, x, from + (to - from) * i / 10);
            Thread.sleep(12);
        }
        send(down, MotionEvent.ACTION_UP, x, to);
        idle();
        assertTrue(
                "Vertical slider did not change gain: "
                        + AudioSettings.current.bands().get(0).gain(),
                AudioSettings.current.bands().get(0).gain() > 4);
        assertEquals(60, AudioSettings.current.bands().get(0).frequency(), 0);
        for (int i = 1; i < 7; i++)
            assertEquals(before.get(i), AudioSettings.current.bands().get(i));
        main(
                () -> {
                    Bundle args = new Bundle();
                    args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 750);
                    assertTrue(
                            bar.performAccessibilityAction(
                                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS
                                            .getId(),
                                    args));
                    assertEquals(6, AudioSettings.current.bands().get(0).gain(), .03);
                    TextView applied = body().findViewWithTag("eqHeadroom");
                    assertTrue(applied.getText().toString().contains("dB"));
                    return null;
                });
    }

    @Test
    public void graphReusesEditorAndRefreshesResponseWithoutStealingEmptyTouches()
            throws Exception {
        AudioSettings.put("eqMode", "parametric");
        launch();
        go("eq");
        idle();
        main(
                () -> {
                    EqGraph graph = findGraph(body());
                    assertNotNull(graph);
                    View gain = findDescription(body(), "게인 dB");
                    assertNotNull(gain);
                    var callback = EqGraph.class.getDeclaredField("onSelect");
                    callback.setAccessible(true);
                    var select = (java.util.function.IntConsumer) callback.get(graph);
                    AudioSettings.band(0, new AudioSettings.Band(1000, 8, 1, "peak"));
                    select.accept(0);
                    assertSame(
                            "Dragging must update existing editor views",
                            gain,
                            findDescription(body(), "게인 dB"));
                    Bitmap b =
                            Bitmap.createBitmap(
                                    graph.getWidth(), graph.getHeight(), Bitmap.Config.ARGB_8888);
                    graph.draw(new Canvas(b));
                    var data = EqGraph.class.getDeclaredField("combined");
                    data.setAccessible(true);
                    double[] response = (double[]) data.get(graph);
                    int index = 125;
                    double f = 20 * Math.pow(1000, index / 250.);
                    double expected =
                            EqHeadroom.appliedPreamp(AudioSettings.current, PcmSink.outputRate);
                    for (var band : AudioSettings.current.bands())
                        expected +=
                                Biquad.response(
                                        Biquad.coefficients(band, PcmSink.outputRate),
                                        f,
                                        PcmSink.outputRate);
                    assertEquals(expected, response[index], .000001);
                    MotionEvent empty = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
                    assertFalse(graph.onTouchEvent(empty));
                    empty.recycle();
                    findSwitch(body(), "EQ 사용").performClick();
                    assertFalse(AudioSettings.current.eq());
                    graph.draw(new Canvas(b));
                    for (double value : (double[]) data.get(graph)) assertEquals(0, value, 0);
                    b.recycle();
                    return null;
                });
    }

    private void launch() throws Exception {
        activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(c, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(
                () ->
                        main(
                                () ->
                                        get(activity, "player") != null
                                                && !(Boolean) get(activity, "libraryLoading")),
                10000);
    }

    private void go(String page) throws Exception {
        main(
                () -> {
                    var method = MainActivity.class.getDeclaredMethod("go", String.class);
                    method.setAccessible(true);
                    method.invoke(activity, page);
                    return null;
                });
    }

    private void click(String label) throws Exception {
        main(
                () -> {
                    View target = text(body(), label);
                    assertNotNull(label, target);
                    while (target != null && !target.isClickable())
                        target = (View) target.getParent();
                    assertNotNull(target);
                    target.performClick();
                    return null;
                });
    }

    private TextView text(View view, String label) {
        if (view instanceof TextView t && label.contentEquals(t.getText())) return t;
        if (view instanceof ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = text(g.getChildAt(i), label);
                if (found != null) return found;
            }
        return null;
    }

    private androidx.appcompat.widget.SwitchCompat findSwitch(View view, String label) {
        TextView found = text(view, label);
        return found instanceof androidx.appcompat.widget.SwitchCompat s ? s : null;
    }

    private View findDescription(View view, String label) {
        if (label.contentEquals(
                view.getContentDescription() == null ? "" : view.getContentDescription()))
            return view;
        if (view instanceof ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findDescription(g.getChildAt(i), label);
                if (found != null) return found;
            }
        return null;
    }

    private EqGraph findGraph(View view) {
        if (view instanceof EqGraph g) return g;
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                EqGraph found = findGraph(group.getChildAt(i));
                if (found != null) return found;
            }
        return null;
    }

    private View body() throws Exception {
        return (View) get(activity, "body");
    }

    private Object get(Object object, String field) throws Exception {
        var f = object.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(object);
    }

    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> f = new FutureTask<>(action);
        instrumentation.runOnMainSync(f);
        return f.get(20, TimeUnit.SECONDS);
    }

    private void idle() throws Exception {
        instrumentation.waitForIdleSync();
        Thread.sleep(500);
    }

    private void await(Callable<Boolean> check, long ms) throws Exception {
        long until = SystemClock.elapsedRealtime() + ms;
        while (!check.call() && SystemClock.elapsedRealtime() < until) Thread.sleep(40);
        assertTrue(check.call());
    }

    private void capture(String name) throws Exception {
        Bitmap b = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(b);
        try (var output =
                new FileOutputStream(
                        new File(c.getExternalFilesDir(null), "styled-" + name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
    }

    private void send(long start, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0);
        instrumentation.sendPointerSync(e);
        e.recycle();
    }
}

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
public class SongInfoDesignTest {
    final android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    final Context c = inst.getTargetContext();
    MainActivity activity;
    String theme;
    boolean scan;

    @Before
    public void setup() {
        theme = c.getSharedPreferences("aura", 0).getString("appearance", "dark");
        scan = c.getSharedPreferences("aura", 0).getBoolean("scanOnLaunch", false);
        c.getSharedPreferences("aura", 0).edit().putBoolean("scanOnLaunch", false).commit();
        AudioSettings.init(c);
        AudioSettings.put("mode", "android");
        AudioSettings.put("perfect", false);
        AudioSettings.put("eq", false);
        AudioSettings.put("sox", false);
        AudioSettings.put("bits", 0);
        AudioSettings.put("rate", 0);
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
        AudioSettings.put("sox", false);
        AudioSettings.put("bits", 0);
        AudioSettings.put("rate", 0);
        c.getSharedPreferences("aura", 0)
                .edit()
                .putString("appearance", theme)
                .putBoolean("scanOnLaunch", scan)
                .commit();
    }

    File copy(String name) throws Exception {
        File file = new File(c.getCacheDir(), "info-" + name);
        try (var in = inst.getContext().getAssets().open(name);
                var out = new FileOutputStream(file)) {
            out.write(Io.read(in));
        }
        return file;
    }

    @Test
    public void realFlacInformationUsesOriginal24Bits48000Hz() throws Exception {
        File file = copy("pcm24.flac");
        Track track =
                new Track(
                        Uri.fromFile(file).toString(),
                        "AURA",
                        "Artist",
                        "Album",
                        "Music",
                        1000,
                        "");
        SongInfo.Info info = SongInfo.read(c, track);
        assertEquals("24 bit", info.rows.get("비트 깊이"));
        assertEquals("48,000 Hz", info.rows.get("샘플레이트"));
        assertEquals("2채널", info.rows.get("채널"));
        assertTrue(info.rows.get("코덱").contains("flac"));
        assertTrue(info.rows.get("파일 크기").contains("바이트"));
        file.delete();
    }

    @Test
    public void wavAndDsfHeadersReportSourceFormats() throws Exception {
        for (String name : List.of("tone.wav", "tone.dsf")) {
            File file = copy(name);
            SongInfo.Info info =
                    SongInfo.read(
                            c,
                            new Track(
                                    Uri.fromFile(file).toString(),
                                    name,
                                    "AURA",
                                    "Album",
                                    "",
                                    0,
                                    ""));
            assertTrue(info.rows.containsKey("샘플레이트"));
            if (name.endsWith("dsf")) {
                assertEquals("1 bit DSD", info.rows.get("비트 깊이"));
                assertEquals("DSD / DSF", info.rows.get("코덱"));
            } else assertEquals("16 bit", info.rows.get("비트 깊이"));
            file.delete();
        }
    }

    @Test
    public void unavailableSourceKeepsKnownMetadataAndUnknownTechnicalFields() throws Exception {
        var info =
                SongInfo.read(
                        c,
                        new Track(
                                "content://missing/unknown",
                                "Keep title",
                                "Keep artist",
                                "Keep album",
                                "Music",
                                90000,
                                ""));
        assertEquals("Keep title", info.rows.get("제목"));
        assertEquals("확인할 수 없음", info.rows.get("비트 깊이"));
    }

    @Test
    public void signalDescribesActualResamplingAndNeverClaimsFloatAsInteger() {
        var v = new SignalFormat(44100, 24, false, 192000, 24, false, 2, false, true);
        assertEquals("24 bit / 44.1 kHz", v.input());
        assertEquals("24 bit / 192 kHz", v.output());
        assertTrue(v.processing().contains("SoX"));
        assertTrue(
                new SignalFormat(48000, 32, true, 48000, 16, false, 2, false, false)
                        .input()
                        .contains("float"));
    }

    @Test
    public void playerMenuAndInfoScreensUseAuraStyleInBothThemes() throws Exception {
        File file = copy("tone.wav");
        Track t =
                new Track(
                        Uri.fromFile(file).toString(),
                        "Blue Hour",
                        "AURA Sessions",
                        "Quiet Tides",
                        "Music/Quiet Tides",
                        3000,
                        "");
        Bitmap art = designCover();
        try {
            ArtworkStore.save(c, t.uri, art);
        } finally {
            art.recycle();
        }
        ArtworkView.clearCache();
        for (String mode : List.of("dark", "light")) {
            c.getSharedPreferences("aura", 0).edit().putString("appearance", mode).commit();
            activity =
                    (MainActivity)
                            inst.startActivitySync(
                                    new Intent(c, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(
                    () -> main(() -> get("player") != null && !(Boolean) get("libraryLoading")),
                    10000);
            main(
                    () -> {
                        set("tracks", List.of(t));
                        player().setMediaItem(t.item());
                        player().setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
                        player().prepare();
                        player().play();
                        go("player");
                        return null;
                    });
            await(() -> PcmSink.signal != null, 7000);
            Thread.sleep(500);
            capture("player-quality-" + mode);
            main(
                    () -> {
                        AudioSettings.put("sox", true);
                        AudioSettings.put("rate", 192000);
                        AudioSettings.put("bits", 24);
                        PlaybackService.instance.applyAudioSettings();
                        return null;
                    });
            await(() -> PcmSink.signal != null && PcmSink.signal.outputRate() == 192000, 7000);
            Thread.sleep(450);
            assertTrue(PcmSink.signal.sox());
            capture("player-resampled-" + mode);
            main(
                    () -> {
                        var m = MainActivity.class.getDeclaredMethod("trackActions", Track.class);
                        m.setAccessible(true);
                        m.invoke(activity, t);
                        return null;
                    });
            Thread.sleep(350);
            capture("song-menu-" + mode);
            assertNotNull(inst.getUiAutomation().getRootInActiveWindow());
            clickText("곡 정보");
            Thread.sleep(600);
            capture("song-info-" + mode);
            clickText("닫기");
            main(
                    () -> {
                        player().stop();
                        player().clearMediaItems();
                        activity.finish();
                        return null;
                    });
            activity = null;
            AudioSettings.put("sox", false);
            AudioSettings.put("rate", 0);
            AudioSettings.put("bits", 0);
        }
    }

    @Test
    public void compactSummaryAndAdaptivePlayerArtwork() throws Exception {
        assertEquals(
                "24 bit · 44.1 kHz",
                new SignalFormat(44100, 24, false, 44100, 16, false, 2, false, false).compact());
        assertTrue(
                new SignalFormat(44100, 24, false, 192000, 24, false, 2, false, true)
                        .compact()
                        .contains("→"));
        assertArrayEquals(new int[] {200, 100}, ArtworkView.fit(200, 200, 800, 400));
        assertArrayEquals(new int[] {100, 200}, ArtworkView.fit(200, 200, 400, 800));
        File file = copy("tone.wav");
        int[] fixedPosition = new int[4];
        for (boolean portrait : new boolean[] {false, true}) {
            Track t =
                    new Track(
                            Uri.fromFile(file).toString() + "#" + portrait,
                            "Blue Hour",
                            "AURA Sessions",
                            "Quiet Tides",
                            "Music",
                            3000,
                            "");
            Bitmap square = designCover(),
                    art =
                            Bitmap.createBitmap(
                                    square,
                                    portrait ? 512 : 0,
                                    portrait ? 0 : 512,
                                    portrait ? 1024 : 2048,
                                    portrait ? 2048 : 1024);
            ArtworkStore.save(c, t.uri, art);
            art.recycle();
            square.recycle();
            ArtworkView.clearCache();
            activity =
                    (MainActivity)
                            inst.startActivitySync(
                                    new Intent(c, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(
                    () -> main(() -> get("player") != null && !(Boolean) get("libraryLoading")),
                    10000);
            main(
                    () -> {
                        set("tracks", List.of(t));
                        player().setMediaItem(t.item());
                        player().setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
                        player().prepare();
                        player().play();
                        go("player");
                        return null;
                    });
            await(() -> PcmSink.signal != null, 7000);
            Thread.sleep(750);
            main(
                    () -> {
                        TextView summary = (TextView) get("signalSummary");
                        assertFalse(summary.getText().toString().contains("→"));
                        assertTrue(summary.getHeight() <= Ui.dp(c, 40));
                        View region =
                                activity.getWindow()
                                        .getDecorView()
                                        .findViewWithTag("player-artwork-region");
                        assertNotNull(region);
                        ArtworkView artwork =
                                (ArtworkView) ((android.view.ViewGroup) region).getChildAt(0);
                        assertEquals(artwork.getWidth(), artwork.getHeight());
                        assertEquals(
                                portrait ? 0.5 : 2.0,
                                artwork.image().getWidth() / (double) artwork.image().getHeight(),
                                0.02);
                        int[] xy = new int[2];
                        summary.getLocationOnScreen(xy);
                        if (!portrait) {
                            fixedPosition[0] = xy[0];
                            fixedPosition[1] = xy[1];
                            fixedPosition[2] = region.getHeight();
                            fixedPosition[3] = artwork.getWidth();
                        } else {
                            assertEquals(fixedPosition[0], xy[0]);
                            assertEquals(fixedPosition[1], xy[1]);
                            assertEquals(fixedPosition[2], region.getHeight());
                            assertEquals(fixedPosition[3], artwork.getWidth());
                        }
                        return null;
                    });
            capture(portrait ? "player-adaptive-portrait" : "player-adaptive-landscape");
            main(
                    () -> {
                        player().stop();
                        player().clearMediaItems();
                        activity.finish();
                        return null;
                    });
            activity = null;
        }
    }

    Bitmap designCover() {
        Bitmap cover = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cover);
        Paint p = new Paint(3);
        p.setShader(
                new LinearGradient(
                        0,
                        0,
                        0,
                        2048,
                        new int[] {0xff10293f, 0xff729cab, 0xffe3b797},
                        null,
                        Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 2048, 2048, p);
        p.setShader(null);
        p.setColor(0xfff6d8b0);
        canvas.drawCircle(1320, 790, 300, p);
        p.setColor(0xff24475b);
        Path water = new Path();
        water.moveTo(0, 1230);
        water.cubicTo(650, 930, 1280, 1450, 2048, 1130);
        water.lineTo(2048, 2048);
        water.lineTo(0, 2048);
        water.close();
        canvas.drawPath(water, p);
        p.setColor(0xff163046);
        canvas.drawRect(0, 1620, 2048, 2048, p);
        p.setColor(0xff80a5b4);
        p.setStrokeWidth(2);
        for (int y = 1340; y < 1570; y += 23)
            canvas.drawLine(150 + (y % 130), y, 1850 - (y % 180), y, p);
        p.setColor(0xfff5e8d5);
        p.setTypeface(Typeface.create("sans-serif-light", 0));
        p.setTextSize(140);
        canvas.drawText("QUIET TIDES", 140, 1870, p);
        p.setTextSize(38);
        canvas.drawText("A U R A   S E S S I O N S", 150, 1950, p);
        return cover;
    }

    void clickText(String text) throws Exception {
        var root = inst.getUiAutomation().getRootInActiveWindow();
        for (var match : root.findAccessibilityNodeInfosByText(text)) {
            for (var n = match; n != null; n = n.getParent())
                if (n.isClickable()) {
                    assertTrue(
                            n.performAction(
                                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
                    Thread.sleep(150);
                    return;
                }
        }
        fail("Cannot click " + text);
    }

    void capture(String name) throws Exception {
        Bitmap b = inst.getUiAutomation().takeScreenshot();
        try (var out = new FileOutputStream(new File(c.getExternalFilesDir(null), name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        b.recycle();
    }

    androidx.media3.session.MediaController player() throws Exception {
        return (androidx.media3.session.MediaController) get("player");
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

    void go(String p) throws Exception {
        var m = MainActivity.class.getDeclaredMethod("go", String.class);
        m.setAccessible(true);
        m.invoke(activity, p);
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

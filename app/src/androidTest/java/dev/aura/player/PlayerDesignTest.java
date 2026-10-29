package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.session.MediaController;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Original geometric test cover and synthetic lyrics; no demo content ships in the app. */
@RunWith(AndroidJUnit4.class)
public class PlayerDesignTest {
    final android.app.Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();
    final Context c = instrumentation.getTargetContext();

    @Test
    public void playerWithArtworkInBothThemes() throws Exception {
        AudioSettings.init(c);
        AudioSettings.put("mode", "android");
        AudioSettings.put("perfect", false);
        AudioSettings.put("eq", false);
        File tone = new File(c.getCacheDir(), "design-tone.wav"),
                lrc = new File(c.getCacheDir(), "design-tone.lrc");
        try (var in = instrumentation.getContext().getAssets().open("tone.wav");
                var out = new FileOutputStream(tone)) {
            out.write(Io.read(in));
        }
        try (var out = new FileOutputStream(lrc)) {
            out.write(
                    "[00:00.00]Slow down and stay a while\n[00:01.50]Under the quiet sky"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Track t =
                new Track(
                        Uri.fromFile(tone).toString(),
                        "Blue Hour",
                        "AURA Sessions",
                        "Quiet Tides",
                        "Music",
                        3000,
                        Uri.fromFile(lrc).toString());
        Bitmap cover = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cover);
        Paint p = new Paint(3);
        p.setShader(
                new LinearGradient(
                        0,
                        0,
                        0,
                        600,
                        new int[] {0xff173a58, 0xff729cab, 0xffdfb89b},
                        null,
                        Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 600, 600, p);
        p.setShader(null);
        p.setColor(0xfff6d8b0);
        canvas.drawCircle(380, 240, 92, p);
        p.setColor(0xff24475b);
        Path water = new Path();
        water.moveTo(0, 360);
        water.cubicTo(180, 290, 380, 420, 600, 330);
        water.lineTo(600, 600);
        water.lineTo(0, 600);
        water.close();
        canvas.drawPath(water, p);
        p.setColor(0xff163046);
        canvas.drawRect(0, 470, 600, 600, p);
        p.setColor(0xfff5e8d5);
        p.setTextSize(44);
        p.setTypeface(Typeface.create("sans-serif-light", 0));
        canvas.drawText("QUIET TIDES", 42, 540, p);
        File file = Library.artFile(c, t.uri);
        file.getParentFile().mkdirs();
        try (var out = new FileOutputStream(file)) {
            cover.compress(Bitmap.CompressFormat.JPEG, 92, out);
        }
        cover.recycle();
        ArtworkView.clearCache();
        for (String mode : List.of("light", "dark")) {
            c.getSharedPreferences("aura", 0)
                    .edit()
                    .putString("appearance", mode)
                    .putBoolean("onlineLyrics", false)
                    .commit();
            MainActivity a =
                    (MainActivity)
                            instrumentation.startActivitySync(
                                    new Intent(c, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                long until = System.currentTimeMillis() + 10000;
                while (main(() -> get(a, "player")) == null && System.currentTimeMillis() < until)
                    Thread.sleep(50);
                main(
                        () -> {
                            var tracks = MainActivity.class.getDeclaredField("tracks");
                            tracks.setAccessible(true);
                            tracks.set(a, List.of(t));
                            var loading = MainActivity.class.getDeclaredField("libraryLoading");
                            loading.setAccessible(true);
                            loading.set(a, false);
                            MediaController player = (MediaController) get(a, "player");
                            assertNotNull(player);
                            player.setMediaItem(t.item());
                            player.prepare();
                            var go = MainActivity.class.getDeclaredMethod("go", String.class);
                            go.setAccessible(true);
                            go.invoke(a, "player");
                            player.setShuffleModeEnabled(false);
                            player.setRepeatMode(0);
                            ((View) get(a, "shuffleButton")).performClick();
                            assertTrue(player.getShuffleModeEnabled());
                            ((View) get(a, "shuffleButton")).performClick();
                            assertFalse(player.getShuffleModeEnabled());
                            for (int expected : new int[] {2, 1, 0}) {
                                ((View) get(a, "repeatButton")).performClick();
                                assertEquals(expected, player.getRepeatMode());
                            }
                            return null;
                        });
                instrumentation.waitForIdleSync();
                Thread.sleep(650);
                main(
                        () -> {
                            assertEquals("player", get(a, "page"));
                            assertEquals(0, ((View) get(a, "scroll")).getScrollY());
                            assertTrue(hasCover(a.getWindow().getDecorView()));
                            return null;
                        });
                Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
                assertNotNull(image);
                try (var out =
                        new FileOutputStream(
                                new File(
                                        c.getExternalFilesDir(null),
                                        "preview-player-" + mode + ".png"))) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            } finally {
                main(
                        () -> {
                            a.finish();
                            return null;
                        });
            }
        }
    }

    private boolean hasCover(View v) {
        if (v instanceof ArtworkView a) {
            assertEquals(a.getWidth(), a.getHeight());
            return a.image().getDrawable() != null;
        }
        if (v instanceof ViewGroup parent)
            for (int i = 0; i < parent.getChildCount(); i++)
                if (hasCover(parent.getChildAt(i))) return true;
        return false;
    }

    private Object get(Object object, String key) throws Exception {
        var f = object.getClass().getDeclaredField(key);
        f.setAccessible(true);
        return f.get(object);
    }

    private <T> T main(Callable<T> work) throws Exception {
        FutureTask<T> result = new FutureTask<>(work);
        instrumentation.runOnMainSync(result);
        return result.get(15, TimeUnit.SECONDS);
    }
}

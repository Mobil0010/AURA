package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.RecyclerView;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(AndroidJUnit4.class)
public class TrackFormatTest {
    final android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    final Context c = inst.getTargetContext();

    @Test
    public void labelsUseIndexedEvidenceAndNeverGuessFlac() throws Exception {
        assertEquals(
                "FLAC", TrackFormat.label("content://music/primary%3AMusic%2FSONG.FLAC", "", ""));
        assertEquals("MP3", TrackFormat.label("content://music/327", "", "audio/mpeg"));
        assertEquals(
                "FLAC",
                TrackFormat.label(
                        DriveUris.file("test", "id", "Blue Hour.FLAC").toString(),
                        "",
                        "application/octet-stream"));
        assertEquals("M4A", TrackFormat.label("file:///song.m4a", "", "audio/mp4"));
        assertEquals("OPUS", TrackFormat.label("file:///song.opus", "", "audio/ogg"));
        assertEquals("AIFF", TrackFormat.label("file:///song.aif", "", ""));
        assertEquals("DSF", TrackFormat.label("content://music/99", "dsf", ""));
        assertEquals("", TrackFormat.label("content://music/99", "", "application/octet-stream"));
        assertEquals("", TrackFormat.label("content://music/99?name=untrusted.flac", "", ""));
        Track stored =
                new Track(
                        "content://music/opaque",
                        "Title",
                        "Artist",
                        "Album",
                        "Folder",
                        1000,
                        "",
                        "mp3");
        assertEquals("MP3", Track.from(stored.json()).formatLabel);
        Track flac =
                new Track("file:///sample.flac", "Title", "Artist", "Album", "Folder", 1000, "");
        assertEquals("Hi-Res", flac.withAudioFormat(48000, 24).formatLabel);
        assertEquals("FLAC", flac.withAudioFormat(44100, 24).formatLabel);
        assertEquals("FLAC", flac.withAudioFormat(96000, 16).formatLabel);
        assertEquals("FLAC", flac.formatLabel);
        Track round =
                Track.from(
                        flac.withAudioFormat(96000, 24)
                                .withGenre("Jazz")
                                .withNumbers(2, 1)
                                .withPath(List.of())
                                .json());
        assertEquals("Hi-Res", round.formatLabel);
        assertEquals(96000, round.sampleRate);
        assertEquals(24, round.bitsPerSample);
        try (var input = inst.getContext().getAssets().open("pcm24.flac")) {
            byte[] head = new byte[42];
            new DataInputStream(input).readFully(head);
            var info = TrackFormat.flacInfo(head, head.length);
            assertEquals(48000, info.sampleRate());
            assertEquals(24, info.bitsPerSample());
            assertEquals(0, TrackFormat.flacInfo(head, 41).sampleRate());
            head[0] = 0;
            assertEquals(0, TrackFormat.flacInfo(head, 42).sampleRate());
        }
    }

    @Test
    public void badgesInAllSongListsAndBothThemes() throws Exception {
        List<Track> songs = new ArrayList<>();
        String[] names = {
            "달빛 산책",
            "Blue Hour",
            "조용한 오후",
            "First Light",
            "바람이 머문 자리",
            "Soft Landing",
            "Evening Walk",
            "이름 없는 멜로디"
        };
        String[] extensions = {"flac", "mp3", "flac", "wav", "aac", "opus", "aiff", ""};
        for (int i = 0; i < names.length; i++) {
            Track t =
                    new Track(
                                    "content://aura-format-fixture/"
                                            + i
                                            + (extensions[i].isEmpty() ? "" : "." + extensions[i]),
                                    names[i],
                                    "AURA Sessions",
                                    "Evening Collection",
                                    "Music/Singles",
                                    219000 + i * 11000,
                                    "")
                            .withNumbers(i + 1, 1)
                            .withPath(
                                    List.of(
                                            new FolderTree.Part("root", "Music"),
                                            new FolderTree.Part("singles", "Singles")));
            if (i == 2) t = t.withAudioFormat(96000, 24);
            songs.add(t);
            cover(t, i);
        }
        for (String theme : List.of("dark", "light")) {
            c.getSharedPreferences("aura", 0)
                    .edit()
                    .putString("appearance", theme)
                    .putBoolean("onlineLyrics", false)
                    .putBoolean("checkLibraryOnStart", false)
                    .commit();
            MainActivity a =
                    (MainActivity)
                            inst.startActivitySync(
                                    new Intent(c, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                await(
                        () ->
                                main(
                                        () ->
                                                get(a, "player") != null
                                                        && !(Boolean) get(a, "libraryLoading")),
                        12000);
                main(
                        () -> {
                            set(a, "tracks", songs);
                            set(a, "source", "local");
                            set(a, "query", "");
                            set(a, "tab", "곡");
                            go(a, "library");
                            return null;
                        });
                ready(a);
                capture("songs-" + theme);
                verifyRecycling(a, songs);
                main(
                        () -> {
                            set(a, "tab", "폴더");
                            set(a, "folderCursor", "local/root/singles");
                            go(a, "library");
                            return null;
                        });
                ready(a);
                capture("folder-" + theme);
                main(
                        () -> {
                            a.musicOpen("Evening Collection", songs);
                            return null;
                        });
                ready(a);
                capture("album-" + theme);
                main(
                        () -> {
                            a.musicOpen("나의 플레이리스트", songs);
                            return null;
                        });
                ready(a);
                capture("playlist-" + theme);
                main(
                        () -> {
                            MediaController p = (MediaController) get(a, "player");
                            p.setMediaItems(
                                    songs.stream()
                                            .map(Track::item)
                                            .collect(java.util.stream.Collectors.toList()));
                            go(a, "queue");
                            return null;
                        });
                ready(a);
                capture("queue-" + theme);
            } finally {
                main(
                        () -> {
                            MediaController p = (MediaController) get(a, "player");
                            if (p != null) {
                                p.stop();
                                p.clearMediaItems();
                            }
                            a.finish();
                            return null;
                        });
            }
        }
    }

    private void ready(MainActivity a) throws Exception {
        await(
                () ->
                        main(
                                () -> {
                                    RecyclerView list = (RecyclerView) get(a, "activeList");
                                    return list != null
                                            && list.isAttachedToWindow()
                                            && list.getChildCount() > 0;
                                }),
                8000);
        inst.waitForIdleSync();
        Thread.sleep(400);
        main(
                () -> {
                    RecyclerView list = (RecyclerView) get(a, "activeList");
                    int visible = 0;
                    for (int i = 0; i < list.getChildCount(); i++) {
                        View row = list.getChildAt(i);
                        TextView badge = row.findViewWithTag("trackFormat"),
                                duration = row.findViewWithTag("trackDuration");
                        if (badge != null && badge.getVisibility() == View.VISIBLE) {
                            int[] b = new int[2], d = new int[2];
                            badge.getLocationOnScreen(b);
                            duration.getLocationOnScreen(d);
                            assertTrue(b[0] + badge.getWidth() <= d[0]);
                            assertEquals(
                                    b[1] + badge.getHeight() / 2,
                                    d[1] + duration.getHeight() / 2,
                                    2);
                            assertTrue(
                                    badge.getWidth()
                                            > badge.getPaddingLeft() + badge.getPaddingRight());
                            visible++;
                        }
                    }
                    assertTrue(visible > 0);
                    return null;
                });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void verifyRecycling(MainActivity a, List<Track> songs) throws Exception {
        main(
                () -> {
                    RecyclerView list = (RecyclerView) get(a, "activeList");
                    RecyclerView.Adapter adapter = list.getAdapter();
                    RecyclerView.ViewHolder holder =
                            adapter.createViewHolder(list, adapter.getItemViewType(0));
                    adapter.bindViewHolder(holder, 0);
                    TextView badge = holder.itemView.findViewWithTag("trackFormat");
                    assertEquals("FLAC", badge.getText().toString());
                    adapter.bindViewHolder(holder, songs.size() - 1);
                    assertEquals(View.GONE, badge.getVisibility());
                    adapter.bindViewHolder(holder, 1);
                    assertSame(badge, holder.itemView.findViewWithTag("trackFormat"));
                    assertEquals(View.VISIBLE, badge.getVisibility());
                    assertEquals("MP3", badge.getText().toString());
                    return null;
                });
    }

    private void cover(Track t, int i) throws Exception {
        Bitmap b = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Paint p = new Paint(3);
        int[] colors = {0xff254c69, 0xff675e88, 0xff547768, 0xffaa7863};
        canvas.drawColor(colors[i % 4]);
        p.setColor(0xffebd6b0);
        canvas.drawCircle(166, 74, 37, p);
        p.setColor(0x66313b56);
        canvas.drawRect(0, 144, 240, 240, p);
        p.setColor(Color.WHITE);
        p.setTextSize(19);
        canvas.drawText("AURA SESSIONS", 16, 208, p);
        File f = Library.artFile(c, t.uri);
        f.getParentFile().mkdirs();
        try (var out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        b.recycle();
    }

    private void capture(String name) throws Exception {
        Bitmap b = inst.getUiAutomation().takeScreenshot();
        assertNotNull(b);
        try (var out =
                new FileOutputStream(
                        new File(c.getExternalFilesDir(null), "format-" + name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        b.recycle();
    }

    private Object get(Object o, String key) throws Exception {
        var f = MainActivity.class.getDeclaredField(key);
        f.setAccessible(true);
        return f.get(o);
    }

    private void set(Object o, String key, Object value) throws Exception {
        var f = MainActivity.class.getDeclaredField(key);
        f.setAccessible(true);
        f.set(o, value);
    }

    private void go(MainActivity a, String page) throws Exception {
        var m = MainActivity.class.getDeclaredMethod("go", String.class);
        m.setAccessible(true);
        m.invoke(a, page);
    }

    private <T> T main(Callable<T> work) throws Exception {
        FutureTask<T> f = new FutureTask<>(work);
        inst.runOnMainSync(f);
        return f.get(20, TimeUnit.SECONDS);
    }

    private void await(Callable<Boolean> condition, long timeout) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.call() && android.os.SystemClock.elapsedRealtime() < end)
            Thread.sleep(50);
        assertTrue(condition.call());
    }
}

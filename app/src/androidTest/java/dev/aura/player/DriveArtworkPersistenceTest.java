package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(AndroidJUnit4.class)
public class DriveArtworkPersistenceTest {
    final android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    final Context c = inst.getTargetContext();
    final List<Track> tracks = new ArrayList<>();

    Track track() throws Exception {
        Track t =
                new Track(
                        DriveUris.file("art-persistence", UUID.randomUUID().toString(), "song.flac")
                                .toString(),
                        "Test",
                        "AURA",
                        "Cover",
                        "Folder",
                        1000,
                        "");
        tracks.add(t);
        return t;
    }

    @After
    public void cleanup() {
        for (Track t : tracks) ArtworkStore.remove(c, t.uri);
        ArtworkView.clearCache();
    }

    void picture(File file) throws Exception {
        file.getParentFile().mkdirs();
        Bitmap b = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.BLUE);
        try (var out = new FileOutputStream(file)) {
            b.compress(Bitmap.CompressFormat.JPEG, 95, out);
        }
        b.recycle();
    }

    @Test
    public void driveCoverSurvivesCacheEvictionAndMemoryReload() throws Exception {
        Track t = track();
        File saved = Library.artFile(c, t.uri);
        picture(saved);
        ArtworkStore.mark(saved);
        assertTrue(
                saved.getCanonicalPath()
                        .startsWith(c.getFilesDir().getCanonicalPath() + File.separator));
        File legacy = Library.legacyArtFile(c, t.uri);
        picture(legacy);
        assertTrue(legacy.delete());
        ArtworkView.clearCache();
        ArtworkView view = main(() -> new ArtworkView(c, t, 52));
        long end = android.os.SystemClock.elapsedRealtime() + 5000;
        while (main(() -> view.image().getDrawable() == null)
                && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(25);
        assertNotNull(main(() -> view.image().getDrawable()));
        assertTrue(ArtworkStore.current(c, t.uri));
    }

    @Test
    public void legacyHighQualityCoverMigratesWithoutLosingQualityStamp() throws Exception {
        Track t = track();
        File legacy = Library.legacyArtFile(c, t.uri);
        picture(legacy);
        ArtworkStore.mark(legacy);
        byte[] before = java.nio.file.Files.readAllBytes(legacy.toPath());
        ArtworkStore.restoreDriveCover(c, t.uri);
        assertArrayEquals(
                before, java.nio.file.Files.readAllBytes(Library.artFile(c, t.uri).toPath()));
        assertFalse(legacy.exists());
        assertFalse(new File(legacy + ".hq2").exists());
        assertTrue(ArtworkStore.current(c, t.uri));
    }

    @Test
    public void smallLegacyCoverIsNotMislabelledAsHighQuality() throws Exception {
        Track t = track();
        picture(Library.legacyArtFile(c, t.uri));
        ArtworkStore.restoreDriveCover(c, t.uri);
        assertTrue(Library.artFile(c, t.uri).isFile());
        assertFalse(ArtworkStore.current(c, t.uri));
    }

    @Test
    public void cleanupRemovesPersistentAndLegacyCopiesAndLocalStorageIsUnchanged()
            throws Exception {
        Track t = track();
        picture(Library.artFile(c, t.uri));
        ArtworkStore.mark(Library.artFile(c, t.uri));
        picture(Library.legacyArtFile(c, t.uri));
        ArtworkStore.mark(Library.legacyArtFile(c, t.uri));
        ArtworkStore.remove(c, t.uri);
        assertFalse(Library.artFile(c, t.uri).exists());
        assertFalse(Library.legacyArtFile(c, t.uri).exists());
        assertFalse(new File(Library.artFile(c, t.uri) + ".hq2").exists());
        assertEquals(
                Library.legacyArtFile(c, "content://local/song.flac"),
                Library.artFile(c, "content://local/song.flac"));
    }

    <T> T main(Callable<T> work) throws Exception {
        FutureTask<T> f = new FutureTask<>(work);
        inst.runOnMainSync(f);
        return f.get(10, TimeUnit.SECONDS);
    }
}

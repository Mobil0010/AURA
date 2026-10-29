package dev.aura.player;

import static org.junit.Assert.*;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class ScanAndPolicyTest {
    private final Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Uri provider = Uri.parse("content://dev.aura.player.test.documents");

    private Uri tree(String id) {
        return DocumentsContract.buildTreeDocumentUri(provider.getAuthority(), id);
    }

    @Before
    public void reset() {
        c.getContentResolver().call(provider, "test.reset", null, null);
        new File(c.getFilesDir(), "library-metadata.json").delete();
    }

    @Test
    public void multipleRootsDeduplicateOverlapsAndReuseMetadata() throws Exception {
        Library library = new Library(c);
        List<Library.Progress> events = new ArrayList<>();
        var list = library.scan(List.of(tree("A"), tree("A/sub"), tree("B")), events::add);
        assertEquals(3, list.size());
        assertEquals(3, library.load().size());
        assertTrue(list.stream().allMatch(t -> t.duration > 0));
        int opens = c.getContentResolver().call(provider, "test.stats", null, null).getInt("opens");
        assertTrue(opens >= 3);
        assertEquals(3, events.get(events.size() - 1).completed());
        assertEquals(3, events.get(events.size() - 1).total());
        assertTrue(events.stream().anyMatch(p -> p.total() == -1));
        library.scan(List.of(tree("A"), tree("B")), p -> {});
        assertEquals(
                "Unchanged tracks must not reopen audio files",
                opens,
                c.getContentResolver().call(provider, "test.stats", null, null).getInt("opens"));
    }

    @Test
    public void failedFolderPreservesPreviouslyCommittedLibrary() throws Exception {
        Library library = new Library(c);
        library.scan(List.of(tree("A"), tree("B")), p -> {});
        c.getContentResolver().call(provider, "test.fail", null, null);
        try {
            library.scan(List.of(tree("A"), tree("B")), p -> {});
            fail("Disconnected root must fail scan");
        } catch (Exception expected) {
        }
        assertEquals(3, library.load().size());
    }

    @Test
    public void oldFolderMigratesAndMultipleSelectionsPersist() {
        var prefs = c.getSharedPreferences("aura", 0);
        String old = prefs.getString("tree", null), saved = prefs.getString("trees", null);
        try {
            prefs.edit().remove("trees").putString("tree", tree("A").toString()).commit();
            assertEquals(List.of(tree("A")), Library.roots(c));
            Library.saveRoots(c, List.of(tree("A"), tree("B"), tree("A")));
            assertEquals(List.of(tree("A"), tree("B")), Library.roots(c));
            Library.saveRoots(c, List.of());
            assertTrue(Library.roots(c).isEmpty());
        } finally {
            prefs.edit().remove("tree").remove("trees").commit();
            if (old != null) prefs.edit().putString("tree", old).commit();
            if (saved != null) prefs.edit().putString("trees", saved).commit();
        }
    }

    @Test
    public void interruptionPoliciesPreservePerfectSamples() {
        assertFalse(PlaybackPolicy.pauseForDuck(false, false, false));
        assertTrue(PlaybackPolicy.pauseForDuck(true, false, false));
        assertTrue(PlaybackPolicy.pauseForDuck(false, true, false));
        assertTrue(PlaybackPolicy.pauseForDuck(false, false, true));
        assertTrue(PlaybackPolicy.resumeAfterDetach(true, false));
        assertFalse(PlaybackPolicy.resumeAfterDetach(true, true));
        assertFalse(PlaybackPolicy.resumeAfterDetach(false, false));
    }

    @Test
    public void scanSurvivesActivityFinishAndPostsProgress() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var prefs = c.getSharedPreferences("aura", 0);
        String saved = prefs.getString("trees", null), old = prefs.getString("tree", null);
        c.getContentResolver().call(provider, "test.slow", null, null);
        Library.saveRoots(c, List.of(tree("A"), tree("B")));
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(c, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> LibraryScanService.start(activity));
            long end = SystemClock.elapsedRealtime() + 5000;
            while (!LibraryScanService.state.running() && SystemClock.elapsedRealtime() < end)
                Thread.sleep(20);
            assertTrue(LibraryScanService.state.running());
            while (Arrays.stream(
                                    c.getSystemService(android.app.NotificationManager.class)
                                            .getActiveNotifications())
                            .noneMatch(n -> n.getId() == 204)
                    && SystemClock.elapsedRealtime() < end) Thread.sleep(20);
            assertTrue(
                    "Foreground scan notification was not posted",
                    Arrays.stream(
                                    c.getSystemService(android.app.NotificationManager.class)
                                            .getActiveNotifications())
                            .anyMatch(n -> n.getId() == 204));
            instrumentation.runOnMainSync(activity::finish);
            while (LibraryScanService.state.running()
                    && SystemClock.elapsedRealtime() < end + 15000) Thread.sleep(40);
            assertFalse(LibraryScanService.state.running());
            assertEquals(3, new Library(c).load().size());
            assertTrue(LibraryScanService.state.message().contains("3곡"));
        } finally {
            prefs.edit().remove("trees").remove("tree").commit();
            if (saved != null) prefs.edit().putString("trees", saved).commit();
            if (old != null) prefs.edit().putString("tree", old).commit();
        }
    }

    @Test
    public void realPlayerDucksPausesAndResumesWithFocusSettings() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(c, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(500);
        var preferences = c.getSharedPreferences("playback", 0);
        boolean pause = preferences.getBoolean("pauseOnDuck", false),
                resume = preferences.getBoolean("resumeFocus", true);
        try {
            instrumentation.runOnMainSync(
                    () -> {
                        try {
                            AudioSettings.init(c);
                            AudioSettings.put("mode", "android");
                            AudioSettings.put("perfect", false);
                            var service = PlaybackService.instance;
                            assertNotNull(service);
                            var pf = PlaybackService.class.getDeclaredField("player");
                            pf.setAccessible(true);
                            var player = (androidx.media3.exoplayer.ExoPlayer) pf.get(service);
                            var ff = PlaybackService.class.getDeclaredField("focus");
                            ff.setAccessible(true);
                            var focus = (PlaybackFocus) ff.get(service);
                            player.stop();
                            player.clearMediaItems();
                            preferences
                                    .edit()
                                    .putBoolean("pauseOnDuck", false)
                                    .putBoolean("resumeFocus", true)
                                    .commit();
                            player.play();
                            assertTrue(player.getPlayWhenReady());
                            focus.change(
                                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
                            assertTrue(player.getPlayWhenReady());
                            assertEquals(.2f, player.getVolume(), .001f);
                            focus.change(android.media.AudioManager.AUDIOFOCUS_GAIN);
                            assertEquals(1f, player.getVolume(), .001f);
                            preferences.edit().putBoolean("pauseOnDuck", true).commit();
                            focus.change(
                                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
                            assertFalse(player.getPlayWhenReady());
                            focus.change(android.media.AudioManager.AUDIOFOCUS_GAIN);
                            assertTrue(player.getPlayWhenReady());
                            preferences.edit().putBoolean("resumeFocus", false).commit();
                            focus.change(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
                            focus.change(android.media.AudioManager.AUDIOFOCUS_GAIN);
                            assertFalse(player.getPlayWhenReady());
                            player.pause();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        } finally {
            preferences
                    .edit()
                    .putBoolean("pauseOnDuck", pause)
                    .putBoolean("resumeFocus", resume)
                    .commit();
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test
    public void cancellingBackgroundScanKeepsCommittedTracks() throws Exception {
        new Library(c).scan(List.of(tree("B")), p -> {});
        c.getContentResolver().call(provider, "test.slow", null, null);
        var prefs = c.getSharedPreferences("aura", 0);
        String saved = prefs.getString("trees", null), old = prefs.getString("tree", null);
        Library.saveRoots(c, List.of(tree("A"), tree("B")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(c, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> LibraryScanService.start(activity));
            long end = SystemClock.elapsedRealtime() + 5000;
            while (!LibraryScanService.state.running() && SystemClock.elapsedRealtime() < end)
                Thread.sleep(20);
            assertTrue(LibraryScanService.state.running());
            instrumentation.runOnMainSync(
                    () ->
                            activity.startService(
                                    new Intent(activity, LibraryScanService.class)
                                            .setAction("cancel")));
            while (LibraryScanService.state.running() && SystemClock.elapsedRealtime() < end)
                Thread.sleep(20);
            Thread.sleep(700);
            assertFalse(LibraryScanService.state.running());
            assertEquals(1, new Library(c).load().size());
            assertFalse(
                    Arrays.stream(
                                    c.getSystemService(android.app.NotificationManager.class)
                                            .getActiveNotifications())
                            .anyMatch(n -> n.getId() == 204));
        } finally {
            prefs.edit().remove("trees").remove("tree").commit();
            if (saved != null) prefs.edit().putString("trees", saved).commit();
            if (old != null) prefs.edit().putString("tree", old).commit();
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test
    public void unplugPolicyPreservesEngineAndHonorsPausePreference() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(c, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(500);
        var prefs = c.getSharedPreferences("playback", 0);
        boolean saved = prefs.getBoolean("pauseUsbDetach", true);
        try {
            instrumentation.runOnMainSync(
                    () -> {
                        try {
                            var service = PlaybackService.instance;
                            assertNotNull(service);
                            var pf = PlaybackService.class.getDeclaredField("player");
                            pf.setAccessible(true);
                            var player = (androidx.media3.exoplayer.ExoPlayer) pf.get(service);
                            var rf = PlaybackService.class.getDeclaredField("routing");
                            rf.setAccessible(true);
                            var routing = (UsbAutoRouting) rf.get(service);
                            var active = UsbAutoRouting.class.getDeclaredField("usbActive");
                            active.setAccessible(true);
                            var detach = UsbAutoRouting.class.getDeclaredMethod("detach");
                            detach.setAccessible(true);
                            player.stop();
                            player.clearMediaItems();
                            for (boolean pause : new boolean[] {true, false}) {
                                prefs.edit().putBoolean("pauseUsbDetach", pause).commit();
                                AudioSettings.put("mode", "usb");
                                AudioSettings.put("device", 0);
                                AudioSettings.put("perfect", true);
                                AudioSettings.put("dsd", "dop");
                                active.setBoolean(routing, true);
                                player.play();
                                detach.invoke(routing);
                                assertEquals("usb", AudioSettings.current.mode());
                                assertTrue(AudioSettings.current.perfect());
                                assertEquals("dop", AudioSettings.current.dsd());
                                assertEquals(!pause, player.getPlayWhenReady());
                                int device = AudioSettings.current.deviceId();
                                assertEquals(0, device);
                                player.pause();
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        } finally {
            prefs.edit().putBoolean("pauseUsbDetach", saved).commit();
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test
    public void launchScanAddsNewMusicAndRemovesDeletedMusic() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var prefs = c.getSharedPreferences("aura", 0);
        String trees = prefs.getString("trees", null), old = prefs.getString("tree", null);
        boolean launch = prefs.getBoolean("scanOnLaunch", false);
        MainActivity activity = null;
        try {
            DriveAuth.get(c).clear();
            Library.saveRoots(c, List.of(tree("A"), tree("B")));
            new Library(c).scan(Library.roots(c), p -> {});
            assertTrue(new Library(c).loadLocal().stream().anyMatch(t -> t.uri.contains("a.wav")));
            c.getContentResolver().call(provider, "test.change", null, null);
            prefs.edit().putBoolean("scanOnLaunch", true).commit();
            long before = LibraryScanService.state.revision();
            activity =
                    (MainActivity)
                            instrumentation.startActivitySync(
                                    new Intent(c, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long until = SystemClock.elapsedRealtime() + 15000;
            while ((LibraryScanService.state.running()
                            || LibraryScanService.state.revision() == before)
                    && SystemClock.elapsedRealtime() < until) Thread.sleep(40);
            assertTrue(LibraryScanService.state.revision() > before);
            var result = new Library(c).loadLocal();
            assertEquals(3, result.size());
            assertTrue(result.stream().anyMatch(t -> t.uri.contains("new.wav")));
            assertFalse(result.stream().anyMatch(t -> t.uri.contains("a.wav")));
        } finally {
            if (activity != null) {
                MainActivity screen = activity;
                instrumentation.runOnMainSync(screen::finish);
            }
            prefs.edit().remove("trees").remove("tree").putBoolean("scanOnLaunch", launch).commit();
            if (trees != null) prefs.edit().putString("trees", trees).commit();
            if (old != null) prefs.edit().putString("tree", old).commit();
        }
    }
}

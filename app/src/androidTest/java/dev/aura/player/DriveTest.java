package dev.aura.player;

import static org.junit.Assert.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class DriveTest {
    private final Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private byte[] local;

    @Before
    public void setup() throws Exception {
        File f = new File(c.getFilesDir(), "library.json");
        local = f.exists() ? java.nio.file.Files.readAllBytes(f.toPath()) : null;
        DriveAuth.get(c).clear();
        AudioSettings.init(c);
        for (String k : List.of("perfect", "sox", "eq", "limiter")) AudioSettings.put(k, false);
        AudioSettings.put("mode", "android");
        AudioSettings.put("bits", 0);
        AudioSettings.put("rate", 0);
        AudioSettings.put("device", 0);
        AudioSettings.put("preamp", 0);
        AudioSettings.put("crossfeed", 0);
        AudioSettings.put("balance", 0);
    }

    @After
    public void cleanup() throws Exception {
        DriveAuth.get(c).clear();
        File f = new File(c.getFilesDir(), "library.json");
        if (local == null) f.delete();
        else java.nio.file.Files.write(f.toPath(), local);
    }

    private Uri uri() throws Exception {
        return DriveUris.file("account", "song", "Tone.wav");
    }

    private DriveHttp http(FakeDrive server) {
        return new DriveHttp(
                server.base(),
                new DriveHttp.Tokens() {
                    public String token() {
                        return "fake-access";
                    }

                    public void invalidate(String t) {}
                });
    }

    @Test
    public void rangeStreamingReadsOnlyRequestedBytesAndSeeks() throws Exception {
        try (FakeDrive server = new FakeDrive();
                DriveDataSource source = new DriveDataSource(http(server), u -> true)) {
            long start = 3 * DriveDataSource.WINDOW + 41;
            assertEquals(
                    128,
                    source.open(
                            new DataSpec.Builder()
                                    .setUri(uri())
                                    .setPosition(start)
                                    .setLength(128)
                                    .build()));
            byte[] b = new byte[256];
            assertEquals(128, source.read(b, 0, b.length));
            assertEquals(-1, source.read(b, 0, b.length));
            assertEquals(1, server.requests.size());
            assertEquals(
                    "bytes=" + start + "-" + (start + 127),
                    server.requests.get(0).headers.get("range"));
            assertEquals(server.audio((int) start), b[0]);
            assertEquals(128, server.sent.get());
        }
    }

    @Test
    public void longStreamUsesBoundedHttpWindows() throws Exception {
        try (FakeDrive server = new FakeDrive();
                DriveDataSource source = new DriveDataSource(http(server), u -> true)) {
            long length = DriveDataSource.WINDOW * 2L + 91;
            source.open(new DataSpec.Builder().setUri(uri()).setLength(length).build());
            byte[] b = new byte[32768];
            long read = 0;
            int n;
            while ((n = source.read(b, 0, b.length)) != -1) read += n;
            assertEquals(length, read);
            assertEquals(3, server.requests.size());
            for (var request : server.requests)
                assertTrue(FakeDrive.rangeLength(request) <= DriveDataSource.WINDOW);
            assertTrue(server.sent.get() < server.total);
        }
    }

    @Test
    public void expiredAccessTokenIsRenewedOnce() throws Exception {
        try (FakeDrive server = new FakeDrive()) {
            server.rejectOld = true;
            AtomicInteger invalidations = new AtomicInteger();
            DriveHttp http =
                    new DriveHttp(
                            server.base(),
                            new DriveHttp.Tokens() {
                                public String token() {
                                    return invalidations.get() == 0 ? "old" : "new";
                                }

                                public void invalidate(String old) {
                                    assertEquals("old", old);
                                    invalidations.incrementAndGet();
                                }
                            });
            try (DriveDataSource source = new DriveDataSource(http, u -> true)) {
                source.open(new DataSpec.Builder().setUri(uri()).setLength(16).build());
                assertEquals(16, source.read(new byte[16], 0, 16));
            }
            assertEquals(1, invalidations.get());
            assertEquals(2, server.requests.size());
        }
    }

    @Test
    public void wrongRangeAndUnseekableResponseFailClosed() throws Exception {
        try (FakeDrive server = new FakeDrive()) {
            for (boolean ignore : new boolean[] {false, true}) {
                server.wrongRange = !ignore;
                server.ignoreRange = ignore;
                try (DriveDataSource source = new DriveDataSource(http(server), u -> true)) {
                    try {
                        source.open(
                                new DataSpec.Builder()
                                        .setUri(uri())
                                        .setPosition(100)
                                        .setLength(10)
                                        .build());
                        fail("Invalid range accepted");
                    } catch (IOException expected) {
                    }
                }
            }
        }
    }

    @Test
    public void unsafeRedirectAndWrongAccountNeverReceiveCredentials() throws Exception {
        try (FakeDrive server = new FakeDrive()) {
            server.redirect = true;
            try (DriveDataSource source = new DriveDataSource(http(server), u -> true)) {
                try {
                    source.open(new DataSpec.Builder().setUri(uri()).setLength(10).build());
                    fail("Untrusted redirect accepted");
                } catch (IOException expected) {
                    assertFalse(expected.getMessage().contains("fake-access"));
                }
            }
            int calls = server.requests.size();
            try (DriveDataSource source = new DriveDataSource(http(server), u -> false)) {
                try {
                    source.open(new DataSpec.Builder().setUri(uri()).build());
                    fail("Wrong account accepted");
                } catch (IOException expected) {
                }
            }
            assertEquals(calls, server.requests.size());
        }
    }

    @Test
    public void truncatedRangeDoesNotSilentlyEndPlayback() throws Exception {
        try (FakeDrive server = new FakeDrive()) {
            server.truncate = true;
            try (DriveDataSource source = new DriveDataSource(http(server), u -> true)) {
                source.open(new DataSpec.Builder().setUri(uri()).setLength(128).build());
                boolean failed = false;
                byte[] b = new byte[1024];
                try {
                    while (source.read(b, 0, b.length) != -1) {}
                } catch (IOException expected) {
                    failed = true;
                }
                assertTrue(failed);
            }
        }
    }

    @Test
    public void folderPaginationDeduplicatesAndScanDoesNotDownloadAudio() throws Exception {
        DriveAuth.get(c).accept("account", "test@example.invalid", "샘플 계정", "fake-access");
        DriveLibrary.saveRoots(
                c,
                List.of(
                        new DriveLibrary.Folder("root", "Music"),
                        new DriveLibrary.Folder("sub", "Sub")));
        try (FakeDrive server = new FakeDrive()) {
            List<Library.Progress> progress = new ArrayList<>();
            var tracks =
                    new DriveLibrary(
                                    c,
                                    new DriveApi(http(server)),
                                    (context, entry, generation) -> entry)
                            .scan(progress::add);
            assertEquals(2, tracks.size());
            assertTrue(tracks.stream().allMatch(Track::drive));
            assertTrue(tracks.stream().allMatch(t -> !t.folderPath.isEmpty()));
            assertTrue(tracks.stream().anyMatch(t -> !t.lrcUri.isEmpty()));
            assertEquals(2, progress.get(progress.size() - 1).total());
            assertTrue(server.requests.stream().noneMatch(r -> r.path.contains("alt=media")));
            assertEquals(3, server.requests.size());
        }
    }

    @Test
    public void cloudFailurePreservesIndexAndLocalTracks() throws Exception {
        DriveAuth.get(c).accept("account", "test@example.invalid", "샘플 계정", "fake-access");
        DriveLibrary.saveRoots(c, List.of(new DriveLibrary.Folder("root", "Music")));
        Track local = new Track("file:///local.wav", "Local", "Artist", "Music", "Music", 1000, "");
        java.nio.file.Files.write(
                new File(c.getFilesDir(), "library.json").toPath(),
                new JSONArray().put(local.json()).toString().getBytes(StandardCharsets.UTF_8));
        try (FakeDrive server = new FakeDrive()) {
            DriveLibrary library =
                    new DriveLibrary(
                            c, new DriveApi(http(server)), (context, entry, generation) -> entry);
            library.scan(p -> {});
            assertEquals(3, new Library(c).load().size());
            byte[] before =
                    java.nio.file.Files.readAllBytes(
                            new File(c.getFilesDir(), "drive-library.json").toPath());
            server.failSub = true;
            try {
                library.scan(p -> {});
                fail("Failed folder scan committed");
            } catch (IOException expected) {
            }
            assertArrayEquals(
                    before,
                    java.nio.file.Files.readAllBytes(
                            new File(c.getFilesDir(), "drive-library.json").toPath()));
            assertEquals(1, new Library(c).loadLocal().size());
            DriveAuth.get(c).clear();
            assertEquals(1, new Library(c).load().size());
        }
    }

    @Test
    public void scanEnrichesBeforePlaybackAndReusesUnchangedMetadata() throws Exception {
        DriveAuth.get(c).accept("account", "test@example.invalid", "샘플 계정", "fake-access");
        DriveLibrary.saveRoots(c, List.of(new DriveLibrary.Folder("root", "Music")));
        try (FakeDrive server = new FakeDrive()) {
            java.util.concurrent.atomic.AtomicInteger reads =
                    new java.util.concurrent.atomic.AtomicInteger();
            DriveLibrary library =
                    new DriveLibrary(
                            c,
                            new DriveApi(http(server)),
                            (context, entry, generation) -> {
                                reads.incrementAndGet();
                                return entry.put("title", "Embedded title")
                                        .put("artist", "Embedded artist")
                                        .put("album", "Embedded album")
                                        .put("duration", 3000)
                                        .put("enriched", true);
                            });
            var result = library.scan(p -> {});
            assertEquals(2, reads.get());
            for (Track track : result) {
                assertEquals("Embedded title", track.title);
                assertEquals("Embedded artist", track.artist);
                assertEquals("Embedded album", track.album);
                assertEquals(3000, track.duration);
                assertFalse(track.folderPath.isEmpty());
            }
            library.scan(p -> {});
            assertEquals("Unchanged files must not refetch tags", 2, reads.get());
        }
    }

    @Test
    public void nativeMetadataReaderGetsDurationWithoutStartingPlayback() throws Exception {
        DriveAuth.get(c).accept("account", "test@example.invalid", "샘플 계정", "fake-access");
        Track track =
                new Track(
                        uri().toString(),
                        "Fallback title",
                        "Fallback artist",
                        "Fallback album",
                        "Music",
                        0,
                        "");
        try (FakeDrive server = new FakeDrive(44100 * 4 + 44)) {
            JSONObject entry = track.json().put("size", server.total).put("modified", "test");
            JSONObject read =
                    DriveLibrary.readMetadata(
                            c, entry, DriveAuth.get(c).generation(), http(server));
            assertTrue(read.optBoolean("enriched"));
            assertEquals(1000, read.optLong("duration"), 10);
            assertEquals("Fallback title", read.getString("title"));
            assertTrue(server.sent.get() <= 4 * 1024 * 1024);
            assertFalse(server.requests.isEmpty());
        }
    }

    @Test
    public void metadataReadersAreBoundedParallelAndCached() throws Exception {
        DriveAuth.get(c).accept("account", "test@example.invalid", "샘플 계정", "fake-access");
        DriveLibrary.saveRoots(c, List.of(new DriveLibrary.Folder("root", "Music")));
        try (FakeDrive server = new FakeDrive()) {
            CountDownLatch pair = new CountDownLatch(2);
            AtomicInteger active = new AtomicInteger(),
                    maximum = new AtomicInteger(),
                    reads = new AtomicInteger();
            DriveLibrary library =
                    new DriveLibrary(
                            c,
                            new DriveApi(http(server)),
                            (context, row, generation) -> {
                                int n = active.incrementAndGet();
                                maximum.accumulateAndGet(n, Math::max);
                                reads.incrementAndGet();
                                pair.countDown();
                                try {
                                    if (!pair.await(4, TimeUnit.SECONDS))
                                        throw new IOException("Not parallel");
                                    Thread.sleep(80);
                                    return row.put("enriched", true)
                                            .put("artist", "Parallel artist");
                                } finally {
                                    active.decrementAndGet();
                                }
                            },
                            2);
            var found = library.scan(p -> {});
            assertEquals(2, maximum.get());
            assertEquals(2, reads.get());
            assertTrue(found.stream().allMatch(t -> t.artist.equals("Parallel artist")));
            library.scan(p -> {});
            assertEquals(2, reads.get());
        }
    }

    @Test
    public void metadataRandomReadsHaveTransferBudget() throws Exception {
        try (FakeDrive server = new FakeDrive();
                DriveMetadata data = new DriveMetadata(http(server), uri(), server.total)) {
            byte[] b = new byte[32];
            assertEquals(32, data.readAt(0, b, 0, 32));
            int calls = server.requests.size();
            assertEquals(32, data.readAt(10, b, 0, 32));
            assertEquals(calls, server.requests.size());
            boolean limited = false;
            for (int i = 1; i <= 17; i++)
                try {
                    data.readAt(i * 256L * 1024, b, 0, b.length);
                } catch (IOException expected) {
                    limited = true;
                    break;
                }
            assertTrue(limited);
            assertTrue(server.sent.get() <= 4 * 1024 * 1024);
        }
    }

    @Test
    public void sourceIdentityAndOldTrackFormatRemainCompatible() throws Exception {
        Track local =
                new Track("file:///Music/a.wav", "Song", "Artist", "Album", "Music", 1000, "");
        Track cloud =
                new Track(
                        uri().toString(),
                        "Song",
                        "Artist",
                        "Album",
                        "Music",
                        1000,
                        "",
                        "",
                        "root",
                        "audio/wav");
        assertNotEquals(local.folderKey(), cloud.folderKey());
        assertEquals("Google Drive", cloud.sourceLabel());
        assertEquals(cloud.uri, cloud.item().localConfiguration.uri.toString());
        assertFalse(cloud.uri.contains("fake-access"));
        assertEquals(cloud.folderKey(), Track.from(cloud.json()).folderKey());
        JSONObject old = local.json();
        old.remove("folderId");
        old.remove("mime");
        assertEquals(local.folderKey(), Track.from(old).folderKey());
        assertFalse(new DriveApi.Item("dsd", "a.dsf", "audio/x-dsf", 100, "", true).audio());
        for (String bad : new String[] {"../file", "a/b", "a?token=x", ""})
            try {
                DriveUris.id(bad);
                fail("Invalid file ID accepted");
            } catch (IOException expected) {
            }
    }

    @Test
    public void mixedFolderUiSeparatesStorageAndHasDriveConnectionPage() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(c, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        Thread.sleep(500);
        try {
            main(
                    () -> {
                        var field = MainActivity.class.getDeclaredField("tracks");
                        field.setAccessible(true);
                        field.set(
                                activity,
                                new ArrayList<>(
                                        List.of(
                                                new Track(
                                                        "file:///a.wav",
                                                        "Local",
                                                        "Artist",
                                                        "Album",
                                                        "Music",
                                                        1000,
                                                        ""),
                                                new Track(
                                                        uri().toString(),
                                                        "Cloud",
                                                        "Artist",
                                                        "Album",
                                                        "Music",
                                                        0,
                                                        "",
                                                        "",
                                                        "root",
                                                        "audio/wav"))));
                        var tab = MainActivity.class.getDeclaredField("tab");
                        tab.setAccessible(true);
                        tab.set(activity, "폴더");
                        for (String key : List.of("source", "query", "folderCursor")) {
                            var state = MainActivity.class.getDeclaredField(key);
                            state.setAccessible(true);
                            state.set(activity, key.equals("source") ? "all" : "");
                        }
                        go(activity, "library");
                        return null;
                    });
            instrumentation.waitForIdleSync();
            Thread.sleep(350);
            main(
                    () -> {
                        var list = findList(activity.getWindow().getDecorView());
                        assertNotNull(list);
                        assertEquals(2, list.getAdapter().getItemCount());
                        return null;
                    });
            capture(activity, "drive-folders.png");
            main(
                    () -> {
                        go(activity, "drive");
                        return null;
                    });
            instrumentation.waitForIdleSync();
            Thread.sleep(350);
            capture(activity, "drive-connect.png");
            assertFalse(activity.isFinishing());
        } finally {
            main(
                    () -> {
                        activity.finish();
                        return null;
                    });
        }
    }

    @Test
    public void media3StreamsLargeRemoteWavAndSeeksBeforeFullTransfer() throws Exception {
        try (FakeDrive server = new FakeDrive()) {
            ExoPlayer player =
                    main(
                            () -> {
                                var factory =
                                        new DefaultRenderersFactory(c) {
                                            @Override
                                            protected androidx.media3.exoplayer.audio.AudioSink
                                                    buildAudioSink(
                                                            Context context, boolean f, boolean p) {
                                                return new PcmSink(context);
                                            }
                                        };
                                return new ExoPlayer.Builder(c, factory)
                                        .setMediaSourceFactory(
                                                new DefaultMediaSourceFactory(
                                                        () ->
                                                                new DriveDataSource(
                                                                        http(server), u -> true)))
                                        .build();
                            });
            try {
                main(
                        () -> {
                            player.setMediaItem(
                                    new MediaItem.Builder()
                                            .setUri(uri())
                                            .setMimeType("audio/wav")
                                            .build());
                            player.prepare();
                            player.play();
                            return null;
                        });
                long end = SystemClock.elapsedRealtime() + 15000;
                while (main(player::getCurrentPosition) < 400
                        && SystemClock.elapsedRealtime() < end) {
                    assertNull(main(player::getPlayerError));
                    Thread.sleep(40);
                }
                assertTrue(main(player::getCurrentPosition) >= 400);
                assertTrue(
                        "Must start without fetching the whole file",
                        server.sent.get() < server.total);
                main(
                        () -> {
                            player.seekTo(120000);
                            return null;
                        });
                end = SystemClock.elapsedRealtime() + 10000;
                while (main(player::getCurrentPosition) < 120300
                        && SystemClock.elapsedRealtime() < end) {
                    assertNull(main(player::getPlayerError));
                    Thread.sleep(40);
                }
                assertTrue(main(player::getCurrentPosition) >= 120300);
                assertTrue(
                        server.requests.stream()
                                .anyMatch(r -> FakeDrive.rangeStart(r) > 10 * 1024 * 1024));
                for (var request : server.requests)
                    assertTrue(FakeDrive.rangeLength(request) <= DriveDataSource.WINDOW);
            } finally {
                main(
                        () -> {
                            player.release();
                            return null;
                        });
            }
        }
    }

    private void go(MainActivity a, String page) throws Exception {
        var go = MainActivity.class.getDeclaredMethod("go", String.class);
        go.setAccessible(true);
        go.invoke(a, page);
    }

    private androidx.recyclerview.widget.RecyclerView findList(View v) {
        if (v instanceof androidx.recyclerview.widget.RecyclerView r) return r;
        if (v instanceof ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) {
                var found = findList(g.getChildAt(i));
                if (found != null) return found;
            }
        return null;
    }

    private void capture(MainActivity a, String name) throws Exception {
        var b = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (b != null)
            try (OutputStream out =
                    new FileOutputStream(new File(c.getExternalFilesDir(null), name))) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
    }

    private <T> T main(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(future);
        return future.get(20, TimeUnit.SECONDS);
    }

    static final class FakeDrive implements AutoCloseable {
        record Request(String path, Map<String, String> headers) {}

        final ServerSocket socket;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final AtomicLong sent = new AtomicLong();
        final int total;
        final byte[] header = new byte[44];
        volatile boolean rejectOld, wrongRange, ignoreRange, truncate, redirect, failSub;
        volatile byte[] prefix;
        volatile int scanFileCount;

        FakeDrive() throws Exception {
            this(64 * 1024 * 1024 + 44);
        }

        FakeDrive(int total) throws Exception {
            this.total = total;
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes())
                    .putInt(total - 8)
                    .put("WAVEfmt ".getBytes())
                    .putInt(16)
                    .putShort((short) 1)
                    .putShort((short) 2)
                    .putInt(44100)
                    .putInt(176400)
                    .putShort((short) 4)
                    .putShort((short) 16)
                    .put("data".getBytes())
                    .putInt(total - 44);
            workers.execute(
                    () -> {
                        while (!socket.isClosed())
                            try {
                                Socket s = socket.accept();
                                workers.execute(() -> serve(s));
                            } catch (IOException ignored) {
                            }
                    });
        }

        String base() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/drive/v3/";
        }

        byte audio(int at) {
            if (prefix != null && at < prefix.length) return prefix[at];
            if (at < 44) return header[at];
            int sample = (at - 44) / 4;
            short value = (short) (Math.sin(2 * Math.PI * 1000 * sample / 44100) * 2000);
            return (byte) ((at - 44) % 2 == 0 ? value : value >> 8);
        }

        static long rangeStart(Request r) {
            String s = r.headers.get("range");
            return s == null ? 0 : Long.parseLong(s.substring(6, s.indexOf('-')));
        }

        static long rangeLength(Request r) {
            String s = r.headers.get("range");
            return s == null
                    ? 0
                    : Long.parseLong(s.substring(s.indexOf('-') + 1)) - rangeStart(r) + 1;
        }

        private void serve(Socket s) {
            try (s) {
                s.setSoTimeout(10000);
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        s.getInputStream(), StandardCharsets.US_ASCII));
                String first = reader.readLine();
                if (first == null) return;
                String path = first.split(" ")[1];
                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0)
                        headers.put(
                                line.substring(0, colon).toLowerCase(Locale.ROOT),
                                line.substring(colon + 1).trim());
                }
                Request request = new Request(path, headers);
                requests.add(request);
                OutputStream out = s.getOutputStream();
                if (rejectOld && "Bearer old".equals(headers.get("authorization"))) {
                    respond(out, 401, "", new byte[0]);
                    return;
                }
                if (redirect) {
                    out.write(
                            "HTTP/1.1 302 Found\r\nLocation: http://evil.invalid/music\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                if (!path.contains("alt=media") && path.contains("fields=size")) {
                    respond(
                            out,
                            200,
                            "",
                            ("{\"size\":" + total + ",\"mimeType\":\"audio/wav\"}")
                                    .getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (!path.contains("alt=media")) {
                    String decoded = URLDecoder.decode(path, "UTF-8");
                    boolean sub = decoded.contains("'sub'");
                    if (sub && failSub) {
                        respond(out, 403, "", new byte[0]);
                        return;
                    }
                    String json;
                    if (scanFileCount > 0) {
                        org.json.JSONArray entries = new org.json.JSONArray();
                        for (int i = 0; i < scanFileCount; i++)
                            entries.put(
                                    new org.json.JSONObject()
                                            .put("id", "fast" + i)
                                            .put("name", "Fast" + i + ".flac")
                                            .put("mimeType", "audio/flac")
                                            .put("size", total)
                                            .put("modifiedTime", "stable"));
                        respond(
                                out,
                                200,
                                "",
                                new org.json.JSONObject()
                                        .put("files", entries)
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    if (sub)
                        json =
                                "{\"files\":[{\"id\":\"song2\",\"name\":\"Second.flac\",\"mimeType\":\"audio/flac\",\"size\":1000,\"modifiedTime\":\"2026-10-19\"}]}";
                    else if (path.contains("pageToken="))
                        json =
                                "{\"files\":[{\"id\":\"lrc\",\"name\":\"Tone.lrc\",\"mimeType\":\"text/plain\",\"size\":30}]}";
                    else
                        json =
                                "{\"nextPageToken\":\"next\",\"files\":[{\"id\":\"sub\",\"name\":\"Sub\",\"mimeType\":\"application/vnd.google-apps.folder\"},{\"id\":\"song\",\"name\":\"Tone.wav\",\"mimeType\":\"audio/wav\",\"size\":1000,\"modifiedTime\":\"2026-10-19\"}]}";
                    respond(out, 200, "", json.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                long start = rangeStart(request), length = rangeLength(request);
                if (length <= 0) length = total - start;
                length = Math.min(length, total - start);
                String response =
                        "HTTP/1.1 "
                                + (ignoreRange ? "200 OK" : "206 Partial Content")
                                + "\r\nContent-Type: audio/wav\r\nContent-Length: "
                                + (ignoreRange ? total : length)
                                + "\r\n"
                                + (ignoreRange
                                        ? ""
                                        : "Content-Range: bytes "
                                                + (wrongRange ? start + 1 : start)
                                                + "-"
                                                + (start + length - 1)
                                                + "/"
                                                + total
                                                + "\r\n")
                                + "ETag: fixture\r\nConnection: close\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.US_ASCII));
                long remaining = truncate ? Math.min(4, length) : length;
                byte[] bytes = new byte[16384];
                while (remaining > 0) {
                    int n = (int) Math.min(bytes.length, remaining);
                    for (int i = 0; i < n; i++) bytes[i] = audio((int) (start + i));
                    sent.addAndGet(n);
                    out.write(bytes, 0, n);
                    remaining -= n;
                    start += n;
                }
            } catch (Exception ignored) {
            }
        }

        private void respond(OutputStream out, int code, String extra, byte[] bytes)
                throws IOException {
            out.write(
                    ("HTTP/1.1 "
                                    + code
                                    + " Response\r\nContent-Type: application/json\r\nContent-Length: "
                                    + bytes.length
                                    + "\r\n"
                                    + extra
                                    + "Connection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
        }

        public void close() throws IOException {
            socket.close();
            workers.shutdownNow();
        }
    }
}

package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import android.media.MediaDataSource;
import android.graphics.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class DriveMetadataSpeedTest {
    final Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void setup() throws Exception {
        DriveAuth.get(c).clear();
        DriveAuth.get(c).accept("account", "test@example.invalid", "Test", "fake-access");
    }

    @After
    public void cleanup() {
        DriveAuth.get(c).clear();
    }

    byte[] asset(String name) throws Exception {
        try (var in =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            return Io.read(in);
        }
    }

    static class Source extends MediaDataSource {
        final byte[] data;
        final long size;
        long readBytes, maxEnd;

        Source(byte[] data, long size) {
            this.data = data;
            this.size = size;
        }

        public long getSize() {
            return size;
        }

        public int readAt(long at, byte[] out, int offset, int count) {
            if (at >= size) return -1;
            int n = (int) Math.min(Math.min(count, 17), size - at);
            for (int i = 0; i < n; i++)
                out[offset + i] = at + i < data.length ? data[(int) at + i] : 0;
            readBytes += n;
            maxEnd = Math.max(maxEnd, at + n);
            return n;
        }

        public void close() {}
    }

    @Test
    public void realFlacTagsAndHiResNeedNoAudioFrameProbe() throws Exception {
        byte[] fixture = asset("numbered.flac");
        Source source = new Source(fixture, 64L * 1024 * 1024);
        var info = FlacMetadata.read(source);
        assertNotNull(info);
        assertEquals("Tagged track", info.tags().get("TITLE"));
        assertEquals(2, TrackOrder.number(info.tags().get("TRACKNUMBER")));
        assertTrue(info.duration() > 0);
        assertTrue(source.maxEnd < fixture.length);
        assertTrue(source.readBytes < 64 * 1024);
        var high = FlacMetadata.read(new Source(asset("pcm24.flac"), 64L * 1024 * 1024));
        assertEquals(48000, high.sampleRate());
        assertEquals(24, high.bitsPerSample());
        assertTrue(high.duration() > 0);
    }

    @Test
    public void httpFastPathUsesOneBoundedRangeAndPreservesTags() throws Exception {
        try (var server = new DriveTest.FakeDrive()) {
            server.prefix = asset("numbered.flac");
            var http =
                    new DriveHttp(
                            server.base(),
                            new DriveHttp.Tokens() {
                                public String token() {
                                    return "fake-access";
                                }

                                public void invalidate(String token) {}
                            });
            Track t =
                    new Track(
                            DriveUris.file("account", "song", "Track.flac").toString(),
                            "Fallback",
                            "Artist",
                            "Album",
                            "Music",
                            0,
                            "",
                            "",
                            "root",
                            "audio/flac");
            JSONObject result =
                    DriveLibrary.readMetadata(
                            c,
                            t.json().put("size", server.total),
                            DriveAuth.get(c).generation(),
                            http);
            assertEquals("Tagged track", result.getString("title"));
            assertEquals(2, result.getInt("trackNumber"));
            assertTrue(result.getBoolean("formatChecked"));
            assertEquals(1, server.requests.size());
            assertTrue(server.sent.get() <= 16 * 1024);
            assertEquals(0, DriveTest.FakeDrive.rangeStart(server.requests.get(0)));
        }
    }

    @Test
    public void embeddedFrontCoverAndTagsArePreservedWithoutDecodingAudio() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
        bitmap.recycle();
        byte[] image = png.toByteArray();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream fields = new DataOutputStream(payload);
        fields.writeInt(3);
        byte[] mime = "image/png".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        fields.writeInt(mime.length);
        fields.write(mime);
        fields.writeInt(0);
        fields.writeInt(96);
        fields.writeInt(64);
        fields.writeInt(24);
        fields.writeInt(0);
        fields.writeInt(image.length);
        fields.write(image);
        byte[] original = asset("numbered.flac");
        int end = 4, last;
        do {
            last = end;
            int length =
                    (original[end + 1] & 255) << 16
                            | (original[end + 2] & 255) << 8
                            | (original[end + 3] & 255);
            end += 4 + length;
        } while ((original[last] & 128) == 0);
        original[last] &= 127;
        ByteArrayOutputStream full = new ByteArrayOutputStream();
        full.write(original, 0, end);
        int length = payload.size();
        full.write(0x86);
        full.write(length >> 16);
        full.write(length >> 8);
        full.write(length);
        full.write(payload.toByteArray());
        full.write(original, end, original.length - end);
        Source source = new Source(full.toByteArray(), full.size());
        var info = FlacMetadata.read(source);
        assertArrayEquals(image, info.picture());
        assertEquals("Tagged track", info.tags().get("TITLE"));
        assertFalse(info.pictureSkipped());
        assertTrue(source.maxEnd < full.size());
    }

    @Test
    public void oversizedPictureIsSkippedWithoutDownloadingPayload() throws Exception {
        byte[] header = Arrays.copyOf(asset("pcm24.flac"), 42);
        header[4] = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        int length = 3 * 1024 * 1024 + 1;
        out.write(0x86);
        out.write(length >> 16);
        out.write(length >> 8);
        out.write(length);
        Source source = new Source(out.toByteArray(), 46L + length + 1024);
        var info = FlacMetadata.read(source);
        assertTrue(info.pictureSkipped());
        assertNull(info.picture());
        assertTrue(source.readBytes < 64);
        assertEquals(24, info.bitsPerSample());
    }

    @Test
    public void malformedCommentsAreRejectedAndNonFlacCanFallBack() throws Exception {
        assertNull(FlacMetadata.read(new Source(new byte[42], 42)));
        byte[] header = Arrays.copyOf(asset("pcm24.flac"), 42);
        header[4] = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(
                new byte[] {
                    (byte) 0x84, 0, 0, 8, 0, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255
                });
        try {
            FlacMetadata.read(new Source(out.toByteArray(), out.size()));
            fail("Invalid count must fail");
        } catch (IOException expected) {
        }
    }

    @Test
    public void fourReadersOverlapAndUnchangedScanDoesNotRepeatAnalysis() throws Exception {
        DriveLibrary.saveRoots(c, List.of(new DriveLibrary.Folder("root", "Music")));
        try (var server = new DriveTest.FakeDrive()) {
            server.scanFileCount = 8;
            var http =
                    new DriveHttp(
                            server.base(),
                            new DriveHttp.Tokens() {
                                public String token() {
                                    return "fake-access";
                                }

                                public void invalidate(String token) {}
                            });
            AtomicInteger active = new AtomicInteger(),
                    maximum = new AtomicInteger(),
                    reads = new AtomicInteger();
            CountDownLatch firstFour = new CountDownLatch(4);
            DriveLibrary library =
                    new DriveLibrary(
                            c,
                            new DriveApi(http),
                            (context, row, generation) -> {
                                int n = active.incrementAndGet();
                                maximum.accumulateAndGet(n, Math::max);
                                reads.incrementAndGet();
                                firstFour.countDown();
                                try {
                                    assertTrue(firstFour.await(4, TimeUnit.SECONDS));
                                    Thread.sleep(80);
                                    return row.put("enriched", true).put("artist", "Parallel");
                                } finally {
                                    active.decrementAndGet();
                                }
                            },
                            100);
            assertEquals(8, library.scan(p -> {}).size());
            assertEquals(4, maximum.get());
            assertEquals(8, reads.get());
            library.scan(p -> {});
            assertEquals(8, reads.get());
        }
    }
}

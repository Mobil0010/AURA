package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.session.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.nio.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class EngineTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void defaults() {
        AudioSettings.init(context);
        AudioSettings.put("autoEqPreamp", false);
        AudioSettings.put("eqMode", "parametric");
        AudioSettings.put("perfect", false);
        AudioSettings.put("mode", "android");
        AudioSettings.put("device", 0);
        AudioSettings.put("sox", false);
        AudioSettings.put("eq", false);
        AudioSettings.put("bits", 0);
        AudioSettings.put("preamp", 0);
        AudioSettings.put("crossfeed", 0);
        AudioSettings.put("balance", 0);
        AudioSettings.put("limiter", false);
        AudioSettings.put("dither", "off");
        AudioSettings.put("dsd", "pcm");
    }

    @Test
    public void soxPreservesToneAndFrameCount() {
        int inRate = 44100, outRate = 96000;
        float[] in = new float[inRate * 2];
        for (int i = 0; i < inRate; i++)
            in[i * 2] = in[i * 2 + 1] = (float) (.2 * Math.sin(2 * Math.PI * 1000 * i / inRate));
        long handle = NativeAudio.createSox(inRate, outRate, 2, 6, 50, 95);
        try {
            float[] a = NativeAudio.resample(handle, in), b = NativeAudio.resample(handle, null);
            assertTrue(Math.abs((a.length + b.length) / 2 - outRate) <= 2);
            double energy = 0;
            for (int i = 2000; i < a.length - 2000; i++) energy += a[i] * a[i];
            double rms = Math.sqrt(energy / (a.length - 4000));
            assertEquals(.2 / Math.sqrt(2), rms, .003);
        } finally {
            NativeAudio.closeSox(handle);
        }
    }

    @Test
    public void soxRejectsAliasing() {
        float[] in = new float[96000];
        for (int i = 0; i < in.length; i++)
            in[i] = (float) (.5 * Math.sin(2 * Math.PI * 35000 * i / 96000));
        long h = NativeAudio.createSox(96000, 44100, 1, 6, 50, 95);
        try {
            float[] a = NativeAudio.resample(h, in);
            double sum = 0;
            for (int i = 2000; i < a.length - 2000; i++) sum += a[i] * a[i];
            assertTrue(Math.sqrt(sum / (a.length - 4000)) < .001);
        } finally {
            NativeAudio.closeSox(h);
        }
    }

    @Test
    public void qualityPhaseOptionsProduceFiniteAudio() {
        for (int quality : new int[] {1, 2, 4, 6}) {
            long h = NativeAudio.createSox(48000, 44100, 1, quality, 0, 90);
            try {
                float[] out = NativeAudio.resample(h, new float[4096]);
                for (float f : out) assertTrue(Float.isFinite(f));
            } finally {
                NativeAudio.closeSox(h);
            }
        }
    }

    @Test
    public void eqGraphUsesActualFilterCoefficients() {
        var b = new AudioSettings.Band(1000, 6, 1, "peak");
        double[] co = Biquad.coefficients(b, 48000);
        assertEquals(6, Biquad.response(co, 1000, 48000), .001);
        Biquad filter = new Biquad(1);
        double inEnergy = 0, outEnergy = 0;
        for (int i = 0; i < 96000; i++) {
            double sample = .1 * Math.sin(2 * Math.PI * 1000 * i / 48000),
                    out = filter.sample(sample, 0, co);
            if (i > 48000) {
                inEnergy += sample * sample;
                outEnergy += out * out;
            }
        }
        assertEquals(6, 10 * Math.log10(outEnergy / inEnergy), .01);
    }

    @Test
    public void pcmRoundTripAndEq() {
        ByteBuffer input = ByteBuffer.allocate(96000).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 48000; i++)
            input.putShort((short) Math.round(Math.sin(i * 2 * Math.PI * 1000 / 48000) * 3000));
        input.flip();
        try (PcmDsp dsp = new PcmDsp(48000, 48000, 1, 16, 16, AudioSettings.current)) {
            ByteBuffer out = dsp.process(input, 2).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(input.remaining(), out.remaining());
            while (input.hasRemaining()) assertEquals(input.getShort(), out.getShort());
        }
    }

    @Test
    public void repeatedPcmBlocksPreserveSamplesAcrossChangingSizes() {
        try (PcmDsp dsp = new PcmDsp(48000, 48000, 2, 32, 32, AudioSettings.current)) {
            ByteBuffer input = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN);
            for (int block = 0; block < 256; block++) {
                int count = block % 3 == 0 ? 1024 : 4096;
                input.clear();
                for (int i = 0; i < count; i++)
                    input.putInt((i % 2 == 0 ? 1 : -1) * (16777217 + i + block));
                input.flip();
                ByteBuffer output = dsp.process(input, C.ENCODING_PCM_32BIT);
                assertEquals(count * 4, output.remaining());
                for (int i = 0; i < count; i++)
                    assertEquals((i % 2 == 0 ? 1 : -1) * (16777217 + i + block), output.getInt());
            }
        }
    }

    @Test
    public void liveEqChangesAfterCachedCoefficients() {
        AudioSettings.put("eq", true);
        AudioSettings.band(3, new AudioSettings.Band(1000, 0, 1, "peak"));
        try (PcmDsp dsp = new PcmDsp(48000, 48000, 1, 16, 16, AudioSettings.current)) {
            ByteBuffer tone = ByteBuffer.allocate(96000).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 48000; i++)
                tone.putShort((short) Math.round(1000 * Math.sin(i * 2 * Math.PI * 1000 / 48000)));
            tone.flip();
            double before = energy(dsp.process(tone, C.ENCODING_PCM_16BIT));
            AudioSettings.band(3, new AudioSettings.Band(1000, 6, 1, "peak"));
            double after = energy(dsp.process(tone, C.ENCODING_PCM_16BIT));
            assertTrue(after > before * 3.5 && after < before * 4.2);
        } finally {
            AudioSettings.band(3, new AudioSettings.Band(1000, 0, 1, "peak"));
            AudioSettings.put("eq", false);
        }
    }

    private double energy(ByteBuffer data) {
        double sum = 0;
        int index = 0;
        while (data.hasRemaining()) {
            double v = data.getShort();
            if (index++ > 24000) sum += v * v;
        }
        return sum;
    }

    @Test
    public void floatOutputKeepsFormatWhenLiveProcessingStarts() {
        AudioSettings.put("preamp", 0);
        try (PcmDsp dsp = new PcmDsp(48000, 48000, 1, 32, 32, AudioSettings.current, false, true)) {
            ByteBuffer input = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 4096; i++) input.putFloat(.1f);
            input.flip();
            ByteBuffer out = dsp.process(input, C.ENCODING_PCM_FLOAT);
            while (out.hasRemaining()) assertEquals(.1f, out.getFloat(), .00001);
            AudioSettings.put("preamp", 6);
            out = dsp.process(input, C.ENCODING_PCM_FLOAT);
            float last = 0;
            while (out.hasRemaining()) {
                last = out.getFloat();
                assertTrue(Float.isFinite(last) && last >= .099 && last < .21);
            }
            assertTrue(last > .19);
        } finally {
            AudioSettings.put("preamp", 0);
        }
    }

    @Test
    public void integerPcmRoundTripsThroughLosslessFloatWithoutRounding() {
        for (int bits : new int[] {16, 24}) {
            int count = bits == 16 ? 65536 : 100000;
            java.util.Random random = new java.util.Random(21);
            int[] samples = new int[count];
            ByteBuffer input = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                samples[i] =
                        bits == 16
                                ? i - 32768
                                : i == 0
                                        ? -8388608
                                        : i == 1 ? 8388607 : random.nextInt(16777216) - 8388608;
                input.putFloat((float) (samples[i] / Math.scalb(1., bits - 1)));
            }
            input.flip();
            ByteBuffer restored = PcmOrigin.restore(input, bits, null);
            for (int value : samples) {
                int actual = 0;
                for (int b = 0; b < bits / 8; b++) actual |= (restored.get() & 255) << (b * 8);
                actual = actual << (32 - bits) >> (32 - bits);
                assertEquals(value, actual);
            }
            assertFalse(restored.hasRemaining());
        }
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, 1f, .1f})
            try {
                ByteBuffer bad =
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(invalid);
                bad.flip();
                PcmOrigin.restore(bad, 24, null);
                fail("Non-integer float sample accepted");
            } catch (IllegalArgumentException expected) {
            }
    }

    @Test
    public void reusedUsbBuffersUseActualLengthRatherThanOldCapacity() throws Exception {
        PcmSink sink = new PcmSink(context);
        java.lang.reflect.Field pending = PcmSink.class.getDeclaredField("pending"),
                bytes = PcmSink.class.getDeclaredField("usbBytes"),
                length = PcmSink.class.getDeclaredField("usbLength");
        for (var f : new java.lang.reflect.Field[] {pending, bytes, length}) f.setAccessible(true);
        var queue = PcmSink.class.getDeclaredMethod("queueUsbBytes");
        queue.setAccessible(true);
        pending.set(sink, ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        queue.invoke(sink);
        byte[] first = (byte[]) bytes.get(sink);
        pending.set(sink, ByteBuffer.wrap(new byte[] {9, 10}));
        queue.invoke(sink);
        assertSame(first, bytes.get(sink));
        assertEquals(2, length.getInt(sink));
        assertEquals(9, first[0]);
        assertEquals(10, first[1]);
        sink.reset();
    }

    @Test
    public void savedUsbSoftwareGainAppliesToTheVeryFirstSample() {
        double saved = UsbVolume.softwareGain;
        try {
            AudioSettings.put("mode", "usb");
            UsbVolume.softwareGain = UsbVolume.gain(50);
            try (PcmDsp dsp = new PcmDsp(48000, 48000, 1, 16, 16, AudioSettings.current)) {
                ByteBuffer input = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < 64; i++) input.putShort((short) 32000);
                input.flip();
                ByteBuffer output = dsp.process(input, C.ENCODING_PCM_16BIT);
                while (output.hasRemaining())
                    assertEquals((short) Math.round(32000 * UsbVolume.gain(50)), output.getShort());
            }
        } finally {
            UsbVolume.softwareGain = saved;
            AudioSettings.put("mode", "android");
        }
    }

    @Test
    public void profilesRestoreTypedSettings() {
        AudioSettings.put("rate", 96000);
        AudioSettings.put("phase", 25.);
        AudioSettings.put("eq", true);
        AudioSettings.saveProfile("test");
        AudioSettings.put("rate", 44100);
        AudioSettings.put("eq", false);
        assertTrue(AudioSettings.restoreProfile("test"));
        assertEquals(96000, AudioSettings.current.sampleRate());
        assertEquals(25, AudioSettings.current.phase(), 0);
        assertTrue(AudioSettings.current.eq());
    }

    @Test
    public void invalidUsbDescriptorFailsSafely() {
        try {
            NativeAudio.openUsb(-1, 44100, 2, 16, 100);
            fail("Invalid USB FD accepted");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void enhancedLyricsKeepWordTiming() {
        var lines = Lrc.parse("[00:01.00]<00:01.00>hello <00:02.00>world");
        assertEquals("hello world", lines.get(0).text());
        assertEquals(2000, lines.get(0).words().get(1).time());
    }

    @Test
    public void dsfPcmAndDop() throws Exception {
        File source = new File(context.getCacheDir(), "test-source.dsf");
        try (InputStream in =
                        InstrumentationRegistry.getInstrumentation()
                                .getContext()
                                .getAssets()
                                .open("tone.dsf");
                OutputStream out = new FileOutputStream(source)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
        try (RandomAccessFile f = new RandomAccessFile(source, "r")) {
            Dsd.Header h = Dsd.header(f);
            assertEquals(2822400, h.rate());
            assertEquals(2, h.channels());
        }
        File pcm = Dsd.prepare(context, Uri.fromFile(source), "pcm");
        try (RandomAccessFile f = new RandomAccessFile(pcm, "r")) {
            f.seek(24);
            assertEquals(176400, Integer.reverseBytes(f.readInt()));
            assertTrue(f.length() > 10000);
        }
        AudioSettings.put("mode", "usb");
        AudioSettings.put("perfect", true);
        File dop = Dsd.prepare(context, Uri.fromFile(source), "dop");
        try (RandomAccessFile f = new RandomAccessFile(dop, "r")) {
            f.seek(46);
            assertEquals(5, f.readUnsignedByte());
            f.seek(49);
            assertEquals(5, f.readUnsignedByte());
            f.seek(52);
            assertEquals(250, f.readUnsignedByte());
        }
    }

    @Test
    public void appScreensLaunch() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent =
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        for (String screen :
                new String[] {
                    "home",
                    "library",
                    "playlists",
                    "settings",
                    "audio",
                    "sox",
                    "eq",
                    "output",
                    "remote",
                    "updates"
                }) {
            main(
                    () -> {
                        var go = MainActivity.class.getDeclaredMethod("go", String.class);
                        go.setAccessible(true);
                        go.invoke(activity, screen);
                        return null;
                    });
            instrumentation.waitForIdleSync();
            Thread.sleep(350);
            assertFalse(activity.isFinishing());
            if (screen.equals("eq") || screen.equals("sox") || screen.equals("home")) {
                android.graphics.Bitmap shot = instrumentation.getUiAutomation().takeScreenshot();
                if (shot != null)
                    try (OutputStream image =
                            new FileOutputStream(
                                    new File(context.getExternalFilesDir(null), screen + ".png"))) {
                        shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, image);
                    }
            }
        }
        main(
                () -> {
                    activity.finish();
                    return null;
                });
    }

    @Test
    public void media3PlaysThroughSoxAudioTrack() throws Exception {
        AudioSettings.put("sox", true);
        AudioSettings.put("rate", 96000);
        File tone = new File(context.getCacheDir(), "playback-tone.wav");
        try (InputStream in =
                        InstrumentationRegistry.getInstrumentation()
                                .getContext()
                                .getAssets()
                                .open("tone.wav");
                OutputStream out = new FileOutputStream(tone)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
        MediaController controller =
                main(() ->
                                new MediaController.Builder(
                                                context,
                                                new SessionToken(
                                                        context,
                                                        new ComponentName(
                                                                context, PlaybackService.class)))
                                        .buildAsync())
                        .get(15, TimeUnit.SECONDS);
        try {
            main(
                    () -> {
                        controller.setMediaItem(MediaItem.fromUri(Uri.fromFile(tone)));
                        controller.prepare();
                        controller.play();
                        return null;
                    });
            long end = System.currentTimeMillis() + 15000;
            boolean progressed = false;
            while (System.currentTimeMillis() < end) {
                if (main(() -> controller.getPlayerError()) != null)
                    fail(main(() -> controller.getPlayerError().toString()));
                if (main(controller::getCurrentPosition) > 500) {
                    progressed = true;
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue("AudioTrack did not progress: " + PcmSink.status, progressed);
            assertTrue(PcmSink.status.contains("96000"));
            main(
                    () -> {
                        controller.pause();
                        controller.seekTo(1500);
                        controller.play();
                        return null;
                    });
            long seekEnd = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < seekEnd
                    && main(controller::getCurrentPosition) < 1800) Thread.sleep(100);
            assertNull(main(controller::getPlayerError));
            assertTrue(
                    "Resume after seek did not advance",
                    main(controller::getCurrentPosition) >= 1800);
        } finally {
            main(
                    () -> {
                        controller.stop();
                        controller.release();
                        return null;
                    });
        }
    }

    @Test
    public void mediaServerSupportsByteRanges() throws Exception {
        File file = new File(context.getCacheDir(), "range.wav");
        try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
            Dsd.writeWav(f, 44100, 2, 16, 400);
            f.write(new byte[400]);
        }
        try (MediaFileServer server = new MediaFileServer(context)) {
            String address = server.url(Uri.fromFile(file));
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
            c.setRequestProperty("Range", "bytes=0-3");
            try {
                assertEquals(206, c.getResponseCode());
                assertEquals(
                        "RIFF",
                        new String(
                                Io.read(c.getInputStream()),
                                java.nio.charset.StandardCharsets.US_ASCII));
                assertEquals("bytes 0-3/444", c.getHeaderField("Content-Range"));
            } finally {
                c.disconnect();
            }
            java.net.HttpURLConnection denied =
                    (java.net.HttpURLConnection) new java.net.URL(address + "x").openConnection();
            try {
                assertEquals(404, denied.getResponseCode());
            } finally {
                denied.disconnect();
            }
        }
    }

    @Test
    public void dlnaXmlRejectsExternalEntities() throws Exception {
        assertEquals("&lt;a&amp;b&gt;", Dlna.escape("<a&b>"));
        assertEquals(
                "거실",
                Dlna.tag(
                        Dlna.xml("<root><friendlyName>거실</friendlyName></root>")
                                .getDocumentElement(),
                        "friendlyName"));
        try {
            Dlna.xml("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>");
            fail("External XML entity allowed");
        } catch (Exception expected) {
        }
    }

    @Test
    public void offlineCastAppliesSox() throws Exception {
        AudioSettings.put("sox", true);
        AudioSettings.put("rate", 96000);
        File source = new File(context.getCacheDir(), "cast-source.wav");
        try (InputStream in =
                        InstrumentationRegistry.getInstrumentation()
                                .getContext()
                                .getAssets()
                                .open("tone.wav");
                OutputStream out = new FileOutputStream(source)) {
            out.write(Io.read(in));
        }
        Track t =
                new Track(Uri.fromFile(source).toString(), "test", "artist", "album", "", 3000, "");
        Uri result = OfflineRender.prepare(context, t);
        try (RandomAccessFile f = new RandomAccessFile(new File(result.getPath()), "r")) {
            f.seek(24);
            assertEquals(96000, Integer.reverseBytes(f.readInt()));
            assertTrue(f.length() > 1000000);
        }
    }

    @Test
    public void castChooserUsesCompatibleActivity() throws Exception {
        org.junit.Assume.assumeTrue(
                com.google.android.gms.common.GoogleApiAvailability.getInstance()
                                .isGooglePlayServicesAvailable(context)
                        == 0);
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        try {
            main(
                    () -> {
                        com.google.android.gms.cast.framework.CastContext.getSharedInstance(
                                activity);
                        androidx.mediarouter.app.MediaRouteButton button =
                                new androidx.mediarouter.app.MediaRouteButton(activity);
                        com.google.android.gms.cast.framework.CastButtonFactory
                                .setUpMediaRouteButton(activity, button);
                        button.performClick();
                        return null;
                    });
            instrumentation.waitForIdleSync();
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
    public void packed24AndBigEndianConvertCorrectly() {
        try (PcmDsp dsp = new PcmDsp(48000, 48000, 1, 24, 16, AudioSettings.current)) {
            ByteBuffer in =
                    ByteBuffer.wrap(new byte[] {0, 0, (byte) 128, (byte) 255, (byte) 255, 127});
            ByteBuffer out = dsp.process(in, 21).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(-32768, out.getShort());
            assertEquals(32767, out.getShort());
        }
        try (PcmDsp dsp = new PcmDsp(48000, 48000, 1, 24, 16, AudioSettings.current)) {
            ByteBuffer out =
                    dsp.process(
                                    ByteBuffer.wrap(
                                            new byte[] {
                                                (byte) 128, 0, 0, 127, (byte) 255, (byte) 255
                                            }),
                                    1342177280)
                            .order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(-32768, out.getShort());
            assertEquals(32767, out.getShort());
        }
    }

    @Test
    public void pcm32LeastSignificantBitsArePreserved() {
        int[] values = {1, -1, 16777217, -16777217, 2147483600, -2147483600};
        ByteBuffer input = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) input.putInt(v);
        input.flip();
        try (PcmDsp dsp = new PcmDsp(48000, 48000, 1, 32, 32, AudioSettings.current)) {
            ByteBuffer output = dsp.process(input, 22).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : values) assertEquals(v, output.getInt());
        }
    }

    @Test
    public void largeLibraryRecyclesRows() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        Thread.sleep(300);
        try {
            main(
                    () -> {
                        var field = MainActivity.class.getDeclaredField("tracks");
                        field.setAccessible(true);
                        List<Track> tracks = new ArrayList<>();
                        for (int i = 0; i < 5000; i++)
                            tracks.add(
                                    new Track(
                                            "content://test/" + i,
                                            "Song " + i,
                                            "Artist",
                                            "Album",
                                            "Music",
                                            1000,
                                            ""));
                        field.set(activity, tracks);
                        var go = MainActivity.class.getDeclaredMethod("go", String.class);
                        go.setAccessible(true);
                        go.invoke(activity, "library");
                        return null;
                    });
            instrumentation.waitForIdleSync();
            Thread.sleep(150);
            androidx.recyclerview.widget.RecyclerView list =
                    main(() -> findRecycler(activity.getWindow().getDecorView()));
            assertNotNull(list);
            assertEquals(5000, main(() -> list.getAdapter().getItemCount()).intValue());
            assertTrue(main(list::getChildCount) < 40);
            main(
                    () -> {
                        list.scrollToPosition(4999);
                        return null;
                    });
            instrumentation.waitForIdleSync();
            Thread.sleep(150);
            assertNotNull(main(() -> list.findViewHolderForAdapterPosition(4999)));
        } finally {
            main(
                    () -> {
                        activity.finish();
                        return null;
                    });
        }
    }

    private androidx.recyclerview.widget.RecyclerView findRecycler(android.view.View view) {
        if (view instanceof androidx.recyclerview.widget.RecyclerView)
            return (androidx.recyclerview.widget.RecyclerView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                var found = findRecycler(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void embeddedArtworkIsDownsampledBeforeDecode() {
        android.graphics.Bitmap original =
                android.graphics.Bitmap.createBitmap(
                        3000, 2000, android.graphics.Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        original.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, image);
        original.recycle();
        android.graphics.Bitmap thumb = Library.thumbnail(image.toByteArray());
        assertNotNull(thumb);
        assertTrue(thumb.getWidth() <= 512);
        assertTrue(thumb.getHeight() <= 512);
        thumb.recycle();
    }

    @Test
    public void audioInitFailureRetriesAutomaticFormat() throws Exception {
        java.util.concurrent.atomic.AtomicInteger opens =
                new java.util.concurrent.atomic.AtomicInteger();
        PcmSink sink =
                new PcmSink(
                        context,
                        (aa, af, buffer) -> {
                            if (opens.incrementAndGet() == 1)
                                throw new IllegalStateException("simulated device init failure");
                            return new android.media.AudioTrack.Builder()
                                    .setAudioAttributes(aa)
                                    .setAudioFormat(af)
                                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                                    .setBufferSizeInBytes(buffer)
                                    .build();
                        });
        try {
            sink.configure(
                    new Format.Builder()
                            .setSampleMimeType(MimeTypes.AUDIO_RAW)
                            .setSampleRate(44100)
                            .setChannelCount(2)
                            .setPcmEncoding(C.ENCODING_PCM_16BIT)
                            .build(),
                    0,
                    null);
            assertEquals(2, opens.get());
            assertEquals(48000, PcmSink.outputRate);
            assertTrue(PcmSink.status.startsWith("호환 출력"));
        } finally {
            sink.reset();
        }
    }

    @Test
    public void explicitFormatDoesNotSilentlyFallback() throws Exception {
        AudioSettings.put("bits", 16);
        java.util.concurrent.atomic.AtomicInteger opens =
                new java.util.concurrent.atomic.AtomicInteger();
        PcmSink sink =
                new PcmSink(
                        context,
                        (aa, af, buffer) -> {
                            opens.incrementAndGet();
                            throw new IllegalStateException("simulated device init failure");
                        });
        try {
            sink.configure(
                    new Format.Builder()
                            .setSampleMimeType(MimeTypes.AUDIO_RAW)
                            .setSampleRate(44100)
                            .setChannelCount(2)
                            .setPcmEncoding(C.ENCODING_PCM_16BIT)
                            .build(),
                    0,
                    null);
            fail("Explicit format must report failure");
        } catch (androidx.media3.exoplayer.audio.AudioSink.ConfigurationException expected) {
            assertEquals(1, opens.get());
            assertTrue(PcmSink.lastFailure.contains("44100"));
        } finally {
            sink.reset();
        }
    }

    @Test
    public void flushReopensOnlyWhenAudioArrives() throws Exception {
        java.util.concurrent.atomic.AtomicInteger opens =
                new java.util.concurrent.atomic.AtomicInteger();
        PcmSink sink =
                new PcmSink(
                        context,
                        (aa, af, buffer) -> {
                            opens.incrementAndGet();
                            return new android.media.AudioTrack.Builder()
                                    .setAudioAttributes(aa)
                                    .setAudioFormat(af)
                                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                                    .setBufferSizeInBytes(buffer)
                                    .build();
                        });
        try {
            sink.configure(
                    new Format.Builder()
                            .setSampleMimeType(MimeTypes.AUDIO_RAW)
                            .setSampleRate(48000)
                            .setChannelCount(2)
                            .setPcmEncoding(C.ENCODING_PCM_16BIT)
                            .build(),
                    0,
                    null);
            assertEquals(1, opens.get());
            sink.flush();
            assertEquals(1, opens.get());
            sink.handleBuffer(ByteBuffer.allocateDirect(1920), 0, 1);
            assertEquals(2, opens.get());
        } finally {
            sink.reset();
        }
    }

    @Test
    public void nestedBackReturnsToSettingsParent() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        try {
            main(
                    () -> {
                        var go = MainActivity.class.getDeclaredMethod("go", String.class);
                        go.setAccessible(true);
                        for (String screen : new String[] {"settings", "audio", "eq"})
                            go.invoke(activity, screen);
                        activity.getOnBackPressedDispatcher().onBackPressed();
                        var page = MainActivity.class.getDeclaredField("page");
                        page.setAccessible(true);
                        assertEquals("audio", page.get(activity));
                        activity.getOnBackPressedDispatcher().onBackPressed();
                        assertEquals("settings", page.get(activity));
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
    public void eqGraphSelectionUpdatesBandLabel() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        try {
            main(
                    () -> {
                        android.widget.LinearLayout root =
                                new android.widget.LinearLayout(activity);
                        root.setOrientation(android.widget.LinearLayout.VERTICAL);
                        AudioUi ui = new AudioUi(activity, root, x -> {});
                        ui.eq();
                        var select = AudioUi.class.getDeclaredField("bandSelector");
                        select.setAccessible(true);
                        var graphField = AudioUi.class.getDeclaredField("graph");
                        graphField.setAccessible(true);
                        EqGraph graph = (EqGraph) graphField.get(ui);
                        var callback = EqGraph.class.getDeclaredField("onSelect");
                        callback.setAccessible(true);
                        ((java.util.function.IntConsumer) callback.get(graph)).accept(4);
                        assertEquals(
                                "밴드 5  ⌄",
                                ((android.widget.TextView) select.get(ui)).getText().toString());
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
    public void playerLayoutKeepsSinglePlaybackControls() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        File tone = new File(context.getCacheDir(), "ui-tone.wav");
        try (InputStream in = instrumentation.getContext().getAssets().open("tone.wav");
                OutputStream out = new FileOutputStream(tone)) {
            out.write(Io.read(in));
        }
        MainActivity activity =
                (MainActivity)
                        instrumentation.startActivitySync(
                                new Intent(context, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        Thread.sleep(400);
        try {
            java.lang.reflect.Field playerField = MainActivity.class.getDeclaredField("player");
            playerField.setAccessible(true);
            long deadline = System.currentTimeMillis() + 10000;
            while (main(() -> playerField.get(activity)) == null
                    && System.currentTimeMillis() < deadline) Thread.sleep(100);
            main(
                    () -> {
                        var tracksField = MainActivity.class.getDeclaredField("tracks");
                        tracksField.setAccessible(true);
                        List<Track> tracks = new ArrayList<>();
                        File lrc = new File(context.getCacheDir(), "ui-lyrics.lrc");
                        try (OutputStream out = new FileOutputStream(lrc)) {
                            out.write(
                                    "[00:00.00]A quiet moment in the blue\n[00:01.50]Let the music carry you\n[00:03.00]Into the morning light"
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                        String[] titles = {
                            "Blue Hour",
                            "A Quiet Place",
                            "Nightfall",
                            "First Light",
                            "Afterglow",
                            "Soft Landing",
                            "Warm Lights",
                            "Sunday Morning"
                        };
                        for (int i = 0; i < titles.length; i++)
                            tracks.add(
                                    new Track(
                                            i == 0
                                                    ? Uri.fromFile(tone).toString()
                                                    : "content://fixture/" + i,
                                            titles[i],
                                            "AURA Sessions",
                                            i < 4 ? "Blue Hour" : "First Light",
                                            "Music",
                                            3000,
                                            i == 0 ? Uri.fromFile(lrc).toString() : ""));
                        tracksField.set(activity, tracks);
                        MediaController controller = (MediaController) playerField.get(activity);
                        controller.setMediaItem(tracks.get(0).item());
                        controller.prepare();
                        return null;
                    });
            for (String screen : new String[] {"home", "library", "settings", "player"}) {
                main(
                        () -> {
                            var go = MainActivity.class.getDeclaredMethod("go", String.class);
                            go.setAccessible(true);
                            go.invoke(activity, screen);
                            return null;
                        });
                instrumentation.waitForIdleSync();
                Thread.sleep(400);
                main(
                        () -> {
                            var bodyField = MainActivity.class.getDeclaredField("body");
                            bodyField.setAccessible(true);
                            android.view.View body = (android.view.View) bodyField.get(activity);
                            int[] at = new int[2];
                            body.getLocationOnScreen(at);
                            assertTrue(
                                    "Screen was panned above the status bar: "
                                            + screen
                                            + " y="
                                            + at[1],
                                    at[1] >= 0);
                            assertSquareArtwork(activity.getWindow().getDecorView());
                            return null;
                        });
                if (screen.equals("player")) {
                    main(
                            () -> {
                                var mini = MainActivity.class.getDeclaredField("mini");
                                mini.setAccessible(true);
                                assertEquals(
                                        android.view.View.GONE,
                                        ((android.view.View) mini.get(activity)).getVisibility());
                                var play = MainActivity.class.getDeclaredField("playButton");
                                play.setAccessible(true);
                                android.view.View playView = (android.view.View) play.get(activity);
                                assertNotNull(playView);
                                android.view.ViewGroup controls =
                                        (android.view.ViewGroup) playView.getParent();
                                android.view.View previous = controls.getChildAt(0),
                                        next = controls.getChildAt(2);
                                int gap =
                                        Math.round(
                                                20
                                                        * activity.getResources()
                                                                .getDisplayMetrics()
                                                                .density);
                                assertTrue(playView.getLeft() - previous.getRight() >= gap);
                                assertTrue(next.getLeft() - playView.getRight() >= gap);
                                return null;
                            });
                }
                android.graphics.Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
                if (image != null)
                    try (OutputStream out =
                            new FileOutputStream(
                                    new File(
                                            context.getExternalFilesDir(null),
                                            "ui-" + screen + ".png"))) {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    }
            }
            main(
                    () -> {
                        var bodyField = MainActivity.class.getDeclaredField("body");
                        bodyField.setAccessible(true);
                        android.view.View body = (android.view.View) bodyField.get(activity);
                        android.widget.TextView lyrics = body.findViewWithTag("liveLyrics");
                        assertNotNull(lyrics);
                        assertTrue(lyrics.getText().toString().contains("quiet moment"));
                        var scrollField = MainActivity.class.getDeclaredField("scroll");
                        scrollField.setAccessible(true);
                        PlayerScrollView scroll = (PlayerScrollView) scrollField.get(activity);
                        scroll.smoothScrollTo(0, lyrics.getTop() - Ui.dp(activity, 340));
                        return null;
                    });
            Thread.sleep(450);
            android.graphics.Bitmap lower = instrumentation.getUiAutomation().takeScreenshot();
            if (lower != null)
                try (OutputStream out =
                        new FileOutputStream(
                                new File(
                                        context.getExternalFilesDir(null),
                                        "ui-player-lyrics.png"))) {
                    lower.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
            main(
                    () -> {
                        var tabField = MainActivity.class.getDeclaredField("tab");
                        tabField.setAccessible(true);
                        tabField.set(activity, "앨범");
                        var go = MainActivity.class.getDeclaredMethod("go", String.class);
                        go.setAccessible(true);
                        go.invoke(activity, "library");
                        return null;
                    });
            instrumentation.waitForIdleSync();
            Thread.sleep(400);
            main(
                    () -> {
                        assertSquareArtwork(activity.getWindow().getDecorView());
                        return null;
                    });
            android.graphics.Bitmap albums = instrumentation.getUiAutomation().takeScreenshot();
            if (albums != null)
                try (OutputStream out =
                        new FileOutputStream(
                                new File(context.getExternalFilesDir(null), "ui-albums.png"))) {
                    albums.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
            main(
                    () -> {
                        ((MediaController) playerField.get(activity)).stop();
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
    public void compressedAacPlaysThroughMediaCodec() throws Exception {
        byte[] wav;
        try (InputStream in =
                InstrumentationRegistry.getInstrumentation()
                        .getContext()
                        .getAssets()
                        .open("tone.wav")) {
            wav = Io.read(in);
        }
        File aac = new File(context.getCacheDir(), "codec-tone.aac");
        android.media.MediaCodec encoder =
                android.media.MediaCodec.createEncoderByType("audio/mp4a-latm");
        android.media.MediaFormat format =
                android.media.MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
        format.setInteger(
                android.media.MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 128000);
        encoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        try (OutputStream out = new FileOutputStream(aac)) {
            boolean inputEnded = false, outputEnded = false;
            int offset = 44;
            long deadline = System.currentTimeMillis() + 15000;
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            while (!outputEnded) {
                assertTrue("AAC fixture encoding timed out", System.currentTimeMillis() < deadline);
                if (!inputEnded) {
                    int slot = encoder.dequeueInputBuffer(10000);
                    if (slot >= 0) {
                        ByteBuffer input = encoder.getInputBuffer(slot);
                        int count = Math.min(input.remaining(), wav.length - offset) / 4 * 4;
                        input.put(wav, offset, count);
                        long pts = (offset - 44) * 1000000L / 176400;
                        offset += count;
                        inputEnded = offset >= wav.length;
                        encoder.queueInputBuffer(
                                slot,
                                0,
                                count,
                                pts,
                                inputEnded
                                        ? android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        : 0);
                    }
                }
                int slot = encoder.dequeueOutputBuffer(info, 10000);
                if (slot >= 0) {
                    if (info.size > 0
                            && (info.flags & android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                    == 0) {
                        int length = info.size + 7;
                        out.write(
                                new byte[] {
                                    (byte) 255,
                                    (byte) 241,
                                    (byte) 80,
                                    (byte) (128 | (length >> 11)),
                                    (byte) (length >> 3),
                                    (byte) (((length & 7) << 5) | 31),
                                    (byte) 252
                                });
                        ByteBuffer buffer = encoder.getOutputBuffer(slot);
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        byte[] bytes = new byte[info.size];
                        buffer.get(bytes);
                        out.write(bytes);
                    }
                    outputEnded =
                            (info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(slot, false);
                }
            }
        } finally {
            encoder.stop();
            encoder.release();
        }
        MediaController controller =
                main(() ->
                                new MediaController.Builder(
                                                context,
                                                new SessionToken(
                                                        context,
                                                        new ComponentName(
                                                                context, PlaybackService.class)))
                                        .buildAsync())
                        .get(15, TimeUnit.SECONDS);
        try {
            main(
                    () -> {
                        controller.setMediaItem(MediaItem.fromUri(Uri.fromFile(aac)));
                        controller.prepare();
                        controller.play();
                        return null;
                    });
            long deadline = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < deadline
                    && main(controller::getCurrentPosition) < 500) {
                PlaybackException error = main(controller::getPlayerError);
                if (error != null)
                    fail("MediaCodec AAC failed: " + error + " / " + PcmSink.lastFailure);
                Thread.sleep(100);
            }
            assertTrue("AAC playback did not advance", main(controller::getCurrentPosition) > 500);
            assertTrue(PcmSink.status.contains("AudioTrack"));
        } finally {
            main(
                    () -> {
                        controller.stop();
                        controller.release();
                        return null;
                    });
        }
    }

    private void assertSquareArtwork(android.view.View view) {
        if (view instanceof ArtworkView && view.getWidth() > 0)
            assertEquals("Artwork must be square", view.getWidth(), view.getHeight());
        if (view instanceof android.view.ViewGroup) {
            var parent = (android.view.ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++)
                assertSquareArtwork(parent.getChildAt(i));
        }
    }

    private <T> T main(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}

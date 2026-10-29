package dev.aura.player;

import static org.junit.Assert.*;
import org.junit.*;
import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.audio.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class CodecNegotiationTest {
    private final android.app.Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();
    private final Context c = instrumentation.getTargetContext();

    @Test
    public void realAacDecoderUsesIntegerPcmForUsbPerfectRequest() throws Exception {
        AudioSettings.init(c);
        AudioSettings.put("mode", "usb");
        AudioSettings.put("perfect", true);
        File source = encode();
        AtomicInteger output = new AtomicInteger(-1);
        ExoPlayer player =
                main(
                        () ->
                                new ExoPlayer.Builder(
                                                c,
                                                new AuraRenderersFactory(c) {
                                                    @Override
                                                    protected AudioSink buildAudioSink(
                                                            Context context,
                                                            boolean floating,
                                                            boolean params) {
                                                        return new PcmSink(context) {
                                                            @Override
                                                            public void configure(
                                                                    Format f,
                                                                    int size,
                                                                    int[] channels) {
                                                                output.set(f.pcmEncoding);
                                                            }

                                                            @Override
                                                            public boolean handleBuffer(
                                                                    ByteBuffer bytes,
                                                                    long time,
                                                                    int units) {
                                                                bytes.position(bytes.limit());
                                                                return true;
                                                            }
                                                        };
                                                    }
                                                })
                                        .build());
        try {
            main(
                    () -> {
                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(source)));
                        player.prepare();
                        player.play();
                        return null;
                    });
            long until = SystemClock.elapsedRealtime() + 15000;
            while (output.get() == -1 && SystemClock.elapsedRealtime() < until) {
                assertNull(main(player::getPlayerError));
                Thread.sleep(30);
            }
            assertEquals(C.ENCODING_PCM_16BIT, output.get());
        } finally {
            main(
                    () -> {
                        player.release();
                        AudioSettings.put("mode", "android");
                        AudioSettings.put("perfect", false);
                        return null;
                    });
            source.delete();
        }
    }

    @Test
    public void real24BitFlacDecoderPreservesEveryOriginalSample() throws Exception {
        AudioSettings.init(c);
        AudioSettings.put("mode", "usb");
        AudioSettings.put("perfect", true);
        File file = new File(c.getCacheDir(), "exact-pcm24.flac");
        try (InputStream in = instrumentation.getContext().getAssets().open("pcm24.flac");
                OutputStream out = new FileOutputStream(file)) {
            out.write(Io.read(in));
        }
        AtomicInteger count = new AtomicInteger();
        ExoPlayer player =
                main(
                        () ->
                                new ExoPlayer.Builder(
                                                c,
                                                new AuraRenderersFactory(c) {
                                                    @Override
                                                    protected AudioSink buildAudioSink(
                                                            Context context,
                                                            boolean floating,
                                                            boolean params) {
                                                        return new PcmSink(context) {
                                                            @Override
                                                            public void configure(
                                                                    Format f,
                                                                    int size,
                                                                    int[] channels) {
                                                                if (f.sampleRate != 48000
                                                                        || f.channelCount != 2)
                                                                    throw new IllegalStateException(
                                                                            "Unexpected FLAC format");
                                                                if (f.pcmEncoding
                                                                        == C.ENCODING_PCM_FLOAT) {
                                                                    if (PcmOrigin.bits(f) != 24)
                                                                        throw new IllegalStateException(
                                                                                "Missing 24-bit provenance");
                                                                } else if (f.pcmEncoding
                                                                        != C.ENCODING_PCM_24BIT)
                                                                    throw new IllegalStateException(
                                                                            "Decoder lost bit depth: "
                                                                                    + f.pcmEncoding);
                                                                captured = f;
                                                            }

                                                            private Format captured;

                                                            @Override
                                                            public boolean handleBuffer(
                                                                    ByteBuffer input,
                                                                    long time,
                                                                    int units) {
                                                                ByteBuffer pcm =
                                                                        captured.pcmEncoding
                                                                                        == C
                                                                                                .ENCODING_PCM_FLOAT
                                                                                ? PcmOrigin.restore(
                                                                                        input, 24,
                                                                                        null)
                                                                                : input.duplicate();
                                                                while (pcm.hasRemaining()) {
                                                                    int
                                                                            at =
                                                                                    count
                                                                                            .getAndIncrement(),
                                                                            frame = at / 2,
                                                                            channel = at % 2;
                                                                    int value =
                                                                            (pcm.get() & 255)
                                                                                    | (pcm.get()
                                                                                                    & 255)
                                                                                            << 8
                                                                                    | (pcm.get()
                                                                                                    & 255)
                                                                                            << 16;
                                                                    value = value << 8 >> 8;
                                                                    int expected =
                                                                            frame == 0
                                                                                    ? (channel == 0
                                                                                            ? -8388608
                                                                                            : 8388607)
                                                                                    : ((frame
                                                                                                                    * 104729
                                                                                                            + channel
                                                                                                                    * 1234567)
                                                                                                    & 0xffffff)
                                                                                            - 8388608;
                                                                    if (expected != value)
                                                                        throw new IllegalStateException(
                                                                                "24-bit sample "
                                                                                        + at
                                                                                        + ": "
                                                                                        + expected
                                                                                        + " != "
                                                                                        + value);
                                                                }
                                                                input.position(input.limit());
                                                                return true;
                                                            }
                                                        };
                                                    }
                                                })
                                        .build());
        try {
            main(
                    () -> {
                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
                        player.prepare();
                        player.play();
                        return null;
                    });
            long until = SystemClock.elapsedRealtime() + 15000;
            while (count.get() < 6144 && SystemClock.elapsedRealtime() < until) {
                assertNull(main(player::getPlayerError));
                Thread.sleep(30);
            }
            assertEquals(6144, count.get());
        } finally {
            main(
                    () -> {
                        player.release();
                        AudioSettings.put("mode", "android");
                        AudioSettings.put("perfect", false);
                        return null;
                    });
            file.delete();
        }
    }

    private File encode() throws Exception {
        File file = new File(c.getCacheDir(), "integer-codec-test.m4a");
        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2);
        format.setInteger(
                MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        MediaCodec encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
        MediaMuxer muxer =
                new MediaMuxer(file.toString(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean started = false, eos = false;
        int frame = 0, track = -1;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            long end = SystemClock.elapsedRealtime() + 15000;
            while (SystemClock.elapsedRealtime() < end) {
                if (!eos) {
                    int at = encoder.dequeueInputBuffer(10000);
                    if (at >= 0) {
                        ByteBuffer buffer =
                                encoder.getInputBuffer(at).order(ByteOrder.LITTLE_ENDIAN);
                        buffer.clear();
                        int count = Math.min(buffer.remaining() / 4, 48000 - frame);
                        for (int i = 0; i < count; i++) {
                            short sample =
                                    (short)
                                            (2000
                                                    * Math.sin(
                                                            (frame + i)
                                                                    * 2
                                                                    * Math.PI
                                                                    * 440
                                                                    / 48000));
                            buffer.putShort(sample).putShort(sample);
                        }
                        encoder.queueInputBuffer(
                                at,
                                0,
                                count * 4,
                                frame * 1000000L / 48000,
                                count == 0 ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        frame += count;
                        eos = count == 0;
                    }
                }
                int at = encoder.dequeueOutputBuffer(info, 10000);
                if (at == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    started = true;
                } else if (at >= 0) {
                    ByteBuffer buffer = encoder.getOutputBuffer(at);
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buffer.position(info.offset).limit(info.offset + info.size);
                        muxer.writeSampleData(track, buffer, info);
                    }
                    boolean done = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(at, false);
                    if (done) return file;
                }
            }
            throw new IOException("AAC fixture encoding timed out");
        } finally {
            encoder.stop();
            encoder.release();
            if (started) muxer.stop();
            muxer.release();
        }
    }

    private <T> T main(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        instrumentation.runOnMainSync(future);
        return future.get(20, TimeUnit.SECONDS);
    }
}

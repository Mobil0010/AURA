package dev.aura.player;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Render a receiver-compatible WAV with a frozen DSP snapshot, on a worker thread. */
public final class OfflineRender {
    public static synchronized Uri prepare(Context context, Track track) throws Exception {
        if (track.drive()) throw new IOException("Google Drive 곡은 이 기기에서 스트리밍으로 재생해 주세요.");
        AudioSettings.Config settings = AudioSettings.current;
        Uri source = Uri.parse(track.uri);
        if (track.kind.equals("dsf") || track.kind.equals("dff"))
            source = Uri.fromFile(Dsd.prepare(context, source, "pcm"));
        if (settings.perfect() || !AudioSettings.dsp(settings) && settings.bits() == 0)
            return source;
        File dir = new File(context.getCacheDir(), "render");
        dir.mkdirs();
        String key =
                Hex.encode(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(
                                        (track.uri
                                                        + settings.toString()
                                                        + AudioSettings.extra.toString())
                                                .getBytes(StandardCharsets.UTF_8)));
        File result = new File(dir, key + ".wav");
        if (result.exists() && result.length() > 44) return Uri.fromFile(result);
        File temp = new File(dir, key + ".partial");
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        PcmDsp dsp = null;
        boolean success = false;
        try (RandomAccessFile out = new RandomAccessFile(temp, "rw")) {
            out.setLength(0);
            extractor.setDataSource(context, source, null);
            MediaFormat sourceFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    sourceFormat = f;
                    break;
                }
            }
            if (sourceFormat == null) throw new IOException("변환할 오디오 트랙이 없습니다");
            codec = MediaCodec.createDecoderByType(sourceFormat.getString(MediaFormat.KEY_MIME));
            codec.configure(sourceFormat, null, null, 0);
            codec.start();
            boolean inputEnded = false, outputEnded = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int rate = 0, channels = 0, bits = 0, encoding = 2;
            long progress = System.currentTimeMillis();
            while (!outputEnded) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (System.currentTimeMillis() - progress > 30000)
                    throw new IOException("캐스트용 오디오 변환 시간이 초과되었습니다");
                if (!inputEnded) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        int count = extractor.readSampleData(input, 0);
                        if (count < 0) {
                            codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            codec.queueInputBuffer(
                                    inputIndex, 0, count, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        progress = System.currentTimeMillis();
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    int originalRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding =
                            f.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                    ? f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                    : 2;
                    int originalBits =
                            encoding == 4 || encoding == 22 || encoding == 0x30000000
                                    ? 32
                                    : encoding == 21 || encoding == 0x20000000 ? 24 : 16;
                    rate =
                            settings.sox() && settings.sampleRate() > 0
                                    ? settings.sampleRate()
                                    : originalRate;
                    bits = settings.bits() > 0 ? settings.bits() : originalBits;
                    if (bits == 32) bits = 24;
                    long needed = Math.max(0, track.duration) * rate * channels * bits / 8000;
                    if (needed > 0xfffffff0L || dir.getUsableSpace() < needed + 8_000_000)
                        throw new IOException("변환용 저장 공간이 부족하거나 WAV 크기 제한을 초과했습니다");
                    Dsd.writeWav(out, rate, channels, bits, 0);
                    dsp =
                            new PcmDsp(
                                    originalRate,
                                    rate,
                                    channels,
                                    originalBits,
                                    bits,
                                    settings,
                                    true);
                } else if (index >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(index);
                    if (info.size > 0) {
                        if (dsp == null) throw new IOException("디코더의 출력 형식을 받지 못했습니다");
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        write(out, dsp.process(output, encoding));
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(index, false);
                    progress = System.currentTimeMillis();
                }
            }
            if (dsp == null) throw new IOException("빈 오디오 파일입니다");
            write(out, dsp.drain());
            long size = out.length() - 44;
            if (size > 0xfffffff0L) throw new IOException("WAV 크기 제한을 초과했습니다");
            out.seek(0);
            Dsd.writeWav(out, rate, channels, bits, size);
            success = true;
        } finally {
            extractor.release();
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
            if (dsp != null) dsp.close();
            if (!success) temp.delete();
        }
        if (!temp.renameTo(result)) throw new IOException("캐스트 변환 결과를 저장할 수 없습니다");
        return Uri.fromFile(result);
    }

    private static void write(RandomAccessFile file, ByteBuffer pcm) throws IOException {
        byte[] bytes = new byte[pcm.remaining()];
        pcm.get(bytes);
        file.write(bytes);
    }
}

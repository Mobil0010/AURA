package dev.aura.player;

import android.media.MediaDataSource;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads FLAC metadata blocks only, without opening a decoder or probing audio frames. */
final class FlacMetadata {
    record Info(
            Map<String, String> tags,
            long duration,
            int sampleRate,
            int bitsPerSample,
            byte[] picture,
            boolean pictureSkipped) {
        String tag(String name, String fallback) {
            String value = tags.get(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private static final int MAX_COMMENTS = 1024 * 1024, MAX_PICTURE = 3 * 1024 * 1024;

    static Info read(MediaDataSource source) throws IOException {
        if (source.getSize() < 42) return null;
        byte[] header = bytes(source, 0, 42);
        var audio = TrackFormat.flacInfo(header, 42);
        if (audio.sampleRate() == 0) return null;
        long samples =
                ((long) (header[21] & 15) << 32)
                        | ((long) (header[22] & 255) << 24)
                        | ((long) (header[23] & 255) << 16)
                        | ((long) (header[24] & 255) << 8)
                        | (header[25] & 255);
        Map<String, String> tags = new HashMap<>();
        byte[] picture = null;
        boolean skipped = false, front = false;
        long at = 4, size = source.getSize();
        for (int blocks = 0; blocks < 256; blocks++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            byte[] block = bytes(source, at, 4);
            int type = block[0] & 127,
                    length = (block[1] & 255) << 16 | (block[2] & 255) << 8 | (block[3] & 255);
            at += 4;
            if (at > size - length) throw new EOFException("Truncated FLAC metadata");
            if (type == 4) {
                if (length > MAX_COMMENTS)
                    throw new IOException("FLAC comments exceed metadata limit");
                comments(bytes(source, at, length), tags);
            } else if (type == 6 && !front) {
                if (length > MAX_PICTURE) skipped = true;
                else {
                    try {
                        ByteBuffer b =
                                ByteBuffer.wrap(bytes(source, at, length))
                                        .order(ByteOrder.BIG_ENDIAN);
                        int pictureType = b.getInt();
                        skip(b, b.getInt());
                        skip(b, b.getInt());
                        skip(b, 16);
                        int n = b.getInt();
                        if (n < 0 || n > b.remaining()) throw new IllegalArgumentException();
                        if (picture == null || pictureType == 3) {
                            picture = new byte[n];
                            b.get(picture);
                            front = pictureType == 3;
                        }
                    } catch (BufferUnderflowException | IllegalArgumentException invalid) {
                        skipped = true;
                    }
                }
            }
            at += length;
            if ((block[0] & 128) != 0)
                return new Info(
                        Map.copyOf(tags),
                        samples * 1000 / audio.sampleRate(),
                        audio.sampleRate(),
                        audio.bitsPerSample(),
                        picture,
                        skipped);
        }
        throw new IOException("Too many FLAC metadata blocks");
    }

    private static void comments(byte[] data, Map<String, String> tags) throws IOException {
        try {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            skip(b, b.getInt());
            int count = b.getInt();
            if (count < 0 || count > 65536) throw new IllegalArgumentException();
            for (int i = 0; i < count; i++) {
                int length = b.getInt();
                if (length < 0 || length > b.remaining()) throw new IllegalArgumentException();
                byte[] text = new byte[length];
                b.get(text);
                String field = new String(text, StandardCharsets.UTF_8);
                int equals = field.indexOf('=');
                if (equals > 0) {
                    String key = field.substring(0, equals).toUpperCase(Locale.ROOT);
                    if (Set.of("TITLE", "ARTIST", "ALBUM", "GENRE", "TRACKNUMBER", "DISCNUMBER")
                            .contains(key))
                        tags.putIfAbsent(key, field.substring(equals + 1).trim());
                }
            }
        } catch (BufferUnderflowException | IllegalArgumentException invalid) {
            throw new IOException("Invalid FLAC comments", invalid);
        }
    }

    private static void skip(ByteBuffer buffer, int length) {
        if (length < 0 || length > buffer.remaining()) throw new IllegalArgumentException();
        buffer.position(buffer.position() + length);
    }

    private static byte[] bytes(MediaDataSource source, long position, int length)
            throws IOException {
        byte[] result = new byte[length];
        int at = 0;
        while (at < length) {
            int n = source.readAt(position + at, result, at, length - at);
            if (n <= 0) throw new EOFException();
            at += n;
        }
        return result;
    }
}

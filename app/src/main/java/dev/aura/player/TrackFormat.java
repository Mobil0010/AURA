package dev.aura.player;

import android.net.Uri;
import java.util.Locale;
import java.util.Set;

/** File format labels from indexed data only; no file reads on list binding. */
final class TrackFormat {
    record AudioInfo(int sampleRate, int bitsPerSample) {}

    static AudioInfo flacInfo(byte[] head, int count) {
        if (count < 42
                || head.length < 42
                || head[0] != 'f'
                || head[1] != 'L'
                || head[2] != 'a'
                || head[3] != 'C'
                || (head[4] & 127) != 0
                || head[5] != 0
                || head[6] != 0
                || (head[7] & 255) != 34) return new AudioInfo(0, 0);
        int rate = (head[18] & 255) << 12 | (head[19] & 255) << 4 | (head[20] & 255) >> 4;
        int bits = (((head[20] & 1) << 4) | ((head[21] & 255) >> 4)) + 1;
        return rate > 0 && bits >= 4 && bits <= 32
                ? new AudioInfo(rate, bits)
                : new AudioInfo(0, 0);
    }

    static Track readFlac(Track track, android.media.MediaDataSource source) {
        if (!TrackFormat.label(track.uri, track.kind, track.mime).equals("FLAC")) return track;
        try {
            byte[] head = new byte[42];
            int at = 0;
            while (at < head.length) {
                int n = source.readAt(at, head, at, head.length - at);
                if (n <= 0) break;
                at += n;
            }
            AudioInfo info = flacInfo(head, at);
            return track.withAudioFormat(info.sampleRate(), info.bitsPerSample());
        } catch (java.io.IOException optional) {
            return track;
        }
    }

    static Track readLocalFlac(android.content.Context c, Track track) {
        if (!TrackFormat.label(track.uri, track.kind, track.mime).equals("FLAC")
                || track.sampleRate > 0 && track.bitsPerSample > 0) return track;
        try (var input = c.getContentResolver().openInputStream(Uri.parse(track.uri))) {
            if (input == null) return track;
            byte[] head = new byte[42];
            int at = 0;
            while (at < head.length) {
                int n = input.read(head, at, head.length - at);
                if (n <= 0) break;
                at += n;
            }
            AudioInfo info = flacInfo(head, at);
            return track.withAudioFormat(info.sampleRate(), info.bitsPerSample());
        } catch (Exception optional) {
            return track;
        }
    }

    private static final Set<String> EXTENSIONS =
            Set.of(
                    "flac", "mp3", "wav", "aac", "m4a", "mp4", "ogg", "opus", "aiff", "aif", "alac",
                    "wma", "ape", "dsf", "dff", "wv", "webm");

    static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSIONS.contains(ext) ? ext : "";
    }

    static String label(String uri, String kind, String mime) {
        String ext = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(ext)) {
            Uri parsed = Uri.parse(uri);
            ext =
                    extension(
                            DriveUris.is(parsed)
                                    ? parsed.getQueryParameter("name")
                                    : parsed.getLastPathSegment());
        }
        if (!ext.isEmpty()) return (ext.equals("aif") ? "aiff" : ext).toUpperCase(Locale.ROOT);
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return switch (type) {
            case "audio/flac", "audio/x-flac" -> "FLAC";
            case "audio/mpeg", "audio/mp3" -> "MP3";
            case "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "WAV";
            case "audio/aac", "audio/aacp", "audio/mp4a-latm" -> "AAC";
            case "audio/mp4", "audio/x-m4a" -> "M4A";
            case "audio/ogg", "application/ogg", "audio/vorbis" -> "OGG";
            case "audio/opus" -> "OPUS";
            case "audio/aiff", "audio/x-aiff" -> "AIFF";
            case "audio/alac", "audio/x-alac" -> "ALAC";
            case "audio/x-ms-wma" -> "WMA";
            case "audio/ape", "audio/x-ape" -> "APE";
            case "audio/webm" -> "WEBM";
            default -> "";
        };
    }
}

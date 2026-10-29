package dev.aura.player;

import java.util.*;
import java.util.regex.*;

public final class Lrc {
    public record Word(long time, String text) {}

    public record Line(long time, String text, List<Word> words) {
        public Line(long time, String text) {
            this(time, text, List.of());
        }
    }

    private static final Pattern TIME = Pattern.compile("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern WORD = Pattern.compile("<(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?>");

    private static long stamp(Matcher m) {
        int seconds = Integer.parseInt(m.group(2));
        if (seconds >= 60) throw new NumberFormatException();
        String f = m.group(3);
        return Math.addExact(
                Math.multiplyExact(Long.parseLong(m.group(1)), 60000),
                seconds * 1000L + (f == null ? 0 : Integer.parseInt((f + "00").substring(0, 3))));
    }

    public static List<Line> parse(String text) {
        List<Line> out = new ArrayList<>();
        long offset = 0;
        Matcher meta =
                Pattern.compile("\\[offset:([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE).matcher(text);
        if (meta.find())
            try {
                offset = Long.parseLong(meta.group(1));
            } catch (NumberFormatException ignored) {
            }
        for (String raw : text.split("\\R")) {
            Matcher m = TIME.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find())
                try {
                    times.add(Math.max(0, Math.addExact(stamp(m), offset)));
                    end = m.end();
                } catch (NumberFormatException | ArithmeticException ignored) {
                }
            if (times.isEmpty()) continue;
            String lyric = raw.substring(end).trim();
            Matcher wm = WORD.matcher(lyric);
            List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
            List<Long> stamps = new ArrayList<>();
            while (wm.find())
                try {
                    stamps.add(Math.max(0, Math.addExact(stamp(wm), offset)));
                    starts.add(wm.start());
                    ends.add(wm.end());
                } catch (NumberFormatException | ArithmeticException ignored) {
                }
            List<Word> words = new ArrayList<>();
            String plain = lyric;
            if (!stamps.isEmpty()) {
                StringBuilder all = new StringBuilder(lyric.substring(0, starts.get(0)));
                for (int i = 0; i < stamps.size(); i++) {
                    String word =
                            lyric.substring(
                                    ends.get(i),
                                    i + 1 < stamps.size() ? starts.get(i + 1) : lyric.length());
                    words.add(new Word(stamps.get(i), word));
                    all.append(word);
                }
                plain = all.toString();
            }
            for (long t : times) out.add(new Line(t, plain, List.copyOf(words)));
        }
        out.sort(Comparator.comparingLong(Line::time));
        return out;
    }

    public static int active(List<Line> lines, long position) {
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).time() <= position) {
                found = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        return found;
    }
}

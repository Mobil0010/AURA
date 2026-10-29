package dev.aura.player;

import java.text.BreakIterator;
import java.util.*;

/** Exact enhanced-LRC markers, or explicitly estimated word slots within a timed line. */
final class KaraokeTiming {
    record Slot(int start, int end, long time, long until) {}

    record Timed(List<Slot> slots, boolean estimated) {}

    record Progress(int complete, int next, float fraction) {}

    static Timed line(List<Lrc.Line> lines, int index, long duration) {
        Lrc.Line line = lines.get(index);
        String text = line.text();
        long end =
                index + 1 < lines.size()
                        ? lines.get(index + 1).time()
                        : Math.min(
                                duration > line.time() ? duration : line.time() + 6000,
                                line.time() + 8000);
        end = Math.max(line.time() + 1, end);
        List<Slot> out = new ArrayList<>();
        if (!line.words().isEmpty()) {
            int at = 0;
            for (int i = 0; i < line.words().size(); i++) {
                var word = line.words().get(i);
                int start = text.indexOf(word.text(), at);
                if (start < 0) start = at;
                int stop = Math.min(text.length(), start + word.text().length());
                long time = Math.max(line.time(), word.time()),
                        until = i + 1 < line.words().size() ? line.words().get(i + 1).time() : end;
                if (stop > start) out.add(new Slot(start, stop, time, Math.max(time + 1, until)));
                at = stop;
            }
            return new Timed(List.copyOf(out), false);
        }
        BreakIterator words = BreakIterator.getWordInstance(Locale.getDefault());
        words.setText(text);
        List<int[]> spans = new ArrayList<>();
        for (int start = words.first(), stop = words.next();
                stop != BreakIterator.DONE;
                start = stop, stop = words.next())
            if (text.substring(start, stop).codePoints().anyMatch(Character::isLetterOrDigit))
                spans.add(new int[] {start, stop});
        int weight = 0;
        for (int[] span : spans) weight += text.codePointCount(span[0], span[1]);
        long until = Math.min(end, line.time() + 10000);
        int walked = 0;
        for (int[] span : spans) {
            int count = text.codePointCount(span[0], span[1]);
            long from = line.time() + (until - line.time()) * walked / Math.max(1, weight);
            walked += count;
            out.add(
                    new Slot(
                            span[0],
                            span[1],
                            from,
                            line.time() + (until - line.time()) * walked / Math.max(1, weight)));
        }
        return new Timed(List.copyOf(out), true);
    }

    static Progress progress(String text, Timed timed, long position) {
        Slot current = null;
        for (Slot slot : timed.slots()) {
            if (slot.time() > position) break;
            current = slot;
        }
        if (current == null) return new Progress(0, 0, 0);
        if (position >= current.until()) return new Progress(current.end(), current.end(), 0);
        int count = text.codePointCount(current.start(), current.end());
        double advance =
                Math.max(
                                0,
                                Math.min(
                                        1,
                                        (position - current.time())
                                                / (double)
                                                        Math.max(
                                                                1,
                                                                current.until() - current.time())))
                        * count;
        int steps = Math.min(count, (int) advance),
                edge = text.offsetByCodePoints(current.start(), steps);
        int next = edge < current.end() ? text.offsetByCodePoints(edge, 1) : edge;
        return new Progress(edge, next, (float) (advance - steps));
    }
}

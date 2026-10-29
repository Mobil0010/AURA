package dev.aura.player;

import java.util.*;

public final class KaraokeTimingTest {
    public static void main(String[] args) {
        var lines =
                Lrc.parse("[00:01.00]<00:01.00>한글 <00:02.00>word<00:03.00>\n[00:04.00]Next line");
        var timing = KaraokeTiming.line(lines, 0, 6000);
        check(!timing.estimated(), "exact marker source");
        check(
                KaraokeTiming.progress(lines.get(0).text(), timing, 999).complete() == 0,
                "before first word");
        var half = KaraokeTiming.progress(lines.get(0).text(), timing, 2500);
        check(half.complete() >= 5 && half.fraction() >= 0, "word boundary progression");
        check(
                KaraokeTiming.progress(lines.get(0).text(), timing, 3500).complete()
                        == lines.get(0).text().length(),
                "explicit end marker");
        var plain = Lrc.parse("[00:01]Hello beautiful world\n[00:05]Next");
        var estimate = KaraokeTiming.line(plain, 0, 9000);
        check(
                estimate.estimated() && estimate.slots().size() == 3,
                "line-only words explicitly estimated");
        check(
                KaraokeTiming.progress(plain.get(0).text(), estimate, 4000).complete() > 5,
                "estimated advance");
        var emoji = Lrc.parse("[00:00]<00:00>😀<00:01>ok");
        var p =
                KaraokeTiming.progress(
                        emoji.get(0).text(), KaraokeTiming.line(emoji, 0, 2000), 500);
        check(p.complete() == 0 && p.next() == 2, "never split surrogate pairs");
        check(
                KaraokeTiming.line(Lrc.parse("[00:00]♪"), 0, 1000).slots().isEmpty(),
                "instrumental symbols");
        System.out.println("PASS: 8 karaoke timing checks");
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
}

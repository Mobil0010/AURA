package dev.aura.player;

public final class CoreTest {
    public static void main(String[] args) {
        var lines =
                Lrc.parse(
                        "[ar:Artist]\n[offset:-500]\n[00:02.50][00:04.005]반복\n[00:01.1]첫 줄\n[00:99.00]잘못된 시간\n[00:05]끝");
        check(lines.size() == 4, "다중 시간 태그 / 메타데이터 / 잘못된 초");
        check(lines.get(0).time() == 600, "소수 시간 / 음수 오프셋");
        check(lines.get(1).time() == 2000, "2자리 소수");
        check(lines.get(2).time() == 3505, "3자리 소수");
        check(Lrc.active(lines, 599) == -1, "첫 가사 전");
        check(Lrc.active(lines, 600) == 0, "정확한 경계");
        check(Lrc.active(lines, 3504) == 1, "이전 가사 유지");
        check(Lrc.active(lines, 99999) == 3, "마지막 가사 유지");
        check(Lrc.parse("일반 가사\n두 번째 줄").isEmpty(), "일반 가사는 싱크 생성 안 함");
        check(Lrc.parse("[offset:-5000]\n[00:01]a").get(0).time() == 0, "음수 시간 제한");
        check(Hex.encode(new byte[] {0, 15, (byte) 255}).equals("000fff"), "인증 토큰 인코딩");
        System.out.println("PASS: 11 core checks");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

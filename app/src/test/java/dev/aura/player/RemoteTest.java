package dev.aura.player;

import java.net.*;
import java.util.concurrent.atomic.*;
import java.nio.charset.StandardCharsets;

/** Exercises the production HTTP server over real loopback sockets without Android mocks. */
public final class RemoteTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        AtomicReference<String> last = new AtomicReference<>("");
        RemoteServer server =
                new RemoteServer(
                        new RemoteServer.Endpoint() {
                            public String status() {
                                return "{\"playing\":true,\"title\":\"시험 곡\"}";
                            }

                            public void command(String c) {
                                last.set(c);
                            }
                        },
                        Runnable::run);
        server.start();
        String link = server.linkForHost("127.0.0.1"),
                base = link.substring(0, link.indexOf("/#")),
                token = link.substring(link.indexOf('#') + 1);
        try {
            check(token.length() == 48, "192bit 인증 토큰");
            check(call(base, "/", "GET", null, null) == 200, "리모컨 페이지");
            check(call(base, "/status", "GET", null, null) == 403, "인증 없는 상태 조회 차단");
            check(call(base, "/command/pause", "POST", "wrong", null) == 403, "잘못된 토큰 차단");
            check(call(base, "/status", "GET", token, null) == 200, "인증된 상태 조회");
            check(call(base, "/command/pause", "GET", token, null) == 400, "GET 명령 차단");
            check(
                    call(base, "/command/pause", "POST", token, "http://evil.invalid") == 403,
                    "다른 출처 명령 차단");
            for (String c : new String[] {"play", "pause", "next", "previous"}) {
                check(call(base, "/command/" + c, "POST", token, base) == 200, "명령 " + c);
                check(last.get().equals(c), "명령 전달 " + c);
            }
            check(call(base, "/command/seek/12345", "POST", token, base) == 200, "탐색 명령");
            check(last.get().equals("seek:12345"), "탐색 위치 전달");
            check(call(base, "/command/seek/999999999", "POST", token, base) == 400, "탐색 범위 제한");
            check(call(base, "/command/delete", "POST", token, null) == 404, "지원하지 않는 명령 차단");
        } finally {
            server.close();
        }
        try {
            call(base, "/status", "GET", token, null);
            throw new AssertionError("닫힌 서버 접근 허용됨");
        } catch (java.io.IOException expected) {
            checks++;
        }
        try (RemoteServer second =
                new RemoteServer(
                        new RemoteServer.Endpoint() {
                            public String status() {
                                return "{}";
                            }

                            public void command(String c) {}
                        },
                        Runnable::run)) {
            second.start();
            check(!second.linkForHost("127.0.0.1").endsWith(token), "새 세션 인증 회전");
        }
        System.out.println("PASS: " + checks + " remote HTTP checks");
    }

    private static int call(String base, String path, String method, String token, String origin)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(1500);
        c.setReadTimeout(1500);
        c.setRequestMethod(method);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (origin != null) c.setRequestProperty("Origin", origin);
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static void check(boolean okay, String name) {
        checks++;
        if (!okay) throw new AssertionError(name);
    }
}

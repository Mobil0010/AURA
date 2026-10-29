package dev.aura.player;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Authenticated Drive requests. Never send account tokens to redirected content hosts. */
final class DriveHttp {
    static final class StatusException extends IOException {
        final int code;

        StatusException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    interface Tokens {
        String token() throws IOException;

        void invalidate(String token);
    }

    record Response(HttpURLConnection connection, InputStream input, int code)
            implements AutoCloseable {
        public void close() {
            try {
                input.close();
            } catch (IOException ignored) {
            }
            connection.disconnect();
        }
    }

    private final String base;
    private final Tokens tokens;

    DriveHttp(Tokens tokens) {
        this("https://www.googleapis.com/drive/v3/", tokens);
    }

    DriveHttp(String base, Tokens tokens) {
        this.base = base;
        this.tokens = tokens;
    }

    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    Response open(String path, long position, long count, String etag) throws IOException {
        for (int auth = 0; auth < 2; auth++) {
            String token = tokens.token();
            URL target = new URL(base + path);
            boolean retry = false;
            for (int redirects = 0; redirects < 5; redirects++) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException();
                HttpURLConnection c = (HttpURLConnection) target.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(12000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("User-Agent", "AURA/" + BuildConfig.VERSION_NAME);
                if (sameOrigin(new URL(base), target))
                    c.setRequestProperty("Authorization", "Bearer " + token);
                if (position >= 0) {
                    long last = count > 0 ? Math.addExact(position, count - 1) : -1;
                    c.setRequestProperty(
                            "Range", "bytes=" + position + "-" + (last >= 0 ? last : ""));
                }
                if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-Match", etag);
                int code;
                try {
                    code = c.getResponseCode();
                } catch (IOException e) {
                    c.disconnect();
                    throw new IOException("Google Drive에 연결하지 못했습니다. 네트워크를 확인해 주세요.");
                }
                if (code == 401 && auth == 0 && sameOrigin(new URL(base), target)) {
                    c.disconnect();
                    tokens.invalidate(token);
                    retry = true;
                    break;
                }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = c.getHeaderField("Location");
                    c.disconnect();
                    if (location == null) throw new IOException("Google Drive 응답에 파일 주소가 없습니다.");
                    URL next = new URL(target, location);
                    if (!allowedRedirect(next))
                        throw new IOException("Google Drive가 허용되지 않은 주소를 반환했습니다.");
                    target = next;
                    continue;
                }
                if (code != 200 && code != 206) {
                    c.disconnect();
                    throw new StatusException(
                            code,
                            switch (code) {
                                case 401 -> "Google Drive를 다시 연결해 주세요.";
                                case 403 -> "Google Drive 읽기 권한 또는 사용 한도를 확인해 주세요.";
                                case 404 -> "Google Drive 파일이 삭제되었거나 접근할 수 없습니다. 폴더를 다시 검색해 주세요.";
                                case 412 -> "재생 중 Google Drive 파일이 변경되었습니다. 다시 재생해 주세요.";
                                case 416 -> "요청한 재생 위치를 읽을 수 없습니다.";
                                case 429 -> "Google Drive 요청이 많습니다. 잠시 후 다시 시도해 주세요.";
                                default -> "Google Drive 서버 응답 오류 (" + code + ")";
                            });
                }
                try {
                    return new Response(c, c.getInputStream(), code);
                } catch (IOException e) {
                    c.disconnect();
                    throw new IOException("Google Drive 파일을 읽지 못했습니다.");
                }
            }
            if (!retry) break;
        }
        throw new IOException("Google Drive 연결을 완료하지 못했습니다. 다시 연결해 주세요.");
    }

    private boolean allowedRedirect(URL u) throws IOException {
        URL origin = new URL(base);
        if (sameOrigin(origin, u)) return true;
        String host = u.getHost();
        return "https".equals(u.getProtocol())
                && u.getUserInfo() == null
                && (host.equals("www.googleapis.com") || host.endsWith(".googleusercontent.com"));
    }

    private static boolean sameOrigin(URL a, URL b) {
        return a.getProtocol().equals(b.getProtocol())
                && a.getHost().equals(b.getHost())
                && a.getPort() == b.getPort()
                && b.getUserInfo() == null;
    }

    JSONObject json(String path) throws IOException {
        try (Response r = open(path, -1, -1, null)) {
            try {
                return new JSONObject(
                        new String(read(r.input(), 4 * 1024 * 1024), StandardCharsets.UTF_8));
            } catch (org.json.JSONException e) {
                throw new IOException("Google Drive 응답을 읽지 못했습니다.");
            }
        }
    }

    static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > max) throw new IOException("Google Drive 응답이 너무 큽니다.");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
}

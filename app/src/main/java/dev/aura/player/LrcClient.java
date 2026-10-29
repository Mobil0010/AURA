package dev.aura.player;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public LRCLIB requests. No account credentials; bounded responses and explicit opt-in at caller. */
final class LrcClient {
    private final String base;

    LrcClient() {
        this("https://lrclib.net/api/");
    }

    LrcClient(String base) {
        this.base = base;
    }

    JSONObject find(Track track) throws Exception {
        if (track.title.isBlank() || track.artist.isBlank())
            throw new IOException("곡명과 가수를 확인할 수 없습니다. 가사 직접 검색을 이용해 주세요.");
        String query =
                "track_name="
                        + enc(track.title.trim())
                        + "&artist_name="
                        + enc(track.artist.trim());
        if (!unknown(track.album)) query += "&album_name=" + enc(track.album.trim());
        if (track.duration >= 1000 && track.duration <= 3_600_000)
            query += "&duration=" + Math.round(track.duration / 1000.0);
        JSONObject plain = null;
        String exact = request("get?" + query, true);
        if (exact != null) {
            JSONObject row = new JSONObject(exact);
            if (row.optBoolean("instrumental") || !Lrc.parse(lyrics(row)).isEmpty()) return row;
            if (!lyrics(row).isEmpty()) plain = row;
        }
        JSONArray rows;
        try {
            rows =
                    new JSONArray(
                            request(
                                    "search?track_name="
                                            + enc(track.title.trim())
                                            + "&artist_name="
                                            + enc(track.artist.trim()),
                                    false));
        } catch (IOException e) {
            if (plain != null) return plain;
            throw e;
        }
        List<JSONObject> matches = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null
                    || !normalize(track.title).equals(normalize(row.optString("trackName")))
                    || !normalize(track.artist).equals(normalize(row.optString("artistName"))))
                continue;
            if (track.duration > 0
                    && (!row.has("duration")
                            || Math.abs(row.optDouble("duration") - track.duration / 1000.0) > 2))
                continue;
            if (!row.optBoolean("instrumental") && lyrics(row).isEmpty()) continue;
            matches.add(row);
        }
        if (matches.isEmpty() && plain != null) return plain;
        if (matches.isEmpty()) throw new IOException("일치하는 가사가 없습니다. 가사 직접 검색에서 다른 표기로 찾아 주세요.");
        for (JSONObject row : matches)
            if (!Lrc.parse(lyrics(row)).isEmpty()
                    && !unknown(track.album)
                    && normalize(track.album).equals(normalize(row.optString("albumName"))))
                return row;
        if (track.duration <= 0 && matches.size() != 1)
            throw new IOException("여러 버전의 가사가 있습니다. 가사 직접 검색에서 선택해 주세요.");
        for (JSONObject row : matches) if (!Lrc.parse(lyrics(row)).isEmpty()) return row;
        if (plain != null) return plain;
        return matches.get(0);
    }

    JSONArray search(String query) throws Exception {
        if (query.trim().isEmpty()) return new JSONArray();
        return new JSONArray(request("search?q=" + enc(query.trim()), false));
    }

    static String lyrics(JSONObject row) {
        String text = clean(row, "syncedLyrics");
        return text.isEmpty() ? clean(row, "plainLyrics") : text;
    }

    private static String clean(JSONObject row, String key) {
        if (row.isNull(key)) return "";
        String text = row.optString(key, "");
        return text.isBlank() || text.equals("null") ? "" : text;
    }

    private static boolean unknown(String s) {
        return s == null || s.isBlank() || s.startsWith("알 수 없는") || s.equalsIgnoreCase("unknown");
    }

    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private String request(String path, boolean missingAllowed) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty(
                "User-Agent",
                "AURA/" + BuildConfig.VERSION_NAME + " (https://github.com/Mobil0010/AURA)");
        try {
            int code = connection.getResponseCode();
            if (code == 404 && missingAllowed) return null;
            if (code == 429) throw new IOException("가사 요청이 잠시 제한되었습니다. 잠시 후 다시 시도해 주세요.");
            if (code >= 500) throw new IOException("LRCLIB 서버가 응답하지 않습니다. 잠시 후 다시 시도해 주세요.");
            if (code != 200) throw new IOException("가사 요청에 실패했습니다. 응답 코드: " + code);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) {
                    if (out.size() + n > 2_000_000) throw new IOException("가사 데이터가 너무 큽니다.");
                    out.write(b, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (java.net.UnknownHostException
                | java.net.ConnectException
                | javax.net.ssl.SSLException e) {
            throw new IOException("가사 서버에 연결하지 못했습니다. 인터넷 연결과 기기의 날짜·시간을 확인해 주세요.", e);
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("가사 서버 연결 시간이 초과되었습니다. 다시 시도해 주세요.", e);
        } finally {
            connection.disconnect();
        }
    }
}

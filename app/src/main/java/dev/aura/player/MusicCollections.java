package dev.aura.player;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;
import java.text.Normalizer;

/** Library-derived collections. Call disk/query methods on a background worker. */
final class MusicCollections extends SQLiteOpenHelper {
    record Mix(String title, List<Track> songs) {}

    MusicCollections(Context c) {
        super(c.getApplicationContext(), "collections.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE added(uri TEXT PRIMARY KEY, stamp INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int old, int next) {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("music", 0);
    }

    static String key(String uri) {
        try {
            return Hex.encode(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(uri.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean favorite(Context c, String uri) {
        return prefs(c).getStringSet("favorites", Set.of()).contains(uri);
    }

    static void favorite(Context c, String uri, boolean enabled) {
        Set<String> all = new HashSet<>(prefs(c).getStringSet("favorites", Set.of()));
        if (enabled) all.add(uri);
        else all.remove(uri);
        prefs(c).edit().putStringSet("favorites", all).apply();
    }

    static List<Track> visible(Context c, List<Track> tracks) {
        Set<String> hidden = prefs(c).getStringSet("hidden", Set.of());
        if (hidden.isEmpty()) return tracks;
        return tracks.stream()
                .filter(t -> !hidden.contains(t.uri))
                .collect(java.util.stream.Collectors.toList());
    }

    List<Mix> mixes(Context c, List<Track> tracks) throws Exception {
        var db = getWritableDatabase();
        long now = System.currentTimeMillis();
        Map<String, Long> added = new HashMap<>(), heard = new HashMap<>();
        try (Cursor q = db.rawQuery("SELECT uri,stamp FROM added", null)) {
            while (q.moveToNext()) added.put(q.getString(0), q.getLong(1));
        }
        List<Track> unseen = new ArrayList<>();
        for (Track t : tracks) if (!added.containsKey(t.uri)) unseen.add(t);
        if (!unseen.isEmpty()) {
            db.beginTransaction();
            try {
                for (Track t : unseen) {
                    db.execSQL(
                            "INSERT OR IGNORE INTO added VALUES(?,?)", new Object[] {t.uri, now});
                    added.put(t.uri, now);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        try (Cursor q =
                ListeningStore.get(c)
                        .getReadableDatabase()
                        .rawQuery("SELECT uri,MAX(last) FROM heard GROUP BY uri", null)) {
            while (q.moveToNext()) heard.put(q.getString(0), q.getLong(1));
        }
        List<Track> recent = new ArrayList<>(tracks);
        recent.sort(
                Comparator.comparingLong((Track t) -> added.getOrDefault(t.uri, 0L))
                        .reversed()
                        .thenComparing(t -> t.sortTitle));
        List<Track> old = new ArrayList<>();
        for (Track t : tracks)
            if (heard.containsKey(t.uri) && heard.get(t.uri) < now - 30L * 86400000) old.add(t);
        old.sort(Comparator.comparingLong(t -> heard.get(t.uri)));
        List<Track> fav = new ArrayList<>(), fresh = new ArrayList<>();
        for (Track t : tracks) {
            if (favorite(c, t.uri)) fav.add(t);
            if (!heard.containsKey(t.uri)) fresh.add(t);
        }
        List<Mix> out = new ArrayList<>();
        out.add(new Mix("최근 추가한 곡", limit(recent, 50)));
        if (!fav.isEmpty()) out.add(new Mix("즐겨찾는 음악", List.copyOf(fav)));
        if (!old.isEmpty()) out.add(new Mix("오랜만에 듣는 곡", limit(old, 50)));
        if (!fresh.isEmpty()) out.add(new Mix("아직 듣지 않은 곡", limit(fresh, 50)));
        JSONArray rules = new JSONArray(prefs(c).getString("mixes", "[]"));
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.getJSONObject(i);
            List<Track> songs = new ArrayList<>();
            for (Track t : recent)
                if ((!r.optBoolean("favorite") || favorite(c, t.uri))
                        && (!r.optBoolean("unheard") || !heard.containsKey(t.uri))
                        && (r.optString("genre").isBlank()
                                || t.genre.equalsIgnoreCase(r.optString("genre")))) songs.add(t);
            out.add(
                    new Mix(
                            r.optString("name", "나의 모음"),
                            limit(songs, Math.max(1, Math.min(500, r.optInt("limit", 50))))));
        }
        return out;
    }

    static List<Track> limit(List<Track> songs, int count) {
        return List.copyOf(songs.subList(0, Math.min(count, songs.size())));
    }

    static String duplicateKey(Track t) {
        return normalize(t.title) + "|" + normalize(t.artist);
    }

    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", "");
    }

    static List<List<Track>> duplicates(List<Track> tracks) {
        Map<String, List<Track>> groups = new LinkedHashMap<>();
        for (Track t : tracks)
            if (!t.title.isBlank() && !t.artist.isBlank())
                groups.computeIfAbsent(duplicateKey(t), k -> new ArrayList<>()).add(t);
        List<List<Track>> out = new ArrayList<>();
        for (List<Track> group : groups.values()) {
            group.sort(Comparator.comparingLong(t -> t.duration));
            List<Track> cluster = new ArrayList<>();
            long start = 0;
            for (Track t : group) {
                if (!cluster.isEmpty() && t.duration - start > 2000) {
                    if (cluster.size() > 1) out.add(List.copyOf(cluster));
                    cluster.clear();
                }
                if (cluster.isEmpty()) start = t.duration;
                cluster.add(t);
            }
            if (cluster.size() > 1) out.add(List.copyOf(cluster));
        }
        return out;
    }
}

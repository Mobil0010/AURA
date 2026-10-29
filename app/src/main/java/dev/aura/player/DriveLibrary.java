package dev.aura.player;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A separate index keeps local scans and cloud scans from replacing one another. */
final class DriveLibrary {
    static final Object LOCK = new Object();
    static volatile long revision;

    record Folder(String id, String name) {}

    interface MetadataReader {
        JSONObject read(Context c, JSONObject entry, long generation) throws Exception;
    }

    private final Context c;
    private final DriveApi api;
    private final MetadataReader metadata;
    private final int readers;

    DriveLibrary(Context c) {
        this(c, new DriveApi(new DriveHttp(DriveAuth.get(c))));
    }

    DriveLibrary(Context c, DriveApi api) {
        this(
                c,
                api,
                (context, row, generation) -> readMetadata(context, row, generation, api.http()));
    }

    DriveLibrary(Context c, DriveApi api, MetadataReader metadata) {
        this(c, api, metadata, PlaybackBudget.get(c).constrained() ? 1 : 4);
    }

    DriveLibrary(Context c, DriveApi api, MetadataReader metadata, int readers) {
        this.c = c.getApplicationContext();
        this.api = api;
        this.metadata = metadata;
        this.readers = Math.max(1, Math.min(4, readers));
    }

    static List<Folder> roots(Context c) {
        List<Folder> roots = new ArrayList<>();
        try {
            JSONArray a =
                    new JSONArray(c.getSharedPreferences("drive", 0).getString("roots", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                roots.add(new Folder(j.getString("id"), j.getString("name")));
            }
        } catch (Exception ignored) {
        }
        return roots;
    }

    static void saveRoots(Context c, List<Folder> roots) {
        JSONArray a = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (Folder f : roots)
            if (seen.add(f.id()))
                try {
                    a.put(new JSONObject().put("id", f.id()).put("name", f.name()));
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
        c.getSharedPreferences("drive", 0).edit().putString("roots", a.toString()).apply();
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "drive-library.json");
    }

    private static JSONObject index(Context c) {
        try {
            return new JSONObject(
                    new String(
                            java.nio.file.Files.readAllBytes(file(c).toPath()),
                            StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static long fileSize(Context c, String uri) {
        JSONObject j = index(c);
        JSONArray a = j.optJSONArray("tracks");
        if (a != null)
            for (int i = 0; i < a.length(); i++) {
                JSONObject row = a.optJSONObject(i);
                if (row != null && uri.equals(row.optString("uri"))) return row.optLong("size", -1);
            }
        return -1;
    }

    static List<Track> load(Context c) {
        String account = DriveAuth.get(c).accountId();
        if (account.isEmpty()) return new ArrayList<>();
        JSONObject j = index(c);
        if (!account.equals(j.optString("account"))) return new ArrayList<>();
        List<Track> out = new ArrayList<>();
        JSONArray a = j.optJSONArray("tracks");
        if (a != null)
            for (int i = 0; i < a.length(); i++)
                try {
                    Track t = Track.from(a.getJSONObject(i));
                    if (t.drive() && account.equals(Uri.parse(t.uri).getAuthority())) {
                        ArtworkStore.restoreDriveCover(c, t.uri);
                        out.add(t);
                    }
                } catch (Exception ignored) {
                }
        return out;
    }

    static void clear(Context c) {
        for (Track t : load(c)) {
            ArtworkStore.remove(c, t.uri);
            try {
                File lyric =
                        new File(
                                c.getFilesDir(),
                                "lyrics/"
                                        + Hex.encode(
                                                java.security.MessageDigest.getInstance("SHA-256")
                                                        .digest(
                                                                t.uri.getBytes(
                                                                        StandardCharsets.UTF_8)))
                                        + ".json");
                lyric.delete();
            } catch (Exception ignored) {
            }
        }
        file(c).delete();
        DriveFolderArtwork.clear(c);
        revision++;
        c.getSharedPreferences("drive", 0).edit().remove("roots").apply();
    }

    private static void write(Context c, JSONObject index) throws IOException {
        write(c, index, true);
    }

    private static void write(Context c, JSONObject index, boolean changed) throws IOException {
        File tmp = new File(c.getFilesDir(), "drive-library.tmp");
        java.nio.file.Files.write(tmp.toPath(), index.toString().getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.move(
                tmp.toPath(), file(c).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (changed) revision++;
    }

    List<Track> scan(java.util.function.Consumer<Library.Progress> progress) throws Exception {
        return scan(progress, false);
    }

    List<Track> scan(java.util.function.Consumer<Library.Progress> progress, boolean additionsOnly)
            throws Exception {
        return scanInternal(progress, additionsOnly, null);
    }

    List<Track> scanFolder(
            FolderScope scope, java.util.function.Consumer<Library.Progress> progress)
            throws Exception {
        if (!scope.drive() || roots(c).stream().noneMatch(r -> r.id().equals(scope.root())))
            throw new IOException("등록된 Drive 음악 폴더를 선택해 주세요.");
        return scanInternal(progress, false, scope);
    }

    private List<Track> scanInternal(
            java.util.function.Consumer<Library.Progress> progress,
            boolean additionsOnly,
            FolderScope scope)
            throws Exception {
        DriveAuth auth = DriveAuth.get(c);
        String account = auth.accountId();
        long generation = auth.generation();
        List<Folder> roots = roots(c);
        if (account.isEmpty()) throw new IOException("Google Drive 계정을 연결해 주세요.");
        if (roots.isEmpty()) throw new IOException("Google Drive 음악 폴더를 추가해 주세요.");
        JSONObject snapshot = index(c);
        if (!account.equals(snapshot.optString("account"))) snapshot = new JSONObject();
        JSONArray rootIds = new JSONArray();
        for (Folder root : roots) rootIds.put(root.id());
        String signature = rootIds.toString();
        JSONObject cachedFolders = snapshot.optJSONObject("folders");
        boolean reusable =
                additionsOnly
                        && signature.equals(snapshot.optString("roots"))
                        && cachedFolders != null;
        Set<String> dirty = new HashSet<>();
        String cursor = additionsOnly ? "" : snapshot.optString("cursor");
        if (additionsOnly) {
            String saved = reusable ? snapshot.optString("cursor") : "";
            if (!saved.isEmpty()) {
                try {
                    DriveApi.Changes changes = api.changes(saved);
                    cursor = changes.token();
                    Map<String, Set<String>> parents = new HashMap<>();
                    for (java.util.Iterator<String> keys = cachedFolders.keys(); keys.hasNext(); ) {
                        String folder = keys.next();
                        for (DriveApi.Item item :
                                DriveApi.items(cachedFolders.getJSONArray(folder)))
                            parents.computeIfAbsent(item.id(), k -> new HashSet<>()).add(folder);
                    }
                    for (JSONObject change : changes.files()) {
                        String id = change.optString("fileId");
                        if (id.isEmpty() || change.optString("changeType").equals("drive")) {
                            for (java.util.Iterator<String> ids = cachedFolders.keys();
                                    ids.hasNext(); ) dirty.add(ids.next());
                        }
                        dirty.addAll(parents.getOrDefault(id, Set.of()));
                        if (cachedFolders.has(id)) dirty.add(id);
                        JSONObject file = change.optJSONObject("file");
                        if (file != null) {
                            JSONArray ps = file.optJSONArray("parents");
                            if (ps != null)
                                for (int i = 0; i < ps.length(); i++) dirty.add(ps.optString(i));
                        }
                    }
                    dirty.removeIf(id -> !cachedFolders.has(id));
                    if (dirty.isEmpty() && snapshot.optInt("folderArtVersion") >= 2) {
                        if (!cursor.equals(saved)) {
                            synchronized (LOCK) {
                                if (auth.generation() != generation || !roots.equals(roots(c)))
                                    throw new IOException("Drive 연결 또는 폴더 설정이 변경되었습니다.");
                                JSONObject current = index(c);
                                current.put("cursor", cursor);
                                write(c, current, false);
                            }
                        }
                        progress.accept(new Library.Progress("새 Drive 음악이 없습니다", 0, 0));
                        return load(c);
                    }
                } catch (DriveHttp.StatusException error) {
                    if (error.code != 410 && error.code != 400) throw error;
                    reusable = false;
                    cursor = api.startToken();
                }
            } else {
                reusable = false;
                cursor = api.startToken();
            }
        }
        JSONObject nextFolders =
                scope != null && cachedFolders != null
                        ? new JSONObject(cachedFolders.toString())
                        : new JSONObject();
        if (scope != null && cachedFolders != null) {
            ArrayDeque<String> removed = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            removed.add(scope.id());
            while (!removed.isEmpty()) {
                String id = removed.removeFirst();
                if (!seen.add(id)) continue;
                JSONArray list = cachedFolders.optJSONArray(id);
                if (list != null)
                    for (var item : DriveApi.items(list)) if (item.folder()) removed.add(item.id());
                nextFolders.remove(id);
            }
        }
        Map<String, JSONObject> old = new HashMap<>(), oldById = new HashMap<>();
        JSONArray previous = snapshot.optJSONArray("tracks");
        if (previous != null)
            for (int i = 0; i < previous.length(); i++) {
                JSONObject item = previous.getJSONObject(i);
                old.put(item.optString("uri"), item);
                try {
                    oldById.put(DriveUris.fileId(Uri.parse(item.optString("uri"))), item);
                } catch (Exception ignored) {
                }
            }
        Map<String, List<DriveApi.Item>> covers = new HashMap<>();
        DriveFolderArtwork folderArtwork = new DriveFolderArtwork(c, api.http(), generation);
        LinkedHashMap<String, JSONObject> found = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<java.util.Map.Entry<String, List<FolderTree.Part>>> folders = new ArrayDeque<>();
        if (scope != null) folders.add(Map.entry(scope.id(), scope.path()));
        else
            for (Folder root : roots)
                folders.add(
                        Map.entry(
                                root.id(),
                                List.of(
                                        new FolderTree.Part(
                                                account + ":" + root.id(), root.name()))));
        while (!folders.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (auth.generation() != generation)
                throw new IOException("Google Drive 연결이 변경되어 검색을 중단했습니다.");
            var pending = folders.removeFirst();
            String[] folder = {
                pending.getKey(),
                pending.getValue().stream()
                        .map(FolderTree.Part::name)
                        .collect(java.util.stream.Collectors.joining("/"))
            };
            if (!visited.add(folder[0])) continue;
            List<DriveApi.Item> items =
                    reusable && !dirty.contains(folder[0]) && cachedFolders.has(folder[0])
                            ? DriveApi.items(cachedFolders.getJSONArray(folder[0]))
                            : api.children(folder[0]);
            nextFolders.put(folder[0], DriveApi.itemsJson(items));
            covers.put(folder[0], DriveFolderArtwork.candidates(items));
            Map<String, String> lyrics = new HashMap<>();
            for (var f : items)
                if (f.lrc() && f.downloadable())
                    lyrics.put(
                            stem(f.name()).toLowerCase(Locale.ROOT),
                            DriveUris.file(account, f.id(), f.name()).toString());
            for (var f : items) {
                if (f.folder()) {
                    var child = new ArrayList<>(pending.getValue());
                    child.add(new FolderTree.Part(account + ":" + f.id(), f.name()));
                    folders.add(Map.entry(f.id(), List.copyOf(child)));
                    continue;
                }
                if (!f.audio() || !f.downloadable() || found.containsKey(f.id())) continue;
                String uri = DriveUris.file(account, f.id(), f.name()).toString();
                Track t =
                        new Track(
                                uri,
                                stem(f.name()),
                                "알 수 없는 아티스트",
                                folder[1],
                                folder[1],
                                0,
                                lyrics.getOrDefault(stem(f.name()).toLowerCase(Locale.ROOT), ""),
                                "",
                                folder[0],
                                f.mime());
                JSONObject hit = old.get(uri);
                if (additionsOnly && hit == null) hit = oldById.get(f.id());
                if (additionsOnly && hit != null) {
                    JSONObject kept = new JSONObject(hit.toString());
                    kept.put("folder", folder[1])
                            .put("folderId", folder[0])
                            .put(
                                    "path",
                                    t.withPath(pending.getValue()).json().getJSONArray("path"));
                    found.put(f.id(), kept);
                    continue;
                }
                boolean enriched = false;
                if (scope == null
                        && hit != null
                        && f.size() >= 0
                        && !f.modified().isEmpty()
                        && hit.optLong("size", -2) == f.size()
                        && f.modified().equals(hit.optString("modified"))) {
                    Track known = Track.from(hit);
                    t =
                            new Track(
                                    uri,
                                    known.title,
                                    known.artist,
                                    known.album,
                                    folder[1],
                                    known.duration,
                                    t.lrcUri,
                                    "",
                                    folder[0],
                                    f.mime());
                    t =
                            t.withGenre(known.genre)
                                    .withNumbers(known.trackNumber, known.discNumber)
                                    .withAudioFormat(known.sampleRate, known.bitsPerSample);
                    enriched =
                            hit.optBoolean("enriched")
                                    && hit.has("trackNumber")
                                    && hit.has("genre");
                }
                t = t.withPath(pending.getValue());
                boolean hasArt =
                        enriched
                                && (hit.optBoolean("art")
                                        || !hit.has("artKind") && Library.artFile(c, uri).isFile());
                String artKind = "none";
                String artKey = "";
                if (enriched) {
                    boolean legacyEmbedded =
                            hit.optBoolean("art") || Library.artFile(c, uri).isFile();
                    artKind = hit.optString("artKind", legacyEmbedded ? "embedded" : "none");
                    artKey = hit.optString("artKey");
                }
                JSONObject row =
                        t.json()
                                .put("size", f.size())
                                .put("modified", f.modified())
                                .put("enriched", enriched)
                                .put("formatChecked", enriched && hit.optBoolean("formatChecked"))
                                .put("art", hasArt)
                                .put("artKind", artKind)
                                .put("artKey", artKey);
                found.put(f.id(), row);
            }
            progress.accept(new Library.Progress("Google Drive 폴더 확인 중", found.size(), -1));
        }
        List<JSONObject> rows = new ArrayList<>(found.values());
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(readers);
        java.util.concurrent.CompletionService<java.util.Map.Entry<Integer, JSONObject>> ready =
                new java.util.concurrent.ExecutorCompletionService<>(pool);
        int next = 0, active = 0, completed = 0, workers = readers;
        progress.accept(new Library.Progress("Google Drive 음악 정보 준비 중", 0, rows.size()));
        try {
            while (next < rows.size() || active > 0) {
                while (next < rows.size() && active < workers) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    JSONObject row = rows.get(next);
                    int at = next++;
                    if (additionsOnly && old.containsKey(row.optString("uri"))) {
                        applyFolderCover(folderArtwork, row, covers);
                        completed++;
                        if ((completed & 127) == 0)
                            progress.accept(
                                    new Library.Progress(
                                            "기존 Drive 음악 유지 중", completed, rows.size()));
                        continue;
                    }
                    if (!needsMetadata(c, row)) {
                        applyFolderCover(folderArtwork, row, covers);
                        progress.accept(
                                new Library.Progress(
                                        "저장한 Drive 정보 확인 중", ++completed, rows.size()));
                        continue;
                    }
                    ready.submit(
                            () -> {
                                try {
                                    return Map.entry(
                                            at,
                                            metadata.read(c, row, generation)
                                                    .put("formatChecked", true));
                                } catch (java.io.InterruptedIOException
                                        | InterruptedException cancelled) {
                                    throw cancelled;
                                } catch (Exception unsupported) {
                                    return Map.entry(at, row);
                                }
                            });
                    active++;
                }
                if (active == 0) continue;
                var result = ready.take().get();
                active--;
                if (auth.generation() != generation)
                    throw new IOException("Google Drive 연결이 변경되었습니다.");
                rows.set(result.getKey(), result.getValue());
                applyFolderCover(folderArtwork, result.getValue(), covers);
                progress.accept(
                        new Library.Progress("Google Drive 음악 정보 읽는 중", ++completed, rows.size()));
            }
        } finally {
            pool.shutdownNow();
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        JSONArray a = new JSONArray();
        for (JSONObject row : rows) a.put(row);
        synchronized (LOCK) {
            if (scope != null) {
                Set<String> refreshed = new HashSet<>();
                for (JSONObject row : rows) refreshed.add(row.optString("uri"));
                JSONArray merged = new JSONArray(), live = index(c).optJSONArray("tracks");
                if (live != null)
                    for (int i = 0; i < live.length(); i++) {
                        JSONObject row = live.getJSONObject(i);
                        if (!scope.contains(Track.from(row))
                                && !refreshed.contains(row.optString("uri"))) merged.put(row);
                    }
                for (JSONObject row : rows) merged.put(row);
                a = merged;
            }
            if (auth.generation() != generation
                    || !account.equals(auth.accountId())
                    || !roots.equals(roots(c)))
                throw new IOException("Google Drive 연결 또는 폴더 설정이 변경되었습니다.");
            if (additionsOnly) {
                JSONArray latest = index(c).optJSONArray("tracks");
                Map<String, JSONObject> current = new HashMap<>();
                if (latest != null)
                    for (int i = 0; i < latest.length(); i++) {
                        JSONObject row = latest.getJSONObject(i);
                        current.put(row.optString("uri"), row);
                    }
                for (int i = 0; i < a.length(); i++) {
                    JSONObject row = a.getJSONObject(i), live = current.get(row.optString("uri"));
                    if (old.containsKey(row.optString("uri")) && live != null) {
                        JSONObject merged = new JSONObject(live.toString());
                        if (live.optString("artKey")
                                        .equals(old.get(row.optString("uri")).optString("artKey"))
                                && live.optString("artKind")
                                        .equals(
                                                old.get(row.optString("uri"))
                                                        .optString("artKind"))) {
                            merged.put("art", row.optBoolean("art"))
                                    .put("artKind", row.optString("artKind"))
                                    .put("artKey", row.optString("artKey"));
                        }
                        merged.put("folder", row.optString("folder"))
                                .put("folderId", row.optString("folderId"))
                                .put("path", row.getJSONArray("path"));
                        a.put(i, merged);
                    }
                }
            }
            JSONObject updated =
                    new JSONObject()
                            .put(
                                    "folderArtVersion",
                                    scope == null ? 2 : snapshot.optInt("folderArtVersion"))
                            .put("account", account)
                            .put("tracks", a)
                            .put("folders", nextFolders)
                            .put("roots", signature)
                            .put("cursor", cursor);
            boolean changed =
                    folderArtwork.changed()
                            || !a.toString().equals(previous == null ? "" : previous.toString());
            write(c, updated, changed);
        }
        progress.accept(new Library.Progress("Google Drive 검색 완료", found.size(), found.size()));
        return load(c);
    }

    private static boolean needsMetadata(Context c, JSONObject row) {
        ArtworkStore.restoreDriveCover(c, row.optString("uri"));
        return !row.optBoolean("enriched")
                || !row.optBoolean("formatChecked")
                        && TrackFormat.label(
                                        row.optString("uri"),
                                        row.optString("kind"),
                                        row.optString("mime"))
                                .equals("FLAC")
                || row.optBoolean("art")
                        && !row.optString("artKind").equals("folder")
                        && !Library.artFile(c, row.optString("uri")).isFile();
    }

    private static void applyFolderCover(
            DriveFolderArtwork artwork, JSONObject row, Map<String, List<DriveApi.Item>> covers)
            throws Exception {
        try {
            artwork.apply(row, covers.getOrDefault(row.optString("folderId"), List.of()));
        } catch (java.io.InterruptedIOException cancelled) {
            throw cancelled;
        } catch (Exception optionalCoverUnavailable) {
            /* Optional artwork must not discard music tags. */
        }
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static Track enrich(Context c, Track track) throws Exception {
        DriveAuth auth = DriveAuth.get(c);
        long generation = auth.generation();
        JSONObject snapshot = index(c);
        JSONArray items = snapshot.optJSONArray("tracks");
        JSONObject entry = null;
        if (items != null)
            for (int i = 0; i < items.length(); i++)
                if (track.uri.equals(items.getJSONObject(i).optString("uri"))) {
                    entry = items.getJSONObject(i);
                    break;
                }
        if (entry == null) return track;
        track = Track.from(entry);
        if (entry.optBoolean("enriched")
                && (!entry.optBoolean("art") || Library.artFile(c, track.uri).isFile()))
            return track;
        JSONObject enriched = readMetadata(c, entry, generation);
        Track result = Track.from(enriched);
        boolean art = enriched.optBoolean("art");
        synchronized (LOCK) {
            if (generation != auth.generation()) return track;
            JSONObject current = index(c);
            JSONArray a = current.optJSONArray("tracks");
            if (a != null)
                for (int i = 0; i < a.length(); i++) {
                    JSONObject item = a.getJSONObject(i);
                    if (track.uri.equals(item.optString("uri"))
                            && entry.optString("modified").equals(item.optString("modified"))) {
                        a.put(i, enriched);
                        write(c, current);
                        break;
                    }
                }
        }
        return result;
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private static JSONObject readMetadata(Context c, JSONObject entry, long generation)
            throws Exception {
        return readMetadata(c, entry, generation, new DriveHttp(DriveAuth.get(c)));
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static JSONObject readMetadata(Context c, JSONObject entry, long generation, DriveHttp http)
            throws Exception {
        DriveAuth auth = DriveAuth.get(c);
        Track track = Track.from(entry);
        Track result = track;
        boolean art = false;
        try (DriveMetadata data =
                new DriveMetadata(http, Uri.parse(track.uri), entry.optLong("size", -1))) {
            JSONObject fast = readFlacMetadata(c, entry, track, generation, data);
            if (fast != null) return fast;
            android.media.MediaMetadataRetriever retriever =
                    new android.media.MediaMetadataRetriever();
            try {
                track = TrackFormat.readFlac(track, data);
                retriever.setDataSource(data);
                String
                        title =
                                value(
                                        retriever,
                                        android.media.MediaMetadataRetriever.METADATA_KEY_TITLE,
                                        track.title),
                        artist =
                                value(
                                        retriever,
                                        android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST,
                                        track.artist),
                        album =
                                value(
                                        retriever,
                                        android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM,
                                        track.album);
                long duration =
                        Long.parseLong(
                                value(
                                        retriever,
                                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                                        "0"));
                result =
                        new Track(
                                        track.uri,
                                        title,
                                        artist,
                                        album,
                                        track.folder,
                                        duration,
                                        track.lrcUri,
                                        "",
                                        track.folderId,
                                        track.mime)
                                .withGenre(
                                        value(
                                                retriever,
                                                android.media.MediaMetadataRetriever
                                                        .METADATA_KEY_GENRE,
                                                ""))
                                .withNumbers(
                                        TrackOrder.number(
                                                value(
                                                        retriever,
                                                        android.media.MediaMetadataRetriever
                                                                .METADATA_KEY_CD_TRACK_NUMBER,
                                                        "0")),
                                        TrackOrder.number(
                                                value(
                                                        retriever,
                                                        android.media.MediaMetadataRetriever
                                                                .METADATA_KEY_DISC_NUMBER,
                                                        "0")))
                                .withPath(track.folderPath)
                                .withAudioFormat(track.sampleRate, track.bitsPerSample);
                byte[] picture = retriever.getEmbeddedPicture();
                if (picture == null && !entry.optString("artKind").equals("folder"))
                    synchronized (LOCK) {
                        if (generation == auth.generation()) Library.artFile(c, track.uri).delete();
                    }
                if (picture != null && picture.length <= ArtworkStore.MAX_EMBEDDED_BYTES) {
                    var bitmap = Library.thumbnail(picture);
                    if (bitmap != null) {
                        synchronized (LOCK) {
                            if (generation == auth.generation()) {
                                ArtworkStore.save(c, track.uri, bitmap);
                                art = true;
                            }
                        }
                        bitmap.recycle();
                    }
                }
            } finally {
                retriever.release();
            }
        }
        boolean folderArt =
                !art
                        && entry.optString("artKind").equals("folder")
                        && Library.artFile(c, track.uri).isFile();
        if (!art && !folderArt)
            synchronized (LOCK) {
                if (generation == auth.generation()) Library.artFile(c, track.uri).delete();
            }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (generation != auth.generation()) throw new IOException("Google Drive 연결이 변경되었습니다.");
        return result.json()
                .put("size", entry.optLong("size", -1))
                .put("modified", entry.optString("modified"))
                .put("enriched", true)
                .put("formatChecked", true)
                .put("art", art || folderArt)
                .put("artKind", art ? "embedded" : folderArt ? "folder" : "none")
                .put("artKey", folderArt ? entry.optString("artKey") : "");
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private static JSONObject readFlacMetadata(
            Context c, JSONObject entry, Track track, long generation, DriveMetadata data)
            throws Exception {
        if (!TrackFormat.label(track.uri, track.kind, track.mime).equals("FLAC")) return null;
        FlacMetadata.Info info;
        try {
            info = FlacMetadata.read(data);
        } catch (java.io.InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException unsupported) {
            return null;
        }
        if (info == null || info.duration() <= 0) return null;
        DriveAuth auth = DriveAuth.get(c);
        Track result =
                new Track(
                                track.uri,
                                info.tag("TITLE", track.title),
                                info.tag("ARTIST", track.artist),
                                info.tag("ALBUM", track.album),
                                track.folder,
                                info.duration(),
                                track.lrcUri,
                                "flac",
                                track.folderId,
                                track.mime)
                        .withGenre(info.tag("GENRE", track.genre))
                        .withNumbers(
                                TrackOrder.number(info.tag("TRACKNUMBER", "")),
                                TrackOrder.number(info.tag("DISCNUMBER", "")))
                        .withPath(track.folderPath)
                        .withAudioFormat(info.sampleRate(), info.bitsPerSample());
        boolean art = false;
        if (info.picture() != null) {
            var bitmap = ArtworkStore.thumbnail(info.picture());
            if (bitmap != null)
                try {
                    synchronized (LOCK) {
                        if (generation != auth.generation())
                            throw new IOException("Google Drive 연결이 변경되었습니다.");
                        ArtworkStore.save(c, track.uri, bitmap);
                        art = true;
                    }
                } finally {
                    bitmap.recycle();
                }
        }
        synchronized (LOCK) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            if (generation != auth.generation()) throw new IOException("Google Drive 연결이 변경되었습니다.");
            boolean folder =
                    !art
                            && entry.optString("artKind").equals("folder")
                            && Library.artFile(c, track.uri).isFile();
            if (!art && !folder && info.pictureSkipped())
                art = Library.artFile(c, track.uri).isFile();
            if (!art && !folder) Library.artFile(c, track.uri).delete();
            return result.json()
                    .put("size", entry.optLong("size", -1))
                    .put("modified", entry.optString("modified"))
                    .put("enriched", true)
                    .put("formatChecked", true)
                    .put("art", art || folder)
                    .put("artKind", art ? "embedded" : folder ? "folder" : "none")
                    .put("artKey", folder ? entry.optString("artKey") : "");
        }
    }

    static void cacheFolderArtwork(Context c, Track track) throws Exception {
        cacheFolderArtwork(c, track, new DriveHttp(DriveAuth.get(c)));
    }

    static void cacheFolderArtwork(Context c, Track track, DriveHttp http) throws Exception {
        DriveAuth auth = DriveAuth.get(c);
        long generation = auth.generation();
        if (!auth.accountId().equals(Uri.parse(track.uri).getAuthority())) return;
        JSONObject snapshot = index(c);
        JSONArray rows = snapshot.optJSONArray("tracks");
        JSONObject folders = snapshot.optJSONObject("folders");
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (track.uri.equals(row.optString("uri"))) {
                if (row.optBoolean("art") && !row.optString("artKind").equals("folder")) {
                    refreshArtwork(c, track, http);
                    return;
                }
                String folder = row.optString("folderId");
                if (folder.isEmpty()) return;
                JSONArray items = folders == null ? null : folders.optJSONArray(folder);
                var children =
                        items == null ? new DriveApi(http).children(folder) : DriveApi.items(items);
                new DriveFolderArtwork(c, http, generation)
                        .apply(row, DriveFolderArtwork.candidates(children));
                return;
            }
        }
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static void refreshArtwork(Context c, Track track) throws Exception {
        refreshArtwork(c, track, new DriveHttp(DriveAuth.get(c)));
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static void refreshArtwork(Context c, Track track, DriveHttp http) throws Exception {
        DriveAuth auth = DriveAuth.get(c);
        long generation = auth.generation();
        if (!auth.accountId().equals(Uri.parse(track.uri).getAuthority())) return;
        JSONObject snapshot = index(c);
        JSONArray rows = snapshot.optJSONArray("tracks");
        JSONObject row = null;
        if (rows != null)
            for (int i = 0; i < rows.length(); i++)
                if (track.uri.equals(rows.getJSONObject(i).optString("uri"))) {
                    row = new JSONObject(rows.getJSONObject(i).toString());
                    break;
                }
        if (row == null) return;
        if (row.optString("artKind").equals("folder")) {
            JSONObject folders = snapshot.optJSONObject("folders");
            JSONArray items =
                    folders == null ? null : folders.optJSONArray(row.optString("folderId"));
            if (items == null) return;
            new DriveFolderArtwork(c, http, generation, true)
                    .apply(row, DriveFolderArtwork.candidates(DriveApi.items(items)));
            if (!ArtworkStore.current(c, track.uri)) return;
        } else {
            try (DriveMetadata data =
                    new DriveMetadata(http, Uri.parse(track.uri), row.optLong("size", -1))) {
                android.media.MediaMetadataRetriever retriever =
                        new android.media.MediaMetadataRetriever();
                try {
                    retriever.setDataSource(data);
                    byte[] picture = retriever.getEmbeddedPicture();
                    if (picture == null || picture.length > ArtworkStore.MAX_EMBEDDED_BYTES) return;
                    var bitmap = Library.thumbnail(picture);
                    if (bitmap == null) return;
                    try {
                        synchronized (LOCK) {
                            if (generation != auth.generation()) return;
                            ArtworkStore.save(c, track.uri, bitmap);
                        }
                    } finally {
                        bitmap.recycle();
                    }
                } finally {
                    retriever.release();
                }
            }
            row.put("art", true).put("artKind", "embedded");
        }
        synchronized (LOCK) {
            if (generation != auth.generation()) return;
            JSONObject live = index(c);
            JSONArray current = live.optJSONArray("tracks");
            if (current == null) return;
            for (int i = 0; i < current.length(); i++) {
                JSONObject item = current.getJSONObject(i);
                if (track.uri.equals(item.optString("uri"))
                        && row.optString("modified").equals(item.optString("modified"))) {
                    item.put("art", true)
                            .put("artKind", row.optString("artKind"))
                            .put("artKey", row.optString("artKey"));
                    write(c, live);
                    break;
                }
            }
        }
    }

    private static String value(android.media.MediaMetadataRetriever r, int key, String fallback) {
        String s = r.extractMetadata(key);
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

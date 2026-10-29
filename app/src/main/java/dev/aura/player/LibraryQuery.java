package dev.aura.player;

import java.util.*;

/** Immutable input snapshot; filtering and grouping happen off the UI thread. */
final class LibraryQuery {
    final List<Track> tracks;
    final Map<String, List<Track>> groups;
    final FolderTree folders;

    private LibraryQuery(List<Track> tracks, Map<String, List<Track>> groups, FolderTree folders) {
        this.tracks = tracks;
        this.groups = groups;
        this.folders = folders;
    }

    static LibraryQuery run(List<Track> source, String storage, String needle, String tab)
            throws InterruptedException {
        return run(source, storage, needle, tab, "track", false);
    }

    static LibraryQuery run(
            List<Track> source,
            String storage,
            String needle,
            String tab,
            String order,
            boolean descending)
            throws InterruptedException {
        String q = needle.trim().toLowerCase(Locale.ROOT);
        List<Track> found = new ArrayList<>();
        Map<String, List<Track>> groups =
                tab.equals("아티스트") ? new TreeMap<>() : new LinkedHashMap<>();
        for (Track t : source) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!(storage.equals("all") || storage.equals("drive") == t.drive())
                    || !t.searchKey.contains(q)) continue;
            found.add(t);
            if (tab.equals("앨범") || tab.equals("아티스트")) {
                String key = tab.equals("앨범") ? t.album + "\u0000" + t.artist : t.artist;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
        }
        found = TrackOrder.sorted(found, order, descending);
        for (var entry : groups.entrySet())
            entry.setValue(TrackOrder.sorted(entry.getValue(), order, descending));
        return new LibraryQuery(found, groups, tab.equals("폴더") ? new FolderTree(found) : null);
    }
}

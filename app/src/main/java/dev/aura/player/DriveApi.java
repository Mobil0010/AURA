package dev.aura.player;

import org.json.*;
import java.io.*;
import java.util.*;

final class DriveApi {
    static final String FOLDER = "application/vnd.google-apps.folder";

    record Item(
            String id, String name, String mime, long size, String modified, boolean downloadable) {
        boolean folder() {
            return FOLDER.equals(mime);
        }

        boolean audio() {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.endsWith(".dsf") || n.endsWith(".dff")) return false;
            return !folder()
                    && (mime.startsWith("audio/")
                            || n.matches(".*\\.(mp3|flac|m4a|aac|wav|ogg|opus|aif|aiff|alac)$"));
        }

        boolean lrc() {
            return name.toLowerCase(Locale.ROOT).endsWith(".lrc");
        }
    }

    private final DriveHttp http;

    DriveApi(DriveHttp http) {
        this.http = http;
    }

    DriveHttp http() {
        return http;
    }

    JSONObject account() throws IOException {
        return http.json("about?fields=user(displayName,emailAddress,permissionId)")
                .optJSONObject("user");
    }

    String startToken() throws IOException {
        String token =
                http.json("changes/startPageToken?supportsAllDrives=true")
                        .optString("startPageToken");
        if (token.isEmpty()) throw new IOException("Drive 변경 확인 기준을 받지 못했습니다.");
        return token;
    }

    record Changes(List<JSONObject> files, String token) {}

    Changes changes(String token) throws IOException {
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String page = token;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (!seen.add(page)) throw new IOException("Drive 변경 목록 응답이 반복되었습니다.");
            JSONObject row =
                    http.json(
                            "changes?pageToken="
                                    + DriveHttp.enc(page)
                                    + "&pageSize=1000&spaces=drive&includeRemoved=true&includeItemsFromAllDrives=true&supportsAllDrives=true&fields="
                                    + DriveHttp.enc(
                                            "nextPageToken,newStartPageToken,changes(changeType,driveId,fileId,removed,file(id,name,mimeType,parents,trashed))"));
            JSONArray changes = row.optJSONArray("changes");
            if (changes == null) throw new IOException("Drive 변경 목록이 올바르지 않습니다.");
            for (int i = 0; i < changes.length(); i++) {
                JSONObject file = changes.optJSONObject(i);
                if (file != null) out.add(file);
            }
            String next = row.optString("nextPageToken");
            if (!next.isEmpty()) {
                page = next;
                continue;
            }
            String end = row.optString("newStartPageToken");
            if (end.isEmpty()) throw new IOException("Drive 변경 확인 기준이 올바르지 않습니다.");
            return new Changes(out, end);
        }
    }

    static JSONArray itemsJson(List<Item> items) throws JSONException {
        JSONArray array = new JSONArray();
        for (Item item : items)
            array.put(
                    new JSONObject()
                            .put("id", item.id())
                            .put("name", item.name())
                            .put("mime", item.mime())
                            .put("size", item.size())
                            .put("modified", item.modified())
                            .put("downloadable", item.downloadable()));
        return array;
    }

    static List<Item> items(JSONArray array) throws JSONException {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            out.add(
                    new Item(
                            item.getString("id"),
                            item.getString("name"),
                            item.getString("mime"),
                            item.getLong("size"),
                            item.getString("modified"),
                            item.getBoolean("downloadable")));
        }
        return out;
    }

    List<Item> children(String folder) throws IOException {
        DriveUris.id(folder);
        List<Item> out = new ArrayList<>();
        Set<String> pages = new HashSet<>();
        String page = "";
        do {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            String query = "'" + folder + "' in parents and trashed = false";
            String path =
                    "files?q="
                            + DriveHttp.enc(query)
                            + "&pageSize=1000&orderBy=folder,name&supportsAllDrives=true&includeItemsFromAllDrives=true&fields="
                            + DriveHttp.enc(
                                    "nextPageToken,incompleteSearch,files(id,name,mimeType,size,modifiedTime,capabilities(canDownload))")
                            + (page.isEmpty() ? "" : "&pageToken=" + DriveHttp.enc(page));
            JSONObject result = http.json(path);
            if (result.optBoolean("incompleteSearch"))
                throw new IOException("Google Drive 폴더를 모두 검색하지 못했습니다. 다시 시도해 주세요.");
            JSONArray files = result.optJSONArray("files");
            if (files == null) throw new IOException("Google Drive 파일 목록이 올바르지 않습니다.");
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                String id = f.optString("id");
                DriveUris.id(id);
                String mime = f.optString("mimeType");
                if (mime.equals("application/vnd.google-apps.shortcut")) continue;
                JSONObject capabilities = f.optJSONObject("capabilities");
                out.add(
                        new Item(
                                id,
                                f.optString("name", id),
                                mime,
                                f.optLong("size", -1),
                                f.optString("modifiedTime"),
                                capabilities == null
                                        || capabilities.optBoolean("canDownload", true)));
            }
            page = result.optString("nextPageToken", "");
            if (!page.isEmpty() && !pages.add(page))
                throw new IOException("Google Drive 목록 응답이 반복되었습니다.");
        } while (!page.isEmpty());
        return out;
    }
}

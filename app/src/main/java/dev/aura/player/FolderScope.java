package dev.aura.player;

import java.util.*;
import org.json.*;

record FolderScope(boolean drive, String root, String id, List<FolderTree.Part> path) {
    FolderScope {
        path = List.copyOf(path);
        if (root.isEmpty() || id.isEmpty() || path.isEmpty())
            throw new IllegalArgumentException("폴더 경로 정보가 없습니다.");
    }

    boolean contains(Track t) {
        if (t.drive() != drive || t.folderPath.size() < path.size()) return false;
        for (int i = 0; i < path.size(); i++)
            if (!path.get(i).id().equals(t.folderPath.get(i).id())) return false;
        return true;
    }

    String json() throws Exception {
        JSONArray p = new JSONArray();
        for (var part : path) p.put(new JSONObject().put("id", part.id()).put("name", part.name()));
        return new JSONObject()
                .put("drive", drive)
                .put("root", root)
                .put("id", id)
                .put("path", p)
                .toString();
    }

    static FolderScope from(String text) throws Exception {
        JSONObject j = new JSONObject(text);
        List<FolderTree.Part> p = new ArrayList<>();
        var a = j.getJSONArray("path");
        for (int i = 0; i < a.length(); i++) {
            var v = a.getJSONObject(i);
            p.add(new FolderTree.Part(v.getString("id"), v.getString("name")));
        }
        return new FolderScope(j.getBoolean("drive"), j.getString("root"), j.getString("id"), p);
    }
}

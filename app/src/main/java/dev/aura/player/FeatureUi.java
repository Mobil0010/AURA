package dev.aura.player;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

@androidx.media3.common.util.UnstableApi
final class FeatureUi {
    static final ExecutorService IO =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "AURA-features");
                        t.setPriority(Thread.MIN_PRIORITY);
                        return t;
                    });
    final MainActivity a;
    final Handler main = new Handler(Looper.getMainLooper());

    FeatureUi(MainActivity a) {
        this.a = a;
    }

    LinearLayout column() {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    TextView text(String s, int size) {
        TextView v = Ui.text(a, s, size, Ui.INK);
        v.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        return v;
    }

    void header(LinearLayout host, String title) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.IconButton back = new Ui.IconButton(a, "back");
        back.setContentDescription("설정으로 돌아가기");
        Ui.tap(back, () -> a.musicGo("settings"));
        row.addView(back, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        TextView name = text(title, 23);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        host.addView(row);
    }

    void note(LinearLayout host, String s) {
        TextView v = text(s, 12);
        v.setTextColor(Ui.MUTED);
        host.addView(v);
    }

    void button(LinearLayout host, String title, Runnable action) {
        host.addView(Ui.button(a, title, action, false));
    }

    void toast(String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    void async(LinearLayout host, Callable<Runnable> job) {
        IO.execute(
                () -> {
                    try {
                        Runnable done = job.call();
                        main.post(
                                () -> {
                                    if (!a.isDestroyed() && host.isAttachedToWindow()) done.run();
                                });
                    } catch (Exception e) {
                        main.post(
                                () -> {
                                    if (!a.isDestroyed() && host.isAttachedToWindow())
                                        note(
                                                host,
                                                e.getMessage() == null
                                                        ? "작업을 완료하지 못했습니다."
                                                        : e.getMessage());
                                });
                    }
                });
    }

    void home(LinearLayout host) {
        LinearLayout section = column();
        host.addView(section);
        List<Track> tracks = List.copyOf(a.musicTracks());
        async(
                section,
                () -> {
                    List<MusicCollections.Mix> mixes;
                    try (MusicCollections store = new MusicCollections(a)) {
                        mixes = store.mixes(a, tracks);
                    }
                    return () -> {
                        for (var mix : mixes) {
                            if (mix.songs().isEmpty()) continue;
                            LinearLayout header = new LinearLayout(a);
                            header.setGravity(Gravity.CENTER_VERTICAL);
                            TextView title = text(mix.title(), 18);
                            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                            TextView all = text("모두 보기 ›", 12);
                            header.addView(all);
                            Ui.tap(header, () -> a.musicOpen(mix.title(), mix.songs()));
                            section.addView(header);
                            HorizontalScrollView rail = new HorizontalScrollView(a);
                            rail.setHorizontalScrollBarEnabled(false);
                            LinearLayout cards = new LinearLayout(a);
                            for (Track t : MusicCollections.limit(mix.songs(), 8)) {
                                LinearLayout card = column();
                                card.setPadding(0, Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 10));
                                card.addView(
                                        new ArtworkView(a, t, 104),
                                        new LinearLayout.LayoutParams(
                                                Ui.dp(a, 104), Ui.dp(a, 104)));
                                TextView name = text(t.title, 13);
                                Ui.lines(name, 2);
                                card.addView(name);
                                TextView artist = text(t.artist, 11);
                                artist.setTextColor(Ui.MUTED);
                                Ui.lines(artist, 1);
                                card.addView(artist);
                                Ui.tap(card, () -> a.musicPlay(t, mix.songs()));
                                cards.addView(
                                        card, new LinearLayout.LayoutParams(Ui.dp(a, 120), -2));
                            }
                            rail.addView(cards);
                            section.addView(rail);
                        }
                        button(section, "나만의 모음 만들기", this::rule);
                    };
                });
    }

    void rule() {
        LinearLayout form = column();
        form.setPadding(32, 8, 32, 8);
        EditText name = new EditText(a), genre = new EditText(a);
        name.setHint("홈에 표시할 이름");
        genre.setHint("장르 · 비우면 모든 장르");
        form.addView(name);
        form.addView(genre);
        CheckBox fav = new CheckBox(a), unheard = new CheckBox(a);
        fav.setText("즐겨찾는 곡만");
        unheard.setText("아직 듣지 않은 곡만");
        form.addView(fav);
        form.addView(unheard);
        new AlertDialog.Builder(a)
                .setTitle("나만의 음악 모음")
                .setView(form)
                .setPositiveButton(
                        "만들기",
                        (d, w) -> {
                            if (name.getText().toString().trim().isEmpty()) {
                                toast("이름을 입력해 주세요.");
                                return;
                            }
                            try {
                                JSONArray rules =
                                        new JSONArray(
                                                MusicCollections.prefs(a).getString("mixes", "[]"));
                                rules.put(
                                        new JSONObject()
                                                .put("name", name.getText().toString().trim())
                                                .put("genre", genre.getText().toString().trim())
                                                .put("favorite", fav.isChecked())
                                                .put("unheard", unheard.isChecked())
                                                .put("limit", 50));
                                MusicCollections.prefs(a)
                                        .edit()
                                        .putString("mixes", rules.toString())
                                        .apply();
                                a.musicRender();
                            } catch (Exception ignored) {
                                toast("모음을 저장하지 못했습니다.");
                            }
                        })
                .setNegativeButton("취소", null)
                .show();
    }

    void collections(LinearLayout host) {
        header(host, "홈 음악 모음");
        button(host, "새 모음 만들기", this::rule);
        note(host, "최근 추가한 곡과 청취 기록에 따른 모음은 홈에서 자동으로 표시됩니다.");
        try {
            JSONArray rules = new JSONArray(MusicCollections.prefs(a).getString("mixes", "[]"));
            for (int i = 0; i < rules.length(); i++) {
                int at = i;
                JSONObject rule = rules.getJSONObject(i);
                button(
                        host,
                        rule.optString("name") + " · 삭제",
                        () ->
                                new AlertDialog.Builder(a)
                                        .setMessage("이 모음을 홈에서 제거하시겠습니까?")
                                        .setPositiveButton(
                                                "제거",
                                                (d, w) -> {
                                                    rules.remove(at);
                                                    MusicCollections.prefs(a)
                                                            .edit()
                                                            .putString("mixes", rules.toString())
                                                            .apply();
                                                    a.musicRender();
                                                })
                                        .setNegativeButton("취소", null)
                                        .show());
            }
        } catch (Exception ignored) {
        }
    }

    void action(Track track) {
        String favorite = MusicCollections.favorite(a, track.uri) ? "즐겨찾기 해제" : "즐겨찾기에 추가";
        String offline = "기기 음악 · 오프라인 사용 가능";
        if (track.drive()) {
            offline = OfflineMusic.available(a, track.uri) == null ? "오프라인 보관" : "오프라인 보관 제거";
        }
        String[] items = {favorite, offline, "현재 위치 북마크", "북마크와 구간 반복", "이 곡의 가사 싱크 · LRC 연결"};
        new AlertDialog.Builder(a)
                .setTitle(track.title)
                .setItems(items, (dialog, index) -> handleTrackAction(track, index))
                .setNegativeButton("닫기", null)
                .show();
    }

    private void handleTrackAction(Track track, int index) {
        switch (index) {
            case 0 -> {
                MusicCollections.favorite(a, track.uri, !MusicCollections.favorite(a, track.uri));
                a.musicRender();
            }
            case 1 -> chooseOfflineAction(track);
            case 2 -> bookmark(track);
            case 3 -> bookmarks(track);
            case 4 -> lyricOptions(track);
        }
    }

    private void chooseOfflineAction(Track track) {
        if (!track.drive()) {
            return;
        }
        if (OfflineMusic.available(a, track.uri) == null) {
            OfflineMusic.enqueue(a, List.of(track));
            return;
        }
        new AlertDialog.Builder(a)
                .setMessage("보관한 사본을 제거하시겠습니까? Drive 원본은 유지됩니다.")
                .setPositiveButton(
                        "제거", (dialog, which) -> IO.execute(() -> removeOfflineCopy(track)))
                .setNegativeButton("취소", null)
                .show();
    }

    private void removeOfflineCopy(Track track) {
        OfflineMusic.remove(a, track.uri);
        main.post(a::musicRender);
    }

    void bookmark(Track t) {
        if (a.musicCurrent() == null || !a.musicCurrent().uri.equals(t.uri)) {
            toast("현재 재생 중인 곡에서 위치를 저장해 주세요.");
            return;
        }
        EditText label = new EditText(a);
        label.setHint("북마크 이름");
        long position = a.musicPosition();
        new AlertDialog.Builder(a)
                .setTitle("현재 위치 저장 · " + MainActivity.format(position))
                .setView(label)
                .setPositiveButton(
                        "저장",
                        (d, w) -> {
                            try {
                                String k = "bookmarks:" + MusicCollections.key(t.uri);
                                JSONArray rows =
                                        new JSONArray(MusicCollections.prefs(a).getString(k, "[]"));
                                rows.put(
                                        new JSONObject()
                                                .put("position", position)
                                                .put(
                                                        "title",
                                                        label.getText().toString().isBlank()
                                                                ? MainActivity.format(position)
                                                                : label.getText().toString()));
                                MusicCollections.prefs(a)
                                        .edit()
                                        .putString(k, rows.toString())
                                        .apply();
                                toast("재생 위치를 저장했습니다.");
                            } catch (Exception ignored) {
                                toast("저장하지 못했습니다.");
                            }
                        })
                .setNegativeButton("취소", null)
                .show();
    }

    void bookmarks(Track t) {
        LinearLayout box = column();
        box.setPadding(28, 8, 28, 8);
        button(box, "현재 위치 북마크", () -> bookmark(t));
        PlaybackTools p = PlaybackTools.instance;
        if (p != null && !a.musicRemote()) {
            button(
                    box,
                    "현재 위치를 A로 설정",
                    () -> {
                        p.pointA();
                        toast("반복 시작 위치를 설정했습니다.");
                    });
            button(
                    box,
                    "현재 위치를 B로 설정",
                    () -> toast(p.pointB() ? "A–B 구간을 반복합니다." : "A보다 0.5초 이상 뒤의 위치를 선택해 주세요."));
            button(
                    box,
                    "구간 반복 해제",
                    () -> {
                        p.clearLoop();
                        toast("구간 반복을 해제했습니다.");
                    });
        }
        String k = "bookmarks:" + MusicCollections.key(t.uri);
        try {
            JSONArray rows = new JSONArray(MusicCollections.prefs(a).getString(k, "[]"));
            for (int i = 0; i < rows.length(); i++) {
                int at = i;
                JSONObject r = rows.getJSONObject(i);
                button(
                        box,
                        r.optString("title") + " · " + MainActivity.format(r.optLong("position")),
                        () -> {
                            if (a.musicCurrent() != null && a.musicCurrent().uri.equals(t.uri))
                                a.musicSeek(r.optLong("position"));
                            else a.musicPlayAt(t, r.optLong("position"));
                        });
                TextView remove = text("이 북마크 삭제", 11);
                Ui.tap(
                        remove,
                        () -> {
                            rows.remove(at);
                            MusicCollections.prefs(a).edit().putString(k, rows.toString()).apply();
                            toast("삭제했습니다. 창을 다시 열어 확인해 주세요.");
                        });
                box.addView(remove);
            }
        } catch (Exception ignored) {
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("북마크와 구간 반복")
                .setView(scroll)
                .setNegativeButton("닫기", null)
                .show();
    }

    void sleep() {
        PlaybackTools p = PlaybackTools.instance;
        if (p == null || a.musicRemote()) {
            toast("이 기기에서 음악을 재생한 뒤 설정해 주세요.");
            return;
        }
        String[] items = {"15분 뒤", "30분 뒤", "60분 뒤", "직접 입력", "현재 곡 끝에", "현재 앨범 끝에", "타이머 해제"};
        new AlertDialog.Builder(a)
                .setTitle("취침 타이머 · " + p.sleepLabel())
                .setItems(
                        items,
                        (d, i) -> {
                            if (i < 3) p.sleepMinutes(new int[] {15, 30, 60}[i]);
                            else if (i == 3) {
                                EditText input = new EditText(a);
                                input.setHint("1~720분");
                                input.setInputType(2);
                                new AlertDialog.Builder(a)
                                        .setTitle("정지까지 남은 시간")
                                        .setView(input)
                                        .setPositiveButton(
                                                "설정",
                                                (x, w) -> {
                                                    try {
                                                        int min =
                                                                Integer.parseInt(
                                                                        input.getText().toString());
                                                        if (min < 1 || min > 720)
                                                            throw new NumberFormatException();
                                                        p.sleepMinutes(min);
                                                    } catch (Exception e) {
                                                        toast("1~720분을 입력해 주세요.");
                                                    }
                                                })
                                        .setNegativeButton("취소", null)
                                        .show();
                            } else if (i == 6) p.cancelSleep();
                            else p.sleepBoundary(i == 5);
                        })
                .setNegativeButton("닫기", null)
                .show();
    }

    void widget(LinearLayout host) {
        header(host, "홈 화면 위젯");
        note(host, "홈 화면을 길게 누른 뒤 위젯 목록에서 AURA를 추가해 주세요. 크기를 조절할 수 있습니다.");
        Track t = a.musicCurrent();
        boolean playing = a.musicPlaying();
        async(
                host,
                () -> {
                    android.widget.RemoteViews views =
                            MusicWidget.views(
                                    a,
                                    t == null ? "AURA" : t.title,
                                    t == null ? "음악을 선택해 주세요." : t.artist,
                                    t == null ? "" : t.uri,
                                    playing);
                    return () -> {
                        View preview = views.apply(a.getApplicationContext(), host);
                        host.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(a, 104)));
                        note(host, "실제 위젯 미리보기 · 이전 곡 · 재생/일시정지 · 다음 곡");
                    };
                });
    }

    void backup(LinearLayout host) {
        header(host, "백업과 복원");
        note(
                host,
                "플레이리스트·통계·EQ·즐겨찾기·북마크·곡별 가사 싱크와 홈 모음을 저장합니다. Google 로그인·음원·폴더 읽기 권한은 포함하지 않습니다. 새 기기에서는 폴더와 계정을 다시 연결해 주세요.");
        button(
                host,
                "백업 파일 저장",
                () ->
                        a.startActivityForResult(
                                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                        .setType("application/json")
                                        .putExtra(Intent.EXTRA_TITLE, "AURA-backup.json"),
                                70));
        button(
                host,
                "백업 파일에서 복원",
                () ->
                        new AlertDialog.Builder(a)
                                .setMessage(
                                        "같은 이름의 설정과 기록은 백업 값으로 바뀝니다. 폴더와 Google 계정은 유지됩니다. 복원하시겠습니까?")
                                .setPositiveButton(
                                        "파일 선택",
                                        (d, w) ->
                                                a.startActivityForResult(
                                                        new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                                                .addCategory(
                                                                        Intent.CATEGORY_OPENABLE)
                                                                .setType("application/json"),
                                                        71))
                                .setNegativeButton("취소", null)
                                .show());
    }

    void offline(LinearLayout host) {
        header(host, "오프라인 음악");
        TextView status =
                text(
                        OfflineService.status.isEmpty()
                                ? "Google Drive의 음악을 선택해 보관해 주세요."
                                : OfflineService.status,
                        12);
        host.addView(status);
        Runnable tick =
                new Runnable() {
                    public void run() {
                        if (!a.isDestroyed() && host.isAttachedToWindow()) {
                            Ui.updateText(
                                    status,
                                    OfflineService.status.isEmpty()
                                            ? "보관할 음악을 선택해 주세요."
                                            : OfflineService.status);
                            main.postDelayed(this, 1000);
                        }
                    }
                };
        main.postDelayed(tick, 1000);
        host.addView(
                Ui.toggle(
                        a,
                        "오래된 보관곡 자동 정리",
                        MusicCollections.prefs(a).getBoolean("offline-clean", false),
                        v ->
                                MusicCollections.prefs(a)
                                        .edit()
                                        .putBoolean("offline-clean", v)
                                        .apply()));
        button(
                host,
                "보관 용량 제한",
                () ->
                        new AlertDialog.Builder(a)
                                .setTitle("오프라인 보관 한도")
                                .setItems(
                                        new String[] {"512 MB", "1 GB", "4 GB", "8 GB"},
                                        (d, i) -> {
                                            MusicCollections.prefs(a)
                                                    .edit()
                                                    .putLong(
                                                            "offline-quota",
                                                            new long[] {
                                                                        512L << 20, 1L << 30,
                                                                        4L << 30, 8L << 30
                                                                    }
                                                                    [i])
                                                    .apply();
                                            a.musicRender();
                                        })
                                .setNegativeButton("취소", null)
                                .show());
        button(host, "목록 새로고침", a::musicRender);
        button(
                host,
                "다운로드 취소",
                () -> a.startService(new Intent(a, OfflineService.class).setAction("cancel")));
        async(
                host,
                () -> {
                    List<Track> songs = OfflineMusic.tracks(a);
                    long used = OfflineMusic.used(a);
                    return () -> {
                        note(
                                host,
                                (used / 1048576)
                                        + " MB / "
                                        + (OfflineMusic.quota(a) / 1048576)
                                        + " MB · "
                                        + songs.size()
                                        + "곡");
                        a.musicList(host, songs);
                    };
                });
    }

    void duplicates(LinearLayout host) {
        header(host, "중복 곡 정리");
        note(
                host,
                "제목·아티스트와 재생 시간이 비슷한 후보입니다. 다른 버전일 수도 있으니 확인해 주세요. 선택하지 않은 곡은 목록에서만 숨기며 원본 파일은 삭제하지 않습니다.");
        button(host, "숨긴 곡 모두 다시 표시", this::showAllHiddenTracks);
        async(host, () -> loadDuplicateGroups(host));
    }

    private void showAllHiddenTracks() {
        MusicCollections.prefs(a).edit().remove("hidden").apply();
        a.musicReload();
    }

    private Runnable loadDuplicateGroups(LinearLayout host) {
        List<List<Track>> groups = MusicCollections.duplicates(new Library(a).load());
        return () -> showDuplicateGroups(host, groups);
    }

    private void showDuplicateGroups(LinearLayout host, List<List<Track>> groups) {
        note(host, groups.size() + "개 후보 그룹");
        for (List<Track> group : groups.subList(0, Math.min(100, groups.size()))) {
            String label = group.get(0).title + " · " + group.size() + "개";
            button(host, label, () -> chooseDuplicateVersion(group));
        }
    }

    private void chooseDuplicateVersion(List<Track> group) {
        String[] labels = group.stream().map(this::duplicateVersionLabel).toArray(String[]::new);
        new AlertDialog.Builder(a)
                .setTitle("라이브러리에 표시할 버전")
                .setItems(labels, (dialog, index) -> keepDuplicateVersion(group, index))
                .setNegativeButton("취소", null)
                .show();
    }

    private String duplicateVersionLabel(Track track) {
        return track.sourceLabel() + " · " + track.album + " · " + track.uri;
    }

    private void keepDuplicateVersion(List<Track> group, int index) {
        Set<String> hidden =
                new HashSet<>(MusicCollections.prefs(a).getStringSet("hidden", Set.of()));
        for (Track track : group) {
            if (track == group.get(index)) {
                hidden.remove(track.uri);
            } else {
                hidden.add(track.uri);
            }
        }
        MusicCollections.prefs(a).edit().putStringSet("hidden", hidden).apply();
        a.musicReload();
    }

    void profiles(LinearLayout host) {
        header(host, "기기별 음향 프로필");
        note(host, "연결된 출력 기기에 EQ·리샘플링·베이스·앱 볼륨 상한을 저장합니다. 출력 엔진과 비트퍼펙트 설정은 프로필이 바꾸지 않습니다.");
        host.addView(
                Ui.toggle(
                        a,
                        "연결 시 저장한 프로필 자동 적용",
                        MusicCollections.prefs(a).getBoolean("autoProfiles", false),
                        v ->
                                MusicCollections.prefs(a)
                                        .edit()
                                        .putBoolean("autoProfiles", v)
                                        .apply()));
        button(
                host,
                "현재 기기의 음향 설정 저장",
                () -> {
                    DeviceProfiles.save(a);
                    toast("현재 기기 프로필을 저장했습니다.");
                });
        button(
                host,
                "현재 기기 프로필 불러오기",
                () -> {
                    if (DeviceProfiles.apply(a, null, true)) {
                        if (PlaybackService.instance != null)
                            PlaybackService.instance.applyAudioSettings();
                        a.musicRender();
                    } else toast("현재 기기에 저장된 프로필이 없습니다.");
                });
        TextView ceiling =
                text("앱 볼륨 상한 · " + Math.round(AudioSettings.extra.volumeCeiling()) + "%", 13);
        host.addView(ceiling);
        SeekBar limit = new SeekBar(a);
        limit.setMax(100);
        limit.setProgress((int) AudioSettings.extra.volumeCeiling());
        limit.setContentDescription("앱 볼륨 상한");
        limit.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar b) {}

                    public void onStopTrackingTouch(SeekBar b) {}

                    public void onProgressChanged(SeekBar b, int n, boolean user) {
                        ceiling.setText("앱 볼륨 상한 · " + n + "%");
                        if (user) AudioSettings.put("volumeCeiling", n);
                    }
                });
        host.addView(limit);
        note(host, "비트퍼펙트 재생에서는 앱 볼륨 상한과 음향 처리를 우회합니다.");
        note(host, "프로필에서 기기를 구분할 수 없는 유선 이어폰은 같은 출력 종류의 프로필을 공유합니다.");
    }

    void lyricOptions(Track track) {
        String key = "lyric-offset:" + MusicCollections.key(track.uri);
        long offset = MusicCollections.prefs(a).getLong(key, 0);
        String[] items = {
            "0.1초 앞당기기", "0.1초 늦추기", "싱크 직접 입력", "이 곡의 보정 초기화", "로컬 LRC 파일 연결", "연결한 LRC 해제"
        };
        new AlertDialog.Builder(a)
                .setTitle("이 곡의 가사 · " + offset + " ms")
                .setItems(items, (dialog, index) -> handleLyricOption(track, key, offset, index))
                .setNegativeButton("닫기", null)
                .show();
    }

    private void handleLyricOption(Track track, String key, long offset, int index) {
        if (index == 5) {
            IO.execute(() -> unlinkLocalLyrics(track));
        } else if (index == 4) {
            chooseLocalLyrics(track);
        } else if (index == 2) {
            enterLyricOffset(key, offset);
        } else {
            saveOffset(key, index == 3 ? 0 : offset + (index == 0 ? 100 : -100));
        }
    }

    private void unlinkLocalLyrics(Track track) {
        String filename = "manual-lyrics/" + MusicCollections.key(track.uri) + ".lrc";
        new java.io.File(a.getFilesDir(), filename).delete();
        main.post(a::musicLyricsChanged);
    }

    private void chooseLocalLyrics(Track track) {
        MusicCollections.prefs(a).edit().putString("pendingLrc", track.uri).apply();
        Intent picker =
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*");
        a.startActivityForResult(picker, 72);
    }

    private void enterLyricOffset(String key, long offset) {
        EditText input = new EditText(a);
        input.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText("" + offset);
        new AlertDialog.Builder(a)
                .setTitle("가사 보정 · 밀리초")
                .setView(input)
                .setPositiveButton("저장", (dialog, which) -> saveEnteredLyricOffset(key, input))
                .setNegativeButton("취소", null)
                .show();
    }

    private void saveEnteredLyricOffset(String key, EditText input) {
        try {
            saveOffset(key, Long.parseLong(input.getText().toString()));
        } catch (Exception e) {
            toast("숫자를 입력해 주세요.");
        }
    }

    void saveOffset(String key, long value) {
        MusicCollections.prefs(a)
                .edit()
                .putLong(key, Math.max(-600000, Math.min(600000, value)))
                .apply();
        a.musicLyricsChanged();
    }

    boolean result(int request, int result, Intent data) {
        if (request < 70 || request > 72) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        IO.execute(
                () -> {
                    try {
                        if (request == 70) {
                            byte[] bytes =
                                    ListeningBackup.export(a)
                                            .toString()
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            if (bytes.length > ListeningBackup.MAX)
                                throw new java.io.IOException("백업 파일이 너무 큽니다.");
                            try (var out = a.getContentResolver().openOutputStream(uri, "wt")) {
                                if (out == null) throw new java.io.IOException("파일을 열지 못했습니다.");
                                out.write(bytes);
                            }
                        } else if (request == 71) {
                            try (var in = a.getContentResolver().openInputStream(uri)) {
                                ListeningBackup.restore(a, in);
                            }
                        } else {
                            String target = MusicCollections.prefs(a).getString("pendingLrc", "");
                            if (target.isEmpty())
                                throw new java.io.IOException("가사를 연결할 곡을 다시 선택해 주세요.");
                            try (var in = a.getContentResolver().openInputStream(uri)) {
                                byte[] bytes = DriveHttp.read(in, 2_000_000);
                                String lrc =
                                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                                if (Lrc.parse(lrc).isEmpty())
                                    throw new java.io.IOException("시간 정보가 있는 LRC 파일을 선택해 주세요.");
                                java.io.File f =
                                        new java.io.File(
                                                a.getFilesDir(),
                                                "manual-lyrics/"
                                                        + MusicCollections.key(target)
                                                        + ".lrc");
                                f.getParentFile().mkdirs();
                                java.nio.file.Files.write(f.toPath(), bytes);
                            }
                            MusicCollections.prefs(a).edit().remove("pendingLrc").apply();
                        }
                        main.post(
                                () -> {
                                    if (a.isDestroyed()) return;
                                    toast(
                                            request == 70
                                                    ? "백업을 저장했습니다."
                                                    : request == 71
                                                            ? "백업을 복원했습니다. 새 기기에서는 음악 폴더를 다시 연결해 주세요."
                                                            : "LRC 파일을 연결했습니다.");
                                    if (request == 71) {
                                        if (PlaybackService.instance != null)
                                            try {
                                                PlaybackService.instance.applyAudioSettings();
                                            } catch (Exception ignored) {
                                            }
                                        a.musicReload();
                                    } else if (request == 72) a.musicLyricsChanged();
                                });
                    } catch (Exception e) {
                        main.post(
                                () ->
                                        toast(
                                                e.getMessage() == null
                                                        ? "작업을 완료하지 못했습니다."
                                                        : e.getMessage()));
                    }
                });
        return true;
    }
}

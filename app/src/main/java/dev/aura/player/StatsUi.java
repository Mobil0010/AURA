package dev.aura.player;

import android.app.Activity;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;

/** Personal, offline listening dashboard. Views and queries are bounded, even for large histories. */
final class StatsUi {
    private final Activity a;
    private final BooleanSupplier visible;
    private ListeningStore store;
    private LinearLayout host, results;
    private int period = 1, category = 0, limit = 20;
    private volatile int generation;
    private boolean paused, byPlays;
    private String genre = "";
    private LocalDate date = LocalDate.now();
    private final android.os.Handler main =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refresh =
            new Runnable() {
                public void run() {
                    if (!paused
                            && visible.getAsBoolean()
                            && host != null
                            && host.isAttachedToWindow()) load();
                }
            };

    StatsUi(Activity a, BooleanSupplier visible) {
        this.a = a;
        this.visible = visible;
        store = ListeningStore.get(a);
    }

    void save(android.os.Bundle out) {
        out.putInt("statsPeriod", period);
        out.putInt("statsCategory", category);
        out.putBoolean("statsPlays", byPlays);
        out.putString("statsGenre", genre);
        out.putString("statsDate", date.toString());
    }

    void restore(android.os.Bundle state) {
        if (state == null) return;
        period = Math.max(0, Math.min(3, state.getInt("statsPeriod", 1)));
        category = Math.max(0, Math.min(3, state.getInt("statsCategory", 0)));
        byPlays = state.getBoolean("statsPlays");
        genre = state.getString("statsGenre", "");
        try {
            date = LocalDate.parse(state.getString("statsDate", LocalDate.now().toString()));
        } catch (RuntimeException ignored) {
            date = LocalDate.now();
        }
    }

    void show(LinearLayout body) {
        host = body;
        build();
    }

    void pause() {
        paused = true;
        generation++;
        main.removeCallbacks(refresh);
    }

    void resume() {
        paused = false;
        if (host != null && visible.getAsBoolean()) load();
    }

    void hide() {
        generation++;
        main.removeCallbacksAndMessages(null);
        host = null;
    }

    private int dp(float n) {
        return Ui.dp(a, n);
    }

    private TextView text(String s, int size, int color) {
        return Ui.text(a, s, size, color);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(a);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private void gap(LinearLayout v, int n) {
        View g = new View(a);
        v.addView(g, new LinearLayout.LayoutParams(1, dp(n)));
    }

    private TextView action(String label, Runnable task) {
        TextView t = text(label, 13, Ui.MINT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setMinHeight(dp(44));
        t.setBackground(Ui.flatSurface(a, Ui.SOFT, 14));
        Ui.tap(t, task);
        return t;
    }

    private void build() {
        if (host == null) return;
        main.removeCallbacks(refresh);
        host.removeAllViews();
        LinearLayout top = row(), words = column();
        TextView eyebrow = text("YOUR LISTENING", 10, Ui.MINT);
        eyebrow.setLetterSpacing(.15f);
        words.addView(eyebrow);
        gap(words, 8);
        words.addView(text("나의 음악 통계", 26, Ui.INK));
        top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.IconButton menu = new Ui.IconButton(a, "⚙");
        menu.setContentDescription("청취 기록 설정");
        Ui.tap(menu, this::settings);
        top.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        host.addView(top);
        gap(host, 20);
        LinearLayout periods = row();
        periods.setPadding(dp(4), dp(4), dp(4), dp(4));
        periods.setBackground(Ui.flatSurface(a, Ui.SURFACE, 16));
        String[] names = {"일", "주", "월", "전체"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            periods.addView(
                    Ui.segment(
                            a,
                            names[i],
                            period == i,
                            () -> {
                                period = index;
                                date = LocalDate.now();
                                limit = 20;
                                build();
                            }),
                    new LinearLayout.LayoutParams(0, dp(40), 1));
        }
        host.addView(periods);
        gap(host, 10);
        var range = ListeningStore.Range.of(period, date, ZoneId.systemDefault());
        LinearLayout calendar = row();
        TextView prev = action("‹", () -> move(-1));
        prev.setContentDescription("이전 기간");
        calendar.addView(prev, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text(label(range), 13, Ui.INK);
        title.setGravity(Gravity.CENTER);
        calendar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView next = action("›", () -> move(1));
        next.setContentDescription("다음 기간");
        boolean future =
                period == 3
                        || range.end()
                                >= LocalDate.now()
                                        .plusDays(1)
                                        .atStartOfDay(ZoneId.systemDefault())
                                        .toInstant()
                                        .toEpochMilli();
        next.setEnabled(!future);
        next.setAlpha(future ? .3f : 1);
        calendar.addView(next, new LinearLayout.LayoutParams(dp(44), dp(44)));
        if (period == 3) {
            prev.setVisibility(View.INVISIBLE);
            next.setVisibility(View.INVISIBLE);
        }
        Ui.tap(
                title,
                () -> {
                    if (period == 3) return;
                    DatePicker picker = new DatePicker(a);
                    picker.init(
                            date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth(), null);
                    picker.setMaxDate(System.currentTimeMillis());
                    ScrollView pickerScroll = new ScrollView(a);
                    pickerScroll.addView(picker);
                    new androidx.appcompat.app.AlertDialog.Builder(a)
                            .setTitle("기간 선택")
                            .setView(pickerScroll)
                            .setNegativeButton("취소", null)
                            .setPositiveButton(
                                    "선택",
                                    (dialog, which) -> {
                                        date =
                                                LocalDate.of(
                                                        picker.getYear(),
                                                        picker.getMonth() + 1,
                                                        picker.getDayOfMonth());
                                        build();
                                    })
                            .show();
                });
        host.addView(calendar);
        results = column();
        host.addView(results);
        results.addView(text("청취 기록을 불러오고 있습니다…", 13, Ui.MUTED));
        load();
    }

    private void move(int step) {
        date =
                switch (period) {
                    case 1 -> date.plusWeeks(step);
                    case 2 -> date.plusMonths(step);
                    default -> date.plusDays(step);
                };
        if (date.isAfter(LocalDate.now())) date = LocalDate.now();
        build();
    }

    private String label(ListeningStore.Range r) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy.MM.dd");
        return switch (period) {
            case 1 ->
                    r.date().format(fmt)
                            + " – "
                            + r.date().plusDays(6).format(DateTimeFormatter.ofPattern("MM.dd"));
            case 2 -> r.date().format(DateTimeFormatter.ofPattern("yyyy년 M월"));
            case 3 -> "기록을 시작한 날부터 지금까지";
            default -> r.date().format(fmt);
        };
    }

    private void load() {
        int token = ++generation;
        var r = ListeningStore.Range.of(period, date, ZoneId.systemDefault());
        int cat = category, offset = limit - 20;
        boolean order = byPlays;
        String filter = genre;
        ZoneId zone = ZoneId.systemDefault();
        ListeningMonitor.checkpoint();
        store.io.execute(
                () -> {
                    if (token != generation) return;
                    ListeningStore.Snapshot data;
                    try {
                        data = store.query(r, cat, order, filter, 20, offset, zone);
                    } catch (RuntimeException e) {
                        data =
                                new ListeningStore.Snapshot(
                                        0,
                                        0,
                                        0,
                                        List.of(),
                                        List.of(),
                                        new long[0],
                                        "기록을 불러오지 못했습니다. 다시 시도해 주세요.");
                    }
                    var snapshot = data;
                    main.post(
                            () -> {
                                if (paused
                                        || token != generation
                                        || host == null
                                        || a.isDestroyed()
                                        || !visible.getAsBoolean()) return;
                                draw(snapshot);
                                main.removeCallbacks(refresh);
                                main.postDelayed(refresh, 30000);
                            });
                });
    }

    static String exact(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(
                Locale.getDefault(),
                "%,d시간 %02d분 %02d초",
                seconds / 3600,
                seconds / 60 % 60,
                seconds % 60);
    }

    static String time(long ms) {
        long minutes = ms / 60000;
        if (minutes >= 60) return (minutes / 60) + "시간 " + (minutes % 60) + "분";
        if (minutes > 0) return minutes + "분";
        return ms / 1000 + "초";
    }

    private void draw(ListeningStore.Snapshot data) {
        results.removeAllViews();
        if (!data.error().isEmpty()) {
            results.addView(text(data.error(), 14, Ui.ERROR));
            results.addView(Ui.button(a, "다시 시도", this::load, false));
            return;
        }
        LinearLayout hero = Ui.card(a, 20);
        hero.setBackground(Ui.flatSurface(a, Ui.SOFT, 24));
        LinearLayout intro = row();
        TextView label = text("음악과 함께한 시간", 12, Ui.MUTED);
        intro.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView live = text(ListeningMonitor.enabled(a) ? "● 기록 중" : "기록 꺼짐", 10, Ui.MINT);
        intro.addView(live);
        hero.addView(intro);
        gap(hero, 13);
        TextView total = text(time(data.ms()), 34, Ui.INK);
        total.setTag("stats-total");
        total.setMaxLines(1);
        total.setAutoSizeTextTypeUniformWithConfiguration(
                22, 34, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        hero.addView(total);
        gap(hero, 8);
        TextView precise = text(exact(data.ms()), 12, Ui.MUTED);
        precise.setTag("stats-exact-total");
        hero.addView(precise);
        gap(hero, 18);
        LinearLayout metrics = row();
        metric(metrics, String.format(Locale.getDefault(), "%,d", data.plays()), "재생 횟수");
        metric(metrics, String.format(Locale.getDefault(), "%,d", data.songs()), "감상한 곡");
        hero.addView(metrics);
        results.addView(hero);
        LinearLayout graph = Ui.card(a, 16);
        TextView graphTitle =
                text(
                        period == 3 ? "최근 12개월 청취 시간" : period == 0 ? "시간대별 청취 시간" : "일별 청취 시간",
                        13,
                        Ui.INK);
        graph.addView(graphTitle);
        gap(graph, 10);
        Bars bars =
                new Bars(
                        a,
                        data.bars(),
                        period,
                        ListeningStore.Range.of(period, date, ZoneId.systemDefault()).date());
        bars.setContentDescription(
                graphTitle.getText() + " · 총 " + time(data.ms()) + ". 막대가 높을수록 오래 감상했습니다.");
        graph.addView(bars, new LinearLayout.LayoutParams(-1, dp(112)));
        results.addView(graph);
        gap(results, 22);
        LinearLayout titles = row();
        titles.addView(text("많이 들은 음악", 19, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
        titles.addView(
                action(
                        byPlays ? "재생 횟수 ↓" : "청취 시간 ↓",
                        () -> {
                            byPlays = !byPlays;
                            limit = 20;
                            load();
                        }));
        results.addView(titles);
        gap(results, 10);
        LinearLayout tabs = row();
        String[] labels = {"곡", "아티스트", "앨범", "장르"};
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            tabs.addView(
                    Ui.segment(
                            a,
                            labels[i],
                            category == i,
                            () -> {
                                category = index;
                                limit = 20;
                                load();
                            }),
                    new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        results.addView(tabs);
        gap(results, 8);
        TextView filter =
                action(
                        genre.isEmpty()
                                ? "모든 장르 ⌄"
                                : (genre.equals("__none__") ? "장르 없음" : genre) + " ⌄",
                        () -> chooseGenre(data.genres()));
        filter.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        results.addView(filter);
        if (data.ranks().isEmpty() && limit > 20) {
            results.addView(
                    Ui.button(
                            a,
                            "이전 순위",
                            () -> {
                                limit = Math.max(20, limit - 20);
                                load();
                            },
                            false));
        }
        if (data.ranks().isEmpty()) {
            LinearLayout empty = Ui.card(a, 20);
            empty.addView(text("아직 청취 기록이 없습니다", 18, Ui.INK));
            gap(empty, 10);
            empty.addView(text("이 기능을 추가한 이후의 재생부터 기록합니다.\n음악을 들으시면 이곳에 취향이 쌓입니다.", 13, Ui.MUTED));
            results.addView(empty);
        } else {
            LinearLayout ranks = Ui.card(a, 12);
            long max = byPlays ? data.ranks().get(0).plays() : data.ranks().get(0).ms();
            for (int i = 0; i < data.ranks().size(); i++) {
                ListeningStore.Rank rank = data.ranks().get(i);
                LinearLayout item = column();
                item.setPadding(dp(4), dp(12), dp(4), dp(12));
                LinearLayout line = row();
                TextView number =
                        text(
                                String.format(Locale.ROOT, "%02d", i + limit - 19),
                                12,
                                i == 0 ? Ui.MINT : Ui.MUTED);
                line.addView(number, new LinearLayout.LayoutParams(dp(28), -2));
                if (category != 3) {
                    Track artTrack =
                            new Track(
                                    rank.uri(),
                                    rank.title(),
                                    category == 1 ? rank.title() : rank.subtitle(),
                                    category == 2 ? rank.title() : rank.subtitle(),
                                    "",
                                    0,
                                    "");
                    ArtworkView cover = new ArtworkView(a, artTrack, 42);
                    LinearLayout.LayoutParams coverParams =
                            new LinearLayout.LayoutParams(dp(42), dp(42));
                    coverParams.rightMargin = dp(10);
                    line.addView(cover, coverParams);
                }
                LinearLayout names = column();
                TextView name = text(rank.title(), 14, Ui.INK);
                Ui.lines(name, 1);
                names.addView(name);
                gap(names, 6);
                TextView sub = text(rank.subtitle(), 10, Ui.MUTED);
                Ui.lines(sub, 1);
                names.addView(sub);
                line.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout values = column();
                values.setPadding(dp(8), 0, 0, 0);
                TextView duration = text(time(rank.ms()), 12, Ui.INK);
                duration.setGravity(Gravity.END);
                values.addView(duration);
                gap(values, 6);
                TextView plays =
                        text(
                                rank.plays()
                                        + "회 · "
                                        + String.format(
                                                Locale.getDefault(), "%,d", rank.ms() / 1000)
                                        + "초",
                                10,
                                Ui.MUTED);
                plays.setGravity(Gravity.END);
                Ui.lines(plays, 1);
                plays.setAutoSizeTextTypeUniformWithConfiguration(
                        8, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
                values.addView(plays);
                duration.setTag("stats-rank-duration");
                Ui.lines(duration, 1);
                duration.setAutoSizeTextTypeUniformWithConfiguration(
                        9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
                line.addView(values, new LinearLayout.LayoutParams(dp(82), -2));
                item.addView(line);
                gap(item, 10);
                ProgressBar bar =
                        new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(1000);
                bar.setProgress(
                        max == 0
                                ? 0
                                : (int)
                                        Math.min(
                                                1000,
                                                (byPlays ? rank.plays() : rank.ms()) * 1000 / max));
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.MINT));
                bar.setProgressBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Ui.BORDER));
                bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                item.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));
                item.setContentDescription(
                        (i + limit - 19)
                                + "위 · "
                                + rank.title()
                                + " · "
                                + rank.subtitle()
                                + " · "
                                + exact(rank.ms())
                                + " · "
                                + rank.plays()
                                + "회");
                int detailCategory = category;
                item.setContentDescription(item.getContentDescription() + " · 상세 청취 시간 보기");
                Ui.tap(item, () -> details(rank, detailCategory));
                ranks.addView(item);
            }
            results.addView(ranks);
            if (limit > 20)
                results.addView(
                        Ui.button(
                                a,
                                "이전 순위",
                                () -> {
                                    limit = Math.max(20, limit - 20);
                                    load();
                                },
                                false));
            if (data.ranks().size() == 20)
                results.addView(
                        Ui.button(
                                a,
                                "다음 순위",
                                () -> {
                                    limit += 20;
                                    load();
                                },
                                false));
        }
        gap(results, 18);
        TextView note = text("이 기기에 저장된 청취 기록입니다.\n기능을 추가한 이후의 재생부터 집계합니다.", 11, Ui.MUTED);
        note.setLineSpacing(dp(4), 1);
        results.addView(note);
    }

    private void metric(LinearLayout target, String value, String label) {
        LinearLayout col = column();
        col.addView(text(value, 21, Ui.INK));
        gap(col, 7);
        col.addView(text(label, 11, Ui.MUTED));
        target.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void chooseGenre(List<String> genres) {
        List<String> values = new ArrayList<>();
        values.add("모든 장르");
        for (String g : genres) values.add(g.isEmpty() ? "장르 없음" : g);
        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("장르 선택")
                .setSingleChoiceItems(
                        values.toArray(new String[0]),
                        genre.isEmpty()
                                ? 0
                                : values.indexOf(genre.equals("__none__") ? "장르 없음" : genre),
                        (d, i) -> {
                            genre =
                                    i == 0
                                            ? ""
                                            : values.get(i).equals("장르 없음")
                                                    ? "__none__"
                                                    : values.get(i);
                            limit = 20;
                            d.dismiss();
                            load();
                        })
                .setNegativeButton("취소", null)
                .show();
    }

    private void details(ListeningStore.Rank rank, int cat) {
        var range = ListeningStore.Range.of(period, date, ZoneId.systemDefault());
        String filter = genre;
        ZoneId zone = ZoneId.systemDefault();
        LinearLayout content = column();
        content.setPadding(dp(22), dp(8), dp(22), dp(12));
        TextView message = text("상세 기록을 불러오고 있습니다…", 14, Ui.INK);
        content.addView(message);
        ScrollView detailScroll = new ScrollView(a);
        detailScroll.addView(content);
        androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(a)
                        .setTitle(rank.title())
                        .setView(detailScroll)
                        .setPositiveButton("닫기", null)
                        .show();
        ListeningMonitor.checkpoint();
        store.io.execute(
                () -> {
                    try {
                        var detail = store.detail(range, cat, rank, filter, zone);
                        main.post(
                                () -> {
                                    if (a.isDestroyed() || !dialog.isShowing()) return;
                                    DateTimeFormatter fmt =
                                            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");
                                    String
                                            first =
                                                    detail.first() == 0
                                                            ? "기록 없음"
                                                            : Instant.ofEpochMilli(detail.first())
                                                                    .atZone(zone)
                                                                    .format(fmt),
                                            last =
                                                    detail.last() == 0
                                                            ? "기록 없음"
                                                            : Instant.ofEpochMilli(detail.last())
                                                                    .atZone(zone)
                                                                    .format(fmt);
                                    message.setText(
                                            "총 청취 시간\n"
                                                    + exact(detail.ms())
                                                    + "\n\n재생 횟수  "
                                                    + detail.plays()
                                                    + "회\n감상한 날짜  "
                                                    + detail.days()
                                                    + "일\n\n기기 저장소  "
                                                    + exact(detail.localMs())
                                                    + "\nGoogle Drive  "
                                                    + exact(detail.driveMs())
                                                    + "\n\n처음 들은 시각\n"
                                                    + first
                                                    + "\n\n마지막으로 들은 시각\n"
                                                    + last
                                                    + "\n\n선택한 기간과 장르 안의 기록입니다.");
                                });
                    } catch (RuntimeException e) {
                        main.post(
                                () -> {
                                    if (dialog.isShowing())
                                        message.setText("상세 기록을 불러오지 못했습니다. 다시 시도해 주세요.");
                                });
                    }
                });
    }

    private void settings() {
        LinearLayout content = column();
        content.setPadding(dp(20), 0, dp(20), 0);
        content.addView(
                Ui.toggle(
                        a,
                        "청취 기록 저장",
                        ListeningMonitor.enabled(a),
                        on -> {
                            ListeningMonitor.enabled(a, on);
                            load();
                        }));
        TextView description =
                text(
                        "기록은 이 기기에만 저장하며 다른 서비스로 전송하지 않습니다. 과거 기록은 가져오지 않습니다.\n\n실제로 재생한 시간을 집계하며 일시정지·버퍼링·탐색으로 건너뛴 구간은 제외합니다. 30초 이상 감상하면 1회, 1분보다 짧은 곡은 절반 이상 감상하면 1회로 집계합니다.\n\n캐스트 청취 시간은 수신 기기가 보고하는 재생 위치로 추정합니다.\n\n장르는 음원 태그를 사용합니다. 이전에 추가한 곡의 장르가 비어 있으면 해당 폴더를 다시 검색해 주세요.",
                        12,
                        Ui.MUTED);
        description.setPadding(0, dp(14), 0, dp(10));
        content.addView(description);
        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("청취 기록")
                .setView(content)
                .setPositiveButton("닫기", null)
                .setNeutralButton("기록 삭제", (dialog, which) -> confirmClearHistory())
                .show();
    }

    private void confirmClearHistory() {
        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("청취 기록을 삭제할까요?")
                .setMessage("이 기기의 모든 청취 통계를 삭제합니다. 삭제한 기록은 복구할 수 없습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> clearHistory())
                .show();
    }

    private void clearHistory() {
        ListeningMonitor.checkpoint();
        ListeningMonitor.resetAll();
        store.clearAsync();
        load();
    }

    private static final class Bars extends View {
        final long[] values;
        final String[] labels, descriptions;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final long max;

        Bars(android.content.Context c, long[] values, int period, LocalDate date) {
            super(c);
            this.values = values;
            labels = new String[values.length];
            descriptions = new String[values.length];
            long maximum = 1;
            String[] weekdays = {"월", "화", "수", "목", "금", "토", "일"};
            for (int i = 0; i < values.length; i++) {
                maximum = Math.max(maximum, values[i]);
                descriptions[i] = periodDescription(period, date, i) + " · " + exact(values[i]);
                boolean label =
                        period == 1
                                || i == 0
                                || i == values.length - 1
                                || i % (values.length >= 24 ? 6 : 3) == 0;
                if (label)
                    labels[i] =
                            period == 1
                                    ? weekdays[i]
                                    : period == 0
                                            ? i + "시"
                                            : period == 2
                                                    ? (i + 1) + "일"
                                                    : LocalDate.now()
                                                                    .minusMonths(11 - i)
                                                                    .getMonthValue()
                                                            + "월";
            }
            max = maximum;
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setTextSize(9 * getResources().getDisplayMetrics().density);
        }

        private String periodDescription(int period, LocalDate date, int index) {
            if (period == 0) return date + " " + index + "시";
            if (period == 1) return date.plusDays(index).toString();
            if (period == 2) return date.withDayOfMonth(index + 1).toString();
            LocalDate month = LocalDate.now().withDayOfMonth(1).minusMonths(11 - index);
            return month.format(DateTimeFormatter.ofPattern("yyyy.MM"));
        }

        private int selected;

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    && values.length > 0) {
                selected =
                        Math.max(
                                0,
                                Math.min(
                                        values.length - 1,
                                        (int)
                                                (event.getX()
                                                        / Math.max(1, getWidth())
                                                        * values.length)));
                performClick();
                return true;
            }
            return event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    || super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (descriptions.length > 0)
                Toast.makeText(getContext(), descriptions[selected], Toast.LENGTH_LONG).show();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int n = values.length;
            if (n == 0) return;
            float d = getResources().getDisplayMetrics().density,
                    w = getWidth(),
                    bottom = getHeight() - 23 * d,
                    step = w / n;
            p.setColor(Ui.BORDER);
            canvas.drawLine(0, bottom, w, bottom, p);
            for (int i = 0; i < n; i++) {
                float height = (bottom - 10 * d) * values[i] / max;
                p.setColor(values[i] > 0 ? Ui.MINT : Ui.BORDER);
                float left = i * step + Math.min(3 * d, step * .18f);
                canvas.drawRoundRect(
                        left,
                        bottom - Math.max(3 * d, height),
                        (i + 1) * step - Math.min(3 * d, step * .18f),
                        bottom,
                        3 * d,
                        3 * d,
                        p);
            }
            p.setColor(Ui.MUTED);
            for (int i = 0; i < n; i++)
                if (labels[i] != null) {
                    p.setTextAlign(
                            i == 0
                                    ? Paint.Align.LEFT
                                    : i == n - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                    canvas.drawText(
                            labels[i],
                            i == 0 ? 0 : i == n - 1 ? w : (i + .5f) * step,
                            getHeight() - 5 * d,
                            p);
                }
        }
    }
}

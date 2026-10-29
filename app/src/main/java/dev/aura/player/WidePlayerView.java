package dev.aura.player;

import android.view.*;
import android.widget.*;
import androidx.media3.common.*;

@androidx.media3.common.util.UnstableApi
final class WidePlayerView extends ScrollView {
    final MainActivity a;
    final LinearLayout body;
    String key = "";
    Ui.IconButton toggle;
    TextView time, quality;
    SeekBar seek;
    boolean dragging;

    WidePlayerView(MainActivity a) {
        super(a);
        this.a = a;
        setFillViewport(true);
        setBackgroundColor(Ui.SURFACE);
        setTag("wide-player");
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(a, 20), Ui.dp(a, 24), Ui.dp(a, 20), Ui.dp(a, 24));
        addView(body);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) post(this::update);
    }

    void update() {
        Track t = a.musicCurrent();
        boolean compact = getHeight() > 0 && getHeight() < Ui.dp(a, 640);
        String next =
                t == null
                        ? "empty"
                        : t.uri
                                + (compact ? "|compact" : "|tall")
                                + (a.musicPage().equals("player") ? "|queue" : "|player");
        if (!key.equals(next)) {
            key = next;
            body.removeAllViews();
            toggle = null;
            seek = null;
            time = null;
            quality = null;
            if (t == null) {
                body.addView(Ui.text(a, "음악을 선택해 주세요.", 19, Ui.INK));
                return;
            }
            if (a.musicPage().equals("player")) {
                body.addView(Ui.text(a, "다음 재생", 21, Ui.INK));
                var upcoming = a.musicUpcoming();
                if (upcoming.isEmpty()) body.addView(Ui.text(a, "대기열에 다음 곡이 없습니다.", 13, Ui.MUTED));
                for (var item : upcoming)
                    body.addView(
                            Ui.button(
                                    a,
                                    item.track().title + " · " + item.track().artist,
                                    () -> a.musicSelectQueued(item),
                                    false));
                body.addView(Ui.button(a, "전체 대기열", () -> a.musicGo("queue"), false));
                body.addView(Ui.button(a, "전체화면 가사", () -> a.musicGo("lyrics"), false));
                return;
            }
            if (compact) {
                compact(t);
                update();
                return;
            }
            TextView label = Ui.text(a, "지금 재생 중", 12, Ui.MUTED);
            body.addView(label);
            FrameLayout art = new FrameLayout(a);
            ArtworkView picture = new ArtworkView(a, t, 240);
            art.addView(
                    picture,
                    new FrameLayout.LayoutParams(Ui.dp(a, 240), Ui.dp(a, 240), Gravity.CENTER));
            art.addOnLayoutChangeListener(
                    (v, l, top, r, b, ol, ot, or, ob) -> {
                        int size = Math.min(Ui.dp(a, 240), r - l);
                        if (picture.getLayoutParams().width != size) {
                            picture.setLayoutParams(
                                    new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
                        }
                    });
            LinearLayout.LayoutParams cover = new LinearLayout.LayoutParams(-1, Ui.dp(a, 240));
            cover.topMargin = Ui.dp(a, 20);
            body.addView(art, cover);
            TextView title = Ui.text(a, t.title, 21, Ui.INK);
            title.setPadding(0, Ui.dp(a, 20), 0, Ui.dp(a, 8));
            Ui.lines(title, 2);
            body.addView(title);
            body.addView(Ui.text(a, t.artist, 13, Ui.MUTED));
            quality = Ui.text(a, "", 11, Ui.MUTED);
            quality.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 12));
            body.addView(quality);
            seek = new SeekBar(a);
            seek.setContentDescription("재생 위치");
            seek.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        public void onStartTrackingTouch(SeekBar b) {
                            dragging = true;
                        }

                        public void onStopTrackingTouch(SeekBar b) {
                            a.musicSeek(b.getProgress());
                            dragging = false;
                        }

                        public void onProgressChanged(SeekBar b, int n, boolean user) {}
                    });
            body.addView(seek);
            time = Ui.text(a, "", 11, Ui.MUTED);
            body.addView(time);
            LinearLayout controls = new LinearLayout(a);
            controls.setGravity(Gravity.CENTER);
            Ui.IconButton prev = new Ui.IconButton(a, "⏮"), nextButton = new Ui.IconButton(a, "⏭");
            toggle = new Ui.IconButton(a, "▶");
            toggle.setBackground(Ui.flatSurface(a, Ui.MINT, 30));
            toggle.iconColor(Ui.onAccent());
            Ui.tap(prev, () -> a.musicStep(false));
            Ui.tap(nextButton, () -> a.musicStep(true));
            Ui.tap(toggle, a::musicToggle);
            controls.addView(prev, new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 56)));
            LinearLayout.LayoutParams center =
                    new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 64));
            center.setMargins(Ui.dp(a, 18), 0, Ui.dp(a, 18), 0);
            controls.addView(toggle, center);
            controls.addView(nextButton, new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 56)));
            body.addView(controls);
            body.addView(Ui.button(a, "전체 재생 화면", () -> a.musicGo("player"), false));
            body.addView(Ui.button(a, "가사", () -> a.musicGo("lyrics"), false));
            body.addView(Ui.button(a, "대기열", () -> a.musicGo("queue"), false));
            body.addView(Ui.button(a, "음악 도구", () -> a.musicActions(t), false));
        }
        if (toggle != null) {
            toggle.setText(a.musicPlaying() ? "Ⅱ" : "▶");
            long duration = a.musicDuration(), position = a.musicPosition();
            if (!dragging) {
                seek.setMax((int) Math.min(Integer.MAX_VALUE, duration));
                seek.setProgress((int) Math.min(Integer.MAX_VALUE, position));
            }
            Ui.updateText(
                    time, MainActivity.format(position) + " / " + MainActivity.format(duration));
            Ui.updateText(quality, PcmSink.signal == null ? "출력 준비 중" : PcmSink.signal.compact());
        }
    }

    void compact(Track t) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int artSize = Math.max(Ui.dp(a, 64), Math.min(Ui.dp(a, 160), getWidth() / 4));
        row.addView(
                new ArtworkView(
                        a, t, Math.round(artSize / a.getResources().getDisplayMetrics().density)),
                new LinearLayout.LayoutParams(artSize, artSize));
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(a, 12), 0, 0, 0);
        TextView title = Ui.text(a, t.title, 18, Ui.INK);
        Ui.lines(title, 2);
        info.addView(title);
        info.addView(Ui.text(a, t.artist, 12, Ui.MUTED));
        quality = Ui.text(a, "", 11, Ui.MUTED);
        quality.setPadding(0, Ui.dp(a, 7), 0, Ui.dp(a, 4));
        info.addView(quality);
        seek = new SeekBar(a);
        seek.setContentDescription("재생 위치");
        seek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar b) {
                        dragging = true;
                    }

                    public void onStopTrackingTouch(SeekBar b) {
                        a.musicSeek(b.getProgress());
                        dragging = false;
                    }

                    public void onProgressChanged(SeekBar b, int n, boolean user) {}
                });
        info.addView(seek);
        time = Ui.text(a, "", 11, Ui.MUTED);
        info.addView(time);
        LinearLayout controls = new LinearLayout(a);
        controls.setGravity(Gravity.CENTER);
        Ui.IconButton previous = new Ui.IconButton(a, "⏮"), next = new Ui.IconButton(a, "⏭");
        toggle = new Ui.IconButton(a, "▶");
        toggle.iconColor(Ui.onAccent());
        toggle.setBackground(Ui.flatSurface(a, Ui.MINT, 26));
        Ui.tap(previous, () -> a.musicStep(false));
        Ui.tap(toggle, a::musicToggle);
        Ui.tap(next, () -> a.musicStep(true));
        controls.addView(previous, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 48)));
        LinearLayout.LayoutParams center =
                new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52));
        center.setMargins(Ui.dp(a, 8), 0, Ui.dp(a, 8), 0);
        controls.addView(toggle, center);
        controls.addView(next, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 48)));
        info.addView(controls);
        LinearLayout links = new LinearLayout(a);
        for (String label : new String[] {"가사", "대기열", "도구"}) {
            TextView button =
                    Ui.segment(
                            a,
                            label,
                            false,
                            () -> {
                                if (label.equals("도구")) a.musicActions(t);
                                else a.musicGo(label.equals("가사") ? "lyrics" : "queue");
                            });
            links.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(a, 44), 1));
        }
        info.addView(links);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(row);
    }
}

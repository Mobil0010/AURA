package dev.aura.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.provider.Settings;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;

/** Shared visual language and short, interruptible motion. */
final class Ui {
    static int BG = 0xff11141d,
            SURFACE = 0xff1c2130,
            INK = 0xfff3f4fa,
            MUTED = 0xffa2abc0,
            MINT = 0xff80b6ff,
            BORDER = 0xff30384a,
            SOFT = 0xff243650,
            ERROR = 0xffffb4a4;
    static boolean dark = true;

    static void configure(Context c) {
        String mode = c.getSharedPreferences("aura", 0).getString("appearance", "dark");
        dark =
                !mode.equals("light")
                        && (!mode.equals("system")
                                || (c.getResources().getConfiguration().uiMode
                                                & android.content.res.Configuration
                                                        .UI_MODE_NIGHT_MASK)
                                        == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        BG = dark ? 0xff11141d : 0xfff7f8fc;
        SURFACE = dark ? 0xff1c2130 : 0xffffffff;
        INK = dark ? 0xfff3f4fa : 0xff1a2030;
        MUTED = dark ? 0xffa2abc0 : 0xff606b80;
        MINT = dark ? 0xff80b6ff : 0xff1764c0;
        BORDER = dark ? 0xff30384a : 0xffe3e7f0;
        SOFT = dark ? 0xff243650 : 0xffe6effc;
        ERROR = dark ? 0xffffb4a4 : 0xffb22935;
    }

    static int onAccent() {
        return dark ? 0xff10213c : 0xffffffff;
    }

    static int dp(Context c, float n) {
        return Math.round(n * c.getResources().getDisplayMetrics().density);
    }

    static boolean motion(Context c) {
        return Settings.Global.getFloat(
                        c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1)
                > 0;
    }

    static GradientDrawable shape(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    static android.graphics.drawable.Drawable surface(Context c, int color, int radius) {
        GradientDrawable d = shape(c, color, radius);
        if (color != MINT && color != BG) d.setStroke(dp(c, 1), BORDER);
        return new RippleDrawable(
                ColorStateList.valueOf((MINT & 0x00ffffff) | 0x24000000),
                d,
                shape(c, 0xffffffff, radius));
    }

    static Drawable flatSurface(Context c, int color, int radius) {
        return new RippleDrawable(
                ColorStateList.valueOf((MINT & 0x00ffffff) | 0x24000000),
                shape(c, color, radius),
                shape(c, 0xffffffff, radius));
    }

    static TextView text(Context c, String s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setFontFeatureSettings("kern");
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(c, 3), 1);
        if (size >= 21) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static void tap(View v, Runnable click) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(x -> click.run());
        v.setOnTouchListener(
                (view, event) -> {
                    if (motion(view.getContext())) {
                        int a = event.getActionMasked();
                        if (a == MotionEvent.ACTION_DOWN)
                            view.animate().scaleX(.97f).scaleY(.97f).setDuration(85).start();
                        else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
                            view.animate().scaleX(1).scaleY(1).setDuration(150).start();
                    }
                    return false;
                });
    }

    static void enter(View view, boolean upward) {
        view.animate().cancel();
        if (!motion(view.getContext())) {
            view.setAlpha(1);
            view.setTranslationY(0);
            return;
        }
        view.setAlpha(0);
        view.setTranslationY(dp(view.getContext(), upward ? 22 : 9));
        view.animate()
                .alpha(1)
                .translationY(0)
                .setDuration(upward ? 250 : 180)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    static void tint(SeekBar b) {
        b.setProgressTintList(ColorStateList.valueOf(MINT));
        b.setThumbTintList(ColorStateList.valueOf(MINT));
        b.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
        b.setMinimumHeight(dp(b.getContext(), 44));
    }

    static TextView button(Context c, String s, Runnable click, boolean primary) {
        TextView b = text(c, s, 14, primary ? onAccent() : INK);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        b.setMinHeight(dp(c, 50));
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(flatSurface(c, primary ? MINT : SURFACE, 20));
        tap(b, click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 10);
        b.setLayoutParams(lp);
        return b;
    }

    /** Bounded lists share drag/fling deltas with the surrounding page at either edge. */
    static androidx.recyclerview.widget.RecyclerView scrollingList(Context c) {
        var list = new androidx.recyclerview.widget.RecyclerView(c);
        list.setNestedScrollingEnabled(true);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setVerticalScrollBarEnabled(true);
        list.setItemAnimator(null);
        list.setHasFixedSize(true);
        list.setItemViewCacheSize(PlaybackBudget.get(c).constrained() ? 6 : 10);
        return list;
    }

    static TextView choice(Context c, String label, boolean selected, Runnable action) {
        TextView view = text(c, label, 13, selected ? onAccent() : MUTED);
        view.setGravity(Gravity.CENTER);
        view.setMaxLines(1);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setMinHeight(dp(c, 44));
        view.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 6));
        view.setSelected(selected);
        view.setContentDescription(label + (selected ? " · 선택됨" : ""));
        view.setBackground(flatSurface(c, selected ? MINT : SURFACE, 12));
        tap(view, action);
        return view;
    }

    static LinearLayout card(Context c, int padding) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(c, padding), dp(c, padding), dp(c, padding), dp(c, padding));
        card.setBackground(flatSurface(c, SURFACE, 24));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(c, 12);
        card.setLayoutParams(params);
        return card;
    }

    static LinearLayout settingRow(
            Context c, String icon, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        row.setMinimumHeight(dp(c, 68));
        row.setBackground(flatSurface(c, 0, 16));
        View badge = new View(c);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        badge.setBackground(
                new LayerDrawable(
                        new Drawable[] {
                            shape(c, SOFT, 14),
                            new InsetDrawable(new Icon(icon, MINT, dp(c, 22)), dp(c, 10))
                        }));
        row.addView(badge, new LinearLayout.LayoutParams(dp(c, 42), dp(c, 42)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(c, 13), 0, dp(c, 10), 0);
        TextView name = text(c, title, 14, INK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        lines(name, 2);
        words.addView(name);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView detail = text(c, subtitle, 11, MUTED);
            lines(detail, 2);
            detail.setPadding(0, dp(c, 5), 0, 0);
            words.addView(detail);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text(c, "›", 19, MUTED);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow);
        tap(row, action);
        return row;
    }

    static TextView segment(Context c, String label, boolean selected, Runnable action) {
        TextView view = text(c, label, 13, selected ? MINT : MUTED);
        view.setGravity(Gravity.CENTER);
        lines(view, 1);
        view.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
        view.setSelected(selected);
        view.setTypeface(
                Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setContentDescription(label + (selected ? " · 선택됨" : ""));
        view.setBackground(flatSurface(c, selected ? SOFT : 0, 12));
        tap(view, action);
        return view;
    }

    static androidx.appcompat.widget.SwitchCompat toggle(
            Context c,
            String label,
            boolean checked,
            java.util.function.Consumer<Boolean> changed) {
        var view = new androidx.appcompat.widget.SwitchCompat(c);
        view.setText(label);
        view.setTextSize(14);
        view.setTextColor(INK);
        view.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        view.setMinHeight(dp(c, 60));
        view.setSwitchPadding(dp(c, 16));
        view.setBackground(flatSurface(c, SURFACE, 20));
        view.setThumbTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {MINT, MUTED}));
        view.setTrackTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {SOFT, BORDER}));
        view.setChecked(checked);
        view.setOnCheckedChangeListener((b, on) -> changed.accept(on));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 10);
        view.setLayoutParams(lp);
        return view;
    }

    static void updateText(TextView view, CharSequence value) {
        if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    static void lines(TextView text, int count) {
        text.setMaxLines(count);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    static String name(String icon) {
        return switch (icon) {
            case "▶" -> "재생";
            case "Ⅱ" -> "일시정지";
            case "⏭" -> "다음 곡";
            case "⏮" -> "이전 곡";
            case "⚙" -> "설정";
            case "chevron" -> "재생 화면 닫기";
            case "back" -> "이전 화면";
            case "more" -> "곡 메뉴";
            case "search" -> "검색";
            case "shuffle" -> "무작위 재생";
            case "repeat", "repeat_one" -> "반복 재생";
            default -> icon;
        };
    }

    static void transition(View view, String page, boolean returning) {
        view.animate().cancel();
        view.setTranslationX(0);
        view.setTranslationY(0);
        view.setScaleX(1);
        view.setScaleY(1);
        view.setAlpha(1);
        if (!motion(view.getContext())) return;
        view.setAlpha(0);
        long duration = 160;
        if (page.equals("player")) {
            view.setTranslationY(dp(view.getContext(), 76));
            duration = 290;
        } else if (page.equals("lyrics")) {
            view.setScaleX(.97f);
            view.setScaleY(.97f);
            duration = 260;
        } else if (!java.util.List.of("home", "library", "playlists", "stats", "settings")
                .contains(page)) {
            view.setTranslationX(dp(view.getContext(), returning ? -22 : 22));
            duration = 220;
        }
        view.animate()
                .alpha(1)
                .translationX(0)
                .translationY(0)
                .scaleX(1)
                .scaleY(1)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    static final class IconButton extends androidx.appcompat.widget.AppCompatTextView {
        private int iconColor = MINT;
        private String glyph = "";
        private Icon icon, previous;
        private float blend = 1;
        private android.animation.ValueAnimator morph;

        void iconColor(int color) {
            if (iconColor == color && icon != null) return;
            iconColor = color;
            if (glyph != null && !glyph.isEmpty()) {
                icon = new Icon(glyph, color, dp(getContext(), 24));
                invalidate();
            }
        }

        IconButton(Context c, String glyph) {
            super(c);
            setGravity(Gravity.CENTER);
            setMinHeight(dp(c, 52));
            setPadding(0, 0, 0, 0);
            setBackground(surface(c, SURFACE, 26));
            setText(glyph);
        }

        @Override
        public void setText(CharSequence s, BufferType type) {
            String value = s == null ? "" : s.toString();
            if (java.util.List.of(
                            "▶",
                            "Ⅱ",
                            "⏭",
                            "⏮",
                            "⚙",
                            "chevron",
                            "back",
                            "more",
                            "plus",
                            "search",
                            "sort",
                            "shuffle",
                            "repeat",
                            "repeat_one")
                    .contains(value)) {
                if (value.equals(glyph) && icon != null) return;
                previous = icon;
                glyph = value;
                super.setText("", type);
                setCompoundDrawables(null, null, null, null);
                setContentDescription(name(value));
                icon = new Icon(value, iconColor, dp(getContext(), 24));
                if (morph != null) morph.cancel();
                if (previous != null && motion(getContext())) {
                    blend = 0;
                    morph = android.animation.ValueAnimator.ofFloat(0, 1);
                    morph.setDuration(130);
                    morph.addUpdateListener(
                            a -> {
                                blend = (float) a.getAnimatedValue();
                                invalidate();
                            });
                    morph.start();
                } else {
                    blend = 1;
                    previous = null;
                }
                invalidate();
            } else {
                icon = null;
                super.setText(s, type);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int size = dp(getContext(), 24),
                    left = (getWidth() - size) / 2,
                    top = (getHeight() - size) / 2;
            if (previous != null && blend < 1) {
                previous.setBounds(left, top, left + size, top + size);
                previous.setAlpha((int) ((1 - blend) * 255));
                previous.draw(canvas);
            }
            if (icon != null) {
                icon.setBounds(left, top, left + size, top + size);
                icon.setAlpha((int) (blend * 255));
                icon.draw(canvas);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            if (morph != null) morph.cancel();
            previous = null;
            blend = 1;
            super.onDetachedFromWindow();
        }
    }

    /** Resolution-independent monochrome controls, independent of emoji fonts. */
    static final class Icon extends Drawable {
        private final Paint p = new Paint(3);
        private final String key;
        private final int size;

        Icon(String key, int color, int size) {
            this.key = key;
            this.size = size;
            p.setColor(color);
            p.setStrokeWidth(1.8f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void draw(Canvas c) {
            c.save();
            c.translate(getBounds().left, getBounds().top);
            c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            p.setStyle(Paint.Style.STROKE);
            Path path = new Path();
            switch (key) {
                case "stats" -> {
                    c.drawRoundRect(4, 13, 7, 21, 1, 1, p);
                    c.drawRoundRect(10.5f, 4, 13.5f, 21, 1, 1, p);
                    c.drawRoundRect(17, 9, 20, 21, 1, 1, p);
                }
                case "sort" -> {
                    c.drawLine(5, 6, 19, 6, p);
                    c.drawLine(5, 12, 15, 12, p);
                    c.drawLine(5, 18, 11, 18, p);
                }
                case "plus" -> {
                    c.drawLine(5, 12, 19, 12, p);
                    c.drawLine(12, 5, 12, 19, p);
                }
                case "back" -> {
                    path.moveTo(14, 5);
                    path.lineTo(7, 12);
                    path.lineTo(14, 19);
                    c.drawPath(path, p);
                }
                case "eq", "audio" -> {
                    for (int x = 5; x <= 19; x += 7) c.drawLine(x, 4, x, 20, p);
                    c.drawRoundRect(2, 7, 8, 11, 1, 1, p);
                    c.drawRoundRect(9, 13, 15, 17, 1, 1, p);
                    c.drawRoundRect(16, 6, 22, 10, 1, 1, p);
                }
                case "sox" -> {
                    path.moveTo(2, 12);
                    path.cubicTo(6, 0, 8, 24, 12, 12);
                    path.cubicTo(16, 0, 18, 24, 22, 12);
                    c.drawPath(path, p);
                }
                case "speaker" -> {
                    path.moveTo(3, 9);
                    path.lineTo(7, 9);
                    path.lineTo(12, 5);
                    path.lineTo(12, 19);
                    path.lineTo(7, 15);
                    path.lineTo(3, 15);
                    path.close();
                    c.drawPath(path, p);
                    c.drawArc(11, 7, 21, 17, -60, 120, false, p);
                }
                case "theme" -> {
                    c.drawCircle(12, 12, 8, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawArc(4, 4, 20, 20, 90, 180, true, p);
                }
                case "notification" -> {
                    path.moveTo(5, 16);
                    path.lineTo(7, 13);
                    path.lineTo(7, 8);
                    path.cubicTo(7, 2, 17, 2, 17, 8);
                    path.lineTo(17, 13);
                    path.lineTo(19, 16);
                    path.close();
                    c.drawPath(path, p);
                    c.drawArc(9, 17, 15, 22, 0, 180, false, p);
                }
                case "cloud" -> {
                    path.moveTo(6, 18);
                    path.cubicTo(-1, 18, 1, 8, 7, 9);
                    path.cubicTo(8, 1, 19, 3, 19, 10);
                    path.cubicTo(25, 11, 23, 18, 18, 18);
                    path.close();
                    c.drawPath(path, p);
                }
                case "wifi", "remote" -> {
                    c.drawArc(2, 4, 22, 23, 225, 90, false, p);
                    c.drawArc(6, 9, 18, 23, 225, 90, false, p);
                    c.drawCircle(12, 19, 1, p);
                }
                case "update" -> {
                    c.drawArc(4, 4, 20, 20, 0, 290, false, p);
                    path.moveTo(3, 4);
                    path.lineTo(3, 10);
                    path.lineTo(9, 10);
                    c.drawPath(path, p);
                }
                case "info" -> {
                    c.drawCircle(12, 12, 9, p);
                    c.drawLine(12, 11, 12, 17, p);
                    c.drawCircle(12, 7, 1, p);
                }
                case "search" -> {
                    c.drawCircle(10, 10, 6, p);
                    c.drawLine(14.5f, 14.5f, 20, 20, p);
                }
                case "music" -> {
                    path.moveTo(9, 17);
                    path.lineTo(9, 5);
                    path.lineTo(20, 3);
                    path.lineTo(20, 15);
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawOval(3, 15, 9, 20, p);
                    c.drawOval(14, 13, 20, 18, p);
                }
                case "album" -> {
                    c.drawRoundRect(3, 3, 21, 21, 4, 4, p);
                    c.drawCircle(12, 12, 5, p);
                    c.drawCircle(12, 12, 1, p);
                }
                case "artist" -> {
                    c.drawCircle(12, 7, 4, p);
                    path.moveTo(4, 21);
                    path.cubicTo(4, 12, 20, 12, 20, 21);
                    c.drawPath(path, p);
                }
                case "folder" -> {
                    path.moveTo(3, 8);
                    path.lineTo(3, 5);
                    path.lineTo(10, 5);
                    path.lineTo(13, 8);
                    path.lineTo(21, 8);
                    path.lineTo(21, 20);
                    path.lineTo(3, 20);
                    path.close();
                    c.drawPath(path, p);
                }
                case "chevron" -> {
                    path.moveTo(6, 9);
                    path.lineTo(12, 15);
                    path.lineTo(18, 9);
                    c.drawPath(path, p);
                }
                case "more" -> {
                    p.setStyle(Paint.Style.FILL);
                    for (int x = 5; x <= 19; x += 7) c.drawCircle(x, 12, 1.5f, p);
                }
                case "shuffle" -> {
                    path.moveTo(4, 6);
                    path.cubicTo(10, 6, 14, 18, 20, 18);
                    path.moveTo(17, 15);
                    path.lineTo(20, 18);
                    path.lineTo(17, 21);
                    path.moveTo(4, 18);
                    path.cubicTo(10, 18, 14, 6, 20, 6);
                    path.moveTo(17, 3);
                    path.lineTo(20, 6);
                    path.lineTo(17, 9);
                    c.drawPath(path, p);
                }
                case "repeat", "repeat_one" -> {
                    path.moveTo(5, 9);
                    path.lineTo(5, 7);
                    path.quadTo(5, 5, 7, 5);
                    path.lineTo(20, 5);
                    path.moveTo(17, 2);
                    path.lineTo(20, 5);
                    path.lineTo(17, 8);
                    path.moveTo(19, 15);
                    path.lineTo(19, 17);
                    path.quadTo(19, 19, 17, 19);
                    path.lineTo(4, 19);
                    path.moveTo(7, 16);
                    path.lineTo(4, 19);
                    path.lineTo(7, 22);
                    c.drawPath(path, p);
                    if (key.equals("repeat_one")) {
                        p.setStyle(Paint.Style.FILL);
                        p.setTextSize(9);
                        p.setTypeface(Typeface.DEFAULT_BOLD);
                        c.drawText("1", 10, 15, p);
                    }
                }
                case "lyrics" -> {
                    c.drawRoundRect(4, 3, 20, 21, 3, 3, p);
                    for (int y = 8; y <= 16; y += 4) c.drawLine(8, y, 16, y, p);
                }
                case "queue" -> {
                    for (int y = 5; y <= 13; y += 4) c.drawLine(3, y, 17, y, p);
                    path.moveTo(14, 17);
                    path.lineTo(14, 22);
                    path.lineTo(20, 19.5f);
                    path.close();
                    c.drawPath(path, p);
                }
                case "home" -> {
                    path.moveTo(3, 11);
                    path.lineTo(12, 3);
                    path.lineTo(21, 11);
                    path.moveTo(5, 10);
                    path.lineTo(5, 21);
                    path.lineTo(10, 21);
                    path.lineTo(10, 14);
                    path.lineTo(14, 14);
                    path.lineTo(14, 21);
                    path.lineTo(19, 21);
                    path.lineTo(19, 10);
                    c.drawPath(path, p);
                }
                case "library" -> {
                    c.drawRoundRect(3, 4, 8, 21, 1, 1, p);
                    c.drawRoundRect(11, 4, 16, 21, 1, 1, p);
                    c.drawLine(19, 5, 22, 20, p);
                }
                case "playlists" -> {
                    for (int y = 5; y <= 17; y += 6) c.drawLine(3, y, 15, y, p);
                    c.drawLine(20, 13, 20, 21, p);
                    c.drawLine(16, 17, 24, 17, p);
                }
                case "⚙", "settings" -> {
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        c.drawLine(
                                12 + (float) Math.cos(a) * 8,
                                12 + (float) Math.sin(a) * 8,
                                12 + (float) Math.cos(a) * 10,
                                12 + (float) Math.sin(a) * 10,
                                p);
                    }
                    c.drawCircle(12, 12, 7.5f, p);
                    c.drawCircle(12, 12, 3, p);
                }
                case "▶", "⏭", "⏮" -> {
                    boolean prev = key.equals("⏮");
                    if (prev) {
                        c.translate(24, 0);
                        c.scale(-1, 1);
                    }
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(7, 4);
                    path.lineTo(19, 12);
                    path.lineTo(7, 20);
                    path.close();
                    c.drawPath(path, p);
                    if (!key.equals("▶")) c.drawRoundRect(19, 4, 21, 20, 1, 1, p);
                }
                case "Ⅱ" -> {
                    p.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(6, 4, 10, 20, 1, 1, p);
                    c.drawRoundRect(14, 4, 18, 20, 1, 1, p);
                }
                default -> {
                    c.drawLine(4, 6, 20, 6, p);
                    c.drawLine(4, 12, 20, 12, p);
                    c.drawLine(4, 18, 20, 18, p);
                }
            }
            c.restore();
        }

        @Override
        public void setAlpha(int a) {
            p.setAlpha(a);
        }

        @Override
        public void setColorFilter(ColorFilter f) {
            p.setColorFilter(f);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}

package dev.aura.player;

import android.app.Activity;
import android.content.*;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;

/** A short-lived volume HUD. Background windows require explicit user consent. */
final class UsbVolumePopup {
    static final String IN_APP = "usbVolumePopup", BACKGROUND = "usbVolumeBackgroundPopup";
    static final Handler main = new Handler(Looper.getMainLooper());
    static WeakReference<MainActivity> foreground = new WeakReference<>(null);
    static Context context;
    static View card;
    static TextView value;
    static SeekBar slider;
    static WindowManager windows;
    static boolean overlay;
    static int epoch;
    static final Runnable dismiss = () -> hide(true);

    static boolean enabled(Context c, String key) {
        return c.getSharedPreferences("playback", 0).getBoolean(key, key.equals(IN_APP));
    }

    static void resume(MainActivity a) {
        context = a.getApplicationContext();
        foreground = new WeakReference<>(a);
        hide(false);
    }

    static void pause(MainActivity a) {
        if (foreground.get() == a) {
            foreground.clear();
            hide(false);
        }
    }

    static void change(Context c, int level) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> change(c, level));
            return;
        }
        context = c.getApplicationContext();
        MainActivity a = foreground.get();
        boolean inApp = a != null && !a.isFinishing() && !a.isDestroyed();
        if (!UsbVolume.direct()
                || inApp && (a.musicVolumePlayerVisible() || !enabled(c, IN_APP))
                || !inApp && (!enabled(c, BACKGROUND) || !Settings.canDrawOverlays(c))) {
            hide(false);
            return;
        }
        if (card == null || overlay == inApp) {
            hide(false);
            create(inApp ? a : context, !inApp);
        }
        if (card == null) return;
        main.removeCallbacks(dismiss);
        epoch++;
        card.animate().cancel();
        value.setText((UsbVolume.state.hardware() ? "DAC 볼륨" : "USB 앱 볼륨") + " · " + level + "%");
        slider.setProgress(level);
        slider.setEnabled(UsbVolume.adjustable());
        card.setVisibility(View.VISIBLE);
        if (Ui.motion(c)) {
            card.animate().alpha(1).translationY(0).setDuration(180).start();
        } else {
            card.setAlpha(1);
            card.setTranslationY(0);
        }
        if (inApp) position(a);
        main.postDelayed(dismiss, 3000);
    }

    static void create(Context c, boolean background) {
        overlay = background;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 16);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        box.setBackground(Ui.flatSurface(c, Ui.SURFACE, 20));
        box.setElevation(Ui.dp(c, 10));
        box.setContentDescription("USB DAC 볼륨 조절");
        box.setTag("usb-volume-popup");
        value = Ui.text(c, "", 13, Ui.INK);
        value.setPadding(0, Ui.dp(c, 4), 0, 0);
        box.addView(value);
        slider = new SeekBar(c);
        Ui.tint(slider);
        slider.setMax(100);
        slider.setContentDescription("USB DAC 볼륨");
        box.addView(slider, new LinearLayout.LayoutParams(-1, Ui.dp(c, 40)));
        slider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar b) {
                        main.removeCallbacks(dismiss);
                    }

                    public void onStopTrackingTouch(SeekBar b) {
                        main.removeCallbacks(dismiss);
                        main.postDelayed(dismiss, 3000);
                    }

                    public void onProgressChanged(SeekBar b, int p, boolean user) {
                        if (user) UsbVolume.set(p);
                    }
                });
        card = box;
        card.setAlpha(0);
        card.setTranslationY(Ui.dp(c, 10));
        int width =
                Math.min(
                        Ui.dp(c, 340),
                        c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 32));
        try {
            if (background) {
                windows = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                WindowManager.LayoutParams lp =
                        new WindowManager.LayoutParams(
                                width,
                                -2,
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                                PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.y = Ui.dp(c, 96);
                lp.setTitle("AURA USB DAC 볼륨");
                windows.addView(box, lp);
            } else {
                FrameLayout decor = (FrameLayout) ((Activity) c).getWindow().getDecorView();
                decor.addView(
                        box,
                        new FrameLayout.LayoutParams(
                                width, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            }
        } catch (RuntimeException denied) {
            remove();
        }
    }

    static void position(MainActivity a) {
        if (card == null || overlay) return;
        View anchor = a.musicVolumeAnchor();
        int[] point = new int[2], origin = new int[2];
        anchor.getLocationOnScreen(point);
        View decor = a.getWindow().getDecorView();
        decor.getLocationOnScreen(origin);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.leftMargin =
                Math.max(
                        Ui.dp(a, 16),
                        Math.min(
                                decor.getWidth() - lp.width - Ui.dp(a, 16),
                                point[0] - origin[0] + anchor.getWidth() / 2 - lp.width / 2));
        lp.bottomMargin =
                Math.max(Ui.dp(a, 16), decor.getHeight() - (point[1] - origin[1]) + Ui.dp(a, 10));
        card.setLayoutParams(lp);
    }

    static void hide(boolean animate) {
        main.removeCallbacks(dismiss);
        if (card == null) return;
        int token = ++epoch;
        card.animate().cancel();
        if (animate && context != null && Ui.motion(context))
            card.animate()
                    .alpha(0)
                    .translationY(Ui.dp(context, 10))
                    .setDuration(220)
                    .withEndAction(
                            () -> {
                                if (token == epoch) remove();
                            })
                    .start();
        else remove();
    }

    static void remove() {
        if (card != null) {
            card.animate().cancel();
            try {
                if (overlay && windows != null) windows.removeViewImmediate(card);
                else if (card.getParent() instanceof ViewGroup p) p.removeView(card);
            } catch (RuntimeException ignored) {
            }
        }
        card = null;
        slider = null;
        value = null;
        windows = null;
    }

    static void routeChanged() {
        if (!UsbVolume.direct() || !UsbVolume.access.connected()) main.post(() -> hide(false));
    }

    static void backgroundSetting(Activity a, boolean on, Runnable refresh) {
        if (!on) {
            a.getSharedPreferences("playback", 0).edit().putBoolean(BACKGROUND, false).apply();
            hide(false);
            refresh.run();
            return;
        }
        if (Settings.canDrawOverlays(a)) {
            a.getSharedPreferences("playback", 0).edit().putBoolean(BACKGROUND, true).apply();
            refresh.run();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("다른 앱 위에 볼륨 표시")
                .setMessage(
                        "USB DAC 직접 출력 중 다른 앱을 사용해도 AURA 볼륨 창을 잠깐 표시하려면 ‘다른 앱 위에 표시’ 권한이 필요합니다.\n\n허용 후 오디오 설정에서 백그라운드 볼륨 팝업을 다시 켜 주세요. 허용하지 않아도 앱 안에서는 사용할 수 있습니다.")
                .setPositiveButton(
                        "권한 설정 열기",
                        (d, w) -> {
                            try {
                                a.startActivity(
                                        new Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:" + a.getPackageName())));
                            } catch (ActivityNotFoundException e) {
                                Toast.makeText(a, "이 기기에서는 권한 설정을 열 수 없습니다.", Toast.LENGTH_LONG)
                                        .show();
                            }
                        })
                .setNegativeButton("취소", null)
                .setOnDismissListener(d -> refresh.run())
                .show();
    }
}

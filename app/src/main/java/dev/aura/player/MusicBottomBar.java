package dev.aura.player;

import android.content.Context;
import android.view.*;
import android.widget.*;

/** Uses window proportions rather than device names, including tall folded displays. */
final class MusicBottomBar extends LinearLayout {
    final View nav, mini;
    boolean wide, fullNavigation;

    MusicBottomBar(Context c, View nav, View mini) {
        super(c);
        this.nav = nav;
        this.mini = mini;
        setTag("music-bottom-bar");
    }

    static boolean wide(int width, int height, float density) {
        return width >= 700 * density && height < width * 1.6f;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        View root = getRootView();
        boolean next =
                !fullNavigation
                        && wide(
                                width,
                                root.getHeight() > 0
                                        ? root.getHeight()
                                        : getResources().getDisplayMetrics().heightPixels,
                                getResources().getDisplayMetrics().density);
        if (getChildCount() == 0 || wide != next) {
            wide = next;
            removeAllViews();
            setOrientation(wide ? HORIZONTAL : VERTICAL);
            setGravity(Gravity.CENTER_VERTICAL);
            if (wide) {
                addView(nav);
                addView(mini);
            } else {
                addView(mini);
                addView(nav);
            }
        }
        int left = width * 5 / 8;
        params(nav, wide ? left : -1);
        params(mini, wide ? width - left : -1);
        mini.setPadding(
                Ui.dp(getContext(), wide ? 4 : 14),
                Ui.dp(getContext(), 8),
                Ui.dp(getContext(), wide ? 8 : 14),
                wide ? Ui.dp(getContext(), 8) : 0);
        super.onMeasure(w, h);
    }

    void params(View view, int width) {
        ViewGroup.LayoutParams old = view.getLayoutParams();
        if (old == null || old.width != width || old.height != -2)
            view.setLayoutParams(new LayoutParams(width, -2));
    }
}

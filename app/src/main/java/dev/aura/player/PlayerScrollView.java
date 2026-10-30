package dev.aura.player;

import android.content.Context;
import android.view.*;
import android.view.animation.*;

import androidx.core.widget.NestedScrollView;

/**
 * The cover/header can be pulled down to dismiss; sliders and scrolled content keep their gestures.
 */
final class PlayerScrollView extends NestedScrollView {
    private Runnable dismiss;
    private boolean eligible, dragging, settling, cancelScroll;
    private float startX, startY, offset;
    private boolean childOwnsDrag;
    private int regionBottom, epoch;
    private VelocityTracker velocity;
    private java.util.function.Consumer<MotionEvent> interaction;

    void interaction(java.util.function.Consumer<MotionEvent> listener) {
        interaction = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (interaction != null) interaction.accept(event);
        return super.dispatchTouchEvent(event);
    }

    PlayerScrollView(Context c) {
        super(c);
    }

    void stopScroll() {
        smoothScrollTo(0, getScrollY(), 0);
        fling(0);
    }

    void dismissHandler(Runnable handler) {
        cancelMotion();
        dismiss = handler;
        regionBottom = 0;
    }

    void dismissRegion(int bottom) {
        regionBottom = bottom;
    }

    private void recycle() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private void cancelMotion() {
        epoch++;
        animate().cancel();
        setTranslationY(0);
        setAlpha(1);
        eligible = dragging = settling = false;
        offset = 0;
        cancelScroll = false;
        recycle();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (settling) cancelMotion();
            childOwnsDrag = ownsDrag(this, e.getRawX(), e.getRawY());
            startX = e.getRawX();
            startY = e.getRawY();
            offset = 0;
            eligible = dismiss != null && getScrollY() == 0 && e.getY() <= regionBottom;
            recycle();
            if (eligible) {
                velocity = VelocityTracker.obtain();
                velocity.addMovement(e);
            }
        }
        if (dragging) return true;
        if (action == MotionEvent.ACTION_MOVE && eligible) {
            if (beginDrag(e)) return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            eligible = false;
            recycle();
        }
        if (action == MotionEvent.ACTION_MOVE && !childOwnsDrag && !dragging) {
            float dx = e.getRawX() - startX, dy = e.getRawY() - startY;
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.abs(dy) > slop
                    && Math.abs(dy) > Math.abs(dx)
                    && canScrollVertically(dy < 0 ? 1 : -1)) return true;
        }
        return super.onInterceptTouchEvent(e);
    }

    private boolean ownsDrag(View view, float x, float y) {
        if (view.getVisibility() != VISIBLE) return false;
        android.graphics.Rect bounds = new android.graphics.Rect();
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains((int) x, (int) y)) return false;
        if (view != this
                && (view instanceof androidx.core.widget.NestedScrollView
                        || view instanceof android.widget.ScrollView
                        || view instanceof androidx.recyclerview.widget.RecyclerView
                        || view instanceof android.widget.SeekBar
                        || view instanceof EqGraph)) return true;
        if (view instanceof ViewGroup group)
            for (int i = group.getChildCount() - 1; i >= 0; i--)
                if (ownsDrag(group.getChildAt(i), x, y)) return true;
        return false;
    }

    private boolean beginDrag(MotionEvent e) {
        float dx = e.getRawX() - startX, dy = e.getRawY() - startY;
        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) eligible = false;
        else if (dy > slop * 2f && dy > Math.abs(dx) * 1.3f) {
            dragging = cancelScroll = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging && eligible && e.getActionMasked() == MotionEvent.ACTION_MOVE) beginDrag(e);
        if (!dragging) return super.onTouchEvent(e);
        if (cancelScroll) {
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.onTouchEvent(cancel);
            cancel.recycle();
            cancelScroll = false;
        }
        if (velocity != null) velocity.addMovement(e);
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            offset = Math.max(0, Math.min(getHeight(), e.getRawY() - startY));
            setTranslationY(offset);
            setAlpha(1 - .16f * offset / Math.max(1, getHeight()));
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP
                || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            float speed = 0;
            if (velocity != null) {
                velocity.computeCurrentVelocity(1000);
                speed = velocity.getYVelocity();
            }
            boolean close =
                    e.getActionMasked() != MotionEvent.ACTION_CANCEL
                            && (offset > Math.max(Ui.dp(getContext(), 90), getHeight() * .18f)
                                    || offset > Ui.dp(getContext(), 64) && speed > 1800);
            recycle();
            dragging = eligible = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            settle(close);
            return true;
        }
        return true;
    }

    private void settle(boolean close) {
        Runnable callback = dismiss;
        if (!Ui.motion(getContext())) {
            cancelMotion();
            if (close && callback != null) callback.run();
            return;
        }
        settling = true;
        int generation = epoch;
        animate()
                .translationY(close ? getHeight() : 0)
                .alpha(close ? 0 : 1)
                .setDuration(close ? 190 : 240)
                .setInterpolator(
                        close ? new AccelerateInterpolator() : new OvershootInterpolator(.65f))
                .withEndAction(
                        () -> {
                            if (epoch != generation) return;
                            cancelMotion();
                            if (close && callback != null) callback.run();
                        })
                .start();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelMotion();
        super.onDetachedFromWindow();
    }
}

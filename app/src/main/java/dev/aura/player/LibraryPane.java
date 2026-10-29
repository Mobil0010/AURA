package dev.aura.player;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/** Keeps the list viewport stable while the header follows a finger using translations. */
final class LibraryPane extends FrameLayout {
    private final LibraryHeader header;
    private final View content;
    private View overlay;

    LibraryPane(Context c, LibraryHeader header, View content) {
        super(c);
        this.header = header;
        this.content = content;
        setClipChildren(true);
        addView(content);
        addView(header);
    }

    void overlay(View view) {
        overlay = view;
        addView(view);
    }

    void followHeader() {
        content.setTranslationY(
                header.getVisibility() == VISIBLE
                        ? Math.max(0, header.visibleHeight() - Ui.dp(getContext(), 48))
                        : 0);
        invalidate();
    }

    @Override
    protected void onMeasure(int width, int height) {
        int w = MeasureSpec.getSize(width), h = MeasureSpec.getSize(height);
        boolean library = header.getVisibility() == VISIBLE;
        if (library) header.measure(width, MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        content.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(
                        Math.max(0, h - (library ? Ui.dp(getContext(), 48) : 0)),
                        MeasureSpec.EXACTLY));
        if (overlay != null && overlay.getVisibility() != GONE)
            overlay.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int top = header.getVisibility() == VISIBLE ? Ui.dp(getContext(), 48) : 0;
        content.layout(0, top, r - l, b - t);
        if (header.getVisibility() == VISIBLE)
            header.layout(0, 0, r - l, header.getMeasuredHeight());
        if (overlay != null && overlay.getVisibility() != GONE) overlay.layout(0, 0, r - l, b - t);
        followHeader();
        header.settled();
    }
}

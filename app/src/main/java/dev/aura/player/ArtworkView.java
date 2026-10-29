package dev.aura.player;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;

/** Aspect-preserving covers inside the existing size limits. All source reads and decoding stay on one bounded worker. */
final class ArtworkView extends FrameLayout {
    private static final android.util.LruCache<String, Bitmap> CACHE =
            new android.util.LruCache<>(4 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getAllocationByteCount();
                }
            };
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORKER =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(48),
                    task -> {
                        Thread thread =
                                new Thread(
                                        () -> {
                                            android.os.Process.setThreadPriority(
                                                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
                                            task.run();
                                        },
                                        "AURA covers");
                        return thread;
                    },
                    (task, pool) -> {
                        Runnable old = pool.getQueue().poll();
                        if (old instanceof Future<?> future) future.cancel(false);
                        pool.execute(task);
                    });
    private static final ThreadPoolExecutor UPGRADES =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(48),
                    task -> {
                        Thread thread =
                                new Thread(
                                        () -> {
                                            android.os.Process.setThreadPriority(
                                                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
                                            task.run();
                                        },
                                        "AURA cover sources");
                        return thread;
                    },
                    (task, pool) -> {
                        pool.getQueue().poll();
                        pool.execute(task);
                    });
    private static final android.util.LruCache<String, Boolean>
            MISSING = new android.util.LruCache<>(256),
            ATTEMPTED = new android.util.LruCache<>(256);

    static void clearCache() {
        CACHE.evictAll();
        MISSING.evictAll();
        ATTEMPTED.evictAll();
    }

    private final boolean folderFallback;
    private final int desired, sizeDp, maxDecode;
    private final ImageView image;
    private final TextView label;
    private volatile int token;
    private Future<?> request;
    private Track current;
    private String boundKey = "";
    private int backgroundHash;
    private boolean hasBackground;
    private android.graphics.drawable.Drawable placeholder;
    private int sourceWidth, sourceHeight;

    ArtworkView(Context c, Track track, int sizeDp) {
        this(c, track, sizeDp, false);
    }

    ArtworkView(Context c, Track track, int sizeDp, boolean folderFallback) {
        super(c);
        this.folderFallback = folderFallback;
        this.sizeDp = sizeDp;
        desired = Ui.dp(c, sizeDp);
        var budget = PlaybackBudget.get(c);
        if (CACHE.maxSize() != budget.artworkBytes()) CACHE.resize(budget.artworkBytes());
        maxDecode = budget.constrained() ? 1024 : 2048;
        setClipToOutline(true);
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(Ui.shape(c, Ui.SURFACE, Math.min(16, sizeDp / 4)));
        image.setClipToOutline(true);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(image, new LayoutParams(-1, -1));
        label = Ui.text(c, "", sizeDp <= 48 ? 16 : 18, 0xfff3f4fa);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(3);
        label.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(label, new LayoutParams(-1, -1));
        bind(track);
    }

    static int decodeEdge(int pixels, boolean low) {
        int edge = 128;
        while (edge < pixels && edge < 2048) edge *= 2;
        return Math.min(low ? 1024 : 2048, edge);
    }

    void bind(Track track) {
        int edge =
                folderFallback
                        ? Math.max(64, Math.min(256, getWidth() > 0 ? getWidth() : desired))
                        : decodeEdge(
                                getWidth() > 0 ? Math.max(getWidth(), getHeight()) : desired,
                                maxDecode == 1024);
        String
                revision =
                        LibraryScanService.state.revision()
                                + "|"
                                + DriveLibrary.revision
                                + "|"
                                + Library.revision,
                key = track.uri + "|" + edge + "|" + revision;
        if (key.equals(boundKey)
                && (image.getDrawable() != null || request != null && !request.isDone())) {
            current = track;
            return;
        }
        boundKey = key;
        current = track;
        int generation = ++token;
        if (request != null) request.cancel(false);
        WORKER.purge();
        setContentDescription(track.album + " 앨범아트");
        int hash = track.album.hashCode();
        if (!hasBackground || backgroundHash != hash) {
            hasBackground = true;
            backgroundHash = hash;
            GradientDrawable background =
                    new GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            new int[] {
                                Color.rgb(
                                        45 + (hash & 31),
                                        54 + ((hash >> 5) & 31),
                                        70 + ((hash >> 10) & 31)),
                                Color.rgb(124, 91, 102),
                                Color.rgb(34, 47, 62)
                            });
            background.setCornerRadius(Ui.dp(getContext(), Math.min(16, sizeDp / 4)));
            placeholder = folderFallback ? Ui.shape(getContext(), Ui.SOFT, 14) : background;
        }
        setBackground(placeholder);
        setClipToOutline(true);
        sourceWidth = sourceHeight = 0;
        image.setVisibility(INVISIBLE);
        if (folderFallback) {
            if (label.getBackground() == null)
                label.setBackground(
                        new android.graphics.drawable.InsetDrawable(
                                new Ui.Icon("folder", Ui.MINT, Ui.dp(getContext(), 24)),
                                Ui.dp(getContext(), 14)));
            label.setText("");
        } else label.setText(sizeDp <= 80 ? "♫" : "♫\n" + track.album);
        image.setImageDrawable(null);
        label.setVisibility(VISIBLE);
        boolean large = sizeDp >= 160;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            show(cached);
            return;
        }
        if (MISSING.get(key) != null && !large && !folderFallback) return;
        WeakReference<ArtworkView> reference = new WeakReference<>(this);
        Context app = getContext().getApplicationContext();
        CoverRequest load =
                new CoverRequest(track, edge, revision, key, generation, large, reference, app);
        request = WORKER.submit(() -> loadCover(load));
    }

    private record CoverRequest(
            Track track,
            int edge,
            String revision,
            String key,
            int generation,
            boolean large,
            WeakReference<ArtworkView> reference,
            Context context) {}

    private boolean isCurrentRequest(CoverRequest load) {
        ArtworkView view = load.reference.get();
        return view != null && view.token == load.generation;
    }

    private void loadCover(CoverRequest load) {
        if (!isCurrentRequest(load)) {
            return;
        }
        ArtworkStore.restoreDriveCover(load.context, load.track.uri);
        File file = Library.artFile(load.context, load.track.uri);
        Bitmap bitmap = CACHE.get(load.key);
        if (bitmap == null && file.isFile()) {
            bitmap = ArtworkStore.file(file, load.edge);
        }
        if (bitmap != null) {
            remember(load.key, bitmap);
            deliver(load.reference, load.generation, bitmap);
        } else if (!file.isFile()) {
            MISSING.put(load.key, true);
            if (!load.large && !folderFallback) {
                return;
            }
        }

        String attempt =
                (folderFallback ? "folder|" : "original|") + load.track.uri + "|" + load.revision;
        boolean upgradeNeeded =
                load.large && !ArtworkStore.current(load.context, load.track.uri)
                        || folderFallback && !file.isFile();
        if (upgradeNeeded
                && !LibraryScanService.state.running()
                && ATTEMPTED.get(attempt) == null) {
            UPGRADES.execute(() -> upgradeCover(load, file, attempt));
        }
    }

    private void upgradeCover(CoverRequest load, File file, String attempt) {
        try {
            if (!isCurrentRequest(load) || ATTEMPTED.get(attempt) != null) {
                return;
            }
            ATTEMPTED.put(attempt, true);
            refreshCoverSource(load.context, load.track);
            if (ArtworkStore.current(load.context, load.track.uri)) {
                WORKER.submit(() -> showUpgradedCover(load, file));
            }
        } catch (Exception optionalSourceUnavailable) {
            // Retain the cached cover offline or after permission loss.
        } catch (OutOfMemoryError exhausted) {
            CACHE.evictAll();
        }
    }

    private void refreshCoverSource(Context context, Track track) throws Exception {
        if (!folderFallback) {
            ArtworkStore.refresh(context, track);
        } else if (track.drive()) {
            DriveLibrary.cacheFolderArtwork(context, track);
        } else if (Library.expectsEmbedded(context, track)) {
            ArtworkStore.refresh(context, track);
        } else {
            LocalFolderArtwork.refresh(context, track);
        }
    }

    private void showUpgradedCover(CoverRequest load, File file) {
        if (!isCurrentRequest(load)) {
            return;
        }
        Bitmap upgraded = ArtworkStore.file(file, load.edge);
        if (upgraded == null) {
            return;
        }
        for (String old : CACHE.snapshot().keySet()) {
            if (old.startsWith(load.track.uri + "|")) {
                CACHE.remove(old);
            }
        }
        MISSING.evictAll();
        remember(load.key, upgraded);
        deliver(load.reference, load.generation, upgraded);
    }

    private static void remember(String key, Bitmap bitmap) {
        if (bitmap.getAllocationByteCount() <= CACHE.maxSize()) CACHE.put(key, bitmap);
    }

    private static void deliver(
            WeakReference<ArtworkView> reference, int generation, Bitmap bitmap) {
        MAIN.post(
                () -> {
                    ArtworkView target = reference.get();
                    if (target != null && target.token == generation) target.show(bitmap);
                });
    }

    private void show(Bitmap bitmap) {
        sourceWidth = bitmap.getWidth();
        sourceHeight = bitmap.getHeight();
        image.setImageBitmap(bitmap);
        image.setVisibility(VISIBLE);
        setBackground(null);
        setClipToOutline(false);
        fitImage();
        if (image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable drawable) {
            drawable.setFilterBitmap(true);
            drawable.setDither(true);
        }
        label.setVisibility(GONE);
    }

    static int[] fit(int maxWidth, int maxHeight, int width, int height) {
        if (maxWidth <= 0 || maxHeight <= 0 || width <= 0 || height <= 0)
            return new int[] {Math.max(0, maxWidth), Math.max(0, maxHeight)};
        double scale = Math.min(maxWidth / (double) width, maxHeight / (double) height);
        return new int[] {
            Math.max(1, Math.min(maxWidth, (int) Math.round(width * scale))),
            Math.max(1, Math.min(maxHeight, (int) Math.round(height * scale)))
        };
    }

    private void fitImage() {
        if (sourceWidth <= 0 || sourceHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        int[] size = fit(getWidth(), getHeight(), sourceWidth, sourceHeight);
        int left = (getWidth() - size[0]) / 2, top = (getHeight() - size[1]) / 2;
        image.layout(left, top, left + size[0], top + size[1]);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        fitImage();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (current != null && w > 0 && w != oldw) bind(current);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (current != null && image.getDrawable() == null && (request == null || request.isDone()))
            bind(current);
    }

    @Override
    protected void onDetachedFromWindow() {
        token++;
        if (request != null) request.cancel(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = resolveSize(desired, widthSpec);
        int height =
                MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                        ? MeasureSpec.getSize(heightSpec)
                        : width;
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.AT_MOST)
            height = Math.min(height, MeasureSpec.getSize(heightSpec));
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    ImageView image() {
        return image;
    }
}

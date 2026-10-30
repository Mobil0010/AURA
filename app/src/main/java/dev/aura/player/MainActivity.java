package dev.aura.player;

import android.Manifest;
import android.app.*;
import androidx.appcompat.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.*;
import java.util.concurrent.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends androidx.appcompat.app.AppCompatActivity {
    private int bg, panel, mint, ink, muted;
    private String restoredScrollPage = "", restoredScrollTrack = "";
    private int restoredScrollOffset;
    private FeatureUi featureUi;
    private AdaptiveMusicPane adaptivePane;
    private WidePlayerView widePlayer;
    private DockPlayerView dockPlayer;
    private MusicBottomBar bottomBar;
    private LinearLayout browseSurface;
    private boolean dockOpened, dockFull, dockLyrics;
    private androidx.window.java.layout.WindowInfoTrackerCallbackAdapter windowTracker;
    private final androidx.core.util.Consumer<androidx.window.layout.WindowLayoutInfo>
            foldListener =
                    info -> {
                        android.graphics.Rect hinge = new android.graphics.Rect();
                        boolean horizontal = false;
                        for (var feature : info.getDisplayFeatures())
                            if (feature instanceof androidx.window.layout.FoldingFeature fold
                                    && fold.isSeparating()) {
                                hinge = fold.getBounds();
                                horizontal =
                                        fold.getOrientation()
                                                == androidx.window.layout.FoldingFeature.Orientation
                                                        .HORIZONTAL;
                                break;
                            }
                        if (adaptivePane != null) adaptivePane.fold(hinge, horizontal);
                    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Track cachedTrack;
    private boolean cachedTrackFallback;
    private List<Track> cachedTrackList;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService searches = Executors.newSingleThreadExecutor();
    private Future<?> searchTask;
    private int searchGeneration, renderGeneration;
    private boolean searchOpen;
    private LibraryPane libraryPane;
    private StatsUi statsUi;
    private DriveUi driveUi;
    private String folderCursor = "";
    private String source = "all";
    private long driveGeneration = -1;
    private MediaController player;
    private ListenableFuture<MediaController> future;
    private boolean libraryLoading = true;
    private androidx.recyclerview.widget.RecyclerView activeList;
    private String activeListKey = "", renderedKey = "";
    private final Bundle listStates = new Bundle();
    private final Map<String, Integer> pageOffsets = new HashMap<>();
    private long lastRouteUpdate;
    private long hiddenScanRevision = Long.MIN_VALUE;
    private String scanMessage = "";
    private long scanFinishedAt;
    private TextView scanDismiss;
    private ProgressBar miniProgress;
    private String miniArtKey = "";
    private FrameLayout miniArt;
    private boolean resetLyricsScroll;
    private String lyricsRenderedTrack = "";
    private boolean resetPlayerScroll, usbPerfectRequested, returning;
    private UpdateUi updates;
    private Library library;
    private List<Track> tracks = new ArrayList<>(), groupTracks;
    private LibraryHeader libraryChrome;
    private LinearLayout libraryExpanded, libraryThin;
    private boolean libraryCollapsed;
    private float headerFraction;
    private LinearLayout root, body, mini, nav;
    private PlayerScrollView scroll;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private ArrayList<String> restoredGroup;
    private long lyricsManualUntil;
    private String renderedPage = "";
    private TextView errorNotice;
    private String page = "home",
            tab = "곡",
            query = "",
            group = "",
            lyricsKey = "",
            lyricsSource = "",
            lyricsMessage = "";
    private List<Lrc.Line> lyricLines = List.of();
    private List<TextView> lyricViews = new ArrayList<>();
    private int activeLine = -2, lyricGeneration = 0;
    private TextView miniTitle,
            miniDetail,
            playButton,
            timeView,
            durationView,
            routeView,
            engineView,
            shuffleButton,
            repeatButton;
    private SeekBar positionBar;
    private boolean dragging = false, scanning = false;
    private long lyricOffset;
    private android.content.SharedPreferences prefs;
    private String lyricsText = "";
    private Track requestedLyrics;
    private NetworkPlayback network;
    private boolean onlineScan = false;
    private final AudioDeviceCallback devices =
            new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] d) {
                    updateRoute();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] d) {
                    updateRoute();
                }
            };
    private SeekBar usbVolumeBar;
    private TextView usbVolumeLabel;
    private boolean usbVolumeDragging;
    private TextView signalSummary;
    private SignalFormat renderedSignal;
    private Boolean renderedSignalRemote;
    private TextView lyricsStatus;
    private TextView scanStatus, lyricPreview;
    private ProgressBar scanProgress;
    private LinearLayout scanPanel;
    private long scanRevision = -1;
    private boolean scanLoading;
    private FrameLayout lyricsChrome;
    private LinearLayout lyricsTop, lyricsBottom;
    private TextView lyricsPlay, lyricsTimingLabel;
    private boolean lyricsChromeShown, lyricsFollowPending;
    private List<KaraokeTiming.Timed> lyricTimings = List.of();
    private final Runnable hideLyricsChrome = this::fadeLyricsChrome;
    private boolean uiStarted, lyricsFramesRunning;
    private long lastLyricsFrame;
    private final Runnable lyricsFrames =
            new Runnable() {
                public void run() {
                    if (!lyricsFramesRunning) return;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastLyricsFrame
                            >= (PlaybackBudget.get(MainActivity.this).constrained() ? 33 : 16)) {
                        lastLyricsFrame = now;
                        boolean remote = network != null && network.active();
                        updateLyrics(
                                Math.max(
                                        0,
                                        remote
                                                ? network.position()
                                                : player == null
                                                        ? 0
                                                        : player.getCurrentPosition()));
                    }
                    scroll.postOnAnimation(this);
                }
            };

    private void syncLyricsFrames() {
        boolean wanted =
                uiStarted
                        && page.equals("lyrics")
                        && (player != null && player.isPlaying()
                                || network != null && network.active() && network.playing());
        if (wanted == lyricsFramesRunning) return;
        lyricsFramesRunning = wanted;
        scroll.removeCallbacks(lyricsFrames);
        if (wanted) {
            lastLyricsFrame = 0;
            scroll.postOnAnimation(lyricsFrames);
        }
    }

    private final Runnable tick =
            new Runnable() {
                public void run() {
                    updateScan();
                    updatePlayback();
                    boolean low = PlaybackBudget.get(MainActivity.this).constrained(),
                            playing =
                                    player != null && player.isPlaying()
                                            || network != null
                                                    && network.active()
                                                    && network.playing();
                    handler.postDelayed(
                            this,
                            page.equals("lyrics") && playing
                                    ? (low ? 400 : 250)
                                    : page.equals("player") && playing
                                            ? (low ? 200 : 100)
                                            : (low ? 600 : 300));
                }
            };
    private final Player.Listener listener =
            new Player.Listener() {
                @Override
                public void onEvents(Player p, Player.Events e) {
                    if (e.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
                        miniArtKey = "";
                        if (cachedTrackFallback) cachedTrack = null;
                    }
                    if (e.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                        resetPlayerScroll = true;
                        resetLyricsScroll = true;
                        lyricsKey = "";
                        if (page.equals("player") || page.equals("lyrics")) render();
                    }
                    if (page.equals("queue")
                            && e.containsAny(
                                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                                    Player.EVENT_TIMELINE_CHANGED)) render();
                    updatePlayback();
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    PcmSink.status =
                            PcmSink.lastFailure.isEmpty()
                                    ? "재생 실패 · " + error.getErrorCodeName()
                                    : "출력 연결 실패";
                    toast(audioError(error));
                    if (page.equals("player")) render();
                }
            };

    @Override
    public void onCreate(Bundle state) {
        Ui.configure(this);
        setTheme(Ui.dark ? R.style.Theme_Aura : R.style.Theme_Aura_Light);
        bg = Ui.BG;
        panel = Ui.SURFACE;
        mint = Ui.MINT;
        ink = Ui.INK;
        muted = Ui.MUTED;
        super.onCreate(state);
        updates = new UpdateUi(this);
        statsUi = new StatsUi(this, () -> page.equals("stats"));
        statsUi.restore(state);
        prefs = getSharedPreferences("aura", MODE_PRIVATE);
        dockFull = prefs.getBoolean("widePlayerFullscreen", false);
        dockOpened = state != null && state.getBoolean("dockOpened", false);
        dockLyrics = state != null && state.getBoolean("dockLyrics", false);
        featureUi = new FeatureUi(this);
        windowTracker =
                new androidx.window.java.layout.WindowInfoTrackerCallbackAdapter(
                        androidx.window.layout.WindowInfoTracker.getOrCreate(this));
        tab = prefs.getString("lastLibraryTab", "곡");
        source = prefs.getString("lastLibrarySource", "all");
        folderCursor =
                prefs.getString("lastFolderCursor." + source, source.equals("all") ? "" : source);
        AudioSettings.init(this);
        network = NetworkPlayback.instance;
        library = new Library(this);
        driveGeneration = DriveLibrary.revision;
        driveUi = new DriveUi(this, this::reloadLibrary, this::render, () -> page.equals("drive"));
        scanRevision = LibraryScanService.state.revision();
        lyricOffset = prefs.getLong("lyricOffset", 0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        androidx.core.view.WindowInsetsControllerCompat bars =
                new androidx.core.view.WindowInsetsControllerCompat(
                        getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!Ui.dark);
        bars.setAppearanceLightNavigationBars(!Ui.dark);
        if (state != null) {
            restoredScrollPage = state.getString("scrollPage", "");
            restoredScrollTrack = state.getString("scrollTrack", "");
            restoredScrollOffset = state.getInt("pageScroll", 0);
            Bundle savedLists = state.getBundle("listStates");
            if (savedLists != null) listStates.putAll(savedLists);
            usbPerfectRequested = state.getBoolean("usbPerfectRequested", false);
            page = state.getString("page", "home");
            tab = state.getString("tab", "곡");
            query = state.getString("query", "");
            source = state.getString("source", "all");
            folderCursor = state.getString("folderCursor", "");
            group = state.getString("group", "");
            restoredGroup = state.getStringArrayList("groupUris");
            ArrayList<String> saved = state.getStringArrayList("history");
            if (saved != null) history.addAll(saved);
        }
        if (getIntent().getBooleanExtra("libraryProgress", false)) page = "library";
        scanning = LibraryScanService.state.running();
        buildShell();
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new androidx.activity.OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                back();
                            }
                        });
        render();
        future =
                new MediaController.Builder(
                                this,
                                new SessionToken(
                                        this, new ComponentName(this, PlaybackService.class)))
                        .buildAsync();
        future.addListener(
                () -> {
                    if (isDestroyed()) return;
                    try {
                        player = future.get();
                        player.addListener(listener);
                        restoreQueue();
                        render();
                    } catch (Exception e) {
                        toast("재생 서비스 연결 실패");
                    }
                },
                r -> handler.post(r));
        worker.execute(
                () -> {
                    List<Track> loaded = loadMusic();
                    onUi(
                            () -> {
                                tracks = loaded;
                                libraryLoading = false;
                                if (restoredGroup != null) {
                                    groupTracks = new ArrayList<>();
                                    for (String uri : restoredGroup)
                                        for (Track t : tracks)
                                            if (t.uri.equals(uri)) {
                                                groupTracks.add(t);
                                                break;
                                            }
                                    restoredGroup = null;
                                }
                                restoreQueue();
                                render();
                            });
                });
        ((AudioManager) getSystemService(AUDIO_SERVICE))
                .registerAudioDeviceCallback(devices, handler);
        IntentFilter usbFilter = new IntentFilter("dev.aura.player.USB_PERMISSION");
        androidx.core.content.ContextCompat.registerReceiver(
                this,
                usbPermission,
                usbFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        if (state == null
                && !getIntent().getBooleanExtra("libraryProgress", false)
                && prefs.getBoolean("scanOnLaunch", false)
                && !LibraryScanService.state.running()
                && (!Library.roots(this).isEmpty() || !DriveLibrary.roots(this).isEmpty()))
            startScan("all", false);
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        statsUi.save(out);
        out.putBoolean("dockOpened", dockOpened);
        out.putBoolean("dockLyrics", dockLyrics);
        saveListPosition();
        out.putString("scrollPage", page);
        out.putString("scrollTrack", current() == null ? "" : current().uri);
        out.putInt("pageScroll", scroll == null ? 0 : scroll.getScrollY());
        out.putBundle("listStates", listStates);
        out.putBoolean("usbPerfectRequested", usbPerfectRequested);
        out.putString("page", page);
        out.putString("tab", tab);
        out.putString("query", query);
        out.putString("source", source);
        out.putString("folderCursor", folderCursor);
        out.putString("group", group);
        out.putStringArrayList("history", new ArrayList<>(history));
        if (groupTracks != null)
            out.putStringArrayList(
                    "groupUris",
                    new ArrayList<>(
                            groupTracks.stream()
                                    .map(t -> t.uri)
                                    .collect(java.util.stream.Collectors.toList())));
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        root.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets i =
                                insets.getInsets(
                                        WindowInsets.Type.systemBars()
                                                | WindowInsets.Type.displayCutout());
                        int bottom =
                                Math.max(
                                        i.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
                        v.setPadding(i.left, i.top, i.right, bottom);
                    } else
                        v.setPadding(
                                insets.getSystemWindowInsetLeft(),
                                insets.getSystemWindowInsetTop(),
                                insets.getSystemWindowInsetRight(),
                                insets.getSystemWindowInsetBottom());
                    return insets;
                });
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(root);
        root.requestApplyInsets();
        scanPanel = column();
        scanPanel.setPadding(dp(22), dp(8), dp(22), dp(8));
        scanStatus = text("", 12, mint);
        LinearLayout scanLine = row();
        scanLine.addView(scanStatus, new LinearLayout.LayoutParams(0, -2, 1));
        scanDismiss = text("×", 22, muted);
        scanDismiss.setGravity(Gravity.CENTER);
        scanDismiss.setContentDescription("라이브러리 검색 안내 닫기");
        Ui.tap(
                scanDismiss,
                () -> {
                    hiddenScanRevision = LibraryScanService.state.revision();
                    scanPanel.setVisibility(View.GONE);
                });
        scanLine.addView(scanDismiss, new LinearLayout.LayoutParams(dp(44), dp(44)));
        scanPanel.addView(scanLine);
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanPanel.addView(scanProgress, new LinearLayout.LayoutParams(-1, dp(4)));
        Ui.tap(scanStatus, this::scanDetails);
        root.addView(scanPanel);
        scanPanel.setVisibility(View.GONE);
        libraryChrome = new LibraryHeader(this);
        libraryChrome.setVisibility(View.GONE);
        scroll = new PlayerScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.interaction(
                event -> {
                    if (page.equals("lyrics")) {
                        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                            lyricsManualUntil = SystemClock.elapsedRealtime() + 5000;
                            lyricsFollowPending = true;
                        }
                        showLyricsChrome();
                    }
                });
        body = column();
        body.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        body.setFocusableInTouchMode(true);
        body.setPadding(dp(22), dp(22), dp(22), dp(24));
        scroll.addView(body);
        libraryPane = new LibraryPane(this, libraryChrome, scroll);
        lyricsChrome = new FrameLayout(this);
        lyricsChrome.setTag("lyricsChrome");
        lyricsChrome.setVisibility(View.GONE);
        libraryPane.overlay(lyricsChrome);
        widePlayer = new WidePlayerView(this);
        dockPlayer = new DockPlayerView(this);
        browseSurface = column();
        browseSurface.addView(libraryPane, new LinearLayout.LayoutParams(-1, 0, 1));
        adaptivePane = new AdaptiveMusicPane(this, browseSurface, dockPlayer);
        adaptivePane.dockOnly = true;
        root.addView(adaptivePane, new LinearLayout.LayoutParams(-1, 0, 1));
        mini = column();
        mini.setPadding(dp(14), dp(8), dp(14), 0);
        nav = row();
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        nav.setBackgroundColor(bg);
        bottomBar = new MusicBottomBar(this, nav, mini);
        browseSurface.addView(bottomBar);
        root.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> {
                    if (r - l != or - ol || b - t != ob - ot) syncDock(false);
                });
        syncDock(false);
    }

    private void render() {
        UsbVolumePopup.hide(false);
        if (body == null || isDestroyed()) return;
        View focused = getCurrentFocus();
        boolean keepSearch =
                page.equals("library")
                        && renderedPage.equals("library")
                        && focused instanceof EditText
                        && "librarySearch".equals(focused.getTag());
        int caret = keepSearch ? ((EditText) focused).getSelectionStart() : 0;
        saveListPosition();
        if (!renderedKey.isEmpty()) pageOffsets.put(renderedKey, scroll.getScrollY());
        int generation = ++renderGeneration;
        scroll.stopScroll();
        scroll.dismissHandler(page.equals("player") ? this::dismissPlayer : null);
        boolean changed = !page.equals(renderedPage);
        renderedPage = page;
        renderedKey = browseKey();
        Track lyricTrack = page.equals("lyrics") ? current() : null;
        boolean newLyrics =
                lyricTrack != null
                        && (resetLyricsScroll || !lyricTrack.uri.equals(lyricsRenderedTrack));
        if (newLyrics) {
            lyricsRenderedTrack = lyricTrack.uri;
            resetLyricsScroll = false;
            lyricsManualUntil = SystemClock.elapsedRealtime() + 5000;
            lyricsFollowPending = false;
        }
        int previousScroll =
                (page.equals("player") && (changed || resetPlayerScroll) || newLyrics)
                        ? 0
                        : pageOffsets.getOrDefault(renderedKey, 0);
        if (!restoredScrollPage.isEmpty()) {
            if (!restoredScrollPage.equals(page)
                    || current() != null
                            && !restoredScrollTrack.isEmpty()
                            && !restoredScrollTrack.equals(current().uri)) restoredScrollPage = "";
            else previousScroll = restoredScrollOffset;
        }
        final int scrollTarget = previousScroll;
        resetPlayerScroll = false;
        body.animate().cancel();
        body.setAlpha(1);
        body.setTranslationY(0);
        body.setTranslationX(0);
        body.setScaleX(1);
        body.setScaleY(1);
        root.requestFocus();
        clearLyricsChrome();
        statsUi.hide();
        body.removeAllViews();
        libraryChrome.cancelMotion();
        libraryChrome.removeAllViews();
        libraryChrome.setVisibility(page.equals("library") ? View.VISIBLE : View.GONE);
        positionBar = null;
        timeView = null;
        durationView = null;
        shuffleButton = null;
        repeatButton = null;
        routeView = null;
        signalSummary = null;
        renderedSignal = null;
        renderedSignalRemote = null;
        playButton = null;
        engineView = null;
        errorNotice = null;
        usbVolumeBar = null;
        usbVolumeLabel = null;
        lyricPreview = null;
        lyricsStatus = null;
        dragging = false;
        lyricViews.clear();
        activeLine = -2;
        nav.setVisibility(
                page.equals("lyrics") || page.equals("player") ? View.GONE : View.VISIBLE);
        body.setPadding(
                dp(22),
                dp(page.equals("player") ? 4 : page.equals("library") ? 0 : 22),
                dp(22),
                dp(24));
        if (List.of("drive", "output", "cast", "remote", "updates", "licenses", "playback", "queue")
                .contains(page)) {
            TextView back = text("‹ " + (page.equals("queue") ? "재생 화면" : "설정"), 13, mint);
            back.setMinHeight(dp(44));
            back.setGravity(Gravity.CENTER_VERTICAL);
            Ui.tap(back, this::back);
            body.addView(back);
        }
        if (page.equals("home") || page.equals("settings"))
            updates.banner(body, () -> go("updates"));
        switch (page) {
            case "queue" -> queueScreen();
            case "playback" -> {
                heading("재생과 알림", null);
                playbackPreferences();
            }
            case "updates" -> updates.screen(body);
            case "drive" -> driveUi.show(body);
            case "library" -> libraryScreen();
            case "playlists" -> playlistsScreen();
            case "stats" -> statsUi.show(body);
            case "group" -> groupScreen();
            case "player" -> playerScreen();
            case "settings" -> settingsScreen();
            case "widget" -> featureUi.widget(body);
            case "backup" -> featureUi.backup(body);
            case "offline" -> featureUi.offline(body);
            case "duplicates" -> featureUi.duplicates(body);
            case "collections" -> featureUi.collections(body);
            case "profiles" -> featureUi.profiles(body);
            case "lyrics-settings" -> {
                settingsHeading("가사 설정", true);
                lyricsPreferences();
            }
            case "library-settings" -> {
                settingsHeading("라이브러리 관리", true);
                libraryPreferences();
            }
            case "lyrics" -> lyricsScreen();
            case "output" -> outputScreen();
            case "remote" -> remoteScreen();
            case "audio" -> new AudioUi(this, body, this::go).audio();
            case "tone" -> new AudioUi(this, body, this::go).tone();
            case "sox" -> new AudioUi(this, body, this::go).sox();
            case "eq" -> new AudioUi(this, body, this::go).eq();
            case "cast" -> castScreen();
            case "licenses" -> {
                heading("오픈소스 라이선스", null);
                try (java.io.InputStream in = getAssets().open("THIRD_PARTY_NOTICES.txt")) {
                    body.addView(
                            text(
                                    new String(
                                            Io.read(in), java.nio.charset.StandardCharsets.UTF_8),
                                    12,
                                    muted));
                } catch (Exception e) {
                    body.addView(text("라이선스를 불러올 수 없습니다", 14, muted));
                }
            }
            default -> homeScreen();
        }
        if (page.equals("library")) rememberLibraryPosition();
        renderMini();
        renderNav();
        updatePlayback();
        body.post(
                () -> {
                    if (!isDestroyed() && generation == renderGeneration) {
                        scroll.scrollTo(0, scrollTarget);
                        if (player != null && !libraryLoading) restoredScrollPage = "";
                        if (keepSearch
                                && root.findViewWithTag("librarySearch")
                                        instanceof EditText input) {
                            input.requestFocus();
                            input.setSelection(Math.max(0, Math.min(caret, input.length())));
                        }
                    }
                });
        if (changed) Ui.transition(body, page, returning);
        returning = false;
    }

    private void go(String target) {
        if (target.equals("player") && wideDock()) {
            dockOpened = true;
            syncDock(true);
            return;
        }
        if (!page.equals(target)) {
            if (List.of("home", "library", "playlists", "stats", "settings").contains(target))
                history.clear();
            else if (target.equals("player") && List.of("lyrics", "queue").contains(page)) {
                if (!history.isEmpty() && history.peekLast().equals("player")) history.removeLast();
            } else history.addLast(page);
            page = target;
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(body.getWindowToken(), 0);
        }
        render();
    }

    private void dismissPlayer() {
        if (!page.equals("player")) return;
        while (!history.isEmpty()
                && List.of("player", "lyrics", "queue").contains(history.peekLast()))
            history.removeLast();
        back();
    }

    private void back() {
        if (dockOpened && wideDock()) {
            musicCloseDock();
            return;
        }
        if (page.equals("library") && tab.equals("폴더") && !folderCursor.isEmpty()) {
            var tree = new FolderTree(filtered());
            var node = tree.node(folderCursor);
            folderCursor = node.parent == null ? "" : node.parent.id;
            render();
            return;
        }
        returning = true;
        if (!history.isEmpty()) {
            page = history.removeLast();
            render();
        } else if (page.equals("home")) finish();
        else {
            page =
                    page.equals("group")
                            ? "library"
                            : List.of("lyrics", "queue").contains(page)
                                    ? "player"
                                    : List.of("sox", "eq").contains(page)
                                            ? "audio"
                                            : List.of(
                                                                    "audio",
                                                                    "output",
                                                                    "remote",
                                                                    "licenses",
                                                                    "cast",
                                                                    "updates",
                                                                    "drive",
                                                                    "playback",
                                                                    "lyrics-settings",
                                                                    "library-settings")
                                                            .contains(page)
                                                    ? "settings"
                                                    : "home";
            render();
        }
    }

    private void onUi(Runnable action) {
        runOnUiThread(
                () -> {
                    if (!isDestroyed() && !isFinishing()) action.run();
                });
    }

    private String browseKey() {
        return page.equals("library")
                ? "library|"
                        + tab
                        + "|"
                        + source
                        + "|"
                        + query
                        + (tab.equals("폴더") ? "|" + folderCursor : "")
                : page.equals("group")
                        ? "group|"
                                + group
                                + (groupTracks == null || groupTracks.isEmpty()
                                        ? ""
                                        : groupTracks.get(0).uri)
                        : page;
    }

    private void saveListPosition() {
        if (activeList != null
                && activeList.getLayoutManager() != null
                && activeList.isAttachedToWindow()) {
            if (listStates.size() > 24) listStates.clear();
            listStates.putParcelable(
                    activeListKey, activeList.getLayoutManager().onSaveInstanceState());
        }
    }

    private void scanDetails() {
        var state = LibraryScanService.state;
        var dialog =
                new AlertDialog.Builder(this)
                        .setTitle("라이브러리 검색")
                        .setMessage(state.message())
                        .setPositiveButton("닫기", null);
        if (state.running())
            dialog.setNegativeButton(
                    "검색 취소",
                    (d, w) ->
                            startService(
                                    new Intent(this, LibraryScanService.class)
                                            .setAction("cancel")));
        dialog.show();
    }

    private void heading(String title, String subtitle) {
        TextView h = text(title, 28, ink);
        h.setPadding(0, dp(4), 0, dp(6));
        body.addView(h);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(subtitle, 12, muted);
            s.setPadding(0, dp(6), 0, dp(18));
            body.addView(s);
        }
    }

    private void homeScreen() {
        LinearLayout toolbar = row();
        LinearLayout intro = column();
        TextView brand = text("AURA", 11, muted);
        brand.setLetterSpacing(.16f);
        intro.addView(brand);
        TextView title = text("나의 음악", 25, ink);
        title.setPadding(0, dp(8), 0, 0);
        intro.addView(title);
        toolbar.addView(intro, new LinearLayout.LayoutParams(0, -2, 1));
        TextView settings = playerIcon("⚙", () -> go("settings"));
        toolbar.addView(settings, new LinearLayout.LayoutParams(dp(44), dp(44)));
        body.addView(toolbar);
        TextView summary =
                text(
                        libraryLoading
                                ? "라이브러리를 불러오고 있습니다…"
                                : scanning
                                        ? "새 음악을 찾고 있습니다…"
                                        : String.format(
                                                Locale.ROOT,
                                                "%,d곡 · 로컬과 Drive를 한곳에서",
                                                tracks.size()),
                        12,
                        muted);
        summary.setPadding(0, dp(12), 0, dp(6));
        body.addView(summary);
        if (tracks.isEmpty()) {
            emptyLibrary();
            homeRoute();
            return;
        }
        Track track = current();
        if (track == null) track = tracks.get(0);
        final Track resume = track;
        Runnable continuePlaying =
                () -> {
                    if (player != null && player.getCurrentMediaItem() != null) {
                        startPlayback();
                        go("player");
                    } else play(resume, tracks);
                };
        LinearLayout hero = row();
        hero.setTag("homeContinue");
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        hero.setBackground(Ui.flatSurface(this, panel, 24));
        hero.addView(art(track, 112), new LinearLayout.LayoutParams(dp(112), dp(112)));
        LinearLayout info = column();
        info.setPadding(dp(16), 0, 0, 0);
        TextView label = text("이어 듣기", 11, muted);
        info.addView(label);
        TextView song = text(track.title, 19, ink);
        song.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Ui.lines(song, 2);
        song.setPadding(0, dp(8), 0, dp(5));
        info.addView(song);
        TextView artist = text(track.artist, 12, muted);
        Ui.lines(artist, 1);
        info.addView(artist);
        LinearLayout action = row();
        action.setPadding(0, dp(12), 0, 0);
        TextView open = text("재생 화면", 11, muted);
        action.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        TextView start = playerIcon("▶", continuePlaying);
        ((Ui.IconButton) start).iconColor(Ui.onAccent());
        start.setBackground(Ui.flatSurface(this, mint, 24));
        start.setContentDescription(track.title + " 이어 듣기");
        action.addView(start, new LinearLayout.LayoutParams(dp(48), dp(48)));
        info.addView(action);
        hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.tap(hero, continuePlaying);
        add(body, hero, 18);
        featureUi.home(body);
        homeSection("내 앨범", "모두 보기", () -> browseLibrary("앨범"));
        homeAlbums();
        homeSection("라이브러리", null, null);
        LinearLayout shortcuts = row();
        String[] tabs = {"곡", "앨범", "아티스트", "폴더"}, icons = {"music", "album", "artist", "folder"};
        for (int i = 0; i < tabs.length; i++) {
            String next = tabs[i];
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(4), dp(12), dp(4), dp(12));
            item.setBackground(Ui.flatSurface(this, panel, 20));
            TextView link = text(next, 11, muted);
            link.setGravity(Gravity.CENTER);
            link.setCompoundDrawablesWithIntrinsicBounds(
                    null, new Ui.Icon(icons[i], ink, dp(22)), null, null);
            link.setCompoundDrawablePadding(dp(9));
            item.addView(link);
            item.setContentDescription(next + " 라이브러리 열기");
            Ui.tap(item, () -> browseLibrary(next));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(78), 1);
            params.setMargins(i == 0 ? 0 : dp(4), 0, i == 3 ? 0 : dp(4), 0);
            shortcuts.addView(item, params);
        }
        body.addView(shortcuts);
        LinearLayout sources = row();
        TextView local = quietLink("기기 폴더 추가", this::pickFolder),
                drive = quietLink("Google Drive", () -> go("drive"));
        sources.addView(local, new LinearLayout.LayoutParams(0, dp(48), 1));
        sources.addView(drive, new LinearLayout.LayoutParams(0, dp(48), 1));
        add(body, sources, 12);
        homeRoute();
    }

    private void homeSection(String title, String action, Runnable open) {
        LinearLayout line = row();
        line.setPadding(0, dp(24), 0, dp(12));
        TextView heading = text(title, 17, ink);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        line.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView link = text(action + " ›", 11, muted);
            link.setMinHeight(dp(44));
            link.setGravity(Gravity.CENTER);
            Ui.tap(link, open);
            line.addView(link);
        }
        body.addView(line);
    }

    private TextView quietLink(String title, Runnable action) {
        TextView link = text(title, 12, muted);
        link.setGravity(Gravity.CENTER);
        link.setBackground(Ui.flatSurface(this, 0, 16));
        Ui.tap(link, action);
        return link;
    }

    private void browseLibrary(String next) {
        tab = next;
        query = "";
        if (next.equals("폴더")) restoreFolderPosition();
        else {
            source = "all";
            folderCursor = prefs.getString("lastFolderCursor.all", "");
        }
        headerFraction = 0;
        go("library");
    }

    private void restoreFolderPosition() {
        source = prefs.getString("lastFolderSource", prefs.getString("lastLibrarySource", "all"));
        folderCursor =
                prefs.getString("lastFolderCursor." + source, source.equals("all") ? "" : source);
    }

    private void rememberLibraryPosition() {
        if (prefs == null || libraryLoading || !page.equals("library") || !query.trim().isEmpty())
            return;
        var edit = prefs.edit();
        boolean changed = false;
        if (!tab.equals(prefs.getString("lastLibraryTab", "곡"))) {
            edit.putString("lastLibraryTab", tab);
            changed = true;
        }
        if (!source.equals(prefs.getString("lastLibrarySource", "all"))) {
            edit.putString("lastLibrarySource", source);
            changed = true;
        }
        if (tab.equals("폴더")) {
            if (!folderCursor.equals(prefs.getString("lastFolderCursor." + source, ""))) {
                edit.putString("lastFolderCursor." + source, folderCursor);
                changed = true;
            }
            if (!source.equals(prefs.getString("lastFolderSource", ""))) {
                edit.putString("lastFolderSource", source);
                changed = true;
            }
        }
        if (changed) edit.apply();
    }

    private void homeRoute() {
        routeView = text("", 11, mint);
        Ui.lines(routeView, 1);
        routeView.setGravity(Gravity.CENTER);
        routeView.setMinHeight(dp(44));
        Ui.tap(routeView, () -> go("output"));
        add(body, routeView, 4);
        updateRoute();
    }

    private void homeAlbums() {
        LinkedHashMap<String, List<Track>> albums = new LinkedHashMap<>();
        for (Track t : tracks) {
            String key = t.album + "\u0000" + t.artist;
            if (albums.containsKey(key) || albums.size() < 8)
                albums.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        HorizontalScrollView rail = new HorizontalScrollView(this);
        rail.setHorizontalScrollBarEnabled(false);
        rail.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout items = row();
        items.setGravity(Gravity.TOP);
        for (List<Track> group : albums.values()) {
            Track t = group.get(0);
            LinearLayout card = column();
            card.addView(art(t, 140), new LinearLayout.LayoutParams(dp(140), dp(140)));
            TextView title = text(t.album, 14, ink);
            Ui.lines(title, 1);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setPadding(0, dp(11), 0, dp(5));
            card.addView(title);
            TextView subtitle = text(t.artist, 11, muted);
            Ui.lines(subtitle, 1);
            card.addView(subtitle);
            Ui.tap(card, () -> openGroup(t.album, group));
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(dp(140), -2);
            item.rightMargin = dp(16);
            items.addView(card, item);
        }
        rail.addView(items);
        body.addView(rail, new LinearLayout.LayoutParams(-1, -2));
    }

    private void emptyLibrary() {
        if (libraryLoading) {
            TextView loading = text("음악 목록을 준비하고 있습니다.", 14, muted);
            loading.setPadding(0, dp(32), 0, dp(32));
            body.addView(loading);
            return;
        }
        LinearLayout box = column();
        box.setPadding(dp(22), dp(28), dp(22), dp(24));
        box.setBackground(Ui.flatSurface(this, panel, 24));
        TextView symbol = text("", 22, mint);
        symbol.setCompoundDrawablesWithIntrinsicBounds(
                new Ui.Icon("library", mint, dp(32)), null, null, null);
        box.addView(symbol);
        TextView title = text("내 음악 가져오기", 21, ink);
        title.setPadding(0, dp(20), 0, dp(12));
        box.addView(title);
        TextView detail = text("음악이 있는 폴더를 연결해 주세요.\n곡·앨범·아티스트별로 정리해 드립니다.", 13, muted);
        box.addView(detail);
        box.addView(button(scanning ? "검색 중…" : "기기 음악 폴더 선택", this::pickFolder, true));
        box.addView(
                quietLink("Google Drive 연결", () -> go("drive")),
                new LinearLayout.LayoutParams(-1, dp(48)));
        add(body, box, 18);
    }

    private void libraryScreen() {
        LinearLayout toolbar = row();
        TextView name = text("라이브러리", 22, ink);
        toolbar.addView(name, new LinearLayout.LayoutParams(0, dp(48), 1));
        name.setGravity(Gravity.CENTER_VERTICAL);
        Ui.lines(name, 1);
        TextView sourceChoice =
                Ui.segment(
                        this,
                        source.equals("drive")
                                ? "Drive ▾"
                                : source.equals("local") ? "기기 ▾" : "전체 ▾",
                        !source.equals("all"),
                        this::chooseSource);
        sourceChoice.setTextSize(12);
        sourceChoice.setContentDescription(
                "음악 저장소 선택 · "
                        + (source.equals("drive")
                                ? "Google Drive"
                                : source.equals("local") ? "기기 저장소" : "전체"));
        toolbar.addView(sourceChoice, new LinearLayout.LayoutParams(dp(76), dp(44)));
        TextView find =
                playerIcon(
                        "search",
                        () -> {
                            searchOpen = !searchOpen;
                            render();
                        });
        find.setContentDescription("라이브러리 검색");
        find.setSelected(searchOpen || !query.isEmpty());
        toolbar.addView(find, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView sort = playerIcon("sort", this::chooseTrackOrder);
        sort.setContentDescription("곡 정렬");
        toolbar.addView(sort, new LinearLayout.LayoutParams(dp(44), dp(44)));
        body.addView(toolbar);
        LinearLayout tabs = row();
        tabs.setPadding(dp(4), dp(3), dp(4), dp(3));
        tabs.setBackground(Ui.flatSurface(this, panel, 16));
        for (String t : List.of("곡", "앨범", "아티스트", "폴더")) {
            TextView button =
                    Ui.segment(
                            this,
                            t,
                            tab.equals(t),
                            () -> {
                                tab = t;
                                if (t.equals("폴더")) restoreFolderPosition();
                                group = "";
                                render();
                            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
            tabs.addView(button, params);
        }
        body.addView(tabs);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(ink);
        search.setHintTextColor(muted);
        search.setTextSize(14);
        search.setHint("곡·앨범·아티스트 검색");
        search.setText(query);
        search.setBackground(Ui.surface(this, panel, 14));
        search.setPadding(dp(16), dp(12), dp(16), dp(12));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        search.setOnEditorActionListener(
                (v, action, event) -> {
                    if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        search.clearFocus();
                        ((android.view.inputmethod.InputMethodManager)
                                        getSystemService(INPUT_METHOD_SERVICE))
                                .hideSoftInputFromWindow(search.getWindowToken(), 0);
                        return true;
                    }
                    return false;
                });
        LinearLayout searchRow = row();
        searchRow.setBackground(Ui.flatSurface(this, panel, 18));
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setTag("librarySearch");
        search.setHint("곡·앨범·아티스트·폴더 검색");
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView clear = text("×", 23, muted);
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("검색어 지우기");
        clear.setTag("clearSearch");
        Ui.tap(clear, () -> search.setText(""));
        searchRow.addView(clear, new LinearLayout.LayoutParams(dp(44), dp(48)));
        clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        add(body, searchRow, 4);
        searchRow.setVisibility(searchOpen || !query.isEmpty() ? View.VISIBLE : View.GONE);
        LinearLayout results = column();
        body.addView(results);
        Runnable fill =
                () -> {
                    if (page.equals("library") && results.isAttachedToWindow())
                        fillLibrary(results);
                };
        search.addTextChangedListener(
                new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                        query = s.toString();
                        clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                        handler.removeCallbacks(fill);
                        handler.postDelayed(fill, 120);
                    }

                    public void afterTextChanged(android.text.Editable e) {}
                });
        fillLibrary(results);
        buildLibraryChrome(results);
    }

    private void buildLibraryChrome(LinearLayout results) {
        libraryThin = row();
        libraryThin.setPadding(dp(22), 0, dp(14), 0);
        TextView title = text("라이브러리 · " + tab, 16, ink);
        Ui.lines(title, 1);
        libraryThin.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView expand = Ui.segment(this, "펼치기", false, () -> collapseLibrary(false));
        expand.setContentDescription("라이브러리 상단 펼치기");
        LinearLayout.LayoutParams expandParams = new LinearLayout.LayoutParams(dp(64), dp(44));
        expandParams.rightMargin = dp(6);
        libraryThin.addView(expand, expandParams);
        TextView search =
                Ui.segment(
                        this,
                        "검색",
                        false,
                        () -> {
                            searchOpen = true;
                            headerFraction = 0;
                            render();
                            root.post(
                                    () -> {
                                        EditText input = root.findViewWithTag("librarySearch");
                                        if (input != null) {
                                            input.requestFocus();
                                            ((android.view.inputmethod.InputMethodManager)
                                                            getSystemService(INPUT_METHOD_SERVICE))
                                                    .showSoftInput(
                                                            input,
                                                            android.view.inputmethod
                                                                    .InputMethodManager
                                                                    .SHOW_IMPLICIT);
                                        }
                                    });
                        });
        libraryThin.addView(search, new LinearLayout.LayoutParams(dp(52), dp(44)));
        libraryExpanded = column();
        libraryExpanded.setPadding(dp(22), dp(4), dp(22), dp(4));
        while (body.getChildCount() > 1) {
            View child = body.getChildAt(0);
            body.removeView(child);
            libraryExpanded.addView(child);
        }
        libraryChrome.content(
                libraryExpanded,
                libraryThin,
                headerFraction,
                value -> {
                    headerFraction = value;
                    libraryCollapsed = value >= .999f;
                    libraryPane.followHeader();
                });
        libraryChrome.onSettled(this::settleLibraryInsets);
        libraryPane.followHeader();
    }

    private void chooseTrackOrder() {
        String mode = prefs.getString("trackSort", "track");
        boolean descending = prefs.getBoolean("trackSortDescending", false);
        String[] labels = java.util.Arrays.copyOf(TrackOrder.LABELS, TrackOrder.LABELS.length + 1);
        labels[labels.length - 1] = descending ? "내림차순 ↓ · 오름차순으로 변경" : "오름차순 ↑ · 내림차순으로 변경";
        new AlertDialog.Builder(this)
                .setTitle("곡 정렬 · " + TrackOrder.label(mode))
                .setItems(
                        labels,
                        (dialog, which) -> {
                            if (which == TrackOrder.IDS.length)
                                prefs.edit().putBoolean("trackSortDescending", !descending).apply();
                            else prefs.edit().putString("trackSort", TrackOrder.IDS[which]).apply();
                            if (page.equals("group") && groupTracks != null)
                                openGroup(group, groupTracks);
                            else render();
                        })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void chooseSource() {
        String[] names = {"전체", "기기 저장소", "Google Drive"};
        String[] ids = {"all", "local", "drive"};
        new AlertDialog.Builder(this)
                .setTitle("음악 저장소")
                .setSingleChoiceItems(
                        names,
                        java.util.Arrays.asList(ids).indexOf(source),
                        (d, i) -> {
                            source = ids[i];
                            folderCursor =
                                    prefs.getString(
                                            "lastFolderCursor." + source,
                                            source.equals("all") ? "" : source);
                            d.dismiss();
                            render();
                        })
                .setNeutralButton("Drive 폴더 관리", (d, i) -> go("drive"))
                .setNegativeButton("닫기", null)
                .show();
    }

    private void settleLibraryInsets() {
        if (activeList != null && page.equals("library")) {
            int bottom = Math.max(0, libraryChrome.visibleHeight() - dp(48));
            if (activeList.getPaddingBottom() != bottom) {
                activeList.setClipToPadding(false);
                activeList.setPadding(0, 0, 0, bottom);
            }
        }
    }

    private void collapseLibrary(boolean collapsed) {
        if (!page.equals("library")) return;
        libraryChrome.animateTo(collapsed ? 1 : 0);
    }

    private List<Track> filtered() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        return tracks.stream()
                .filter(
                        t ->
                                (source.equals("all") || source.equals("drive") == t.drive())
                                        && t.searchKey.contains(q))
                .collect(java.util.stream.Collectors.toList());
    }

    private void fillLibrary(LinearLayout target) {
        int generation = ++searchGeneration;
        if (searchTask != null) searchTask.cancel(true);
        List<Track> snapshot = tracks;
        String needle = query.trim().toLowerCase(Locale.ROOT), storage = source;
        String selectedTab = tab, order = prefs.getString("trackSort", "track");
        boolean descending = prefs.getBoolean("trackSortDescending", false);
        searchTask =
                searches.submit(
                        () -> {
                            try {
                                LibraryQuery result =
                                        LibraryQuery.run(
                                                snapshot,
                                                storage,
                                                needle,
                                                selectedTab,
                                                order,
                                                descending);
                                onUi(
                                        () -> {
                                            if (generation == searchGeneration
                                                    && page.equals("library")
                                                    && target.getParent() == body)
                                                showLibrary(target, result);
                                        });
                            } catch (InterruptedException cancelled) {
                                Thread.currentThread().interrupt();
                            }
                        });
    }

    private void showLibrary(LinearLayout target, LibraryQuery result) {
        saveListPosition();
        target.removeAllViews();
        List<Track> found = result.tracks;
        if (found.isEmpty()) {
            String message =
                    libraryLoading
                            ? "라이브러리를 불러오고 있습니다…"
                            : !query.trim().isEmpty()
                                    ? "검색 결과가 없습니다."
                                    : source.equals("drive")
                                            ? "Google Drive 음악이 없습니다."
                                            : source.equals("local")
                                                    ? "기기에 등록한 음악이 없습니다."
                                                    : "음악 라이브러리를 만들어 보세요.";
            TextView empty = text(message, 18, ink);
            empty.setPadding(0, dp(28), 0, dp(12));
            target.addView(empty);
            if (libraryLoading) return;
            if (!query.trim().isEmpty())
                target.addView(
                        button(
                                "검색어 지우기",
                                () -> {
                                    query = "";
                                    render();
                                },
                                false));
            else {
                target.addView(text("음악 폴더를 추가하면 이곳에서 확인할 수 있습니다.", 13, muted));
                target.addView(
                        button(
                                source.equals("drive") ? "Drive 폴더 연결" : "기기 음악 폴더 추가",
                                source.equals("drive") ? () -> go("drive") : this::pickFolder,
                                true));
                if (source.equals("all"))
                    target.addView(button("Google Drive 연결", () -> go("drive"), false));
            }
            return;
        }
        if (tab.equals("앨범")) {
            albumGrid(target, found, result.groups);
            return;
        }
        if (tab.equals("곡")) {
            virtualList(
                    target,
                    found.size(),
                    i -> trackRow(found.get(i), found),
                    1,
                    (view, i) -> ((SongCell) view).bind(found.get(i), found));
            return;
        }
        if (tab.equals("폴더")) {
            folderBrowser(target, found, result.folders);
            return;
        }
        Map<String, List<Track>> groups = result.groups;
        if (!tab.equals("폴더")) {
            List<String> names = new ArrayList<>(groups.keySet());
            virtualList(
                    target,
                    names.size(),
                    i -> {
                        String name = names.get(i);
                        List<Track> list = groups.get(name);
                        return new LibraryGroupCell(
                                "artist", name, list.size() + "곡", () -> openGroup(name, list));
                    },
                    1,
                    (view, i) -> {
                        String name = names.get(i);
                        List<Track> list = groups.get(name);
                        ((LibraryGroupCell) view)
                                .bind(
                                        "artist",
                                        name,
                                        list.size() + "곡",
                                        () -> openGroup(name, list));
                    });
            return;
        }
    }

    private void folderBrowser(LinearLayout target, List<Track> found, FolderTree tree) {
        FolderTree.Node node = tree.closest(folderCursor);
        if (query.trim().isEmpty()) {
            folderCursor = node.id;
            rememberLibraryPosition();
        }
        HorizontalScrollView breadcrumb = new HorizontalScrollView(this);
        breadcrumb.setHorizontalScrollBarEnabled(false);
        LinearLayout path = row();
        ArrayDeque<FolderTree.Node> parents = new ArrayDeque<>();
        for (FolderTree.Node at = node; at != null; at = at.parent) parents.addFirst(at);
        for (FolderTree.Node at : parents) {
            TextView step =
                    Ui.segment(
                            this,
                            at.name,
                            at == node,
                            () -> {
                                folderCursor = at.id;
                                render();
                            });
            path.addView(step, new LinearLayout.LayoutParams(-2, dp(44)));
            if (at != node) {
                TextView slash = text(" / ", 13, muted);
                path.addView(slash);
            }
        }
        breadcrumb.addView(path);
        target.addView(breadcrumb);
        breadcrumb.post(() -> breadcrumb.fullScroll(View.FOCUS_RIGHT));
        if (node.parent != null && node.parent.parent != null && !tree.legacy) {
            target.addView(
                    quietLink("이 폴더만 다시 검색", () -> rescanFolder(node)),
                    new LinearLayout.LayoutParams(-1, dp(44)));
        }
        if (node.parent != null)
            target.addView(
                    button(
                            "‹ 상위 폴더",
                            () -> {
                                folderCursor = node.parent.id;
                                render();
                            },
                            false));
        if (tree.legacy) {
            TextView hint = text("이전 목록의 경로를 사용하고 있습니다. 폴더를 다시 검색하면 경로 정보를 갱신할 수 있습니다.", 11, muted);
            hint.setPadding(0, dp(10), 0, dp(6));
            target.addView(hint);
        }
        List<Object> entries = new ArrayList<>();
        node.children.values().stream()
                .sorted(Comparator.comparing(n -> n.name.toLowerCase(Locale.ROOT)))
                .forEach(entries::add);
        entries.addAll(node.songs);
        if (node.first != null && node.first.drive())
            target.addView(button("이 폴더 오프라인 보관", () -> saveOfflineFolder(node), false));
        virtualList(
                target,
                entries.size(),
                i -> {
                    Object entry = entries.get(i);
                    if (entry instanceof FolderTree.Node child)
                        return new LibraryGroupCell(
                                        "folder",
                                        child.name,
                                        child.children.size() + "개 폴더 · " + child.count() + "곡",
                                        () -> {
                                            folderCursor = child.id;
                                            render();
                                        })
                                .folder(() -> playFolder(child), () -> rescanFolder(child))
                                .cover(child.first);
                    return trackRow((Track) entry, node.songs);
                },
                1,
                (view, i) -> {
                    Object entry = entries.get(i);
                    if (entry instanceof FolderTree.Node child) {
                        ((LibraryGroupCell) view)
                                .bind(
                                        "folder",
                                        child.name,
                                        child.children.size() + "개 폴더 · " + child.count() + "곡",
                                        () -> {
                                            folderCursor = child.id;
                                            render();
                                        });
                        ((LibraryGroupCell) view)
                                .folder(() -> playFolder(child), () -> rescanFolder(child))
                                .cover(child.first);
                    } else ((SongCell) view).bind((Track) entry, node.songs);
                },
                i -> entries.get(i) instanceof FolderTree.Node ? 0 : 1);
    }

    private void saveOfflineFolder(FolderTree.Node folder) {
        searches.execute(
                () -> {
                    List<Track> songs = new ArrayList<>();
                    ArrayDeque<FolderTree.Node> pending = new ArrayDeque<>();
                    pending.add(folder);
                    while (!pending.isEmpty()) {
                        var n = pending.removeFirst();
                        songs.addAll(n.songs);
                        pending.addAll(n.children.values());
                    }
                    onUi(() -> OfflineMusic.enqueue(this, songs));
                });
    }

    private void playFolder(FolderTree.Node folder) {
        String order = prefs.getString("trackSort", "track");
        boolean descending = prefs.getBoolean("trackSortDescending", false);
        searches.execute(
                () -> {
                    List<Track> all = new ArrayList<>();
                    ArrayDeque<FolderTree.Node> pending = new ArrayDeque<>();
                    pending.add(folder);
                    while (!pending.isEmpty()) {
                        var node = pending.removeFirst();
                        all.addAll(node.songs);
                        pending.addAll(node.children.values());
                    }
                    List<Track> sorted = TrackOrder.sorted(all, order, descending);
                    onUi(
                            () -> {
                                if (sorted.isEmpty()) toast("이 폴더에 재생할 곡이 없습니다.");
                                else play(sorted.get(0), sorted);
                            });
                });
    }

    private void rescanFolder(FolderTree.Node node) {
        if (LibraryScanService.state.running()) {
            toast("진행 중인 검색이 끝난 뒤 다시 시도해 주세요.");
            return;
        }
        if (node.id.equals("local") || node.id.equals("drive")) {
            LibraryScanService.start(this, node.id.equals("drive") ? "drive" : "local");
            toast("선택한 저장소의 등록 폴더를 다시 검색합니다.");
            return;
        }
        try {
            ArrayDeque<FolderTree.Part> path = new ArrayDeque<>();
            for (var at = node;
                    at != null && at.parent != null && at.parent.parent != null;
                    at = at.parent)
                path.addFirst(
                        new FolderTree.Part(
                                Uri.decode(at.id.substring(at.id.lastIndexOf('/') + 1)), at.name));
            List<FolderTree.Part> parts = new ArrayList<>(path);
            boolean drive = node.id.startsWith("drive/");
            String first = parts.get(0).id(), last = parts.get(parts.size() - 1).id();
            FolderScope scope =
                    drive
                            ? new FolderScope(
                                    true,
                                    first.substring(first.indexOf(':') + 1),
                                    last.substring(last.indexOf(':') + 1),
                                    parts)
                            : new FolderScope(
                                    false,
                                    first,
                                    parts.size() == 1
                                            ? android.provider.DocumentsContract.getTreeDocumentId(
                                                    Uri.parse(first))
                                            : android.provider.DocumentsContract.getDocumentId(
                                                    Uri.parse(last)),
                                    parts);
            LibraryScanService.startFolder(this, scope);
            toast("선택한 폴더와 하위 폴더를 다시 검색합니다.");
        } catch (Exception e) {
            toast("폴더 경로를 확인할 수 없습니다. 전체 폴더를 먼저 검색해 주세요.");
        }
    }

    private void albumGrid(LinearLayout target, List<Track> source) {
        LinkedHashMap<String, List<Track>> albums = new LinkedHashMap<>();
        for (Track t : source) {
            String key = t.album + "\u0000" + t.artist;
            if (albums.containsKey(key) || target != body || albums.size() < 8)
                albums.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        albumGrid(target, source, albums);
    }

    private void albumGrid(
            LinearLayout target, List<Track> source, Map<String, List<Track>> albums) {
        if (target != body) {
            List<List<Track>> values = new ArrayList<>(albums.values());
            virtualList(
                    target,
                    values.size(),
                    i -> new AlbumCell(values.get(i)),
                    2,
                    (view, i) -> ((AlbumCell) view).bind(values.get(i)));
            return;
        }
        LinearLayout line = null;
        int i = 0;
        for (List<Track> ts : albums.values()) {
            if (i >= 8) break;
            if (i % 2 == 0) {
                line = row();
                line.setGravity(Gravity.TOP);
                add(target, line, 12);
            }
            Track t = ts.get(0);
            LinearLayout cell = column();
            cell.setPadding(dp(6), 0, dp(6), dp(8));
            ArtworkView art = art(t, 120);
            cell.addView(art, new LinearLayout.LayoutParams(-1, -2));
            TextView album = text(t.album, 15, ink);
            Ui.lines(album, 2);
            album.setPadding(0, dp(8), 0, dp(3));
            cell.addView(album);
            TextView artist = text(t.artist + " · " + ts.size() + "곡", 11, muted);
            Ui.lines(artist, 1);
            cell.addView(artist);
            cell.setOnClickListener(v -> openGroup(t.album, ts));
            line.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
            i++;
        }
        if (i % 2 == 1) line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private void virtualList(
            LinearLayout target,
            int count,
            java.util.function.IntFunction<View> factory,
            int columns) {
        virtualList(target, count, factory, columns, null);
    }

    private void virtualList(
            LinearLayout target,
            int count,
            java.util.function.IntFunction<View> factory,
            int columns,
            java.util.function.BiConsumer<View, Integer> bind) {
        virtualList(target, count, factory, columns, bind, i -> 0);
    }

    private void virtualList(
            LinearLayout target,
            int count,
            java.util.function.IntFunction<View> factory,
            int columns,
            java.util.function.BiConsumer<View, Integer> bind,
            java.util.function.IntUnaryOperator types) {
        androidx.recyclerview.widget.RecyclerView list = Ui.scrollingList(this);
        list.setLayoutManager(
                columns == 1
                        ? new androidx.recyclerview.widget.LinearLayoutManager(this)
                        : new androidx.recyclerview.widget.GridLayoutManager(this, columns));
        list.setAdapter(
                new androidx.recyclerview.widget.RecyclerView.Adapter<
                        androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                    @Override
                    public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(
                            ViewGroup parent, int type) {
                        FrameLayout cell = new FrameLayout(MainActivity.this);
                        cell.setLayoutParams(
                                new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2));
                        return new androidx.recyclerview.widget.RecyclerView.ViewHolder(cell) {};
                    }

                    @Override
                    public void onBindViewHolder(
                            androidx.recyclerview.widget.RecyclerView.ViewHolder holder,
                            int index) {
                        FrameLayout frame = (FrameLayout) holder.itemView;
                        if (bind != null && frame.getChildCount() > 0) {
                            bind.accept(frame.getChildAt(0), index);
                            return;
                        }
                        frame.removeAllViews();
                        View child = factory.apply(index);
                        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2);
                        if (child.getLayoutParams()
                                instanceof ViewGroup.MarginLayoutParams margins) {
                            params.setMargins(
                                    margins.leftMargin,
                                    margins.topMargin,
                                    margins.rightMargin,
                                    margins.bottomMargin);
                        }
                        frame.addView(child, params);
                    }

                    @Override
                    public int getItemViewType(int index) {
                        return types.applyAsInt(index);
                    }

                    @Override
                    public int getItemCount() {
                        return count;
                    }
                });
        list.addOnItemTouchListener(
                new androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
                    @Override
                    public boolean onInterceptTouchEvent(
                            androidx.recyclerview.widget.RecyclerView view, MotionEvent event) {
                        if (page.equals("library")) libraryChrome.touch(event);
                        return false;
                    }
                });
        list.addOnScrollListener(
                new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(
                            androidx.recyclerview.widget.RecyclerView view, int state) {
                        if (page.equals("library")
                                && state
                                        != androidx.recyclerview.widget.RecyclerView
                                                .SCROLL_STATE_DRAGGING) libraryChrome.release();
                    }
                });
        activeList = list;
        activeListKey = browseKey();
        Parcelable restored = listStates.getParcelable(activeListKey);
        if (restored != null) list.getLayoutManager().onRestoreInstanceState(restored);
        target.addView(list, new LinearLayout.LayoutParams(-1, dp(320)));
        Runnable fit =
                () -> {
                    if (!list.isAttachedToWindow()) return;
                    int available =
                            scroll.getHeight()
                                    - body.getPaddingBottom()
                                    - (page.equals("library") ? target.getTop() : dp(180));
                    int height = Math.max(dp(160), available);
                    if (list.getLayoutParams().height != height) {
                        list.getLayoutParams().height = height;
                        list.requestLayout();
                    }
                };
        View.OnLayoutChangeListener resize = (v, l, t, r, b, ol, ot, or, ob) -> fit.run();
        scroll.addOnLayoutChangeListener(resize);
        list.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View v) {}

                    public void onViewDetachedFromWindow(View v) {
                        scroll.removeOnLayoutChangeListener(resize);
                    }
                });
        list.post(
                () -> {
                    fit.run();
                    settleLibraryInsets();
                });
    }

    private int groupSortGeneration;

    private void openGroup(String name, List<Track> list) {
        group = name;
        groupTracks = null;
        int generation = ++groupSortGeneration;
        String mode = prefs.getString("trackSort", "track");
        boolean descending = prefs.getBoolean("trackSortDescending", false);
        go("group");
        searches.execute(
                () -> {
                    List<Track> sorted = TrackOrder.sorted(list, mode, descending);
                    onUi(
                            () -> {
                                if (generation == groupSortGeneration) {
                                    groupTracks = sorted;
                                    if (page.equals("group")) render();
                                }
                            });
                });
    }

    private void groupScreen() {
        LinearLayout toolbar = row();
        TextView back = playerIcon("back", this::back);
        back.setContentDescription("이전 화면으로 돌아가기");
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text(group, 22, ink);
        Ui.lines(title, 2);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView sort = playerIcon("sort", this::chooseTrackOrder);
        sort.setContentDescription("곡 정렬");
        toolbar.addView(sort, new LinearLayout.LayoutParams(dp(44), dp(44)));
        body.addView(toolbar);
        if (groupTracks == null) {
            body.addView(text("라이브러리를 불러오고 있습니다…", 14, muted));
            return;
        }
        if (groupTracks.isEmpty()) {
            TextView empty = text("아직 추가한 곡이 없습니다.", 16, muted);
            empty.setPadding(0, dp(32), 0, dp(16));
            body.addView(empty);
            body.addView(button("라이브러리 열기", () -> go("library"), true));
            return;
        }
        LinearLayout summary = row();
        summary.setPadding(dp(16), dp(16), dp(16), dp(16));
        summary.setBackground(Ui.flatSurface(this, panel, 24));
        summary.addView(
                art(groupTracks.get(0), 104), new LinearLayout.LayoutParams(dp(104), dp(104)));
        LinearLayout detail = column();
        detail.setPadding(dp(18), 0, 0, 0);
        detail.addView(text(groupTracks.size() + "곡", 15, ink));
        TextView caption = text("전체 재생", 12, muted);
        caption.setPadding(0, dp(8), 0, dp(12));
        detail.addView(caption);
        TextView play = playerIcon("▶", () -> play(groupTracks.get(0), groupTracks));
        ((Ui.IconButton) play).iconColor(Ui.onAccent());
        play.setBackground(Ui.flatSurface(this, mint, 26));
        detail.addView(play, new LinearLayout.LayoutParams(dp(52), dp(52)));
        summary.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
        add(body, summary, 16);
        if (groupTracks.stream().anyMatch(Track::drive))
            body.addView(
                    button(
                            "이 모음의 Drive 음악 오프라인 보관",
                            () -> OfflineMusic.enqueue(this, List.copyOf(groupTracks)),
                            false));
        virtualList(
                body,
                groupTracks.size(),
                i -> trackRow(groupTracks.get(i), groupTracks),
                1,
                (view, i) -> ((SongCell) view).bind(groupTracks.get(i), groupTracks));
    }

    private LinearLayout trackRow(Track track, List<Track> queue) {
        return new SongCell(track, queue);
    }

    private final class SongCell extends LinearLayout {
        final ArtworkView cover;
        final TextView title, detail, more, duration, formatBadge;
        Track track;
        List<Track> queue;
        boolean hiResBadge;

        SongCell(Track first, List<Track> items) {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(10), 0, dp(10));
            cover = art(first, 52);
            addView(cover, new LinearLayout.LayoutParams(dp(52), dp(52)));
            LinearLayout info = column();
            info.setPadding(dp(12), 0, 0, 0);
            title = text("", 14, ink);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            Ui.lines(title, 2);
            info.addView(title);
            detail = text("", 11, muted);
            Ui.lines(detail, 1);
            detail.setPadding(0, dp(4), 0, 0);
            info.addView(detail);
            addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            formatBadge = text("", 10, mint);
            formatBadge.setTag("trackFormat");
            formatBadge.setSingleLine(true);
            formatBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            formatBadge.setGravity(Gravity.CENTER);
            formatBadge.setPadding(dp(6), dp(4), dp(6), dp(4));
            formatBadge.setBackground(Ui.shape(MainActivity.this, Ui.SOFT, 5));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
            badgeParams.leftMargin = dp(8);
            addView(formatBadge, badgeParams);
            duration = text("", 11, muted);
            duration.setTag("trackDuration");
            duration.setGravity(Gravity.END);
            duration.setPadding(dp(8), 0, 0, 0);
            addView(duration, new LinearLayout.LayoutParams(-2, -2));
            more = playerIcon("more", () -> trackActions(track));
            more.setGravity(Gravity.CENTER);
            Ui.tap(more, () -> trackActions(track));
            addView(more, new LinearLayout.LayoutParams(dp(44), dp(48)));
            setBackground(Ui.flatSurface(MainActivity.this, Ui.BG, 16));
            Ui.tap(this, () -> play(track, queue));
            setOnLongClickListener(
                    v -> {
                        addToPlaylist(track);
                        return true;
                    });
            track = first;
            bind(first, items);
        }

        void bind(Track next, List<Track> items) {
            boolean same = track == next;
            track = next;
            queue = items;
            Ui.updateText(title, next.title);
            Ui.updateText(detail, next.artist + (next.drive() ? " · Drive" : ""));
            boolean hiRes = next.formatLabel.equals("Hi-Res");
            if (hiResBadge != hiRes) {
                hiResBadge = hiRes;
                formatBadge.setTextColor(hiRes ? (Ui.dark ? 0xff8ad59b : 0xff26753d) : mint);
                formatBadge.setBackground(
                        Ui.shape(
                                MainActivity.this,
                                hiRes ? (Ui.dark ? 0xff203d2c : 0xffe3f2e6) : Ui.SOFT,
                                5));
            }
            Ui.updateText(formatBadge, next.formatLabel);
            formatBadge.setVisibility(next.formatLabel.isEmpty() ? View.GONE : View.VISIBLE);
            formatBadge.setContentDescription(
                    next.formatLabel.isEmpty()
                            ? null
                            : hiRes
                                    ? "Hi-Res FLAC · "
                                            + next.bitsPerSample
                                            + " bit · "
                                            + next.sampleRate
                                            + " Hz"
                                    : "파일 형식 " + next.formatLabel);
            Ui.updateText(duration, next.duration > 0 ? format(next.duration) : "");
            more.setContentDescription(next.title + " 곡 메뉴");
            if (!same) cover.bind(next);
        }
    }

    private final class LibraryGroupCell extends LinearLayout {
        final TextView symbol, title, detail, folderPlay;
        final FrameLayout badge;
        ArtworkView folderCover;
        Runnable action;
        String iconKey = "";

        LibraryGroupCell(String icon, String name, String subtitle, Runnable open) {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(12), dp(14), dp(12));
            setBackground(Ui.flatSurface(MainActivity.this, panel, 20));
            symbol = text("", 16, mint);
            symbol.setGravity(Gravity.CENTER);
            symbol.setBackground(Ui.shape(MainActivity.this, Ui.SOFT, 26));
            badge = new FrameLayout(MainActivity.this);
            badge.addView(symbol, new FrameLayout.LayoutParams(-1, -1));
            addView(badge, new LinearLayout.LayoutParams(dp(52), dp(52)));
            LinearLayout words = column();
            words.setPadding(dp(14), 0, dp(8), 0);
            title = text("", 15, ink);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            Ui.lines(title, 2);
            detail = text("", 11, muted);
            detail.setPadding(0, dp(6), 0, 0);
            Ui.lines(detail, 1);
            words.addView(title);
            words.addView(detail);
            addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            folderPlay = playerIcon("▶", () -> {});
            folderPlay.setVisibility(View.GONE);
            addView(folderPlay, new LinearLayout.LayoutParams(dp(44), dp(48)));
            TextView arrow = text("›", 20, muted);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(arrow);
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
            spacing.topMargin = dp(8);
            setLayoutParams(spacing);
            Ui.tap(
                    this,
                    () -> {
                        if (action != null) action.run();
                    });
            bind(icon, name, subtitle, open);
        }

        LibraryGroupCell folder(Runnable play, Runnable scan) {
            folderPlay.setVisibility(View.VISIBLE);
            folderPlay.setContentDescription(title.getText() + " 폴더 전체 재생");
            Ui.tap(folderPlay, play);
            setOnLongClickListener(
                    v -> {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(title.getText())
                                .setItems(
                                        new String[] {"폴더 전체 재생", "이 폴더만 다시 검색"},
                                        (dialog, index) -> {
                                            if (index == 0) play.run();
                                            else scan.run();
                                        })
                                .setNegativeButton("닫기", null)
                                .show();
                        return true;
                    });
            return this;
        }

        LibraryGroupCell cover(Track first) {
            if (first == null) {
                if (folderCover != null) folderCover.setVisibility(View.GONE);
                symbol.setVisibility(View.VISIBLE);
                return this;
            }
            if (folderCover == null) {
                folderCover = new ArtworkView(MainActivity.this, first, 52, true);
                badge.addView(folderCover, new FrameLayout.LayoutParams(-1, -1));
            } else folderCover.bind(first);
            folderCover.setVisibility(View.VISIBLE);
            symbol.setVisibility(View.GONE);
            return this;
        }

        void bind(String icon, String name, String subtitle, Runnable open) {
            if (folderCover != null) folderCover.setVisibility(View.GONE);
            symbol.setVisibility(View.VISIBLE);
            action = open;
            Ui.updateText(title, name);
            Ui.updateText(detail, subtitle);
            if (!icon.equals(iconKey)) {
                iconKey = icon;
                symbol.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
                symbol.setBackground(
                        new android.graphics.drawable.LayerDrawable(
                                new android.graphics.drawable.Drawable[] {
                                    Ui.shape(MainActivity.this, Ui.SOFT, 26),
                                    new android.graphics.drawable.InsetDrawable(
                                            new Ui.Icon(icon, mint, dp(24)), dp(14))
                                }));
            }
            setContentDescription(name + " · " + subtitle);
        }
    }

    private final class AlbumCell extends LinearLayout {
        final ArtworkView cover;
        final TextView title, subtitle;
        List<Track> songs;
        Track track;

        AlbumCell(List<Track> first) {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(6), dp(10), dp(6), dp(10));
            cover = art(first.get(0), 120);
            addView(cover, new LinearLayout.LayoutParams(-1, -2));
            title = text("", 14, ink);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            Ui.lines(title, 2);
            title.setPadding(0, dp(11), 0, dp(5));
            addView(title);
            subtitle = text("", 11, muted);
            Ui.lines(subtitle, 1);
            addView(subtitle);
            Ui.tap(this, () -> openGroup(track.album, songs));
            track = first.get(0);
            bind(first);
        }

        void bind(List<Track> next) {
            songs = next;
            Track first = next.get(0);
            Ui.updateText(title, first.album);
            Ui.updateText(subtitle, first.artist + " · " + next.size() + "곡");
            if (track != first) {
                track = first;
                cover.bind(first);
            }
            setContentDescription(first.album + " · " + first.artist + " · " + next.size() + "곡");
        }
    }

    private void trackActions(Track track) {
        LinearLayout content = column();
        content.setPadding(dp(12), dp(18), dp(12), dp(6));
        LinearLayout header = row();
        header.setPadding(dp(8), 0, dp(8), dp(16));
        header.addView(art(track, 52), new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout words = column();
        words.setPadding(dp(14), 0, 0, 0);
        TextView title = text(track.title, 17, ink);
        Ui.lines(title, 2);
        words.addView(title);
        TextView artist = text(track.artist, 12, muted);
        Ui.lines(artist, 1);
        artist.setPadding(0, dp(7), 0, 0);
        words.addView(artist);
        header.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(header);
        ScrollView menuScroll = new ScrollView(this);
        menuScroll.addView(content);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setView(menuScroll)
                        .setNegativeButton("닫기", null)
                        .create();
        String[]
                titles =
                        {
                            "이 곡만 재생",
                            "플레이리스트에 추가",
                            "이 앨범 보기",
                            "이 아티스트 보기",
                            "곡 정보",
                            "즐겨찾기 · 보관 · 북마크"
                        },
                icons = {"▶", "playlists", "album", "artist", "info", "music"};
        Runnable[] actions = {
            () -> play(track, List.of(track)),
            () -> addToPlaylist(track),
            () ->
                    openGroup(
                            track.album,
                            tracks.stream()
                                    .filter(
                                            t ->
                                                    t.album.equals(track.album)
                                                            && t.artist.equals(track.artist))
                                    .collect(java.util.stream.Collectors.toList())),
            () ->
                    openGroup(
                            track.artist,
                            tracks.stream()
                                    .filter(t -> t.artist.equals(track.artist))
                                    .collect(java.util.stream.Collectors.toList())),
            () -> SongInfo.show(this, track),
            () -> featureUi.action(track)
        };
        for (int i = 0; i < titles.length; i++) {
            int index = i;
            LinearLayout item =
                    Ui.settingRow(
                            this,
                            icons[i],
                            titles[i],
                            "",
                            () -> {
                                dialog.dismiss();
                                actions[index].run();
                            });
            item.setContentDescription(titles[i]);
            content.addView(item);
        }
        dialog.show();
    }

    private void play(Track track, List<Track> queue) {
        if (track.drive()
                && !DriveAuth.get(this).connected()
                && OfflineMusic.available(this, track.uri) == null) {
            toast("Google Drive 계정을 연결해 주세요.");
            go("drive");
            return;
        }
        if (!ensureOutput()) return;
        if (network != null && network.active()) network.disconnect();
        if (player == null) {
            toast("재생 엔진 연결 중입니다");
            return;
        }
        try {
            cachedTrack = track;
            cachedTrackFallback = false;
            cachedTrackList = tracks;
            List<MediaItem> items =
                    queue.stream().map(Track::item).collect(java.util.stream.Collectors.toList());
            int index = queue.indexOf(track);
            player.setMediaItems(items, Math.max(0, index), 0);
            player.prepare();
            player.play();
            prefs.edit().putString("lastTrack", track.uri).apply();
            go("player");
        } catch (Exception e) {
            toast("재생을 시작할 수 없습니다");
        }
    }

    private void restoreQueue() {
        if (player == null || tracks.isEmpty()) return;
        if (player.getCurrentMediaItem() != null) {
            MediaItem item = player.getCurrentMediaItem();
            Track track = findTrack(item.mediaId);
            if (track != null) {
                MediaMetadata known = item.mediaMetadata, updated = track.item().mediaMetadata;
                boolean
                        ready =
                                known.extras != null
                                        && known.extras.getBoolean("auraTagsReady", false),
                        newReady =
                                updated.extras != null
                                        && updated.extras.getBoolean("auraTagsReady", false);
                if (!android.text.TextUtils.equals(known.title, updated.title)
                        || !android.text.TextUtils.equals(known.artist, updated.artist)
                        || !android.text.TextUtils.equals(known.albumTitle, updated.albumTitle)
                        || ready != newReady)
                    player.replaceMediaItem(
                            player.getCurrentMediaItemIndex(),
                            item.buildUpon().setMediaMetadata(updated).build());
            }
            return;
        }
        String uri = prefs.getString("lastTrack", "");
        for (int i = 0; i < tracks.size(); i++)
            if (tracks.get(i).uri.equals(uri)) {
                player.setMediaItems(
                        tracks.stream()
                                .map(Track::item)
                                .collect(java.util.stream.Collectors.toList()),
                        i,
                        prefs.getLong("lastPosition", 0));
                if (OutputRouting.current(this).allowed()) player.prepare();
                else PcmSink.status = "출력 설정 확인 필요";
                break;
            }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UsbVolumePopup.resume(this);
        updates.resume();
    }

    @Override
    protected void onPause() {
        UsbVolumePopup.pause(this);
        updates.pause();
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (windowTracker != null)
            windowTracker.addWindowLayoutInfoListener(
                    this, androidx.core.content.ContextCompat.getMainExecutor(this), foldListener);
        uiStarted = true;
        statsUi.resume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        rememberLibraryPosition();
        if (windowTracker != null) windowTracker.removeWindowLayoutInfoListener(foldListener);
        statsUi.pause();
        uiStarted = false;
        lyricsFramesRunning = false;
        scroll.removeCallbacks(lyricsFrames);
        handler.removeCallbacks(tick);
        if (player != null && player.getCurrentMediaItem() != null)
            prefs.edit()
                    .putString("lastTrack", player.getCurrentMediaItem().mediaId)
                    .putLong("lastPosition", Math.max(0, player.getCurrentPosition()))
                    .apply();
        super.onStop();
    }

    private Track current() {
        if (network != null && network.active()) return network.track();
        if (player == null || player.getCurrentMediaItem() == null) return null;
        return queueTrack(player.getCurrentMediaItem());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (UsbVolume.direct()
                && (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                        || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                UsbVolume.adjust(event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ? 2 : -2);
                updateUsbVolume();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void usbVolumeControls() {
        if (!UsbVolume.direct()) return;
        LinearLayout box = column();
        box.setPadding(dp(12), dp(8), dp(12), dp(4));
        box.setBackground(Ui.surface(this, panel, 14));
        usbVolumeLabel = text("", 12, mint);
        box.addView(usbVolumeLabel);
        usbVolumeBar = new SeekBar(this);
        usbVolumeBar.setContentDescription("USB DAC 전용 볼륨");
        usbVolumeBar.setMax(100);
        usbVolumeBar.setProgress(UsbVolume.state.level());
        usbVolumeBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar bar) {
                        usbVolumeDragging = true;
                    }

                    public void onStopTrackingTouch(SeekBar bar) {
                        usbVolumeDragging = false;
                        UsbVolume.set(bar.getProgress());
                    }

                    public void onProgressChanged(SeekBar bar, int value, boolean user) {
                        if (user) {
                            usbVolumeLabel.setText("USB 전용 볼륨 · " + value + "%");
                            UsbVolume.set(value);
                        }
                    }
                });
        box.addView(usbVolumeBar);
        add(body, box, 10);
        updateUsbVolume();
    }

    private void updateUsbVolume() {
        if (usbVolumeBar == null) return;
        var state = UsbVolume.state;
        usbVolumeBar.setEnabled(UsbVolume.adjustable());
        if (!usbVolumeDragging) {
            usbVolumeBar.setProgress(state.level());
            Ui.updateText(
                    usbVolumeLabel, "USB 전용 볼륨 · " + state.level() + "% · " + state.message());
        }
    }

    private void playerScreen() {
        LinearLayout toolbar = row();
        TextView close = playerIcon("chevron", this::dismissPlayer);
        close.setContentDescription("재생 화면 닫기");
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView caption = text("지금 재생 중", 12, muted);
        caption.setGravity(Gravity.CENTER);
        caption.setLetterSpacing(.06f);
        toolbar.addView(caption, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView more =
                playerIcon(
                        "more",
                        () -> {
                            Track track = current();
                            if (track != null) trackActions(track);
                        });
        toolbar.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        body.addView(toolbar);
        Track t = current();
        if (t == null) {
            heading("재생할 음악을 선택해 주세요", null);
            body.addView(button("라이브러리", () -> go("library"), true));
            return;
        }
        int available =
                root.getHeight() > 0
                        ? root.getHeight() - root.getPaddingTop() - root.getPaddingBottom()
                        : getResources().getDisplayMetrics().heightPixels - dp(64);
        int coverSize =
                Math.max(
                        dp(164),
                        Math.min(
                                Math.min(dp(340), available - dp(450)),
                                (libraryPane.getWidth() > 0
                                                ? libraryPane.getWidth()
                                                : getResources().getDisplayMetrics().widthPixels)
                                        - dp(56)));
        FrameLayout cover = new FrameLayout(this);
        ArtworkView image =
                art(t, Math.round(coverSize / getResources().getDisplayMetrics().density));
        cover.addView(image, new FrameLayout.LayoutParams(coverSize, coverSize, Gravity.CENTER));
        cover.setTag("player-artwork-region");
        add(body, cover, 18);
        cover.addOnLayoutChangeListener(
                (view, l, t0, r, b, ol, ot, or, ob) -> {
                    if (page.equals("player") && cover.isAttachedToWindow())
                        scroll.dismissRegion(cover.getBottom() + body.getPaddingTop());
                });
        TextView title = text(t.title, 23, ink);
        title.setTag("playerTitle");
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(8), dp(24), dp(8), dp(8));
        Ui.lines(title, 2);
        body.addView(title);
        TextView artist = text(t.artist, 13, muted);
        artist.setGravity(Gravity.CENTER);
        Ui.lines(artist, 1);
        artist.setPadding(0, 0, 0, dp(14));
        body.addView(artist);
        LinearLayout quality = row();
        quality.setTag("signal-quality");
        quality.setGravity(Gravity.CENTER);
        signalSummary = text("형식을 확인하고 있습니다…", 11, muted);
        signalSummary.setTag("signal-summary");
        signalSummary.setGravity(Gravity.CENTER);
        signalSummary.setPadding(dp(12), dp(5), dp(12), dp(5));
        signalSummary.setSingleLine(true);
        signalSummary.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(68));
        signalSummary.setAutoSizeTextTypeUniformWithConfiguration(
                9, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        signalSummary.setBackground(Ui.flatSurface(this, panel, 12));
        quality.addView(signalSummary, new LinearLayout.LayoutParams(-2, -2));
        Ui.tap(quality, () -> showSignalDetails(t));
        body.addView(quality, new LinearLayout.LayoutParams(-1, dp(40)));

        positionBar = new SeekBar(this);
        Ui.tint(positionBar);
        body.addView(positionBar);
        positionBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar b, int p, boolean user) {}

                    public void onStartTrackingTouch(SeekBar b) {
                        dragging = true;
                    }

                    public void onStopTrackingTouch(SeekBar b) {
                        if (network != null && network.active()) {
                            long pos = b.getProgress();
                            if (network.isCast())
                                try {
                                    network.seek(pos);
                                } catch (Exception e) {
                                    toast(e.getMessage());
                                }
                            else
                                worker.execute(
                                        () -> {
                                            try {
                                                network.seek(pos);
                                            } catch (Exception e) {
                                                onUi(() -> toast(e.getMessage()));
                                            }
                                        });
                        } else if (player != null) player.seekTo(b.getProgress());
                        dragging = false;
                    }
                });

        positionBar.setContentDescription("재생 위치");
        LinearLayout times = row();
        timeView = text("", 11, muted);
        durationView = text("", 11, muted);
        durationView.setGravity(Gravity.END);
        times.setPadding(dp(4), 0, dp(4), 0);
        times.addView(timeView, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(durationView, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(times);
        boolean compact =
                (libraryPane.getWidth() > 0
                                ? libraryPane.getWidth()
                                        / getResources().getDisplayMetrics().density
                                : getResources().getConfiguration().screenWidthDp)
                        <= 380;
        int size = compact ? 68 : 76, gap = compact ? 20 : 22, side = compact ? 44 : 48;
        LinearLayout transport = row();
        transport.setGravity(Gravity.CENTER);
        shuffleButton =
                playerIcon(
                        "shuffle",
                        () -> {
                            if (player != null) {
                                player.setShuffleModeEnabled(!player.getShuffleModeEnabled());
                                updatePlayerModes();
                            }
                        });
        transport.addView(shuffleButton, new LinearLayout.LayoutParams(dp(40), dp(48)));
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        TextView previous =
                playerIcon(
                        "⏮",
                        () -> {
                            if (network != null && network.active()) networkCommand("previous");
                            else if (player != null) player.seekToPreviousMediaItem();
                        });
        controls.addView(previous, new LinearLayout.LayoutParams(dp(side), dp(56)));
        playButton = playerIcon("▶", this::toggle);
        ((Ui.IconButton) playButton).iconColor(Ui.onAccent());
        android.graphics.drawable.GradientDrawable ring = Ui.shape(this, Ui.SOFT, size / 2),
                center = Ui.shape(this, mint, size / 2);
        android.graphics.drawable.LayerDrawable playSurface =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] {ring, center});
        playSurface.setLayerInset(1, dp(5), dp(5), dp(5), dp(5));
        playButton.setBackground(
                new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x30ffffff),
                        playSurface,
                        Ui.shape(this, 0xffffffff, size / 2)));
        LinearLayout.LayoutParams primary = new LinearLayout.LayoutParams(dp(size), dp(size));
        primary.setMargins(dp(gap), 0, dp(gap), 0);
        controls.addView(playButton, primary);
        TextView next =
                playerIcon(
                        "⏭",
                        () -> {
                            if (network != null && network.active()) networkCommand("next");
                            else if (player != null) player.seekToNextMediaItem();
                        });
        controls.addView(next, new LinearLayout.LayoutParams(dp(side), dp(56)));
        transport.addView(controls, new LinearLayout.LayoutParams(0, -2, 1));
        repeatButton =
                playerIcon(
                        "repeat",
                        () -> {
                            if (player != null) {
                                player.setRepeatMode(
                                        switch (player.getRepeatMode()) {
                                            case Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL;
                                            case Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE;
                                            default -> Player.REPEAT_MODE_OFF;
                                        });
                                updatePlayerModes();
                            }
                        });
        transport.addView(repeatButton, new LinearLayout.LayoutParams(dp(40), dp(48)));
        add(body, transport, 18);
        updatePlayerModes();
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER);
        for (String action : List.of("가사", "대기열", "도구")) {
            TextView link = text(action, 12, muted);
            link.setGravity(Gravity.CENTER);
            link.setCompoundDrawablesWithIntrinsicBounds(
                    new Ui.Icon(action.equals("가사") ? "lyrics" : "queue", muted, dp(18)),
                    null,
                    null,
                    null);
            link.setCompoundDrawablePadding(dp(8));
            link.setPadding(dp(16), 0, dp(16), 0);
            link.setBackground(Ui.flatSurface(this, 0, 16));
            Ui.tap(
                    link,
                    () -> {
                        if (action.equals("도구")) featureUi.action(t);
                        else go(action.equals("가사") ? "lyrics" : "queue");
                    });
            actions.addView(link, new LinearLayout.LayoutParams(-2, dp(44)));
        }
        add(body, actions, 8);
        routeView = text("", 11, mint);
        Ui.lines(routeView, 1);
        routeView.setGravity(Gravity.CENTER);
        routeView.setMinHeight(dp(40));
        routeView.setBackground(Ui.flatSurface(this, 0, 16));
        Ui.tap(routeView, () -> go("output"));
        add(body, routeView, 4);
        updateRoute();
        usbVolumeControls();
        errorNotice = text("", 13, Ui.ERROR);
        body.addView(errorNotice);
        TextView recover =
                button(
                        t.drive() ? "Google Drive 연결 확인" : "기본 출력으로 복구",
                        t.drive() ? () -> go("drive") : this::recoverAudio,
                        false);
        recover.setTag("audioRecovery");
        recover.setVisibility(
                player != null && player.getPlayerError() != null ? View.VISIBLE : View.GONE);
        body.addView(recover);
        TextView copy = button("오류 정보 복사", this::copyAudioError, false);
        copy.setTag("copyAudioError");
        copy.setVisibility(recover.getVisibility());
        body.addView(copy);

        ensureLyrics(t);
        LinearLayout lyrics = column();
        lyrics.setPadding(dp(18), dp(14), dp(18), dp(16));
        lyrics.setBackground(Ui.flatSurface(this, panel, 20));
        lyricsStatus = text("실시간 가사 · 전체화면 ›", 10, muted);
        lyrics.addView(lyricsStatus);
        lyricPreview = text("가사를 확인하고 있습니다…", 16, ink);
        lyricPreview.setTag("liveLyrics");
        Ui.lines(lyricPreview, 1);
        lyricPreview.setMinHeight(dp(32));
        lyricPreview.setGravity(Gravity.CENTER_VERTICAL);
        lyricPreview.setPadding(0, dp(8), 0, 0);
        lyrics.addView(lyricPreview);
        Ui.tap(lyrics, () -> go("lyrics"));
        add(body, lyrics, 12);
    }

    private TextView playerIcon(String key, Runnable action) {
        Ui.IconButton button = new Ui.IconButton(this, key);
        button.iconColor(ink);
        button.setBackground(Ui.flatSurface(this, 0, 24));
        Ui.tap(button, action);
        return button;
    }

    private void updatePlayerModes() {
        if (player == null) return;
        boolean local = network == null || !network.active();
        if (shuffleButton != null) {
            shuffleButton.setEnabled(local);
            shuffleButton.setAlpha(local ? 1 : .3f);
            ((Ui.IconButton) shuffleButton)
                    .iconColor(player.getShuffleModeEnabled() ? mint : muted);
            shuffleButton.setContentDescription(
                    "무작위 재생 · " + (player.getShuffleModeEnabled() ? "켜짐" : "꺼짐"));
        }
        if (repeatButton != null) {
            repeatButton.setEnabled(local);
            repeatButton.setAlpha(local ? 1 : .3f);
            boolean one = player.getRepeatMode() == Player.REPEAT_MODE_ONE;
            repeatButton.setText(one ? "repeat_one" : "repeat");
            ((Ui.IconButton) repeatButton)
                    .iconColor(player.getRepeatMode() == Player.REPEAT_MODE_OFF ? muted : mint);
            repeatButton.setContentDescription(
                    "반복 재생 · "
                            + (one
                                    ? "한 곡"
                                    : player.getRepeatMode() == Player.REPEAT_MODE_ALL
                                            ? "전체"
                                            : "꺼짐"));
        }
    }

    private org.json.JSONObject playlistData() {
        try {
            return new org.json.JSONObject(prefs.getString("playlists", "{}"));
        } catch (Exception e) {
            return new org.json.JSONObject();
        }
    }

    private void addToPlaylist(Track t) {
        org.json.JSONObject lists = playlistData();
        List<String> names = new ArrayList<>();
        lists.keys().forEachRemaining(names::add);
        names.add("＋ 새 플레이리스트");
        new AlertDialog.Builder(this)
                .setTitle("플레이리스트에 추가")
                .setItems(
                        names.toArray(new String[0]),
                        (d, which) -> {
                            if (which == names.size() - 1) {
                                EditText field = new EditText(this);
                                field.setHint("플레이리스트 이름");
                                new AlertDialog.Builder(this)
                                        .setTitle("새 플레이리스트")
                                        .setView(field)
                                        .setPositiveButton(
                                                "만들기",
                                                (dialog, w) ->
                                                        savePlaylistTrack(
                                                                field.getText().toString().trim(),
                                                                t))
                                        .setNegativeButton("취소", null)
                                        .show();
                            } else savePlaylistTrack(names.get(which), t);
                        })
                .show();
    }

    private void savePlaylistTrack(String name, Track t) {
        if (name.isEmpty()) return;
        try {
            org.json.JSONObject lists = playlistData();
            org.json.JSONArray a = lists.optJSONArray(name);
            if (a == null) a = new org.json.JSONArray();
            for (int i = 0; i < a.length(); i++)
                if (a.getString(i).equals(t.uri)) {
                    toast("이미 들어 있는 곡입니다");
                    return;
                }
            a.put(t.uri);
            lists.put(name, a);
            prefs.edit().putString("playlists", lists.toString()).apply();
            toast("플레이리스트에 추가했습니다");
        } catch (Exception e) {
            toast("저장하지 못했습니다");
        }
    }

    private record PlaylistEntry(String name, List<Track> songs) {}

    private void playlistsScreen() {
        LinearLayout top = row();
        TextView title = text("플레이리스트", 25, ink);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView create = playerIcon("plus", () -> editPlaylist(null));
        create.setContentDescription("플레이리스트 만들기");
        top.addView(create, new LinearLayout.LayoutParams(dp(44), dp(44)));
        body.addView(top);
        org.json.JSONObject lists = playlistData();
        TextView subtitle = text(lists.length() + "개의 플레이리스트", 12, muted);
        subtitle.setPadding(0, dp(10), 0, dp(16));
        body.addView(subtitle);
        if (lists.length() == 0) {
            LinearLayout empty = Ui.card(this, 22);
            TextView message = text("나만의 음악 모음", 22, ink);
            empty.addView(message);
            TextView hint = text("라이브러리에서 곡을 길게 누르면\n플레이리스트에 추가할 수 있습니다.", 13, muted);
            hint.setPadding(0, dp(12), 0, dp(8));
            empty.addView(hint);
            empty.addView(button("플레이리스트 만들기", () -> editPlaylist(null), true));
            empty.addView(
                    quietLink("라이브러리 열기", () -> go("library")),
                    new LinearLayout.LayoutParams(-1, dp(48)));
            body.addView(empty);
            return;
        }
        Map<String, Track> byUri = new HashMap<>();
        for (Track t : tracks) byUri.put(t.uri, t);
        List<PlaylistEntry> entries = new ArrayList<>();
        lists.keys()
                .forEachRemaining(
                        name -> {
                            List<Track> songs = new ArrayList<>();
                            org.json.JSONArray array = lists.optJSONArray(name);
                            if (array != null)
                                for (int i = 0; i < array.length(); i++) {
                                    Track t = byUri.get(array.optString(i));
                                    if (t != null) songs.add(t);
                                }
                            entries.add(new PlaylistEntry(name, List.copyOf(songs)));
                        });
        entries.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
        virtualList(
                body,
                entries.size(),
                i -> new PlaylistCell(entries.get(i)),
                1,
                (view, i) -> ((PlaylistCell) view).bind(entries.get(i)));
    }

    private final class PlaylistCell extends LinearLayout {
        final FrameLayout cover;
        final TextView title, count, more;
        PlaylistEntry item;
        String coverKey = "";

        PlaylistCell(PlaylistEntry entry) {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(14), dp(10), dp(14));
            setBackground(Ui.flatSurface(MainActivity.this, panel, 22));
            cover = new FrameLayout(MainActivity.this);
            addView(cover, new LinearLayout.LayoutParams(dp(64), dp(64)));
            LinearLayout info = column();
            info.setPadding(dp(16), 0, 0, 0);
            title = text("", 16, ink);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            Ui.lines(title, 2);
            count = text("", 12, muted);
            count.setPadding(0, dp(7), 0, 0);
            info.addView(title);
            info.addView(count);
            addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            more = playerIcon("more", () -> playlistMenu(item));
            addView(more, new LinearLayout.LayoutParams(dp(44), dp(48)));
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
            spacing.topMargin = dp(10);
            setLayoutParams(spacing);
            Ui.tap(this, () -> openGroup(item.name(), item.songs()));
            setOnLongClickListener(
                    v -> {
                        playlistMenu(item);
                        return true;
                    });
            bind(entry);
        }

        void bind(PlaylistEntry entry) {
            item = entry;
            Ui.updateText(title, entry.name());
            Ui.updateText(count, entry.songs().size() + "곡");
            more.setContentDescription(entry.name() + " 플레이리스트 메뉴");
            String key = entry.songs().isEmpty() ? "empty" : entry.songs().get(0).uri;
            if (!key.equals(coverKey) || cover.getChildCount() == 0) {
                coverKey = key;
                cover.removeAllViews();
                cover.setBackground(
                        entry.songs().isEmpty() ? Ui.shape(MainActivity.this, Ui.SOFT, 16) : null);
                if (entry.songs().isEmpty()) {
                    View icon = new View(MainActivity.this);
                    icon.setBackground(new Ui.Icon("playlists", mint, dp(28)));
                    cover.addView(
                            icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
                } else
                    cover.addView(
                            art(entry.songs().get(0), 64), new FrameLayout.LayoutParams(-1, -1));
            }
        }
    }

    private void editPlaylist(String original) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setHint("플레이리스트 이름");
        if (original != null) input.setText(original);
        var dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(original == null ? "플레이리스트 만들기" : "이름 바꾸기")
                        .setView(input)
                        .setPositiveButton("저장", null)
                        .setNegativeButton("취소", null)
                        .create();
        dialog.setOnShowListener(
                ignored ->
                        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        v -> {
                                            String name = input.getText().toString().trim();
                                            org.json.JSONObject lists = playlistData();
                                            if (name.isEmpty()) {
                                                input.setError("이름을 입력해 주세요.");
                                                return;
                                            }
                                            if (!name.equals(original) && lists.has(name)) {
                                                input.setError("같은 이름의 플레이리스트가 있습니다.");
                                                return;
                                            }
                                            try {
                                                org.json.JSONArray songs =
                                                        original == null
                                                                ? new org.json.JSONArray()
                                                                : lists.optJSONArray(original);
                                                if (original != null) lists.remove(original);
                                                lists.put(
                                                        name,
                                                        songs == null
                                                                ? new org.json.JSONArray()
                                                                : songs);
                                                prefs.edit()
                                                        .putString("playlists", lists.toString())
                                                        .apply();
                                                dialog.dismiss();
                                                render();
                                            } catch (Exception e) {
                                                toast("저장하지 못했습니다.");
                                            }
                                        }));
        dialog.show();
    }

    private void playlistMenu(PlaylistEntry item) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(item.name())
                .setItems(
                        new String[] {"이름 바꾸기", "라이브러리 열기", "플레이리스트 삭제"},
                        (dialog, index) -> {
                            if (index == 0) editPlaylist(item.name());
                            else if (index == 1) go("library");
                            else
                                new androidx.appcompat.app.AlertDialog.Builder(this)
                                        .setMessage(
                                                item.name() + " 플레이리스트를 삭제하시겠습니까? 음악 파일은 유지됩니다.")
                                        .setPositiveButton(
                                                "삭제",
                                                (d, w) -> {
                                                    org.json.JSONObject lists = playlistData();
                                                    lists.remove(item.name());
                                                    prefs.edit()
                                                            .putString(
                                                                    "playlists", lists.toString())
                                                            .apply();
                                                    render();
                                                })
                                        .setNegativeButton("취소", null)
                                        .show();
                        })
                .show();
    }

    private void settingsHeading(String title, boolean back) {
        LinearLayout row = row();
        if (back) {
            TextView arrow = playerIcon("back", this::back);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        TextView name = text(title, back ? 22 : 25, ink);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(44), 1));
        name.setGravity(Gravity.CENTER_VERTICAL);
        if (!back) {
            TextView version = text(BuildConfig.VERSION_NAME, 11, muted);
            row.addView(version);
        }
        body.addView(row);
    }

    private LinearLayout settingsGroup(String title) {
        TextView label = text(title, 12, muted);
        label.setPadding(dp(4), dp(24), 0, dp(8));
        body.addView(label);
        LinearLayout group = Ui.card(this, 4);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) group.getLayoutParams();
        params.topMargin = 0;
        body.addView(group);
        return group;
    }

    private void settingRow(
            LinearLayout group, String icon, String title, String detail, Runnable action) {
        if (group.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(Ui.BORDER);
            line.setAlpha(.45f);
            LinearLayout.LayoutParams divider = new LinearLayout.LayoutParams(-1, dp(1));
            divider.setMargins(dp(69), 0, dp(14), 0);
            group.addView(line, divider);
        }
        group.addView(Ui.settingRow(this, icon, title, detail, action));
    }

    private void settingsScreen() {
        settingsHeading("설정", false);
        LinearLayout sound = settingsGroup("사운드");
        settingRow(
                sound,
                "speaker",
                "출력 기기",
                PcmSink.routed.isEmpty() ? "Android 기본 출력" : PcmSink.routed,
                () -> go("output"));
        settingRow(sound, "audio", "오디오 상세 설정", "비트퍼펙트 · USB DAC · DSD", () -> go("audio"));
        settingRow(sound, "audio", "음향 효과", "다이내믹 베이스 · ReplayGain · 크로스피드", () -> go("tone"));
        settingRow(
                sound,
                "eq",
                "이퀄라이저",
                AudioSettings.current.eqMode().equals("graphic") ? "7밴드 그래픽 EQ" : "파라메트릭 EQ",
                () -> go("eq"));
        settingRow(sound, "sox", "SoX Resampler", "품질 · 위상 · 컷오프 · 디더링", () -> go("sox"));
        settingRow(sound, "audio", "기기별 음향 프로필", "연결 시 자동 적용 · 앱 볼륨 상한", () -> go("profiles"));
        LinearLayout experience = settingsGroup("사용 환경");
        String appearance = prefs.getString("appearance", "dark");
        String[] modes = {"dark", "light", "system"}, names = {"다크 모드", "라이트 모드", "기기 설정 따르기"};
        int selected = Math.max(0, List.of(modes).indexOf(appearance));
        settingRow(
                experience,
                "theme",
                "화면 테마",
                names[selected],
                () ->
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("화면 테마")
                                .setSingleChoiceItems(
                                        names,
                                        selected,
                                        (dialog, index) -> {
                                            prefs.edit()
                                                    .putString("appearance", modes[index])
                                                    .apply();
                                            dialog.dismiss();
                                            recreate();
                                        })
                                .setNegativeButton("취소", null)
                                .show());
        settingRow(
                experience,
                "notification",
                "재생과 알림",
                "알림 소리 · 자동 재개 · USB 연결과 분리",
                () -> go("playback"));
        settingRow(
                experience, "lyrics", "가사 설정", "LRCLIB 검색 · 저장한 가사", () -> go("lyrics-settings"));
        LinearLayout connections = settingsGroup("라이브러리와 연결");
        settingRow(
                connections,
                "folder",
                "라이브러리 관리",
                "음악 폴더 · 시작 시 변경 확인",
                () -> go("library-settings"));
        settingRow(connections, "cloud", "Google Drive", "계정 연결 · 스트리밍 · 음악 폴더", () -> go("drive"));
        settingRow(connections, "wifi", "Wi-Fi 캐스트", "Google Cast · UPnP / DLNA", () -> go("cast"));
        settingRow(
                connections, "remote", "같은 Wi-Fi에서 제어", "다른 기기의 브라우저로 재생 제어", () -> go("remote"));
        LinearLayout tools = settingsGroup("음악 도구");
        settingRow(tools, "music", "홈 음악 모음", "최근 추가 · 즐겨찾기 · 청취 기록", () -> go("collections"));
        settingRow(tools, "cloud", "오프라인 음악", "Drive 음악 보관 · 용량 관리", () -> go("offline"));
        settingRow(tools, "folder", "중복 곡 정리", "원본을 지우지 않고 표시할 버전 선택", () -> go("duplicates"));
        settingRow(tools, "music", "홈 화면 위젯", "앨범아트와 재생 제어", () -> go("widget"));
        settingRow(tools, "info", "백업과 복원", "통계 · 플레이리스트 · 음향 설정", () -> go("backup"));
        settingRow(
                tools,
                "notification",
                "취침 타이머",
                PlaybackTools.instance == null ? "기기 재생에서 사용" : PlaybackTools.instance.sleepLabel(),
                featureUi::sleep);
        body.addView(
                Ui.toggle(
                        this,
                        "취침 타이머 종료 전 서서히 음량 줄이기",
                        MusicCollections.prefs(this).getBoolean("sleep-fade", true),
                        v ->
                                MusicCollections.prefs(this)
                                        .edit()
                                        .putBoolean("sleep-fade", v)
                                        .apply()));
        LinearLayout about = settingsGroup("앱 정보");
        settingRow(about, "update", "앱 업데이트", "새 버전 확인 · 다운로드 · 설치", () -> go("updates"));
        settingRow(
                about,
                "info",
                "앱 종료 기록 복사",
                "문제 확인을 위한 진단 정보",
                () -> {
                    ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(
                                    ClipData.newPlainText(
                                            "AURA 종료 기록", AuraApplication.report(this)));
                    toast("앱 종료 기록을 복사했습니다.");
                });
        settingRow(about, "info", "오픈소스 라이선스", "사용 중인 라이브러리와 라이선스", () -> go("licenses"));
    }

    private void lyricsPreferences() {
        body.addView(
                Ui.toggle(
                        this,
                        "LRCLIB 온라인 검색",
                        prefs.getBoolean("onlineLyrics", false),
                        on -> prefs.edit().putBoolean("onlineLyrics", on).apply()));
        body.addView(
                text(
                        "켜면 곡명·가수·앨범·재생 시간을 LRCLIB에 전송합니다.\n받은 가사는 앱에 저장하며 로컬 LRC를 우선 사용합니다.",
                        12,
                        muted));
        body.addView(button("저장한 온라인 가사 삭제", this::confirmClearOnlineLyrics, false));
    }

    private void confirmClearOnlineLyrics() {
        new AlertDialog.Builder(this)
                .setMessage("저장한 온라인 가사를 삭제하시겠습니까? 로컬 LRC는 유지됩니다.")
                .setPositiveButton("삭제", (dialog, which) -> clearOnlineLyrics())
                .setNegativeButton("취소", null)
                .show();
    }

    private void clearOnlineLyrics() {
        worker.execute(this::deleteOnlineLyricsCache);
        lyricsKey = "";
        toast("가사 캐시 삭제 요청됨");
    }

    private void deleteOnlineLyricsCache() {
        java.io.File directory = new java.io.File(getFilesDir(), "lyrics");
        java.io.File[] files = directory.listFiles();
        if (files != null) {
            for (java.io.File file : files) {
                file.delete();
            }
        }
    }

    private void libraryPreferences() {
        body.addView(
                menu(
                        "기본 곡 정렬",
                        TrackOrder.label(prefs.getString("trackSort", "track"))
                                + (prefs.getBoolean("trackSortDescending", false)
                                        ? " · 내림차순"
                                        : " · 오름차순"),
                        this::chooseTrackOrder));
        body.addView(
                text(
                        "기존 목록에 트랙 번호가 없으면 모든 폴더 다시 검색을 한 번 실행해 주세요. 시작 시 새 음악 확인에서는 기존 음원을 다시 분석하지 않습니다.",
                        12,
                        muted));
        body.addView(
                Ui.toggle(
                        this,
                        "앱 시작 시 음악 변경 확인",
                        prefs.getBoolean("scanOnLaunch", false),
                        on -> {
                            prefs.edit().putBoolean("scanOnLaunch", on).apply();
                            if (on
                                    && Build.VERSION.SDK_INT >= 33
                                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                            != PackageManager.PERMISSION_GRANTED)
                                requestPermissions(
                                        new String[] {Manifest.permission.POST_NOTIFICATIONS}, 31);
                        }));
        body.addView(
                text(
                        "켜면 새 곡만 추가하고 삭제된 곡을 정리합니다. 기존 곡의 정보와 표지는 다시 분석하지 않습니다. 저장소를 읽지 못하면 기존 목록을 유지합니다. 기존 곡 정보 갱신은 전체 다시 검색을 이용해 주세요.",
                        12,
                        muted));
        body.addView(menu("Google Drive", "계정 연결 · 스트리밍 · 음악 폴더", () -> go("drive")));
        body.addView(button("기기 음악 폴더 추가", this::pickFolder, false));
        for (Uri tree : Library.roots(this)) {
            String label = Uri.decode(tree.getLastPathSegment());
            body.addView(menu(label, "기기 저장소 · 눌러서 제거", () -> confirmRemoveMusicFolder(tree)));
        }
        body.addView(
                button(
                        scanning ? "검색 중…" : "모든 폴더 다시 검색",
                        () -> {
                            if (Library.roots(this).isEmpty() && DriveLibrary.roots(this).isEmpty())
                                pickFolder();
                            else startScan("all");
                        },
                        false));
        body.addView(
                text(
                        "폴더를 여러 개 추가할 수 있습니다. 검색은 화면을 닫아도 계속되며, 진행 상황은 상단과 알림에서 확인할 수 있습니다.",
                        12,
                        muted));
    }

    private void confirmRemoveMusicFolder(Uri tree) {
        if (LibraryScanService.state.running()) {
            toast("검색을 취소한 뒤 폴더를 변경해 주세요.");
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("이 폴더를 라이브러리에서 제거하시겠습니까? 음악 파일은 유지됩니다.")
                .setPositiveButton("제거", (dialog, which) -> removeMusicFolder(tree))
                .setNegativeButton("취소", null)
                .show();
    }

    private void removeMusicFolder(Uri tree) {
        var roots = Library.roots(this);
        roots.remove(tree);
        Library.saveRoots(this, roots);
        if (!roots.isEmpty()) {
            scan(null);
            return;
        }
        tracks = DriveLibrary.load(this);
        try {
            var localIndex = new java.io.File(getFilesDir(), "library.json").toPath();
            byte[] emptyLibrary = "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.write(localIndex, emptyLibrary);
        } catch (Exception ignored) {
        }
        render();
    }

    private void outputScreen() {
        heading("출력 기기", "시스템에 연결된 출력 목록");
        body.addView(text(BluetoothCodecMonitor.description(), 12, muted));
        body.addView(text(PcmSink.status, 12, muted));
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        for (AudioDeviceInfo d :
                OutputDevices.music(
                        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS),
                        AudioSettings.current.deviceId())) {
            String name = d.getProductName().toString();
            String types = type(d.getType());
            body.addView(menu(name, types + " · 선택해서 출력", () -> chooseOutput(d)));
        }
        body.addView(button("USB DAC 직접 출력 권한", this::selectUsb, false));
        body.addView(button("Wi-Fi 캐스트", () -> go("cast"), false));
        body.addView(button("샘플레이트 · Bit Depth · 버퍼", () -> go("audio"), false));
        body.addView(
                text(
                        "시스템에 연결된 기기 목록입니다. 실제 재생 경로는 재생 중 확인할 수 있습니다.\n블루투스 코덱은 시스템이 제공하는 경우에만 표시합니다.",
                        12,
                        muted));
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED)
            body.addView(
                    button(
                            "블루투스 기기 이름 권한 허용",
                            () ->
                                    requestPermissions(
                                            new String[] {Manifest.permission.BLUETOOTH_CONNECT},
                                            12),
                            true));
    }

    private void chooseOutput(AudioDeviceInfo device) {
        var c = AudioSettings.current;
        if (c.perfect() && !OutputRouting.usb(device)) {
            new AlertDialog.Builder(this)
                    .setTitle("일반 출력으로 전환")
                    .setMessage("선택한 기기는 USB 비트퍼펙트 출력이 아닙니다. 비트퍼펙트를 끄고 이 기기로 전환하시겠습니까?")
                    .setPositiveButton("끄고 전환", (d, w) -> connectOutput(device, false))
                    .setNegativeButton("취소", null)
                    .show();
            return;
        }
        String mode = c.mode().equals("usb") ? "android" : c.mode();
        var result = OutputRouting.assess(this, mode, c.perfect(), device.getId());
        if (!result.allowed() || result.deviceId() != device.getId()) {
            new AlertDialog.Builder(this)
                    .setTitle("출력 연결 확인")
                    .setMessage(
                            result.message().isEmpty()
                                    ? "선택한 기기의 공식 비트퍼펙트 지원 정보가 없습니다."
                                    : result.message())
                    .setPositiveButton("USB 직접 연결", (d, w) -> requestUsbOutput(true))
                    .setNegativeButton("취소", null)
                    .show();
            return;
        }
        connectOutput(device, c.perfect());
    }

    private void connectOutput(AudioDeviceInfo device, boolean perfect) {
        var old = AudioSettings.current;
        try {
            if (old.mode().equals("usb")) AudioSettings.put("mode", "android");
            AudioSettings.put("device", device.getId());
            AudioSettings.put("perfect", perfect);
            OutputRouting.requireReady(this);
            PlaybackService service = PlaybackService.instance;
            if (service != null) {
                service.applyAudioSettings();
                service.userSelectedOutput(device, perfect);
            }
            toast("출력 기기 변경을 요청했습니다.");
            render();
        } catch (IllegalStateException e) {
            AudioSettings.put("mode", old.mode());
            AudioSettings.put("device", old.deviceId());
            AudioSettings.put("perfect", old.perfect());
            toast(e.getMessage());
        }
    }

    private String type(int t) {
        return switch (t) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                    "블루투스";
            case AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                    "USB";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "폰 스피커";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선";
            default -> "오디오 출력";
        };
    }

    private void updateRoute() {
        if (routeView == null) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        List<String> names = new ArrayList<>();
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                String type = type(d.getType());
                if (type.equals("USB") || type.equals("블루투스") || type.equals("유선"))
                    names.add(d.getProductName().toString());
            }
        } catch (SecurityException ignored) {
        }
        Ui.updateText(
                routeView,
                network != null && network.active()
                        ? "재생 중 · " + network.label() + " ›"
                        : !PcmSink.routed.isEmpty()
                                ? "출력 · " + PcmSink.routed + " ›"
                                : names.isEmpty()
                                        ? (!PcmSink.lastFailure.isEmpty()
                                                ? "출력 연결 실패 · "
                                                        + (AudioSettings.current
                                                                        .mode()
                                                                        .equals("usb")
                                                                ? "USB 직접 출력"
                                                                : "오디오 설정")
                                                        + "  ›"
                                                : AudioSettings.current.perfect()
                                                        ? "비트퍼펙트 출력 연결 대기  ›"
                                                        : "Android 기본 출력  ›")
                                        : "연결됨 · " + String.join(", ", names) + "  ›");
    }

    private final BroadcastReceiver usbPermission =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    android.hardware.usb.UsbDevice device =
                            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);
                    if (device != null
                            && intent.getBooleanExtra(
                                    android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            && ((android.hardware.usb.UsbManager) getSystemService(USB_SERVICE))
                                    .hasPermission(device)) activateUsb(device);
                    else toast("USB 권한이 허용되지 않았습니다. 기존 출력 설정을 유지합니다.");
                }
            };

    private void selectUsb() {
        requestUsbOutput(AudioSettings.current.perfect());
    }

    void requestUsbOutput(boolean perfect) {
        usbPerfectRequested = perfect;
        android.hardware.usb.UsbManager manager =
                (android.hardware.usb.UsbManager) getSystemService(USB_SERVICE);
        List<android.hardware.usb.UsbDevice> devices = new ArrayList<>();
        for (var device : manager.getDeviceList().values())
            for (int i = 0; i < device.getInterfaceCount(); i++)
                if (device.getInterface(i).getInterfaceClass() == 1) {
                    devices.add(device);
                    break;
                }
        if (devices.isEmpty()) {
            toast("연결된 USB 오디오 기기가 없습니다. USB DAC를 연결한 뒤 다시 선택해 주세요.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("USB DAC 직접 연결")
                .setItems(
                        devices.stream()
                                .map(
                                        d ->
                                                d.getProductName() == null
                                                        ? d.getDeviceName()
                                                        : d.getProductName())
                                .toArray(String[]::new),
                        (dialog, index) -> {
                            var device = devices.get(index);
                            if (manager.hasPermission(device)) activateUsb(device);
                            else
                                manager.requestPermission(
                                        device,
                                        PendingIntent.getBroadcast(
                                                this,
                                                33,
                                                new Intent("dev.aura.player.USB_PERMISSION")
                                                        .setPackage(getPackageName()),
                                                PendingIntent.FLAG_UPDATE_CURRENT
                                                        | PendingIntent.FLAG_MUTABLE));
                        })
                .setNegativeButton("취소", null)
                .show();
    }

    private void activateUsb(android.hardware.usb.UsbDevice device) {
        try {
            PlaybackService service = PlaybackService.instance;
            if (service != null) service.switchUsb(device, usbPerfectRequested);
            else {
                UsbRoute.select(this, device);
                EngineMemory.enteringUsb(this);
                AudioSettings.put("mode", "usb");
                AudioSettings.put("perfect", usbPerfectRequested);
            }
            go("audio");
            toast("USB 직접 출력이 선택되었습니다.");
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    private boolean ensureOutput() {
        var result = OutputRouting.current(this);
        if (result.allowed()) {
            if (result.deviceId() != AudioSettings.current.deviceId())
                AudioSettings.put("device", result.deviceId());
            return true;
        }
        new AlertDialog.Builder(this)
                .setTitle("출력 연결 확인")
                .setMessage(result.message())
                .setPositiveButton(
                        "USB 직접 연결", (d, w) -> requestUsbOutput(AudioSettings.current.perfect()))
                .setNeutralButton("출력 설정", (d, w) -> go("audio"))
                .setNegativeButton("취소", null)
                .show();
        return false;
    }

    private void startPlayback() {
        if (player == null || !ensureOutput()) return;
        if (player.getPlayerError() != null || player.getPlaybackState() == Player.STATE_IDLE)
            player.prepare();
        player.play();
    }

    private NetworkPlayback network() throws Exception {
        if (network == null || network.closed()) network = new NetworkPlayback(this);
        return network;
    }

    private void networkCommand(String command) {
        if (network.isCast()) {
            try {
                network.command(command);
            } catch (Exception e) {
                toast(e.getMessage());
            }
            return;
        }
        worker.execute(
                () -> {
                    try {
                        network.command(command);
                    } catch (Exception e) {
                        onUi(() -> toast(e.getMessage()));
                    }
                });
    }

    private List<Track> castQueue(Track current) {
        Map<String, Track> known = new HashMap<>();
        for (Track t : tracks) known.put(t.uri, t);
        List<Track> out = new ArrayList<>();
        if (player != null)
            for (int i = 0; i < player.getMediaItemCount(); i++) {
                MediaItem item = player.getMediaItemAt(i);
                Track t = known.get(item.mediaId);
                out.add(t == null ? queueTrack(item) : t);
            }
        return out.isEmpty() ? List.of(current) : out;
    }

    private void castScreen() {
        heading("Wi-Fi 캐스트", "같은 네트워크의 스피커·플레이어");
        Track track = current();
        if (track == null) {
            body.addView(text("먼저 음악을 선택해 주세요", 14, muted));
            return;
        }

        body.addView(text(track.title + " · " + track.artist, 16, ink));
        addGoogleCastControls(track);
        String discoverLabel = onlineScan ? "DLNA 검색 중…" : "UPnP / DLNA 기기 검색";
        body.addView(button(discoverLabel, () -> discoverDlnaDevices(track), false));
        if (network != null && network.active()) {
            addActiveCastControls();
        }
    }

    private void addGoogleCastControls(Track track) {
        try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(this);
            var castButton = new androidx.mediarouter.app.MediaRouteButton(this);
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(
                    this, castButton);
            body.addView(castButton, new LinearLayout.LayoutParams(dp(64), dp(64)));
            body.addView(button("선택한 Cast 기기에 이 곡 재생", () -> startCastPlayback(track), true));
        } catch (Exception e) {
            body.addView(text("Google Cast는 Google Play 서비스가 필요합니다", 12, muted));
        }
    }

    private void startCastPlayback(Track track) {
        try {
            NetworkPlayback target = network();
            target.queue(castQueue(track), track);
            toast("캐스트용 오디오를 준비하고 있습니다");
            worker.execute(() -> prepareCastPlayback(target, track));
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    private void prepareCastPlayback(NetworkPlayback target, Track track) {
        try {
            target.prepare(track);
            onUi(() -> sendCastPlayback(target, track));
        } catch (Exception e) {
            onUi(() -> toast(e.getMessage()));
        }
    }

    private void sendCastPlayback(NetworkPlayback target, Track track) {
        try {
            target.cast(track);
            player.pause();
            toast("Cast에 재생 요청을 보냈습니다");
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    private void discoverDlnaDevices(Track track) {
        if (onlineScan) {
            return;
        }
        onlineScan = true;
        toast("같은 Wi-Fi의 DLNA 기기를 찾고 있습니다");
        worker.execute(() -> loadDlnaDevices(track));
    }

    private void loadDlnaDevices(Track track) {
        try {
            List<Dlna.Device> found = Dlna.discover();
            onUi(() -> showDlnaDevices(track, found));
        } catch (Exception e) {
            onUi(() -> finishDlnaSearchWithError(e));
        }
    }

    private void finishDlnaSearchWithError(Exception error) {
        onlineScan = false;
        toast(error.getMessage());
    }

    private void showDlnaDevices(Track track, List<Dlna.Device> devices) {
        onlineScan = false;
        if (devices.isEmpty()) {
            toast("DLNA 기기를 찾지 못했습니다");
            return;
        }
        String[] names = devices.stream().map(Dlna.Device::name).toArray(String[]::new);
        new AlertDialog.Builder(this)
                .setTitle("DLNA 재생 기기")
                .setItems(names, (dialog, index) -> startDlnaPlayback(track, devices.get(index)))
                .show();
    }

    private void startDlnaPlayback(Track track, Dlna.Device device) {
        try {
            NetworkPlayback target = network();
            worker.execute(() -> prepareDlnaPlayback(target, track, device));
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    private void prepareDlnaPlayback(NetworkPlayback target, Track track, Dlna.Device device) {
        try {
            target.queue(castQueue(track), track);
            target.prepare(track);
            target.dlna(device, track);
            onUi(this::finishDlnaPlayback);
        } catch (Exception e) {
            onUi(() -> toast(e.getMessage()));
        }
    }

    private void finishDlnaPlayback() {
        player.pause();
        toast("DLNA 재생을 시작했습니다");
        render();
    }

    private void addActiveCastControls() {
        body.addView(text("재생 기기: " + network.label(), 14, mint));
        body.addView(
                button(
                        "재생 / 일시정지",
                        () -> networkCommand(network.playing() ? "pause" : "play"),
                        false));
        body.addView(button("캐스트 중지", () -> networkCommand("stop"), false));
        body.addView(button("폰 재생으로 돌아오기", this::resumePhonePlayback, false));
    }

    private void resumePhonePlayback() {
        network.disconnect();
        player.play();
        go("player");
    }

    private void remoteScreen() {
        heading("다른 기기에서 제어", "같은 Wi-Fi · 브라우저 리모컨");
        PlaybackService service = PlaybackService.instance;
        if (service == null) {
            body.addView(text("재생 서비스 연결 중입니다", 14, muted));
            return;
        }
        String link = service.remoteLink();
        if (link == null) {
            body.addView(
                    text(
                            "태블릿이나 PC의 브라우저에서 이 폰의 재생·일시정지·이전/다음 곡을 제어할 수 있습니다.\n\n신뢰하는 Wi-Fi에서만 켜 주세요. 이 버전은 로컬 HTTP 연결이고 링크를 가진 기기가 제어할 수 있습니다.",
                            14,
                            muted));
            body.addView(
                    button(
                            "원격 제어 켜기",
                            () -> {
                                try {
                                    service.enableRemote();
                                    render();
                                } catch (Exception e) {
                                    toast("원격 제어를 시작할 수 없습니다");
                                }
                            },
                            true));
        } else {
            TextView address = text(link, 13, mint);
            address.setTextIsSelectable(true);
            body.addView(address);
            body.addView(
                    button(
                            "연결 링크 복사",
                            () -> {
                                ((android.content.ClipboardManager)
                                                getSystemService(CLIPBOARD_SERVICE))
                                        .setPrimaryClip(ClipData.newPlainText("AURA Remote", link));
                                toast("다른 기기 브라우저에 붙여넣어 주세요");
                            },
                            true));
            body.addView(
                    button(
                            "원격 제어 끄기",
                            () -> {
                                service.disableRemote();
                                render();
                            },
                            false));
            body.addView(
                    text(
                            "끄면 기존 링크는 더 이상 사용할 수 없습니다. 다시 켜면 새 인증 링크가 생성됩니다.\n\n절전 모드·백그라운드 제한에 따라 연결이 끊길 수 있습니다.",
                            12,
                            muted));
        }
    }

    private void ensureLyrics(Track t) {
        ensureLyrics(t, false);
    }

    private void ensureLyrics(Track t, boolean refresh) {
        lyricOffset =
                prefs.getLong("lyricOffset", 0)
                        + MusicCollections.prefs(this)
                                .getLong("lyric-offset:" + MusicCollections.key(t.uri), 0);
        String lookup =
                t.uri
                        + "|"
                        + t.title
                        + "|"
                        + t.artist
                        + "|"
                        + t.album
                        + "|"
                        + t.duration
                        + "|"
                        + prefs.getBoolean("onlineLyrics", false);
        if (refresh || !lyricsKey.equals(lookup)) {
            lyricsKey = lookup;
            lyricsText = "";
            requestedLyrics = t;
            lyricsMessage = "가사를 찾고 있습니다…";
            lyricsSource = "";
            lyricLines = List.of();
            int generation = ++lyricGeneration;
            worker.execute(
                    () -> {
                        try {
                            LyricsRepository.Result r =
                                    new LyricsRepository(this)
                                            .get(
                                                    t,
                                                    prefs.getBoolean("onlineLyrics", false),
                                                    refresh);
                            onUi(
                                    () -> {
                                        if (generation != lyricGeneration) return;
                                        lyricsText = r.text();
                                        lyricLines = r.lines();
                                        lyricsSource = r.source();
                                        lyricsMessage = r.lines().isEmpty() ? r.text() : "";
                                        if (page.equals("lyrics")) render();
                                        else updatePlayback();
                                    });
                        } catch (Exception e) {
                            onUi(
                                    () -> {
                                        if (generation != lyricGeneration) return;
                                        lyricsMessage =
                                                e.getMessage() == null
                                                        ? "가사를 불러오지 못했습니다"
                                                        : e.getMessage();
                                        if (page.equals("lyrics")) render();
                                        else updatePlayback();
                                    });
                        }
                    });
        }
    }

    private void refreshLyrics(Track track) {
        if (!prefs.getBoolean("onlineLyrics", false)) {
            new AlertDialog.Builder(this)
                    .setTitle("온라인 가사 검색")
                    .setMessage("곡명·가수·앨범·재생 시간을 LRCLIB로 보내 실시간 가사를 찾습니다. 온라인 검색을 켜시겠습니까?")
                    .setPositiveButton(
                            "켜고 검색",
                            (d, w) -> {
                                prefs.edit().putBoolean("onlineLyrics", true).apply();
                                ensureLyrics(track, true);
                                render();
                            })
                    .setNegativeButton("취소", null)
                    .show();
            return;
        }
        ensureLyrics(track, true);
        render();
    }

    private void lyricsScreen() {
        Track track = current();
        if (track == null) {
            heading("재생 중인 곡이 없습니다", null);
            body.addView(button("재생 화면", () -> go("player"), false));
            return;
        }
        ensureLyrics(track);
        View top = new View(this);
        body.addView(top, new LinearLayout.LayoutParams(-1, dp(90)));
        lyricTimings = new ArrayList<>();
        for (int i = 0; i < lyricLines.size(); i++)
            lyricTimings.add(KaraokeTiming.line(lyricLines, i, track.duration));
        if (lyricLines.isEmpty()) {
            TextView caption = text(lyricsText.isEmpty() ? "가사" : "일반 가사 · 시간 정보가 없습니다", 12, muted);
            body.addView(caption);
            TextView message = text(lyricsMessage.isEmpty() ? lyricsText : lyricsMessage, 24, ink);
            message.setLineSpacing(dp(12), 1);
            message.setPadding(0, dp(24), 0, dp(28));
            body.addView(message);
            body.addView(button("실시간 가사 다시 찾기", () -> refreshLyrics(track), false));
        } else
            for (Lrc.Line line : lyricLines) {
                KaraokeLineView lyric = new KaraokeLineView(this);
                lyric.setText(line.text().isEmpty() ? "♪" : line.text());
                lyric.setTextSize(26);
                lyric.setTextColor(muted);
                lyric.setAlpha(.43f);
                lyric.setGravity(Gravity.TOP | Gravity.START);
                lyric.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                lyric.setPadding(0, dp(18), 0, dp(18));
                Ui.tap(
                        lyric,
                        () -> {
                            seek(Math.max(0, line.time() - lyricOffset));
                            showLyricsChrome();
                        });
                body.addView(lyric);
                lyricViews.add(lyric);
            }
        View bottom = new View(this);
        body.addView(
                bottom,
                new LinearLayout.LayoutParams(-1, Math.max(dp(230), scroll.getHeight() / 2)));
        buildLyricsChrome(track);
    }

    private void buildLyricsChrome(Track track) {
        lyricsTop = row();
        lyricsTop.setPadding(dp(8), dp(10), dp(8), dp(10));
        lyricsTop.setBackground(Ui.flatSurface(this, panel, 22));
        TextView close = playerIcon("chevron", () -> go("player"));
        close.setContentDescription("재생 화면으로 돌아가기");
        lyricsTop.addView(close, new LinearLayout.LayoutParams(dp(40), dp(44)));
        lyricsTop.addView(art(track, 40), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout info = column();
        info.setPadding(dp(12), 0, dp(4), 0);
        TextView title = text(track.title, 14, ink);
        Ui.lines(title, 1);
        TextView artist = text(track.artist, 11, muted);
        Ui.lines(artist, 1);
        artist.setPadding(0, dp(5), 0, 0);
        info.addView(title);
        info.addView(artist);
        lyricsTop.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView options = playerIcon("more", () -> lyricsOptions(track));
        options.setContentDescription("가사 옵션");
        lyricsTop.addView(options, new LinearLayout.LayoutParams(dp(40), dp(44)));
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        top.setMargins(dp(16), dp(10), dp(16), 0);
        lyricsChrome.addView(lyricsTop, top);
        lyricsBottom = column();
        lyricsBottom.setPadding(dp(16), dp(8), dp(16), dp(12));
        lyricsBottom.setBackground(Ui.flatSurface(this, panel, 24));
        LinearLayout modes = row();
        lyricsTimingLabel = text(lyricLines.isEmpty() ? "시간 정보 없음" : "전주", 11, muted);
        modes.addView(lyricsTimingLabel, new LinearLayout.LayoutParams(0, -2, 1));
        var karaoke = new androidx.appcompat.widget.SwitchCompat(this);
        karaoke.setText("단어 강조");
        karaoke.setTextSize(12);
        karaoke.setTextColor(ink);
        karaoke.setMinHeight(dp(44));
        karaoke.setSwitchPadding(dp(10));
        karaoke.setThumbTintList(android.content.res.ColorStateList.valueOf(mint));
        karaoke.setTag("karaokeToggle");
        karaoke.setChecked(prefs.getBoolean("karaoke", true));
        karaoke.setEnabled(!lyricLines.isEmpty());
        karaoke.setOnCheckedChangeListener(
                (button, on) -> {
                    prefs.edit().putBoolean("karaoke", on).apply();
                    activeLine = -2;
                    lyricsManualUntil = SystemClock.elapsedRealtime() + 5000;
                    updatePlayback();
                    showLyricsChrome();
                });
        modes.addView(karaoke);
        lyricsBottom.addView(modes);
        positionBar = new SeekBar(this);
        positionBar.setTag("lyricsPosition");
        positionBar.setContentDescription("재생 위치");
        Ui.tint(positionBar);
        lyricsBottom.addView(positionBar, new LinearLayout.LayoutParams(-1, dp(40)));
        positionBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int value, boolean user) {
                        if (user && timeView != null) Ui.updateText(timeView, format(value));
                    }

                    public void onStartTrackingTouch(SeekBar bar) {
                        dragging = true;
                        handler.removeCallbacks(hideLyricsChrome);
                    }

                    public void onStopTrackingTouch(SeekBar bar) {
                        seek(bar.getProgress());
                        dragging = false;
                        showLyricsChrome();
                    }
                });
        LinearLayout times = row();
        timeView = text("0:00", 11, muted);
        durationView = text("0:00", 11, muted);
        durationView.setGravity(Gravity.END);
        times.addView(timeView, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(durationView);
        times.setPadding(dp(8), 0, dp(8), dp(10));
        lyricsBottom.addView(times);
        LinearLayout transport = row();
        transport.setGravity(Gravity.CENTER);
        TextView previous =
                playerIcon(
                        "⏮",
                        () -> {
                            if (network != null && network.active()) networkCommand("previous");
                            else if (player != null) player.seekToPreviousMediaItem();
                            showLyricsChrome();
                        });
        transport.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(52)));
        lyricsPlay =
                playerIcon(
                        "▶",
                        () -> {
                            toggle();
                            showLyricsChrome();
                        });
        ((Ui.IconButton) lyricsPlay).iconColor(Ui.onAccent());
        lyricsPlay.setBackground(Ui.flatSurface(this, mint, 30));
        LinearLayout.LayoutParams primary = new LinearLayout.LayoutParams(dp(60), dp(60));
        primary.setMargins(dp(26), 0, dp(26), 0);
        transport.addView(lyricsPlay, primary);
        TextView next =
                playerIcon(
                        "⏭",
                        () -> {
                            if (network != null && network.active()) networkCommand("next");
                            else if (player != null) player.seekToNextMediaItem();
                            showLyricsChrome();
                        });
        transport.addView(next, new LinearLayout.LayoutParams(dp(48), dp(52)));
        lyricsBottom.addView(transport);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottom.setMargins(dp(16), 0, dp(16), dp(16));
        lyricsChrome.addView(lyricsBottom, bottom);
        showLyricsChrome();
    }

    private void lyricsOptions(Track track) {
        showLyricsChrome();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("가사 옵션")
                .setItems(
                        new String[] {
                            "가사 직접 검색",
                            "실시간 가사 다시 찾기",
                            "싱크 −0.5초",
                            "싱크 +0.5초",
                            "싱크 초기화",
                            "가사 표시 정보",
                            "이 곡의 싱크 · LRC 연결"
                        },
                        (dialog, index) -> {
                            switch (index) {
                                case 0 -> searchLyrics(track);
                                case 1 -> refreshLyrics(track);
                                case 2 -> offset(-500);
                                case 3 -> offset(500);
                                case 4 -> {
                                    prefs.edit().putLong("lyricOffset", 0).apply();
                                    offset(0);
                                }
                                case 6 -> featureUi.lyricOptions(track);
                                default ->
                                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                                .setTitle("단어 강조")
                                                .setMessage(
                                                        "단어별 시간이 있으면 해당 타이밍을 사용합니다. 줄 시간만 있는 가사는 줄 구간을 나눠 추정하며 실제 발음과 다를 수 있습니다. 단어 강조는 이 화면에서 끌 수 있습니다.")
                                                .setPositiveButton("확인", null)
                                                .show();
                            }
                            showLyricsChrome();
                        })
                .show();
    }

    private void showLyricsChrome() {
        if (!page.equals("lyrics") || lyricsTop == null) return;
        handler.removeCallbacks(hideLyricsChrome);
        lyricsChrome.setVisibility(View.VISIBLE);
        if (!lyricsChromeShown) {
            lyricsChromeShown = true;
            for (View view : List.of(lyricsTop, lyricsBottom)) {
                view.animate().cancel();
                if (Ui.motion(this)) {
                    if (view.getAlpha() == 1) {
                        view.setAlpha(0);
                        view.setTranslationY(dp(view == lyricsTop ? -8 : 12));
                    }
                    view.animate()
                            .alpha(1)
                            .translationY(0)
                            .setDuration(220)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                } else {
                    view.setAlpha(1);
                    view.setTranslationY(0);
                }
            }
        }
        if (!dragging) handler.postDelayed(hideLyricsChrome, 3000);
    }

    private void fadeLyricsChrome() {
        if (!page.equals("lyrics") || lyricsTop == null) return;
        lyricsChromeShown = false;
        for (View view : List.of(lyricsTop, lyricsBottom)) {
            view.animate().cancel();
            if (Ui.motion(this)) {
                var animation =
                        view.animate()
                                .alpha(0)
                                .translationY(dp(view == lyricsTop ? -8 : 12))
                                .setDuration(260);
                if (view == lyricsBottom)
                    animation.withEndAction(
                            () -> {
                                if (!lyricsChromeShown && page.equals("lyrics"))
                                    lyricsChrome.setVisibility(View.GONE);
                            });
                animation.start();
            } else {
                view.setAlpha(0);
                lyricsChrome.setVisibility(View.GONE);
            }
        }
    }

    private void clearLyricsChrome() {
        handler.removeCallbacks(hideLyricsChrome);
        if (lyricsTop != null) lyricsTop.animate().cancel();
        if (lyricsBottom != null) lyricsBottom.animate().cancel();
        if (lyricsChrome != null) {
            lyricsChrome.removeAllViews();
            lyricsChrome.setVisibility(View.GONE);
        }
        lyricsChromeShown = false;
        lyricsTop = null;
        lyricsBottom = null;
        lyricsPlay = null;
        lyricsTimingLabel = null;
    }

    private void searchLyrics(Track track) {
        if (!prefs.getBoolean("onlineLyrics", false)) {
            toast("설정에서 LRCLIB 온라인 검색을 켜 주세요");
            go("lyrics-settings");
            return;
        }
        EditText input = new EditText(this);
        input.setText(track.artist + " " + track.title);
        new AlertDialog.Builder(this)
                .setTitle("가사 검색")
                .setView(input)
                .setPositiveButton("검색", (dialog, which) -> startLyricsSearch(track, input))
                .setNegativeButton("취소", null)
                .show();
    }

    private void startLyricsSearch(Track track, EditText input) {
        toast("검색하고 있습니다");
        worker.execute(() -> loadLyricsCandidates(track, input));
    }

    private void loadLyricsCandidates(Track track, EditText input) {
        try {
            List<LyricsRepository.Candidate> results =
                    new LyricsRepository(this).search(input.getText().toString());
            onUi(() -> showLyricsCandidates(track, results));
        } catch (Exception e) {
            onUi(() -> toast(e.getMessage()));
        }
    }

    private void showLyricsCandidates(Track track, List<LyricsRepository.Candidate> results) {
        if (results.isEmpty()) {
            toast("검색 결과가 없습니다");
            return;
        }
        String[] labels = results.stream().map(this::lyricsCandidateLabel).toArray(String[]::new);
        new AlertDialog.Builder(this)
                .setTitle("가사 선택")
                .setItems(
                        labels,
                        (dialog, index) ->
                                worker.execute(() -> saveSelectedLyrics(track, results.get(index))))
                .show();
    }

    private String lyricsCandidateLabel(LyricsRepository.Candidate candidate) {
        String timing = candidate.synced() ? "실시간 · " : "일반 · ";
        return timing + candidate.title() + " · " + candidate.artist() + " / " + candidate.album();
    }

    private void saveSelectedLyrics(Track track, LyricsRepository.Candidate candidate) {
        try {
            LyricsRepository.Result selected = new LyricsRepository(this).choose(track, candidate);
            onUi(() -> showSelectedLyrics(track, selected));
        } catch (Exception e) {
            onUi(() -> toast(e.getMessage()));
        }
    }

    private void showSelectedLyrics(Track track, LyricsRepository.Result selected) {
        if (current() == null || !current().uri.equals(track.uri)) {
            toast("가사를 저장했습니다.");
            return;
        }
        lyricGeneration++;
        lyricsKey =
                track.uri
                        + "|"
                        + track.title
                        + "|"
                        + track.artist
                        + "|"
                        + track.album
                        + "|"
                        + track.duration
                        + "|"
                        + prefs.getBoolean("onlineLyrics", false);
        lyricsText = selected.text();
        lyricLines = selected.lines();
        lyricsSource = selected.source();
        lyricsMessage = selected.lines().isEmpty() ? selected.text() : "";
        go("lyrics");
    }

    private void offset(long ms) {
        long global = prefs.getLong("lyricOffset", 0) + ms;
        prefs.edit().putLong("lyricOffset", global).apply();
        lyricOffset =
                global
                        + (current() == null
                                ? 0
                                : MusicCollections.prefs(this)
                                        .getLong(
                                                "lyric-offset:"
                                                        + MusicCollections.key(current().uri),
                                                0));
        activeLine = -2;
        toast("싱크 보정 " + (lyricOffset / 1000.0) + "초");
    }

    private Track findTrack(String id) {
        if (cachedTrackList == tracks && cachedTrack != null && cachedTrack.uri.equals(id))
            return cachedTrack;
        for (Track track : tracks)
            if (track.uri.equals(id)) {
                cachedTrack = track;
                cachedTrackList = tracks;
                return track;
            }
        return null;
    }

    private Track queueTrack(MediaItem item) {
        String uri =
                item.mediaId.isEmpty()
                        ? (item.localConfiguration == null
                                ? ""
                                : item.localConfiguration.uri.toString())
                        : item.mediaId;
        Track known = findTrack(uri);
        if (known != null) return known;
        var m = item.mediaMetadata;
        Track fallback =
                new Track(
                                uri,
                                m.title == null ? "제목 없는 곡" : m.title.toString(),
                                m.artist == null ? "알 수 없는 아티스트" : m.artist.toString(),
                                m.albumTitle == null ? "알 수 없는 앨범" : m.albumTitle.toString(),
                                "",
                                player != null ? Math.max(0, player.getDuration()) : 0,
                                "")
                        .withGenre(m.genre == null ? "" : m.genre.toString())
                        .withNumbers(
                                m.trackNumber == null ? 0 : m.trackNumber,
                                m.discNumber == null ? 0 : m.discNumber);
        if (player != null
                && player.getCurrentMediaItem() != null
                && player.getCurrentMediaItem().mediaId.equals(item.mediaId)) {
            cachedTrack = fallback;
            cachedTrackList = tracks;
            cachedTrackFallback = true;
        }
        return fallback;
    }

    private void queueScreen() {
        if (network != null && network.active()) {
            heading("재생 대기열", "Wi-Fi 재생 중입니다.");
            body.addView(text("연결한 기기에서 대기열을 관리해 주세요.", 14, muted));
            return;
        }
        int count = player == null ? 0 : player.getMediaItemCount();
        heading("재생 대기열", count + "곡 · 곡을 누르면 해당 위치부터 재생합니다.");
        if (count == 0) {
            body.addView(button("라이브러리에서 음악 선택", () -> go("library"), true));
            return;
        }
        List<MediaItem> queued = new ArrayList<>();
        for (int i = 0; i < count; i++) queued.add(player.getMediaItemAt(i));
        virtualList(
                body,
                count,
                index -> {
                    Track track = queueTrack(queued.get(index));
                    LinearLayout row = trackRow(track, List.of(track));
                    boolean active = index == player.getCurrentMediaItemIndex();
                    if (active) row.setBackground(Ui.surface(this, Ui.SOFT, 12));
                    row.setContentDescription(
                            (index + 1) + ". " + track.title + (active ? " · 현재 곡" : ""));
                    Ui.tap(
                            row,
                            () -> {
                                if (index >= player.getMediaItemCount()
                                        || !queued.get(index)
                                                .equals(player.getMediaItemAt(index))) {
                                    toast("대기열이 변경되었습니다. 곡을 다시 선택해 주세요.");
                                    render();
                                    return;
                                }
                                player.seekTo(index, 0);
                                startPlayback();
                                go("player");
                            });
                    return row;
                },
                1);
    }

    boolean musicVolumePlayerVisible() {
        return page.equals("player") || page.equals("lyrics") || dockOpened && wideDock();
    }

    View musicVolumeAnchor() {
        return mini.getVisibility() == View.VISIBLE ? mini : bottomBar;
    }

    private boolean wideDock() {
        int w = root == null ? 0 : root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int h = root == null ? 0 : root.getHeight();
        return MusicBottomBar.wide(w, h, getResources().getDisplayMetrics().density);
    }

    private void syncDock(boolean animate) {
        UsbVolumePopup.hide(false);
        if (adaptivePane == null || dockPlayer == null) return;
        boolean visible = dockOpened && wideDock();
        adaptivePane.dock(visible ? (dockFull || dockLyrics ? 1f : .375f) : 0, animate);
        bottomBar.fullNavigation = visible;
        bottomBar.requestLayout();
        bottomBar.setVisibility(visible && dockFull ? View.GONE : View.VISIBLE);
        if (visible) dockPlayer.modes(dockFull, dockLyrics);
        updatePlayback();
    }

    private void openMiniPlayer() {
        if (wideDock()) {
            dockOpened = true;
            syncDock(true);
            return;
        }
        go("player");
        if (!Ui.motion(this)) return;
        body.animate().cancel();
        body.setAlpha(1);
        body.setTranslationY(Math.max(dp(160), scroll.getHeight()));
        body.animate()
                .translationY(0)
                .setDuration(340)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    void musicCloseDock() {
        dockOpened = false;
        dockLyrics = false;
        syncDock(true);
    }

    void musicToggleDockFull() {
        dockFull = !dockFull;
        prefs.edit().putBoolean("widePlayerFullscreen", dockFull).apply();
        syncDock(true);
    }

    void musicToggleDockLyrics() {
        dockLyrics = !dockLyrics;
        syncDock(true);
    }

    void musicShuffle() {
        if (player != null) player.setShuffleModeEnabled(!player.getShuffleModeEnabled());
    }

    boolean musicShuffled() {
        return player != null && player.getShuffleModeEnabled();
    }

    int musicRepeat() {
        return player == null ? 0 : player.getRepeatMode();
    }

    void musicCycleRepeat() {
        if (player != null) player.setRepeatMode((player.getRepeatMode() + 1) % 3);
    }

    void musicDockQueue() {
        musicCloseDock();
        go("queue");
    }

    void musicTrackMenu(Track t) {
        trackActions(t);
    }

    boolean musicKaraoke() {
        return prefs.getBoolean("karaoke", true);
    }

    String musicDockError() {
        return player == null || player.getPlayerError() == null
                ? ""
                : audioError(player.getPlayerError()) + "\n눌러서 오류 정보를 확인해 주세요.";
    }

    void musicDockErrorMenu() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("오디오 출력 오류")
                .setMessage(musicDockError())
                .setPositiveButton("오류 정보 복사", (d, w) -> copyAudioError())
                .setNeutralButton(
                        "출력 설정",
                        (d, w) -> {
                            musicCloseDock();
                            go("output");
                        })
                .setNegativeButton("닫기", null)
                .show();
    }

    void musicDockSignal(Track t) {
        showSignalDetails(t);
    }

    void musicDockOutput() {
        musicCloseDock();
        go("output");
    }

    String musicDockRoute() {
        return network != null && network.active()
                ? "재생 중 · " + network.label() + " ›"
                : PcmSink.routed.isEmpty() ? "Android 기본 출력 ›" : "출력 · " + PcmSink.routed + " ›";
    }

    String musicDockPreview(Track t) {
        ensureLyrics(t);
        int i = Lrc.active(lyricLines, musicPosition() + lyricOffset);
        return i >= 0
                ? lyricLines.get(i).text()
                : !lyricLines.isEmpty()
                        ? "전주 · 가사가 곧 시작됩니다"
                        : !lyricsText.isEmpty()
                                ? "일반 가사입니다. 전체화면에서 확인해 주세요."
                                : lyricsMessage.contains("찾고")
                                        ? "실시간 가사를 찾고 있습니다…"
                                        : "가사를 보려면 눌러 주세요.";
    }

    String musicSignal() {
        if (network != null && network.active()) return "Wi-Fi · " + network.label();
        return PcmSink.signal == null ? "출력 준비 중" : PcmSink.signal.compact();
    }

    List<Lrc.Line> musicLyricLines(Track t) {
        ensureLyrics(t);
        return lyricLines;
    }

    String musicLyricText() {
        return !lyricsText.isEmpty()
                ? lyricsText
                : lyricsMessage.isEmpty() ? "가사를 찾고 있습니다…" : lyricsMessage;
    }

    long musicLyricOffset() {
        return lyricOffset;
    }

    private void renderMini() {
        mini.removeAllViews();
        miniArtKey = "";
        LinearLayout line = row();
        line.setPadding(dp(10), dp(8), dp(6), dp(8));
        line.setBackground(Ui.flatSurface(this, panel, 22));
        miniArt = new FrameLayout(this);
        miniArt.setContentDescription("지금 재생 중인 곡 열기");
        Ui.tap(miniArt, this::openMiniPlayer);
        line.addView(miniArt, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout info = column();
        info.setPadding(dp(12), 0, dp(6), 0);
        miniTitle = text("음악을 선택해 주세요", 14, ink);
        Ui.lines(miniTitle, 1);
        miniDetail = text("라이브러리에서 재생", 11, muted);
        Ui.lines(miniDetail, 1);
        miniDetail.setPadding(0, dp(5), 0, 0);
        info.addView(miniTitle);
        info.addView(miniDetail);
        Ui.tap(info, this::openMiniPlayer);
        line.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView toggle =
                iconButton(player != null && player.isPlaying() ? "Ⅱ" : "▶", this::toggle);
        toggle.setBackground(Ui.flatSurface(this, mint, 22));
        ((Ui.IconButton) toggle).iconColor(Ui.onAccent());
        toggle.setTag("toggle");
        line.addView(toggle, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView next =
                iconButton(
                        "⏭",
                        () -> {
                            if (network != null && network.active()) networkCommand("next");
                            else if (player != null) player.seekToNextMediaItem();
                        });
        next.setBackground(Ui.flatSurface(this, 0, 22));
        ((Ui.IconButton) next).iconColor(ink);
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        nextParams.leftMargin = dp(6);
        line.addView(next, nextParams);
        Ui.tap(line, this::openMiniPlayer);
        mini.addView(line);
        miniProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        miniProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(mint));
        miniProgress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(panel));
        LinearLayout.LayoutParams progress = new LinearLayout.LayoutParams(-1, dp(2));
        progress.setMargins(dp(14), dp(4), dp(14), 0);
        mini.addView(miniProgress, progress);
    }

    private void renderNav() {
        nav.removeAllViews();
        String[] keys = {"home", "library", "playlists", "stats", "settings"},
                labels = {"홈", "라이브러리", "플레이리스트", "통계", "설정"};
        String selected =
                List.of("group", "queue").contains(page)
                        ? "library"
                        : List.of(
                                                "audio",
                                                "sox",
                                                "eq",
                                                "output",
                                                "cast",
                                                "remote",
                                                "licenses",
                                                "updates",
                                                "drive",
                                                "playback",
                                                "lyrics-settings",
                                                "library-settings")
                                        .contains(page)
                                ? "settings"
                                : page;
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            boolean active = selected.equals(key);
            TextView b = text(labels[i], 10, active ? mint : muted);
            b.setGravity(Gravity.CENTER);
            b.setCompoundDrawablesWithIntrinsicBounds(
                    null, new Ui.Icon(key, active ? mint : muted, dp(22)), null, null);
            b.setCompoundDrawablePadding(dp(5));
            b.setPadding(0, dp(9), 0, dp(6));
            b.setBackground(Ui.flatSurface(this, active ? Ui.SOFT : bg, 16));
            b.setSelected(active);
            b.setContentDescription(labels[i] + (active ? " · 선택됨" : ""));
            Ui.tap(b, () -> go(key));
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(59), 1);
            itemParams.setMargins(dp(3), 0, dp(3), 0);
            nav.addView(b, itemParams);
        }
    }

    private void updatePlayback() {
        if (dockPlayer != null && dockOpened && wideDock()) dockPlayer.update();
        if (page.equals("lyrics")) {
            Track now = current();
            if (now != null && !now.uri.equals(lyricsRenderedTrack)) {
                resetLyricsScroll = true;
                lyricsKey = "";
                render();
                return;
            }
        }
        updateSignal();
        syncLyricsFrames();
        if (player == null) return;
        updatePlayerModes();
        updateUsbVolume();
        boolean remote = network != null && network.active();
        Track t = remote ? network.track() : null;
        MediaMetadata m = player.getMediaMetadata();
        boolean isPlaying = remote ? network.playing() : player.isPlaying();
        boolean hasMedia = remote || player.getCurrentMediaItem() != null;
        mini.setVisibility(
                hasMedia
                                && !page.equals("player")
                                && !page.equals("lyrics")
                                && !(dockOpened && wideDock())
                        ? View.VISIBLE
                        : View.GONE);
        if (miniTitle != null) {
            Ui.updateText(
                    miniTitle, t != null ? t.title : m.title == null ? "음악을 선택해 주세요" : m.title);
            String artist = t != null ? t.artist : m.artist == null ? "" : m.artist.toString();
            String state =
                    !remote && player.getPlayerError() != null
                            ? "재생 확인 필요"
                            : !remote && player.getPlaybackState() == Player.STATE_BUFFERING
                                    ? "불러오는 중…"
                                    : !remote && player.getPlaybackState() == Player.STATE_ENDED
                                            ? "재생 완료"
                                            : isPlaying ? "재생 중" : "일시정지";
            Ui.updateText(miniDetail, state + (artist.isEmpty() ? "" : " · " + artist));
            String key =
                    t != null
                            ? t.uri
                            : player.getCurrentMediaItem() == null
                                    ? ""
                                    : player.getCurrentMediaItem().mediaId;
            if (miniArt != null && !key.equals(miniArtKey)) {
                Track cover =
                        t != null
                                ? t
                                : player.getCurrentMediaItem() == null
                                        ? null
                                        : queueTrack(player.getCurrentMediaItem());
                miniArtKey = key;
                miniArt.removeAllViews();
                if (cover != null)
                    miniArt.addView(art(cover, 42), new FrameLayout.LayoutParams(-1, -1));
            }
        }
        TextView toggle = mini.findViewWithTag("toggle");
        if (toggle != null) Ui.updateText(toggle, isPlaying ? "Ⅱ" : "▶");
        if (playButton != null) Ui.updateText(playButton, isPlaying ? "Ⅱ" : "▶");
        if (lyricsPlay != null) Ui.updateText(lyricsPlay, isPlaying ? "Ⅱ" : "▶");
        long duration = Math.max(0, remote ? (t == null ? 0 : t.duration) : player.getDuration()),
                position = Math.max(0, remote ? network.position() : player.getCurrentPosition());
        if (miniProgress != null) {
            miniProgress.setMax(1000);
            miniProgress.setProgress(
                    duration > 0 ? (int) Math.min(1000, position * 1000 / duration) : 0);
        }
        if (positionBar != null && !dragging) {
            positionBar.setMax((int) Math.min(Integer.MAX_VALUE, duration));
            positionBar.setProgress((int) Math.min(Integer.MAX_VALUE, position));
        }
        if (timeView != null) Ui.updateText(timeView, format(position));
        if (durationView != null) Ui.updateText(durationView, format(duration));
        if (engineView != null)
            Ui.updateText(engineView, remote ? "Wi-Fi · " + network.label() : PcmSink.status);
        if (errorNotice != null) {
            PlaybackException error = player.getPlayerError();
            Ui.updateText(errorNotice, !remote && error != null ? audioError(error) : "");
            errorNotice.setVisibility(!remote && error != null ? View.VISIBLE : View.GONE);
            View recover = body.findViewWithTag("audioRecovery");
            if (recover != null)
                recover.setVisibility(!remote && error != null ? View.VISIBLE : View.GONE);
            View copy = body.findViewWithTag("copyAudioError");
            if (copy != null)
                copy.setVisibility(!remote && error != null ? View.VISIBLE : View.GONE);
        }
        if (SystemClock.elapsedRealtime() - lastRouteUpdate >= 1000) {
            lastRouteUpdate = SystemClock.elapsedRealtime();
            updateRoute();
        }
        if (remote && !network.lastError.isEmpty()) {
            toast(network.lastError);
            network.lastError = "";
        }
        updateLyrics(position);
    }

    private void updateSignal() {
        if (signalSummary == null) return;
        boolean remote = network != null && network.active();
        SignalFormat value = remote ? null : PcmSink.signal;
        if (value == renderedSignal
                && renderedSignalRemote != null
                && renderedSignalRemote == remote) return;
        renderedSignal = value;
        renderedSignalRemote = remote;
        Ui.updateText(
                signalSummary,
                remote ? "캐스트 · 출력 형식 미확인" : value == null ? "출력 준비 중" : value.compact());
        signalSummary.setContentDescription(
                remote
                        ? "캐스트 수신 기기의 최종 출력 형식은 확인할 수 없습니다."
                        : value == null
                                ? "재생 형식을 확인하고 있습니다."
                                : "입력 PCM "
                                        + value.input()
                                        + ", 앱 출력 "
                                        + value.output()
                                        + ", "
                                        + value.processing()
                                        + ". 눌러서 자세히 보기");
    }

    private void showSignalDetails(Track track) {
        SignalFormat value = PcmSink.signal;
        boolean remote = network != null && network.active();
        String message =
                remote
                        ? "캐스트 수신 기기의 최종 출력 형식은 확인할 수 없습니다."
                        : value == null
                                ? "재생이 시작되면 실제 앱 처리 형식을 표시합니다."
                                : "입력 PCM\n"
                                        + value.input()
                                        + "\n\n앱 출력\n"
                                        + value.output()
                                        + "\n\n"
                                        + value.processing()
                                        + "\n\n앱이 출력한 PCM 형식입니다. 시스템 믹서나 DAC 내부의 최종 형식은 다를 수 있습니다.";
        new AlertDialog.Builder(this)
                .setTitle("재생 형식")
                .setMessage(message)
                .setPositiveButton("닫기", null)
                .setNeutralButton(
                        "곡 정보",
                        (d, w) -> SongInfo.show(this, current() == null ? track : current()))
                .show();
    }

    private void styleLyricLine(int index, boolean active, boolean karaoke, boolean motion) {
        if (index < 0 || index >= lyricViews.size()) return;
        TextView view = lyricViews.get(index);
        view.setTextColor(active && !karaoke ? mint : muted);
        if (view instanceof KaraokeLineView word)
            word.highlight(new KaraokeTiming.Progress(0, 0, 0), false, mint);
        view.animate().cancel();
        if (motion) view.animate().alpha(active ? 1 : .43f).setDuration(200).start();
        else view.setAlpha(active ? 1 : .43f);
    }

    private void updateLyrics(long position) {
        if (lyricPreview != null) {
            int current = Lrc.active(lyricLines, position + lyricOffset);
            String value =
                    current >= 0
                            ? lyricLines.get(current).text()
                            : !lyricLines.isEmpty()
                                    ? "전주 · 가사가 곧 시작됩니다"
                                    : !lyricsText.isEmpty()
                                            ? "일반 가사입니다. 전체화면에서 확인해 주세요."
                                            : lyricsMessage.contains("찾고")
                                                    ? "실시간 가사를 찾고 있습니다…"
                                                    : "가사를 보려면 눌러 주세요.";
            if (lyricsStatus != null)
                Ui.updateText(lyricsStatus, (lyricLines.isEmpty() ? "가사" : "실시간 가사") + " · 전체화면 ›");
            if (!value.contentEquals(lyricPreview.getText())) {
                lyricPreview.setText(value);
                if (Ui.motion(this)) {
                    lyricPreview.setAlpha(.45f);
                    lyricPreview.animate().alpha(1).setDuration(220).start();
                }
            }
        }
        if (!page.equals("lyrics") || lyricViews.isEmpty()) return;
        long at = position + lyricOffset;
        int now = Lrc.active(lyricLines, at);
        boolean karaoke = prefs.getBoolean("karaoke", true), changed = now != activeLine;
        if (changed) {
            boolean motion = Ui.motion(this);
            if (activeLine == -2) {
                for (int i = 0; i < lyricViews.size(); i++)
                    styleLyricLine(i, false, karaoke, false);
            } else styleLyricLine(activeLine, false, karaoke, motion);
            styleLyricLine(now, true, karaoke, motion);
            activeLine = now;
        }
        if (now >= 0 && now < lyricViews.size()) {
            if ((changed || lyricsFollowPending)
                    && SystemClock.elapsedRealtime() > lyricsManualUntil) {
                lyricsFollowPending = false;
                scroll.smoothScrollTo(
                        0, Math.max(0, lyricViews.get(now).getTop() - scroll.getHeight() / 3));
            }
            if (now < lyricTimings.size() && lyricViews.get(now) instanceof KaraokeLineView view) {
                var timing = lyricTimings.get(now);
                view.highlight(
                        KaraokeTiming.progress(lyricLines.get(now).text(), timing, at),
                        karaoke,
                        mint);
                if (lyricsTimingLabel != null)
                    Ui.updateText(
                            lyricsTimingLabel,
                            !karaoke
                                    ? "줄 싱크"
                                    : timing.slots().isEmpty()
                                            ? "간주"
                                            : timing.estimated() ? "줄 기준 추정 강조" : "단어 싱크");
            }
        }
    }

    private void seek(long position) {
        if (network != null && network.active()) {
            if (network.isCast())
                try {
                    network.seek(position);
                } catch (Exception e) {
                    toast(e.getMessage());
                }
            else
                worker.execute(
                        () -> {
                            try {
                                network.seek(position);
                            } catch (Exception e) {
                                onUi(() -> toast(e.getMessage()));
                            }
                        });
        } else if (player != null) player.seekTo(position);
    }

    private String audioError(PlaybackException error) {
        boolean output =
                error.getErrorCodeName().contains("AUDIO_TRACK") || !PcmSink.lastFailure.isEmpty();
        if (output) return "오디오 출력을 시작할 수 없습니다. 출력 기기와 형식을 확인해 주세요. 자세한 정보는 오류 정보 복사로 확인할 수 있습니다.";
        if (current() != null && current().drive())
            return "Drive 음원을 불러오지 못했습니다. 인터넷 연결과 Google Drive 읽기 권한을 확인해 주세요.";
        return "음원을 재생하지 못했습니다. 파일 접근 권한과 지원 형식을 확인해 주세요.";
    }

    private String deepestMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private void copyAudioError() {
        if (player == null || player.getPlayerError() == null) return;
        StringBuilder info =
                new StringBuilder("AURA ")
                        .append(BuildConfig.VERSION_NAME)
                        .append("\n")
                        .append(Build.MANUFACTURER)
                        .append(" ")
                        .append(Build.MODEL)
                        .append(" / Android ")
                        .append(Build.VERSION.RELEASE)
                        .append("\n")
                        .append(AudioSettings.current.mode())
                        .append(" / ")
                        .append(PcmSink.status)
                        .append("\n")
                        .append(player.getPlayerError().getErrorCodeName());
        for (Throwable error = player.getPlayerError(); error != null; error = error.getCause())
            info.append("\n")
                    .append(error.getClass().getSimpleName())
                    .append(": ")
                    .append(error.getMessage());
        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("AURA 오류 정보", info));
        toast("오류 정보를 복사했습니다.");
    }

    private void recoverAudio() {
        AudioSettings.put("mode", "android");
        AudioSettings.put("device", 0);
        AudioSettings.put("perfect", false);
        AudioSettings.put("bits", 0);
        AudioSettings.put("rate", 0);
        AudioSettings.put("sox", false);
        AudioSettings.put("dsd", "pcm");
        PlaybackService service = PlaybackService.instance;
        if (service != null) service.applyAudioSettings();
        if (player != null) player.play();
        render();
        toast("기본 Android 출력으로 다시 시도합니다.");
    }

    private void toggle() {
        if (player == null) return;
        if (network != null && network.active()) {
            networkCommand(network.playing() ? "pause" : "play");
            return;
        }
        if (player.isPlaying()) player.pause();
        else if (player.getCurrentMediaItem() != null) startPlayback();
        else go("library");
    }

    private void pickFolder() {
        if (scanning) {
            toast("검색이 끝날 때까지 기다려 주세요");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, 10);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (featureUi.result(request, result, data)) return;
        if (driveUi.result(request, result, data)) return;
        if (request == 10 && result == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver()
                        .takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                var roots = Library.roots(this);
                if (!roots.contains(tree)) roots.add(tree);
                Library.saveRoots(this, roots);
                scan(null);
            } catch (SecurityException e) {
                toast("폴더 읽기 권한이 필요합니다");
            }
        }
    }

    private void scan(Uri uri) {
        startScan("local");
    }

    void scanDrive() {
        startScan("drive");
    }

    private void startScan(String kind) {
        startScan(kind, true);
    }

    private void startScan(String kind, boolean permission) {
        if (LibraryScanService.state.running()) return;
        if (permission
                && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 31);
        try {
            LibraryScanService.start(this, kind, !permission);
            scanning = true;
            toast("백그라운드에서 음악을 검색합니다.");
        } catch (Exception e) {
            toast("검색을 시작하지 못했습니다. 앱을 연 상태에서 다시 시도해 주세요.");
        }
        render();
    }

    private List<Track> loadMusic() {
        List<Track> rows = library.load();
        try {
            BackupLinks.apply(this, rows);
        } catch (Exception e) {
            android.util.Log.w("AURA", "백업 음악 연결 확인 필요", e);
        }
        return MusicCollections.visible(this, rows);
    }

    private void reloadLibrary() {
        worker.execute(
                () -> {
                    List<Track> loaded = loadMusic();
                    onUi(
                            () -> {
                                tracks = loaded;
                                libraryLoading = false;
                                restoreQueue();
                                render();
                            });
                });
    }

    void removeDriveQueue() {
        PlaybackService service = PlaybackService.instance;
        if (service != null) service.removeDriveQueue();
    }

    private void updateScan() {
        if (driveGeneration != DriveLibrary.revision) {
            driveGeneration = DriveLibrary.revision;
            reloadLibrary();
        }
        var state = LibraryScanService.state;
        if (scanPanel == null) return;
        scanning = state.running();
        if (scanning) hiddenScanRevision = Long.MIN_VALUE;
        if (!state.message().equals(scanMessage)) {
            scanMessage = state.message();
            scanFinishedAt = SystemClock.elapsedRealtime();
        }
        boolean success = !scanning && state.message().endsWith("곡을 검색했습니다.");
        boolean visible =
                !state.message().isEmpty()
                        && (scanning
                                || hiddenScanRevision != state.revision()
                                        && (!success
                                                || SystemClock.elapsedRealtime() - scanFinishedAt
                                                        < 8000));
        scanPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        scanDismiss.setVisibility(scanning ? View.GONE : View.VISIBLE);
        scanProgress.setVisibility(scanning ? View.VISIBLE : View.GONE);
        Ui.updateText(scanStatus, state.message());
        scanProgress.setIndeterminate(state.total() < 0);
        if (state.total() >= 0) {
            scanProgress.setMax(Math.max(1, state.total()));
            scanProgress.setProgress(state.completed());
        }
        if (scanRevision < 0) scanRevision = state.revision();
        if (!state.running() && state.revision() != scanRevision && !scanLoading) {
            scanLoading = true;
            long revision = state.revision();
            worker.execute(
                    () -> {
                        var loaded = loadMusic();
                        onUi(
                                () -> {
                                    tracks = loaded;
                                    libraryLoading = false;
                                    scanRevision = revision;
                                    scanLoading = false;
                                    render();
                                    toast(state.message());
                                });
                    });
        }
    }

    private void preferenceSwitch(String title, String key, boolean fallback, Runnable changed) {
        body.addView(
                Ui.toggle(
                        this,
                        title,
                        getSharedPreferences("playback", 0).getBoolean(key, fallback),
                        on -> {
                            getSharedPreferences("playback", 0).edit().putBoolean(key, on).apply();
                            if (changed != null) changed.run();
                        }));
    }

    private void playbackPreferences() {
        section("알림과 출력 전환");
        var p = getSharedPreferences("playback", 0);
        String[] choices = {"음악 볼륨을 낮춥니다", "음악을 일시정지합니다"};
        body.addView(
                menu(
                        "알림 소리가 날 때",
                        choices[p.getBoolean("pauseOnDuck", false) ? 1 : 0],
                        () ->
                                new AlertDialog.Builder(this)
                                        .setTitle("알림 소리가 날 때")
                                        .setSingleChoiceItems(
                                                choices,
                                                p.getBoolean("pauseOnDuck", false) ? 1 : 0,
                                                (dialog, index) -> {
                                                    p.edit()
                                                            .putBoolean("pauseOnDuck", index == 1)
                                                            .apply();
                                                    dialog.dismiss();
                                                    render();
                                                })
                                        .setNegativeButton("취소", null)
                                        .show()));
        preferenceSwitch("알림·통화 종료 후 자동으로 재생합니다", "resumeFocus", true, null);
        preferenceSwitch("새 블루투스·유선 기기로 자동 전환합니다", "autoNewOutput", true, null);
        preferenceSwitch("USB DAC 연결 시 자동으로 선택합니다", "autoUsb", true, null);
        preferenceSwitch("USB DAC 연결 시 음악을 일시정지합니다", "pauseUsbAttach", false, null);
        preferenceSwitch("USB DAC 분리 시 음악을 일시정지합니다", "pauseUsbDetach", true, null);
        body.addView(
                text(
                        "통화나 다른 앱이 오디오를 독점하면 일시정지합니다. 비트퍼펙트·USB 직접 출력은 원본 보존을 위해 볼륨 감소 대신 일시정지합니다. 출력 엔진과 비트퍼펙트·DSD 설정은 연결·분리 후에도 유지됩니다. Android/Hi-Res 엔진은 출력 기기만 전환하며, USB 직접 출력은 DAC가 없으면 연결을 기다립니다. 연결·분리 시 일시정지는 각각 끌 수 있습니다.",
                        12,
                        muted));
    }

    private void section(String title) {
        TextView v = text(title, 15, mint);
        v.setPadding(0, dp(24), 0, dp(12));
        body.addView(v);
    }

    private LinearLayout menu(String title, String sub, Runnable click) {
        LinearLayout item = column();
        item.setPadding(dp(18), dp(16), dp(18), dp(16));
        item.setBackground(Ui.flatSurface(this, panel, 20));
        LinearLayout head = row();
        TextView label = text(title, 15, ink);
        Ui.lines(label, 2);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("›", 20, muted);
        arrow.setPadding(dp(12), 0, 0, 0);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(arrow);
        item.addView(head);
        TextView detail = text(sub, 12, muted);
        detail.setPadding(0, dp(6), 0, 0);
        Ui.lines(detail, 2);
        item.addView(detail);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        item.setLayoutParams(lp);
        Ui.tap(item, click);
        return item;
    }

    private ArtworkView art(Track track, int size) {
        return new ArtworkView(this, track, size);
    }

    private TextView text(String s, float size, int color) {
        return Ui.text(this, s, size, color);
    }

    private TextView button(String label, Runnable action, boolean primary) {
        return Ui.button(this, label, action, primary);
    }

    private TextView iconButton(String label, Runnable action) {
        TextView b = new Ui.IconButton(this, label);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(54), dp(54)));
        Ui.tap(b, action);
        return b;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(margin);
        parent.addView(child, lp);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static String format(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        statsUi.hide();
        clearLyricsChrome();
        unregisterReceiver(usbPermission);
        driveUi.close();
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        searches.shutdownNow();
        ((AudioManager) getSystemService(AUDIO_SERVICE)).unregisterAudioDeviceCallback(devices);
        if (player != null) player.removeListener(listener);
        if (future != null) MediaController.releaseFuture(future);
        super.onDestroy();
    }

    void musicList(LinearLayout host, List<Track> songs) {
        virtualList(
                host,
                songs.size(),
                i -> trackRow(songs.get(i), songs),
                1,
                (view, i) -> ((SongCell) view).bind(songs.get(i), songs));
    }

    List<Track> musicTracks() {
        return tracks;
    }

    Track musicCurrent() {
        return current();
    }

    long musicPosition() {
        return Math.max(
                0,
                network != null && network.active()
                        ? network.position()
                        : player == null ? 0 : player.getCurrentPosition());
    }

    long musicDuration() {
        return Math.max(
                0,
                network != null && network.active()
                        ? (current() == null ? 0 : current().duration)
                        : player == null ? 0 : player.getDuration());
    }

    boolean musicPlaying() {
        return network != null && network.active()
                ? network.playing()
                : player != null && player.isPlaying();
    }

    boolean musicRemote() {
        return network != null && network.active();
    }

    void musicSeek(long p) {
        seek(p);
    }

    void musicPlay(Track t, List<Track> q) {
        play(t, q);
    }

    void musicPlayAt(Track t, long pos) {
        play(t, List.of(t));
        if (player != null) player.seekTo(pos);
    }

    void musicOpen(String title, List<Track> songs) {
        openGroup(title, songs);
    }

    void musicRender() {
        render();
    }

    void musicReload() {
        reloadLibrary();
    }

    void musicGo(String p) {
        go(p);
    }

    void musicActions(Track t) {
        featureUi.action(t);
    }

    void musicToggle() {
        toggle();
    }

    void musicStep(boolean next) {
        if (network != null && network.active()) networkCommand(next ? "next" : "previous");
        else if (player != null) {
            if (next) player.seekToNextMediaItem();
            else player.seekToPreviousMediaItem();
        }
    }

    void musicLyricsChanged() {
        lyricsKey = "";
        activeLine = -2;
        render();
    }

    String musicPage() {
        return page;
    }

    record Upcoming(Track track, int index) {}

    List<Upcoming> musicUpcoming() {
        List<Upcoming> out = new ArrayList<>();
        if (player == null || musicRemote()) return out;
        for (int i = player.getCurrentMediaItemIndex() + 1;
                i < player.getMediaItemCount() && out.size() < 12;
                i++) out.add(new Upcoming(queueTrack(player.getMediaItemAt(i)), i));
        return out;
    }

    void musicSelectQueued(Upcoming item) {
        if (player != null
                && item.index() < player.getMediaItemCount()
                && player.getMediaItemAt(item.index()).mediaId.equals(item.track().uri)) {
            player.seekTo(item.index(), 0);
            startPlayback();
        }
    }
}

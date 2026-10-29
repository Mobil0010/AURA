package dev.aura.player;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.Bitmap;
import android.widget.RemoteViews;
import androidx.media3.common.*;
import java.util.concurrent.*;

@androidx.media3.common.util.UnstableApi
public final class MusicWidget extends AppWidgetProvider {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static String title = "AURA", artist = "음악을 선택해 주세요.", uri = "";
    static boolean playing;

    @Override
    public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        update(c);
    }

    static void state(Context c, MediaItem item, boolean play) {
        title = item == null ? "AURA" : String.valueOf(item.mediaMetadata.title);
        artist = item == null ? "음악을 선택해 주세요." : String.valueOf(item.mediaMetadata.artist);
        uri = item == null ? "" : item.mediaId;
        playing = play;
        update(c);
    }

    static void update(Context c) {
        Context app = c.getApplicationContext();
        String song = title, who = artist, key = uri;
        boolean play = playing;
        int[] ids =
                AppWidgetManager.getInstance(app)
                        .getAppWidgetIds(new ComponentName(app, MusicWidget.class));
        if (ids.length == 0) return;
        IO.execute(
                () -> {
                    RemoteViews v = views(app, song, who, key, play);
                    AppWidgetManager.getInstance(app).updateAppWidget(ids, v);
                });
    }

    static RemoteViews views(Context app, String song, String who, String key, boolean play) {
        RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.music_widget);
        v.setTextViewText(R.id.widget_title, song);
        v.setTextViewText(R.id.widget_artist, who);
        v.setImageViewResource(
                R.id.widget_play, play ? R.drawable.ic_widget_pause : R.drawable.ic_widget_play);
        Bitmap art = ArtworkStore.file(Library.artFile(app, key), 160);
        if (art != null) v.setImageViewBitmap(R.id.widget_art, art);
        else v.setImageViewResource(R.id.widget_art, R.drawable.ic_aura);
        v.setOnClickPendingIntent(
                R.id.widget_open,
                PendingIntent.getActivity(
                        app,
                        70,
                        new Intent(app, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        int[] buttons = {R.id.widget_previous, R.id.widget_play, R.id.widget_next};
        String[] actions = {"previous", "toggle", "next"};
        for (int i = 0; i < buttons.length; i++)
            v.setOnClickPendingIntent(
                    buttons[i],
                    PendingIntent.getBroadcast(
                            app,
                            71 + i,
                            new Intent(app, MusicWidget.class)
                                    .setAction("dev.aura.player.widget." + actions[i]),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (i.getAction() == null || !i.getAction().startsWith("dev.aura.player.widget.")) return;
        String action = i.getAction().substring("dev.aura.player.widget.".length());
        PendingResult pending = goAsync();
        var future =
                new androidx.media3.session.MediaController.Builder(
                                c.getApplicationContext(),
                                new androidx.media3.session.SessionToken(
                                        c.getApplicationContext(),
                                        new ComponentName(
                                                c.getApplicationContext(), PlaybackService.class)))
                        .buildAsync();
        java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean();
        Runnable done =
                () -> {
                    if (finished.compareAndSet(false, true)) pending.finish();
                };
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(
                        () -> {
                            if (!future.isDone()) future.cancel(true);
                            done.run();
                        },
                        8000);
        future.addListener(
                () -> {
                    try {
                        var controller = future.get();
                        if (PlaybackService.instance != null)
                            PlaybackService.instance.widgetCommand(action);
                        controller.release();
                    } catch (Exception ignored) {
                    } finally {
                        done.run();
                    }
                },
                androidx.core.content.ContextCompat.getMainExecutor(c));
    }
}

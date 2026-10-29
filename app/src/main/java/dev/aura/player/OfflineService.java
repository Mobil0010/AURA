package dev.aura.player;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;

/** Sequential foreground downloads, independent of Activity lifetime. */
public final class OfflineService extends Service {
    static volatile String status = "";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancel;
    private int pending;
    private long lastNotice;

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class)
                .createNotificationChannel(
                        new NotificationChannel(
                                "offline", "오프라인 음악 보관", NotificationManager.IMPORTANCE_LOW));
    }

    Notification notice(String text) {
        Intent stop = new Intent(this, OfflineService.class).setAction("cancel");
        Intent open = new Intent(this, MainActivity.class);
        return new Notification.Builder(this, "offline")
                .setSmallIcon(R.drawable.ic_stat_aura)
                .setContentTitle("AURA · 오프라인 보관")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(
                        PendingIntent.getActivity(
                                this,
                                60,
                                open,
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(
                        new Notification.Action.Builder(
                                        null,
                                        "취소",
                                        PendingIntent.getService(
                                                this,
                                                61,
                                                stop,
                                                PendingIntent.FLAG_IMMUTABLE
                                                        | PendingIntent.FLAG_UPDATE_CURRENT))
                                .build())
                .build();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i == null) {
            stopSelf(id);
            return START_NOT_STICKY;
        }
        if ("cancel".equals(i.getAction())) {
            cancel = true;
            if (pending == 0) stopSelf(id);
            return START_NOT_STICKY;
        }
        cancel = false;
        pending++;
        startForeground(604, notice("보관할 음악을 준비하고 있습니다."));
        java.util.List<Track> rows = OfflineMusic.jobs.remove(i.getStringExtra("job"));
        worker.execute(
                () -> {
                    try {
                        if (rows == null) throw new java.io.IOException("보관할 음악을 다시 선택해 주세요.");
                        for (int n = 0; n < rows.size() && !cancel; n++) {
                            Track t = rows.get(n);
                            int at = n + 1;
                            OfflineMusic.download(
                                    this,
                                    t,
                                    bytes -> {
                                        if (cancel)
                                            throw new java.util.concurrent.CancellationException();
                                        if (android.os.SystemClock.elapsedRealtime() - lastNotice
                                                > 700) {
                                            lastNotice = android.os.SystemClock.elapsedRealtime();
                                            status =
                                                    at
                                                            + " / "
                                                            + rows.size()
                                                            + " · "
                                                            + t.title
                                                            + " · "
                                                            + (bytes / 1048576)
                                                            + " MB";
                                            getSystemService(NotificationManager.class)
                                                    .notify(604, notice(status));
                                        }
                                    });
                        }
                        status = cancel ? "보관을 취소했습니다." : "오프라인 보관을 완료했습니다.";
                    } catch (Exception e) {
                        status =
                                "오프라인 보관 중단 · "
                                        + (e.getMessage() == null
                                                ? "연결을 확인해 주세요."
                                                : e.getMessage());
                    } finally {
                        new Handler(Looper.getMainLooper())
                                .post(
                                        () -> {
                                            if (--pending == 0) {
                                                stopForeground(STOP_FOREGROUND_REMOVE);
                                                stopSelf();
                                            }
                                        });
                    }
                });
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onDestroy() {
        cancel = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}

package org.oylama.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class TallyService extends Service {
    static final int ID = 7;
    static volatile String title = "Oylama";
    static volatile String label = "Starting";
    static volatile int done;
    static volatile int total;
    static volatile long lastUpdate;
    private PowerManager.WakeLock lock;

    static void start(Context c, String electionTitle) {
        title = electionTitle;
        label = "Starting the encrypted tally";
        done = 0;
        total = 0;
        try {
            c.startForegroundService(new Intent(c, TallyService.class));
        } catch (Exception ignored) {
        }
    }

    static void update(Context c, int d, int t, String l) {
        done = d;
        total = t;
        label = l;
        long now = System.currentTimeMillis();
        if (now - lastUpdate < 1500) {
            return;
        }
        lastUpdate = now;
        try {
            c.getSystemService(NotificationManager.class).notify(ID, build(c, true));
        } catch (Exception ignored) {
        }
    }

    static void finish(Context c, boolean ok) {
        OylamaApp app = (OylamaApp) c.getApplicationContext();
        if (!app.node().tallyRunning()) {
            c.stopService(new Intent(c, TallyService.class));
        }
        try {
            Notification.Builder b = new Notification.Builder(c, OylamaApp.CHANNEL_RESULTS)
                .setSmallIcon(R.drawable.ic_chart)
                .setContentTitle(ok ? "Result published" : "Tally stopped")
                .setContentText(ok ? title + ": every proof verified" : title)
                .setContentIntent(open(c))
                .setAutoCancel(true);
            c.getSystemService(NotificationManager.class).notify(ID + 1, b.build());
        } catch (Exception ignored) {
        }
    }

    static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification build(Context c, boolean ongoing) {
        Notification.Builder b = new Notification.Builder(c, OylamaApp.CHANNEL_TALLY)
            .setSmallIcon(R.drawable.ic_activity)
            .setContentTitle("Tallying " + title)
            .setContentText(label)
            .setContentIntent(open(c))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true);
        if (total > 0) {
            b.setProgress(total, done, false);
            b.setSubText(Math.round(100.0 * done / total) + "%");
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, build(this, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ID, build(this, true));
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (lock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oylama:tally");
            lock.acquire(45 * 60 * 1000L);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (lock != null && lock.isHeld()) {
            lock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

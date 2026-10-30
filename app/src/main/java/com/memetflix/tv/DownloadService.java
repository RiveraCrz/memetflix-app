package com.memetflix.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Mantiene viva la app mientras hay descargas (notificación con el avance), para que
 * sigan aunque el usuario salga de Memetflix o apague la pantalla.
 */
public class DownloadService extends Service {

    private static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 42;

    private OfflineStore store;
    private PowerManager.WakeLock wakeLock;
    private final OfflineStore.Listener listener = new OfflineStore.Listener() {
        @Override
        public void onUpdate(String itemJson) {
            refresh();
        }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, DownloadService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignored) {
            // Sin servicio la descarga sigue mientras la app esté abierta
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = OfflineStore.get(this);
        store.addListener(listener);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "memetflix:download");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        refresh();
        return START_NOT_STICKY;
    }

    private void refresh() {
        if (!store.hasActiveDownloads()) {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Descargando en Memetflix")
                .setContentText(store.activeSummary())
                .setProgress(100, store.activeProgress(), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        store.removeListener(listener);
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

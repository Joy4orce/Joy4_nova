package com.archos.mediacenter.video.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.archos.mediacenter.video.R;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalPlayerService extends Service {

    private static final String ACTION_STOP_SERVICE = "StopExternalPlayerService";

    private static final String NOTIFICATION_CHANNEL_ID = "ExternalPlayerService";
    private static final int NOTIFICATION_ID = 13;

    private static final Logger log = LoggerFactory.getLogger(ExternalPlayerService.class);

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void onCreate() {
        super.onCreate();
        log.debug("onCreate()");

        IntentFilter intentFilter = new IntentFilter(ACTION_STOP_SERVICE);
        registerReceiver(stopServiceBroadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);

        startForeground(NOTIFICATION_ID, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log.debug("onStartCommand({})", intent);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log.debug("onDestroy()");
        unregisterReceiver(stopServiceBroadcastReceiver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel(notificationManager);

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSmallIcon(R.drawable.nova_notification)
                .setContentTitle(getString(R.string.external_player_service_title))
                .setContentText(getString(R.string.external_player_service_description))
                .setWhen(0)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, getString(R.string.stop), createStopServicePendingIntent())
                .build();
    }

    private void createNotificationChannel(NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel notificationChannel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.external_player_service_title),
                NotificationManager.IMPORTANCE_LOW
        );

        notificationChannel.enableVibration(false);
        notificationChannel.enableLights(false);
        notificationChannel.setShowBadge(false);

        notificationManager.createNotificationChannel(notificationChannel);
    }

    private PendingIntent createStopServicePendingIntent() {
        Intent intent = new Intent(ACTION_STOP_SERVICE);
        intent.setPackage(getPackageName());

        return PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static Intent createServiceIntent(Context context) {
        return new Intent(context, ExternalPlayerService.class);
    }

    public static void startService(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            final Intent intent = createServiceIntent(context);
            context.startForegroundService(intent);
        }
    }

    public static void stopService(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            final Intent intent = createServiceIntent(context);
            context.stopService(intent);
        }
    }

    private final BroadcastReceiver stopServiceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP_SERVICE.equals(intent.getAction())) {
                stopSelf();
            }
        }

    };

}

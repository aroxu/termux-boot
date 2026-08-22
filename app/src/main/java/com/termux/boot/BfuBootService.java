package com.termux.boot;

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
import android.os.UserManager;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BfuBootService extends Service {

    static final String ACTION_START = "com.termux.boot.action.START_BFU";

    private static final String TAG = "TermuxBFU";
    private static final String NOTIFICATION_CHANNEL_ID = "termux_bfu";
    private static final int NOTIFICATION_ID = 2222;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean probeStarted = new AtomicBoolean(false);

    private final BroadcastReceiver userUnlockedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                Log.i(TAG, "USER_UNLOCKED received");
                handOffAfterUnlock();
            }
        }
    };
    private boolean unlockReceiverRegistered;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification());
        registerUnlockReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isUserUnlocked()) {
            handOffAfterUnlock();
            return START_NOT_STICKY;
        }

        if (!BfuPreferences.isEnabled(this)) {
            Log.i(TAG, "BFU service stopped because BFU mode is disabled");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (probeStarted.compareAndSet(false, true)) {
            executor.execute(this::provisionAndProbeRuntime);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (unlockReceiverRegistered) {
            unregisterReceiver(userUnlockedReceiver);
            unlockReceiverRegistered = false;
        }
        executor.shutdownNow();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void provisionAndProbeRuntime() {
        try {
            Context deContext = BfuPreferences.deviceProtectedContext(this);
            Log.i(TAG, "DE context initialized: " + deContext.getFilesDir());
            BfuRuntime.Layout layout = BfuRuntime.provision(deContext);
            Log.i(TAG, "BFU runtime verified: " + layout.root);
            String output = BfuRuntime.executeDirectBootProbe(layout);
            Log.i(TAG, "DE executable probe succeeded: " + output);
        } catch (IOException e) {
            Log.e(TAG, "BFU runtime provisioning or executable probe failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "BFU executable probe interrupted");
        }
    }

    private void handOffAfterUnlock() {
        if (!isUserUnlocked()) {
            Log.w(TAG, "Ignoring unlock handoff because CE storage is still locked");
            return;
        }
        BootReceiver.startNormalTermuxBoot(this);
        if (BfuPreferences.shouldStopAfterUnlock(this)) {
            Log.i(TAG, "BFU daemon stopped");
            stopSelf();
        }
    }

    private boolean isUserUnlocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        return userManager != null && userManager.isUserUnlocked();
    }

    private void registerUnlockReceiver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        registerReceiver(userUnlockedReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        unlockReceiverRegistered = true;
    }

    private Notification buildNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.bfu_notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.bfu_notification_channel_description));
            manager.createNotificationChannel(channel);
        }

        Intent activityIntent = new Intent(this, BootActivity.class);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, activityIntent, pendingIntentFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.bfu_notification_title))
                .setContentText(getString(R.string.bfu_notification_text,
                        BfuPreferences.getSshPort(this)))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}

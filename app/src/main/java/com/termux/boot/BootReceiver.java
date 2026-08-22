package com.termux.boot;

import android.annotation.SuppressLint;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.UserManager;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "TermuxBFU";

    public static final int TERMUX_BOOT_JOB_ID_BASE = 1000;
    static int jobId = TERMUX_BOOT_JOB_ID_BASE;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "LOCKED_BOOT_COMPLETED received");
            appendLockedBootMarker(context);
            recordPersistentEvent(context, "LOCKED_BOOT_COMPLETED received");
            if (BfuPreferences.isEnabled(context)) {
                startBfuEnvironment(context);
            } else {
                Log.i(TAG, "BFU mode is disabled");
            }
            if (isUserUnlocked(context)) {
                Log.i(TAG, "User is already unlocked; also using normal Termux boot path");
                startNormalTermuxBoot(context);
            }
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "BOOT_COMPLETED received");
            if (BfuPreferences.isEnabled(context)) startBfuEnvironment(context);
            if (isUserUnlocked(context)) {
                startNormalTermuxBoot(context);
            }
        }
    }

    private static void appendLockedBootMarker(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        Context deContext = context.createDeviceProtectedStorageContext();
        File marker = new File(deContext.getFilesDir(), "bfu-boot.log");
        try (FileWriter writer = new FileWriter(marker, true)) {
            writer.write("LOCKED_BOOT_COMPLETED " + System.currentTimeMillis() + "\n");
            Log.i(TAG, "DE locked boot marker appended: " + marker);
        } catch (IOException e) {
            Log.e(TAG, "Failed to append DE locked boot marker: " + marker, e);
        }
    }

    private static void startBfuEnvironment(Context context) {
        Intent serviceIntent = new Intent(context, BfuBootService.class)
                .setAction(BfuBootService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to start BFU service", e);
        }
    }

    static void startNormalTermuxBoot(Context context) {
        if (!isUserUnlocked(context)) {
            Log.w(TAG, "Refusing to access Termux CE storage while user is locked");
            recordPersistentEvent(context,
                    "NORMAL_BOOT_HANDOFF_REJECTED user_unlocked=false");
            return;
        }
        if (!BfuPreferences.shouldStartNormalBoot(context)) {
            Log.i(TAG, "Normal Termux:Boot handoff is disabled");
            recordPersistentEvent(context, "NORMAL_BOOT_HANDOFF_DISABLED");
            return;
        }
        if (!BfuPreferences.tryMarkNormalBootDispatch(context)) {
            Log.i(TAG, "Normal Termux:Boot handoff already dispatched this Android boot");
            recordPersistentEvent(context,
                    "NORMAL_BOOT_HANDOFF_DUPLICATE_SUPPRESSED same_android_boot=true");
            return;
        }

        Log.i(TAG, "Termux Boot handoff started");
        recordPersistentEvent(context, "NORMAL_BOOT_HANDOFF_STARTED user_unlocked=true");
        scheduleNormalBootScripts(context);
    }

    private static boolean isUserUnlocked(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        return userManager != null && userManager.isUserUnlocked();
    }

    private static void recordPersistentEvent(Context context, String message) {
        try {
            BfuOperationLog.append(context, message);
        } catch (IOException e) {
            Log.e(TAG, "Failed to append persistent BFU event", e);
        }
    }

    private static void scheduleNormalBootScripts(Context context) {
        @SuppressLint("SdCardPath") final String bootScriptPath =
                "/data/data/com.termux/files/home/.termux/boot";
        final File bootScriptDir = new File(bootScriptPath);
        File[] files = bootScriptDir.listFiles();
        if (files == null) files = new File[0];

        // Sort files so that they get executed in a repeatable and logical order.
        Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));

        StringBuilder logMessage = new StringBuilder();
        for (File file : files) {
            if (!file.isFile()) continue;

            if (logMessage.length() > 0) logMessage.append(", ");
            logMessage.append(file.getName());

            ensureFileReadableAndExecutable(file);

            PersistableBundle extras = new PersistableBundle();
            extras.putString(BootJobService.SCRIPT_FILE_PATH, file.getAbsolutePath());

            ComponentName serviceComponent = new ComponentName(context, BootJobService.class);
            JobInfo job = new JobInfo.Builder(jobId++, serviceComponent)
                    .setExtras(extras)
                    .setOverrideDeadline(3 * 1000)
                    .build();
            JobScheduler jobScheduler =
                    (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobScheduler == null || jobScheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.e(TAG, "Failed to schedule normal boot script: " + file.getName());
            }
        }

        if (logMessage.length() > 0) {
            Log.i(TAG, "Scheduled normal boot files: " + logMessage);
        } else {
            Log.i(TAG, "No normal Termux boot files to execute");
        }
    }

    /** Ensure readable and executable file if user forgot to do so. */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void ensureFileReadableAndExecutable(File file) {
        if (!file.canRead()) file.setReadable(true);
        if (!file.canExecute()) file.setExecutable(true);
    }
}

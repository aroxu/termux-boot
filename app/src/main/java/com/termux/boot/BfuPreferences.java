package com.termux.boot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

final class BfuPreferences {

    static final int DEFAULT_SSH_PORT = 2222;

    private static final String PREFS_NAME = "termux_bfu";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SSH_PORT = "ssh_port";
    private static final String KEY_AUTHORIZED_KEYS = "authorized_keys";
    private static final String KEY_STOP_AFTER_UNLOCK = "stop_after_unlock";
    private static final String KEY_START_NORMAL_BOOT = "start_normal_boot";
    private static final String KEY_LAST_NORMAL_DISPATCH_ELAPSED =
            "last_normal_dispatch_elapsed";
    private static final long DUPLICATE_DISPATCH_WINDOW_MS = 60_000L;

    private BfuPreferences() {}

    static Context deviceProtectedContext(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return context.createDeviceProtectedStorageContext();
        }
        return context;
    }

    private static SharedPreferences get(Context context) {
        return deviceProtectedContext(context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return get(context).getBoolean(KEY_ENABLED, false);
    }

    static int getSshPort(Context context) {
        int port = get(context).getInt(KEY_SSH_PORT, DEFAULT_SSH_PORT);
        return isValidPort(port) ? port : DEFAULT_SSH_PORT;
    }

    static String getAuthorizedKeys(Context context) {
        return get(context).getString(KEY_AUTHORIZED_KEYS, "");
    }

    static boolean shouldStopAfterUnlock(Context context) {
        return get(context).getBoolean(KEY_STOP_AFTER_UNLOCK, true);
    }

    static boolean shouldStartNormalBoot(Context context) {
        return get(context).getBoolean(KEY_START_NORMAL_BOOT, true);
    }

    static void save(Context context, boolean enabled, int sshPort, String authorizedKeys,
                     boolean stopAfterUnlock, boolean startNormalBoot) {
        if (!isValidPort(sshPort)) {
            throw new IllegalArgumentException("SSH port must be between 1024 and 65535");
        }
        boolean saved = get(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putInt(KEY_SSH_PORT, sshPort)
                .putString(KEY_AUTHORIZED_KEYS,
                        authorizedKeys == null ? "" : authorizedKeys.trim())
                .putBoolean(KEY_STOP_AFTER_UNLOCK, stopAfterUnlock)
                .putBoolean(KEY_START_NORMAL_BOOT, startNormalBoot)
                .commit();
        if (!saved) throw new IllegalStateException("Failed to save BFU settings");
    }

    static synchronized boolean tryMarkNormalBootDispatch(Context context) {
        SharedPreferences preferences = get(context);
        long now = SystemClock.elapsedRealtime();
        long previous = preferences.getLong(KEY_LAST_NORMAL_DISPATCH_ELAPSED, -1L);
        if (previous >= 0 && now >= previous
                && now - previous < DUPLICATE_DISPATCH_WINDOW_MS) {
            return false;
        }
        return preferences.edit().putLong(KEY_LAST_NORMAL_DISPATCH_ELAPSED, now).commit();
    }

    private static boolean isValidPort(int port) {
        return port >= 1024 && port <= 65535;
    }
}

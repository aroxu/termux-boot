package com.termux.boot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

final class BfuPreferences {

    private static final String PREFS_NAME = "termux_bfu";
    private static final String KEY_ENABLED = "enabled";
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

    static boolean shouldStartNormalBoot(Context context) {
        return get(context).getBoolean(KEY_START_NORMAL_BOOT, true);
    }

    static void save(Context context, boolean enabled, boolean startNormalBoot) {
        boolean saved = get(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
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
}

package com.termux.boot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.UserManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Fail-closed proof that normal Termux Credential Encrypted data is unavailable in BFU. */
@SuppressLint("SdCardPath") // Deliberately probes Termux's two canonical CE aliases.
final class BfuCeIsolationProbe {

    private static final long PROBE_TIMEOUT_MS = 15_000L;
    private static final String LOG_FILE = "bfu-ce-isolation.log";
    private static final String SUCCESS_MARKER = "TERMUX_CE_ISOLATED";
    private static final String PROBE =
            "for path in /data/data/com.termux/files/home "
                    + "/data/user/0/com.termux/files/home; do "
                    + "if [ -e \"$path\" ] "
                    + "&& /system/bin/ls -A \"$path\" >/dev/null 2>&1; then "
                    + "echo TERMUX_CE_ACCESSIBLE path=\"$path\"; exit 41; fi; "
                    + "done; echo TERMUX_CE_ISOLATED paths_unreadable=true";

    static final class Result {
        final boolean isolated;
        final String command;
        final int exitCode;
        final boolean timedOut;
        final boolean userUnlockedBefore;
        final boolean userUnlockedAfter;
        final String output;

        Result(boolean isolated, String command, int exitCode, boolean timedOut,
               boolean userUnlockedBefore, boolean userUnlockedAfter, String output) {
            this.isolated = isolated;
            this.command = command;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.userUnlockedBefore = userUnlockedBefore;
            this.userUnlockedAfter = userUnlockedAfter;
            this.output = output;
        }

        String summary() {
            return "command=" + command
                    + " exit=" + exitCode
                    + " timeout=" + timedOut
                    + " ce_isolated=" + isolated
                    + " user_unlocked_before=" + userUnlockedBefore
                    + " user_unlocked_after=" + userUnlockedAfter
                    + " output=" + output;
        }

        boolean succeededDuringBfu() {
            return isolated && !userUnlockedBefore && !userUnlockedAfter;
        }
    }

    private BfuCeIsolationProbe() {}

    static Result run(Context context) throws IOException, InterruptedException {
        Context deContext = BfuPreferences.deviceProtectedContext(context);
        boolean userUnlockedBefore = isUserUnlocked(context);
        String command = "/system/bin/sh -c " + BfuSu.shellQuote(PROBE);
        BfuSu.Result commandResult = BfuSu.run(command, PROBE_TIMEOUT_MS);
        boolean userUnlockedAfter = isUserUnlocked(context);
        boolean isolated = commandResult.exitedSuccessfully()
                && commandResult.output.contains(SUCCESS_MARKER)
                && !commandResult.output.contains("TERMUX_CE_ACCESSIBLE");
        Result result = new Result(isolated, commandResult.command,
                commandResult.exitCode, commandResult.timedOut, userUnlockedBefore,
                userUnlockedAfter, commandResult.output);
        appendPersistentResult(deContext, result);
        return result;
    }

    static String readLastPersistentResult(Context context) throws IOException {
        Context deContext = BfuPreferences.deviceProtectedContext(context);
        File log = new File(deContext.getFilesDir(), LOG_FILE);
        if (!log.isFile()) return "";
        String lastLine = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(log), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) lastLine = line;
            }
        }
        return lastLine;
    }

    private static boolean isUserUnlocked(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        UserManager userManager = (UserManager) context.getSystemService(
                Context.USER_SERVICE);
        return userManager != null && userManager.isUserUnlocked();
    }

    private static void appendPersistentResult(Context deContext, Result result)
            throws IOException {
        File log = new File(deContext.getFilesDir(), LOG_FILE);
        String line = "CE_ISOLATION_PROBE " + System.currentTimeMillis() + " "
                + result.summary() + "\n";
        try (FileOutputStream output = new FileOutputStream(log, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}

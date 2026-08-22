package com.termux.boot;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.UserManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class BfuRootProbe {

    private static final long PROBE_TIMEOUT_MS = 15_000L;
    private static final long TERMINATION_GRACE_MS = 1_000L;
    private static final int MAX_OUTPUT_BYTES = 4_096;
    private static final int EXIT_TIMEOUT = -2;
    private static final int EXIT_NOT_STARTED = -3;

    private static final String[] SU_CANDIDATES = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "su"
    };

    static final class Result {
        final boolean root;
        final String command;
        final int exitCode;
        final boolean timedOut;
        final boolean userUnlockedBefore;
        final boolean userUnlockedAfter;
        final String output;

        Result(boolean root, String command, int exitCode, boolean timedOut,
               boolean userUnlockedBefore, boolean userUnlockedAfter, String output) {
            this.root = root;
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
                    + " root=" + root
                    + " user_unlocked_before=" + userUnlockedBefore
                    + " user_unlocked_after=" + userUnlockedAfter
                    + " output=" + output;
        }
    }

    private BfuRootProbe() {}

    static Result run(Context context) throws IOException, InterruptedException {
        Context deContext = BfuPreferences.deviceProtectedContext(context);
        boolean userUnlockedBefore = isUserUnlocked(context);
        Result commandResult = findAndRunSu();
        boolean userUnlockedAfter = isUserUnlocked(context);
        Result result = new Result(commandResult.root, commandResult.command,
                commandResult.exitCode, commandResult.timedOut, userUnlockedBefore,
                userUnlockedAfter, commandResult.output);
        appendPersistentResult(deContext, result);
        return result;
    }

    private static Result findAndRunSu() throws InterruptedException {
        StringBuilder failures = new StringBuilder();
        for (String candidate : SU_CANDIDATES) {
            if (candidate.startsWith("/")) {
                File executable = new File(candidate);
                if (!executable.isFile() || !executable.canExecute()) continue;
            }

            try {
                return runSu(candidate);
            } catch (IOException e) {
                if (failures.length() > 0) failures.append("; ");
                failures.append(candidate).append(": ").append(sanitize(e.getMessage()));
            }
        }

        String output = failures.length() == 0 ? "no executable su found" : failures.toString();
        return new Result(false, "none", EXIT_NOT_STARTED, false,
                false, false, output);
    }

    private static Result runSu(String command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command, "-c", "id");
        builder.redirectErrorStream(true);
        Process process = builder.start();

        Integer exitCode;
        try {
            exitCode = waitForExit(process, PROBE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            process.destroy();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.destroyForcibly();
            }
            throw e;
        }
        boolean timedOut = exitCode == null;
        if (timedOut) exitCode = terminate(process);

        String output = exitCode == null ? "process did not terminate" : readOutput(process);
        int resolvedExitCode = timedOut ? EXIT_TIMEOUT : exitCode;
        boolean root = !timedOut && resolvedExitCode == 0 && containsRootUid(output);
        return new Result(root, command, resolvedExitCode, timedOut,
                false, false, sanitize(output));
    }

    private static Integer terminate(Process process) throws InterruptedException {
        process.destroy();
        Integer exitCode = waitForExit(process, TERMINATION_GRACE_MS);
        if (exitCode == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly();
            exitCode = waitForExit(process, TERMINATION_GRACE_MS);
        }
        return exitCode;
    }

    private static Integer waitForExit(Process process, long timeoutMs)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                Thread.sleep(100L);
            }
        }
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException ignored) {
            return null;
        }
    }

    private static String readOutput(Process process) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[512];
            int count;
            while (output.size() < MAX_OUTPUT_BYTES
                    && (count = input.read(buffer, 0,
                    Math.min(buffer.length, MAX_OUTPUT_BYTES - output.size()))) >= 0) {
                output.write(buffer, 0, count);
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean containsRootUid(String output) {
        return output.matches("(?s).*(^|\\s)uid=0(?:\\(|\\s|$).*");
    }

    private static boolean isUserUnlocked(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        return userManager != null && userManager.isUserUnlocked();
    }

    private static void appendPersistentResult(Context deContext, Result result)
            throws IOException {
        File log = new File(deContext.getFilesDir(), "bfu-root.log");
        String line = "ROOT_PROBE " + System.currentTimeMillis() + " "
                + result.summary() + "\n";
        try (FileOutputStream output = new FileOutputStream(log, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) return "(none)";
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (sanitized.length() > MAX_OUTPUT_BYTES) {
            return sanitized.substring(0, MAX_OUTPUT_BYTES) + "…";
        }
        return sanitized.isEmpty() ? "(none)" : sanitized;
    }
}

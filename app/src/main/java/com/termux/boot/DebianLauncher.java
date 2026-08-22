package com.termux.boot;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Root-backed lifecycle control for the long-running Debian systemd namespace. */
final class DebianLauncher {

    enum Operation { START, STATUS, STOP }

    private static final String TAG = "TermuxBFU";
    private static final String STATUS_FILE = "debian-lifecycle.status";
    private static final int MAX_TAIL_BYTES = 64 * 1024;
    private static final Object FILE_LOCK = new Object();

    private DebianLauncher() {}

    static boolean run(Context context, BfuRuntime.Layout layout, Operation operation,
                       String trigger) throws IOException, InterruptedException {
        appendLog(layout.lifecycleLog,
                "ANDROID_REQUEST operation=" + operation.name().toLowerCase(Locale.US)
                        + " trigger=" + BfuSu.sanitize(trigger));
        writeStatus(context, "RUNNING " + operation.name() + " requested by " + trigger);

        String command = BfuSu.shellQuote(layout.namespaceProbeBinary.getAbsolutePath())
                + " " + operation.name().toLowerCase(Locale.US)
                + " " + BfuSu.shellQuote(BfuRootfsProbe.ROOTFS_PATH)
                + " " + BfuSu.shellQuote(layout.run.getAbsolutePath());
        long timeoutMs;
        if (operation == Operation.START) {
            command += " " + BfuSu.shellQuote(layout.lifecycleLog.getAbsolutePath());
            timeoutMs = 35_000L;
        } else if (operation == Operation.STOP) {
            timeoutMs = 35_000L;
        } else {
            timeoutMs = 12_000L;
        }

        BfuSu.Result result = BfuSu.run(command, timeoutMs);
        String summary = operation.name() + " command=" + result.command
                + " exit=" + result.exitCode
                + " timeout=" + result.timedOut
                + " output=" + result.output;
        appendLog(layout.lifecycleLog, "ANDROID_RESULT " + summary);
        boolean successful = result.exitedSuccessfully();
        writeStatus(context, (successful ? "SUCCEEDED " : "FAILED ") + summary);
        if (successful) {
            Log.i(TAG, "Debian lifecycle " + summary);
        } else {
            Log.w(TAG, "Debian lifecycle " + summary);
        }
        return successful;
    }

    static String readStatus(Context context) throws IOException {
        Context deContext = BfuPreferences.deviceProtectedContext(context);
        File file = new File(deContext.getFilesDir(), STATUS_FILE);
        if (!file.isFile()) return "";
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int count;
            while (output.size() < 4096
                    && (count = input.read(buffer, 0,
                    Math.min(buffer.length, 4096 - output.size()))) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    static String readLogTail(Context context) throws IOException {
        BfuRuntime.Layout layout = BfuRuntime.layout(context);
        File file = layout.lifecycleLog;
        if (!file.isFile()) return "";
        synchronized (FILE_LOCK) {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                long length = input.length();
                long start = Math.max(0L, length - MAX_TAIL_BYTES);
                input.seek(start);
                byte[] bytes = new byte[(int) (length - start)];
                input.readFully(bytes);
                int offset = 0;
                if (start > 0L) {
                    while (offset < bytes.length && bytes[offset] != '\n') offset++;
                    if (offset < bytes.length) offset++;
                }
                String value = new String(bytes, offset, bytes.length - offset,
                        StandardCharsets.UTF_8);
                return start > 0L ? "… earlier log omitted …\n" + value : value;
            }
        }
    }

    static void recordFailure(Context context, BfuRuntime.Layout layout, Operation operation,
                              String reason) {
        String message = operation.name() + " failed before execution: "
                + BfuSu.sanitize(reason);
        try {
            if (layout != null) appendLog(layout.lifecycleLog, "ANDROID_FAILURE " + message);
            writeStatus(context, "FAILED " + message);
        } catch (IOException e) {
            Log.e(TAG, "Failed to persist Debian lifecycle failure", e);
        }
    }

    private static void appendLog(File file, String value) throws IOException {
        String line = "[" + utcTimestamp() + "] "
                + value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ') + "\n";
        synchronized (FILE_LOCK) {
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            setOwnerOnly(file);
        }
    }

    private static void writeStatus(Context context, String value) throws IOException {
        Context deContext = BfuPreferences.deviceProtectedContext(context);
        File destination = new File(deContext.getFilesDir(), STATUS_FILE);
        File temporary = new File(deContext.getFilesDir(), STATUS_FILE + ".new");
        String contents = System.currentTimeMillis() + " " + value + "\n";
        synchronized (FILE_LOCK) {
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(contents.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            setOwnerOnly(temporary);
            if (destination.exists() && !destination.delete()) {
                throw new IOException("cannot replace lifecycle status");
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("cannot publish lifecycle status");
            }
            setOwnerOnly(destination);
        }
    }

    private static String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void setOwnerOnly(File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }
}

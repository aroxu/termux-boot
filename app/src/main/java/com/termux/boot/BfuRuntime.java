package com.termux.boot;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class BfuRuntime {

    private static final String TEST_SCRIPT =
            "#!/system/bin/sh\n" +
            "echo 'TermuxBFU DE executable OK'\n" +
            "echo \"uid=$(id -u)\"\n" +
            "echo \"cwd=$(pwd)\"\n";

    static final class Layout {
        final File root;
        final File bin;
        final File etc;
        final File home;
        final File run;
        final File scripts;
        final File tmp;
        final File downloads;
        final File testScript;
        final File rootfsProbeScript;
        final File rootfsInstallerScript;
        final File debootstrapArchive;
        final File archiveKeyringPackage;

        Layout(File root) {
            this.root = root;
            bin = new File(root, "bin");
            etc = new File(root, "etc");
            home = new File(root, "home");
            run = new File(root, "run");
            scripts = new File(root, "scripts");
            tmp = new File(root, "tmp");
            downloads = new File(root, "downloads");
            testScript = new File(scripts, "test.sh");
            rootfsProbeScript = new File(scripts, "probe-rootfs.sh");
            rootfsInstallerScript = new File(scripts, "install-debian-rootfs.sh");
            debootstrapArchive = new File(downloads, "debootstrap_1.0.144.tar.gz");
            archiveKeyringPackage = new File(downloads,
                    "debian-archive-keyring_2023.3+deb12u2_all.deb");
        }
    }

    private BfuRuntime() {}

    static Layout provision(Context context) throws IOException {
        Context deContext = BfuPreferences.deviceProtectedContext(context);
        Layout layout = new Layout(new File(deContext.getFilesDir(), "bfu"));
        ensureDirectory(layout.root);
        ensureDirectory(layout.bin);
        ensureDirectory(layout.etc);
        ensureDirectory(layout.home);
        ensureDirectory(layout.run);
        ensureDirectory(layout.scripts);
        ensureDirectory(layout.tmp);
        ensureDirectory(layout.downloads);

        writePrivateFile(layout.testScript, TEST_SCRIPT, true);
        copyPrivateAsset(deContext, "bfu/probe-rootfs.sh", layout.rootfsProbeScript, true);
        copyPrivateAsset(deContext, "bfu/install-debian-rootfs.sh",
                layout.rootfsInstallerScript, true);
        return layout;
    }

    static String executeDirectBootProbe(Layout layout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(layout.testScript.getAbsolutePath());
        builder.directory(layout.home);
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("HOME", layout.home.getAbsolutePath());
        environment.put("PATH", layout.bin.getAbsolutePath() + ":/system/bin:/system/xbin");
        environment.put("TMPDIR", layout.tmp.getAbsolutePath());

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append("; ");
                output.append(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("DE executable probe exited with " + exitCode + ": " + output);
        }
        return output.toString();
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Failed to create directory " + directory);
        }
    }

    private static void writePrivateFile(File file, String contents, boolean executable)
            throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".new");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        setPrivateMode(temporary, executable);
        if (file.exists() && !file.delete()) {
            throw new IOException("Failed to replace " + file);
        }
        if (!temporary.renameTo(file)) {
            throw new IOException("Failed to install " + file);
        }
        setPrivateMode(file, executable);
    }

    private static void copyPrivateAsset(Context context, String assetPath, File file,
                                         boolean executable) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".new");
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[8_192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        setPrivateMode(temporary, executable);
        if (file.exists() && !file.delete()) {
            throw new IOException("Failed to replace " + file);
        }
        if (!temporary.renameTo(file)) {
            throw new IOException("Failed to install " + file);
        }
        setPrivateMode(file, executable);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void setPrivateMode(File file, boolean executable) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (executable) file.setExecutable(true, true);
    }
}

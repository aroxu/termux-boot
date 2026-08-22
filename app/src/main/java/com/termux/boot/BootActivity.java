package com.termux.boot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserManager;
import android.graphics.Typeface;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BootActivity extends Activity {

    private CheckBox enableBfu;
    private CheckBox startNormalBoot;
    private TextView rootProbeStatus;
    private TextView rootfsProbeStatus;
    private Button rootAuthorizationButton;
    private TextView rootAuthorizationStatus;
    private TextView installStatus;
    private TextView installLog;
    private Handler liveLogHandler;
    private final ExecutorService rootAuthorizationExecutor =
            Executors.newSingleThreadExecutor();
    private volatile boolean rootAuthorizationInProgress;
    private boolean activityResumed;
    private BfuRootAuthorization.Result pendingRootAuthorizationResult;
    private String pendingRootAuthorizationFailure;
    private String lastDisplayedInstallLog = "";

    private final Runnable refreshLiveLog = new Runnable() {
        @Override
        public void run() {
            refreshRootAuthorizationStatus();
            refreshInstallerStatus();
            liveLogHandler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.bfu_settings_title);
        liveLogHandler = new Handler(Looper.getMainLooper());
        setContentView(buildSettingsView());
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        liveLogHandler.removeCallbacks(refreshLiveLog);
        liveLogHandler.post(refreshLiveLog);
        showPendingRootAuthorizationResult();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        liveLogHandler.removeCallbacks(refreshLiveLog);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        rootAuthorizationExecutor.shutdownNow();
        super.onDestroy();
    }

    private ScrollView buildSettingsView() {
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView explanation = new TextView(this);
        explanation.setText(R.string.bfu_settings_explanation);
        content.addView(explanation, matchWrap());

        enableBfu = new CheckBox(this);
        enableBfu.setText(R.string.bfu_enable);
        content.addView(enableBfu, matchWrap());

        startNormalBoot = new CheckBox(this);
        startNormalBoot.setText(R.string.bfu_start_normal_boot);
        content.addView(startNormalBoot, matchWrap());

        TextView rootAuthorizationExplanation = new TextView(this);
        rootAuthorizationExplanation.setText(R.string.bfu_root_authorization_explanation);
        content.addView(rootAuthorizationExplanation, matchWrap());

        rootAuthorizationStatus = new TextView(this);
        content.addView(rootAuthorizationStatus, matchWrap());

        rootAuthorizationButton = new Button(this);
        rootAuthorizationButton.setText(R.string.bfu_request_root_authorization);
        rootAuthorizationButton.setOnClickListener(view -> confirmRootAuthorization());
        content.addView(rootAuthorizationButton, matchWrap());

        rootProbeStatus = new TextView(this);
        content.addView(rootProbeStatus, matchWrap());

        rootfsProbeStatus = new TextView(this);
        content.addView(rootfsProbeStatus, matchWrap());

        Button refreshRootStatus = new Button(this);
        refreshRootStatus.setText(R.string.bfu_refresh_probe_status);
        refreshRootStatus.setOnClickListener(view -> refreshProbeStatus());
        content.addView(refreshRootStatus, matchWrap());

        Button save = new Button(this);
        save.setText(R.string.bfu_save_and_provision);
        save.setOnClickListener(view -> saveAndProvision());
        content.addView(save, matchWrap());

        TextView installExplanation = new TextView(this);
        installExplanation.setText(R.string.bfu_debian_install_explanation);
        content.addView(installExplanation, matchWrap());

        Button install = new Button(this);
        install.setText(R.string.bfu_install_debian);
        install.setOnClickListener(view -> confirmDebianInstall());
        content.addView(install, matchWrap());

        installStatus = new TextView(this);
        content.addView(installStatus, matchWrap());

        TextView installLogTitle = new TextView(this);
        installLogTitle.setText(R.string.bfu_debian_install_log_title);
        content.addView(installLogTitle, matchWrap());

        installLog = new TextView(this);
        installLog.setTypeface(Typeface.MONOSPACE);
        installLog.setTextSize(12f);
        installLog.setMinLines(10);
        installLog.setMaxLines(18);
        installLog.setVerticalScrollBarEnabled(true);
        installLog.setMovementMethod(new ScrollingMovementMethod());
        int logPadding = (int) (8 * getResources().getDisplayMetrics().density);
        installLog.setPadding(logPadding, logPadding, logPadding, logPadding);
        content.addView(installLog, matchWrap());

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        return scrollView;
    }

    private void loadSettings() {
        enableBfu.setChecked(BfuPreferences.isEnabled(this));
        startNormalBoot.setChecked(BfuPreferences.shouldStartNormalBoot(this));
        refreshRootAuthorizationStatus();
        refreshProbeStatus();
        refreshInstallerStatus();
    }

    private void refreshProbeStatus() {
        try {
            String result = BfuRootProbe.readLastPersistentResult(this);
            if (result.isEmpty()) result = getString(R.string.bfu_root_probe_none);
            rootProbeStatus.setText(getString(R.string.bfu_root_probe_status, result));
        } catch (IOException e) {
            rootProbeStatus.setText(getString(R.string.bfu_root_probe_read_failed,
                    e.getMessage()));
        }

        try {
            String result = BfuRootfsProbe.readLastPersistentResult(this);
            if (result.isEmpty()) result = getString(R.string.bfu_rootfs_probe_none);
            rootfsProbeStatus.setText(getString(R.string.bfu_rootfs_probe_status, result));
        } catch (IOException e) {
            rootfsProbeStatus.setText(getString(R.string.bfu_rootfs_probe_read_failed,
                    e.getMessage()));
        }
    }

    private void saveAndProvision() {
        try {
            BfuPreferences.save(this, enableBfu.isChecked(), startNormalBoot.isChecked());
            BfuRuntime.Layout layout = BfuRuntime.provision(this);
            Toast.makeText(this, getString(R.string.bfu_saved, layout.root),
                    Toast.LENGTH_LONG).show();
        } catch (IOException | IllegalStateException e) {
            Toast.makeText(this, getString(R.string.bfu_provision_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRootAuthorization() {
        if (!isUserUnlocked()) {
            Toast.makeText(this, R.string.bfu_root_authorization_requires_unlock,
                    Toast.LENGTH_LONG).show();
            return;
        }

        int uid = Process.myUid();
        String packages = packagesForSharedUid(uid);
        new AlertDialog.Builder(this)
                .setTitle(R.string.bfu_root_authorization_confirm_title)
                .setMessage(getString(R.string.bfu_root_authorization_confirm_message,
                        Integer.toString(uid), packages))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.bfu_root_authorization_confirm_button,
                        (dialog, which) -> requestRootAuthorization())
                .show();
    }

    private void requestRootAuthorization() {
        if (rootAuthorizationInProgress) return;
        rootAuthorizationInProgress = true;
        rootAuthorizationButton.setEnabled(false);
        rootAuthorizationStatus.setText(R.string.bfu_root_authorization_waiting);
        Context applicationContext = getApplicationContext();

        rootAuthorizationExecutor.execute(() -> {
            BfuRootAuthorization.Result result = null;
            String failure = null;
            try {
                result = BfuRootAuthorization.request(applicationContext);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = applicationContext.getString(
                        R.string.bfu_root_authorization_interrupted);
            } catch (IOException | IllegalStateException e) {
                failure = BfuSu.sanitize(e.getMessage());
            }

            BfuRootAuthorization.Result completedResult = result;
            String completedFailure = failure;
            liveLogHandler.post(() -> finishRootAuthorization(
                    completedResult, completedFailure));
        });
    }

    private void finishRootAuthorization(BfuRootAuthorization.Result result, String failure) {
        if (isFinishing() || isDestroyed()) return;
        rootAuthorizationInProgress = false;
        rootAuthorizationButton.setEnabled(true);
        refreshRootAuthorizationStatus();

        pendingRootAuthorizationResult = result;
        pendingRootAuthorizationFailure = failure;
        if (activityResumed) showPendingRootAuthorizationResult();
    }

    private void showPendingRootAuthorizationResult() {
        BfuRootAuthorization.Result result = pendingRootAuthorizationResult;
        String failure = pendingRootAuthorizationFailure;
        if (result == null && failure == null) return;
        pendingRootAuthorizationResult = null;
        pendingRootAuthorizationFailure = null;

        if (result != null && result.authorizedWhileUnlocked()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.bfu_root_authorization_verified_title)
                    .setMessage(getString(R.string.bfu_root_authorization_verified_message,
                            Integer.toString(result.appUid)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        String reason = failure;
        if (reason == null && result != null) reason = result.summary();
        if (reason == null) reason = getString(R.string.bfu_root_authorization_unknown_failure);
        new AlertDialog.Builder(this)
                .setTitle(R.string.bfu_root_authorization_failed_title)
                .setMessage(getString(R.string.bfu_root_authorization_failed_message, reason))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void refreshRootAuthorizationStatus() {
        if (rootAuthorizationStatus == null || rootAuthorizationInProgress) return;
        try {
            String result = BfuRootAuthorization.readLastPersistentResult(this);
            if (result.isEmpty()) result = getString(R.string.bfu_root_authorization_none);
            rootAuthorizationStatus.setText(getString(
                    R.string.bfu_root_authorization_status, result));
        } catch (IOException e) {
            rootAuthorizationStatus.setText(getString(
                    R.string.bfu_root_authorization_read_failed, e.getMessage()));
        }
    }

    private String packagesForSharedUid(int uid) {
        String[] packages = getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) return getPackageName();
        Arrays.sort(packages);
        StringBuilder result = new StringBuilder();
        for (String packageName : packages) {
            if (result.length() > 0) result.append("\n");
            result.append("• ").append(packageName);
        }
        return result.toString();
    }

    private void confirmDebianInstall() {
        if (!enableBfu.isChecked()) {
            Toast.makeText(this, R.string.bfu_install_requires_enabled,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!isUserUnlocked()) {
            Toast.makeText(this, R.string.bfu_install_requires_unlock,
                    Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.bfu_install_confirm_title)
                .setMessage(R.string.bfu_install_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.bfu_install_confirm_button,
                        (dialog, which) -> startDebianInstall())
                .show();
    }

    private void startDebianInstall() {
        try {
            BfuPreferences.save(this, enableBfu.isChecked(), startNormalBoot.isChecked());
            BfuRuntime.provision(this);
            BfuBootService.requestDebianRootfsInstall(this);
            Toast.makeText(this, R.string.bfu_install_requested,
                    Toast.LENGTH_LONG).show();
            refreshInstallerStatus();
        } catch (IOException | IllegalStateException e) {
            Toast.makeText(this, getString(R.string.bfu_provision_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void refreshInstallerStatus() {
        if (installStatus == null || installLog == null) return;

        String status;
        try {
            status = DebianRootfsInstaller.readStatus(this);
            if (status.isEmpty()) status = getString(R.string.bfu_debian_install_status_none);
            installStatus.setText(getString(R.string.bfu_debian_install_status, status));
        } catch (IOException e) {
            status = "";
            installStatus.setText(getString(R.string.bfu_debian_install_status_failed,
                    e.getMessage()));
        }

        try {
            String log = DebianRootfsInstaller.readLogTail(this);
            if (log.isEmpty()) log = getString(R.string.bfu_debian_install_log_none);
            if (!log.equals(lastDisplayedInstallLog)) {
                lastDisplayedInstallLog = log;
                installLog.setText(log);
                if (status.contains(" RUNNING ")) scrollInstallLogToBottom();
            }
        } catch (IOException e) {
            installLog.setText(getString(R.string.bfu_debian_install_log_failed,
                    e.getMessage()));
        }
    }

    private void scrollInstallLogToBottom() {
        installLog.post(() -> {
            if (installLog.getLayout() == null) return;
            int scroll = installLog.getLayout().getLineTop(installLog.getLineCount())
                    - installLog.getHeight();
            installLog.scrollTo(0, Math.max(0, scroll));
        });
    }

    private boolean isUserUnlocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        UserManager userManager = (UserManager) getSystemService(USER_SERVICE);
        return userManager != null && userManager.isUserUnlocked();
    }

    private static ViewGroup.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}

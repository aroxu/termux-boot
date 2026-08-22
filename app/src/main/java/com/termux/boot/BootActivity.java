package com.termux.boot;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.IOException;

public class BootActivity extends Activity {

    private CheckBox enableBfu;
    private CheckBox startNormalBoot;
    private TextView rootProbeStatus;
    private TextView rootfsProbeStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.bfu_settings_title);
        setContentView(buildSettingsView());
        loadSettings();
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

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        return scrollView;
    }

    private void loadSettings() {
        enableBfu.setChecked(BfuPreferences.isEnabled(this));
        startNormalBoot.setChecked(BfuPreferences.shouldStartNormalBoot(this));
        refreshProbeStatus();
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

    private static ViewGroup.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}

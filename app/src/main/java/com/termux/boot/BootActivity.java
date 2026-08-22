package com.termux.boot;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.IOException;

public class BootActivity extends Activity {

    private CheckBox enableBfu;
    private EditText sshPort;
    private EditText authorizedKeys;
    private CheckBox stopAfterUnlock;
    private CheckBox startNormalBoot;

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

        TextView portLabel = new TextView(this);
        portLabel.setText(R.string.bfu_ssh_port);
        content.addView(portLabel, matchWrap());

        sshPort = new EditText(this);
        sshPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(sshPort, matchWrap());

        TextView keysLabel = new TextView(this);
        keysLabel.setText(R.string.bfu_authorized_keys);
        content.addView(keysLabel, matchWrap());

        authorizedKeys = new EditText(this);
        authorizedKeys.setGravity(Gravity.TOP | Gravity.START);
        authorizedKeys.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        authorizedKeys.setMinLines(5);
        authorizedKeys.setHint(R.string.bfu_authorized_keys_hint);
        content.addView(authorizedKeys, matchWrap());

        stopAfterUnlock = new CheckBox(this);
        stopAfterUnlock.setText(R.string.bfu_stop_after_unlock);
        content.addView(stopAfterUnlock, matchWrap());

        startNormalBoot = new CheckBox(this);
        startNormalBoot.setText(R.string.bfu_start_normal_boot);
        content.addView(startNormalBoot, matchWrap());

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
        sshPort.setText(String.valueOf(BfuPreferences.getSshPort(this)));
        authorizedKeys.setText(BfuPreferences.getAuthorizedKeys(this));
        stopAfterUnlock.setChecked(BfuPreferences.shouldStopAfterUnlock(this));
        startNormalBoot.setChecked(BfuPreferences.shouldStartNormalBoot(this));
    }

    private void saveAndProvision() {
        final int port;
        try {
            port = Integer.parseInt(sshPort.getText().toString().trim());
            BfuPreferences.save(this, enableBfu.isChecked(), port,
                    authorizedKeys.getText().toString(), stopAfterUnlock.isChecked(),
                    startNormalBoot.isChecked());
            BfuRuntime.Layout layout = BfuRuntime.provision(this);
            Toast.makeText(this, getString(R.string.bfu_saved, layout.root),
                    Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException e) {
            sshPort.setError(getString(R.string.bfu_invalid_port));
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

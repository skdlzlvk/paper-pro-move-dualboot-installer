package com.android.launcher3.paper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import com.android.launcher3.R;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Owner-approved, E Ink-first remote update check and staging screen. */
public final class UpdateActivity extends Activity {
    private static final String PREFS = "paper-ota";
    private static final String CHANNEL = "channel";
    private static final String[] CHANNELS = {
            "stable", "public-beta", "private-beta"
    };

    private TextView stateText;
    private TextView detailText;
    private Button primaryButton;
    private Button channelButton;
    private volatile Thread worker;
    private OtaUpdateClient.Release availableRelease;
    private String channel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        channel = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(CHANNEL, "stable");
        PaperSystemBars.setContent(this, buildContent());
        showInitialState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PaperSystemBars.applyEinkSystemBarContrast(this);
    }

    @Override
    protected void onDestroy() {
        Thread active = worker;
        if (active != null) {
            active.interrupt();
        }
        super.onDestroy();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 28, 34, 42);
        root.setBackgroundColor(Color.WHITE);

        Button back = button(getString(R.string.update_back), false);
        back.setOnClickListener(view -> finishWithoutAnimation());
        root.addView(back, new LinearLayout.LayoutParams(190, 72));

        TextView title = text(getString(R.string.update_title), 36, true);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = 42;
        root.addView(title, titleParams);

        TextView subtitle = text(getString(R.string.update_subtitle), 20,
                false);
        LinearLayout.LayoutParams subtitleParams =
                new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = 12;
        root.addView(subtitle, subtitleParams);

        channelButton = button(channelLabel(), false);
        channelButton.setOnClickListener(view -> cycleChannel());
        LinearLayout.LayoutParams channelParams =
                new LinearLayout.LayoutParams(-1, 82);
        channelParams.topMargin = 34;
        root.addView(channelButton, channelParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(28, 28, 28, 28);
        card.setBackground(box(Color.rgb(248, 248, 244),
                Color.rgb(98, 98, 94), 2, 10));
        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = 30;
        root.addView(card, cardParams);

        stateText = text("", 28, true);
        card.addView(stateText);
        detailText = text("", 19, false);
        detailText.setLineSpacing(8f, 1f);
        LinearLayout.LayoutParams detailParams =
                new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = 20;
        card.addView(detailText, detailParams);

        root.addView(new Space(this), new LinearLayout.LayoutParams(1, 34));
        primaryButton = button(getString(R.string.update_check), true);
        primaryButton.setOnClickListener(view -> primaryAction());
        root.addView(primaryButton,
                new LinearLayout.LayoutParams(-1, 92));

        TextView safety = text(getString(R.string.update_safety), 18, false);
        safety.setLineSpacing(7f, 1f);
        LinearLayout.LayoutParams safetyParams =
                new LinearLayout.LayoutParams(-1, -2);
        safetyParams.topMargin = 30;
        root.addView(safety, safetyParams);
        return root;
    }

    private void showInitialState() {
        OtaUpdateClient.Configuration configuration = configuration();
        if (!configuration.isConfigured()) {
            stateText.setText(R.string.update_not_configured_title);
            detailText.setText(R.string.update_not_configured_detail);
            primaryButton.setEnabled(false);
            primaryButton.setText(R.string.update_unavailable);
            return;
        }
        File ready = new File(getFilesDir(), "ota-staging/current-ready");
        if (ready.isFile()) {
            stateText.setText(R.string.update_ready_title);
            detailText.setText(getString(R.string.update_ready_detail,
                    readShort(ready)));
            primaryButton.setEnabled(false);
            primaryButton.setText(R.string.update_waiting_applier);
            return;
        }
        stateText.setText(R.string.update_current_title);
        detailText.setText(getString(R.string.update_current_detail,
                configuration.currentReleaseId));
        primaryButton.setEnabled(true);
        primaryButton.setText(R.string.update_check);
    }

    private void primaryAction() {
        if (worker != null) {
            return;
        }
        if (availableRelease == null) {
            checkForUpdate();
        } else {
            downloadUpdate();
        }
    }

    private void checkForUpdate() {
        setBusy(R.string.update_checking_title,
                R.string.update_checking_detail);
        OtaUpdateClient.Configuration configuration = configuration();
        worker = new Thread(() -> {
            try {
                OtaUpdateClient.Release release =
                        OtaUpdateClient.check(configuration);
                runOnUiThread(() -> {
                    availableRelease = release;
                    stateText.setText(R.string.update_available_title);
                    detailText.setText(getString(
                            R.string.update_available_detail,
                            release.toReleaseId,
                            formatBytes(release.totalBytes),
                            release.minimumBatteryPercent));
                    primaryButton.setEnabled(true);
                    primaryButton.setText(R.string.update_download);
                    channelButton.setEnabled(true);
                    worker = null;
                });
            } catch (Exception failure) {
                showFailure(failure);
            }
        }, "paper-ota-check");
        worker.start();
    }

    private void downloadUpdate() {
        int battery = batteryPercent();
        if (battery < availableRelease.minimumBatteryPercent) {
            stateText.setText(R.string.update_battery_title);
            detailText.setText(getString(R.string.update_battery_detail,
                    battery, availableRelease.minimumBatteryPercent));
            return;
        }
        OtaUpdateClient.Release release = availableRelease;
        setBusy(R.string.update_downloading_title,
                R.string.update_downloading_detail);
        worker = new Thread(() -> {
            try {
                File staged = OtaUpdateClient.stage(this, release,
                        (name, received, total) -> {
                            int percent = total <= 0 ? 0
                                    : (int) Math.min(100,
                                            received * 100L / total);
                            runOnUiThread(() -> detailText.setText(getString(
                                    R.string.update_download_progress,
                                    name, percent)));
                        });
                runOnUiThread(() -> {
                    stateText.setText(R.string.update_ready_title);
                    detailText.setText(getString(
                            R.string.update_staged_detail,
                            release.toReleaseId, staged.getAbsolutePath()));
                    primaryButton.setEnabled(false);
                    primaryButton.setText(R.string.update_waiting_applier);
                    channelButton.setEnabled(true);
                    availableRelease = null;
                    worker = null;
                });
            } catch (Exception failure) {
                showFailure(failure);
            }
        }, "paper-ota-download");
        worker.start();
    }

    private void showFailure(Exception failure) {
        runOnUiThread(() -> {
            stateText.setText(R.string.update_failed_title);
            detailText.setText(getString(R.string.update_failed_detail,
                    safeMessage(failure)));
            primaryButton.setEnabled(true);
            primaryButton.setText(R.string.update_retry);
            channelButton.setEnabled(true);
            availableRelease = null;
            worker = null;
        });
    }

    private void setBusy(int title, int detail) {
        stateText.setText(title);
        detailText.setText(detail);
        primaryButton.setEnabled(false);
        channelButton.setEnabled(false);
    }

    private void cycleChannel() {
        if (worker != null) {
            return;
        }
        int next = 0;
        for (int index = 0; index < CHANNELS.length; index++) {
            if (CHANNELS[index].equals(channel)) {
                next = (index + 1) % CHANNELS.length;
                break;
            }
        }
        channel = CHANNELS[next];
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(CHANNEL, channel).apply();
        channelButton.setText(channelLabel());
        availableRelease = null;
        showInitialState();
    }

    private OtaUpdateClient.Configuration configuration() {
        return new OtaUpdateClient.Configuration(
                getString(R.string.ota_feed_base_url),
                getString(R.string.ota_trusted_key_id),
                getString(R.string.ota_public_key_spki),
                getString(R.string.ota_current_release_id),
                getString(R.string.ota_installed_stock_version),
                channel,
                readPhysicalModel());
    }

    private String readPhysicalModel() {
        String value = readShort(new File("/proc/device-tree/model"));
        return value.replace("\u0000", "").trim();
    }

    private int batteryPercent() {
        BatteryManager battery = (BatteryManager)
                getSystemService(BATTERY_SERVICE);
        return battery == null ? -1 : battery.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private String channelLabel() {
        int label = R.string.update_channel_stable;
        if ("public-beta".equals(channel)) {
            label = R.string.update_channel_public_beta;
        } else if ("private-beta".equals(channel)) {
            label = R.string.update_channel_private_beta;
        }
        return getString(R.string.update_channel, getString(label));
    }

    private static String readShort(File file) {
        if (!file.isFile() || file.length() > 4096L) {
            return "";
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int count = input.read(data, offset, data.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            return new String(data, 0, offset,
                    StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(PaperLocale.current(this), "%.1f GB",
                    bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return String.format(PaperLocale.current(this), "%.1f MB",
                bytes / (1024.0 * 1024.0));
    }

    private TextView text(String value, int size, boolean bold) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextColor(Color.rgb(28, 28, 27));
        result.setTextSize(size);
        result.setTypeface(Typeface.create("sans-serif",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return result;
    }

    private Button button(String value, boolean strong) {
        Button result = new Button(this);
        result.setText(value);
        result.setTextSize(20);
        result.setTextColor(strong ? Color.WHITE : Color.rgb(28, 28, 27));
        result.setAllCaps(false);
        result.setGravity(Gravity.CENTER);
        result.setBackground(box(
                strong ? Color.rgb(38, 38, 36) : Color.WHITE,
                Color.rgb(72, 72, 69), 2, 8));
        result.setStateListAnimator(null);
        return result;
    }

    private GradientDrawable box(int fill, int stroke,
            int strokeWidth, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(strokeWidth, stroke);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void finishWithoutAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }
}

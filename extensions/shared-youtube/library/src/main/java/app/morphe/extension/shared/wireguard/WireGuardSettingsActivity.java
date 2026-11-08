package app.morphe.extension.shared.wireguard;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/**
 * Minimal profile UI with no VpnService, storage permission, account login or secret export.
 * The native backend is loaded only after an explicit import/enable or enabled network use.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class WireGuardSettingsActivity extends Activity {
    private static final int IMPORT_REQUEST = 0x5747;
    private static final int PAGE_PADDING_DP = 20;
    private static final int STATUS_TEXT_SP = 16;

    // Executors create their first thread lazily, only after a user starts an operation.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button importButton;
    private Button toggleButton;
    private Button removeButton;
    private Button restartButton;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(str("morphe_wireguard_settings_title"));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final int padding = Math.round(PAGE_PADDING_DP * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);

        TextView introduction = new TextView(this);
        introduction.setText(str("morphe_wireguard_intro"));
        introduction.setTextSize(STATUS_TEXT_SP);
        content.addView(introduction);

        status = new TextView(this);
        status.setTextSize(STATUS_TEXT_SP);
        status.setPadding(0, padding, 0, padding);
        content.addView(status);
        importButton = button(content, "morphe_wireguard_import", view -> openPicker());
        toggleButton = button(content, "morphe_wireguard_enable", view -> confirmToggle());
        removeButton = button(content, "morphe_wireguard_remove", view -> confirmRemove());
        restartButton = button(content, "morphe_wireguard_restart", view -> restart());
        setContentView(scroll);
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshState();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private Button button(LinearLayout content, String textKey, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(str(textKey));
        button.setFilterTouchesWhenObscured(true);
        button.setOnClickListener(listener);
        content.addView(button);
        return button;
    }

    private void refreshState() {
        final boolean available = WireGuardProxy.isPatchIncluded();
        final boolean hasProfile = WireGuardConfigStore.hasProfile(this);
        final boolean savedEnabled = SharedYouTubeSettings.WIREGUARD_ENABLED.get();
        String stateKey = switch (WireGuardProxy.state()) {
            case DISABLED -> "morphe_wireguard_state_disabled";
            case NOT_STARTED -> "morphe_wireguard_state_pending";
            case READY -> "morphe_wireguard_state_ready";
            case BLOCKED -> "morphe_wireguard_state_blocked";
        };
        String text = str(hasProfile ? "morphe_wireguard_profile_present"
                : "morphe_wireguard_profile_missing") + "\n\n" + str(stateKey)
                + "\n\n" + str(savedEnabled ? "morphe_wireguard_saved_enabled"
                : "morphe_wireguard_saved_disabled");
        if (WireGuardProxy.isRestartRequired()) {
            text += "\n\n" + str("morphe_wireguard_restart_needed");
        }
        if (!available) text = str("morphe_wireguard_unavailable");
        if (busy) text += "\n\n" + str("morphe_wireguard_busy");
        status.setText(text);
        toggleButton.setText(str(savedEnabled ? "morphe_wireguard_disable"
                : "morphe_wireguard_enable"));
        importButton.setEnabled(available && !busy);
        toggleButton.setEnabled(available && !busy && (hasProfile || savedEnabled));
        removeButton.setEnabled(available && !busy && hasProfile);
        restartButton.setEnabled(!busy);
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Providers often assign generic MIME types to .conf files; the contents are validated.
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, IMPORT_REQUEST);
        } catch (ActivityNotFoundException ex) {
            Logger.printException(() -> "No document picker available for WireGuard import");
            toast("morphe_wireguard_missing_picker");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_REQUEST || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) {
            toast("morphe_wireguard_import_failed");
            return;
        }
        Context application = getApplicationContext();
        runOperation(() -> {
            String config;
            try (InputStream input = application.getContentResolver().openInputStream(uri)) {
                config = ConfigText.read(input);
            }
            NativeTunnel.validate(config);
            if (Thread.currentThread().isInterrupted()) return;
            WireGuardConfigStore.save(application, config);
            WireGuardProxy.markRestartRequired();
        }, () -> {
            toast("morphe_wireguard_imported");
            refreshState();
        }, "morphe_wireguard_import_failed");
    }

    private void confirmToggle() {
        final boolean enable = !SharedYouTubeSettings.WIREGUARD_ENABLED.get();
        new AlertDialog.Builder(this)
                .setTitle(str("morphe_wireguard_settings_title"))
                .setMessage(str(enable ? "morphe_wireguard_enable_confirm"
                        : "morphe_wireguard_disable_confirm"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(str("morphe_wireguard_apply_restart"), (dialog, which) -> {
                    Context application = getApplicationContext();
                    runOperation(() -> {
                        if (enable) NativeTunnel.validate(WireGuardConfigStore.load(application));
                        if (Thread.currentThread().isInterrupted()) return;
                        // Morphe settings persist synchronously; changing routing in this process
                        // is deliberately avoided because existing engines keep their proxy options.
                        SharedYouTubeSettings.WIREGUARD_ENABLED.save(enable);
                        WireGuardProxy.markRestartRequired();
                    }, this::restart, "morphe_wireguard_import_failed");
                }).show();
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setTitle(str("morphe_wireguard_remove"))
                .setMessage(str("morphe_wireguard_remove_confirm"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(str("morphe_wireguard_apply_restart"), (dialog, which) -> {
                    Context application = getApplicationContext();
                    runOperation(() -> {
                        WireGuardConfigStore.delete(application);
                        SharedYouTubeSettings.WIREGUARD_ENABLED.save(false);
                        WireGuardProxy.markRestartRequired();
                    }, this::restart, "morphe_wireguard_remove_failed");
                }).show();
    }

    private void runOperation(Operation operation, Runnable success, String failureKey) {
        if (busy) return;
        busy = true;
        refreshState();
        worker.execute(() -> {
            boolean completed = false;
            try {
                operation.run();
                completed = !Thread.currentThread().isInterrupted();
            } catch (Exception | LinkageError ex) {
                // Neither document URIs, profile contents, native errors nor keys are logged.
                Logger.printException(() -> "WireGuard profile operation failed");
            }
            final boolean succeeded = completed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                refreshState();
                if (succeeded) success.run();
                else toast(failureKey);
            });
        });
    }

    private void restart() {
        try {
            Utils.restartApp(this);
        } catch (Exception ex) {
            Logger.printException(() -> "WireGuard settings require a manual app restart");
            toast("morphe_wireguard_restart_failed");
        }
    }

    private void toast(String key) {
        Toast.makeText(this, str(key), Toast.LENGTH_LONG).show();
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }
}

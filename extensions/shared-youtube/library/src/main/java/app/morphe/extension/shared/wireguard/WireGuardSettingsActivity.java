// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseActivityHook;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.ToolbarPreferenceFragment;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * Morphe preference UI with no VpnService, storage permission, account login or secret export.
 * The native backend is loaded only after an explicit import/enable or enabled network use.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class WireGuardSettingsActivity extends BaseActivityHook {
    private static final int IMPORT_REQUEST = 0x5747;
    private static final int PAGE_PADDING_DP = 20;
    private static final String LICENSE_ASSET_PATH = "wireguard/licenses";

    // Executors create their first thread lazily, only after a user starts an operation.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @Nullable
    private SettingsFragment settingsFragment;
    private boolean busy;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(BaseActivityHook.getAttachBaseContext(base));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        customizeActivityTheme(this);
        super.onCreate(savedInstanceState);
        setTitle(localizedString("morphe_wireguard_settings_title"));
        setContentView(getContentViewResourceId());
        getWindow().getDecorView().setBackgroundColor(ThemeUtils.getAppBackgroundColor());

        Fragment restored = getFragmentManager().findFragmentById(ID_MORPHE_SETTINGS_FRAGMENTS);
        settingsFragment = restored instanceof SettingsFragment
                ? (SettingsFragment) restored : new SettingsFragment();
        createToolbar(this, settingsFragment);
        if (!(restored instanceof SettingsFragment)) {
            getFragmentManager().beginTransaction()
                    .replace(ID_MORPHE_SETTINGS_FRAGMENTS, settingsFragment)
                    .commit();
        }
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void customizeActivityTheme(Activity activity) {
        // Reuse the themes selected by the existing Morphe settings hooks.
        final int musicTheme = ResourceUtils.getIdentifier(
                ResourceType.STYLE, "Theme.Morphe.YouTubeMusic.Settings");
        if (musicTheme != 0) {
            Utils.setIsDarkModeEnabled(true);
            activity.setTheme(musicTheme);
        } else {
            activity.setTheme(ResourceUtils.getIdentifierOrThrow(ResourceType.STYLE,
                    Utils.isDarkModeEnabled() ? "Theme.YouTube.Settings.Dark" : "Theme.YouTube.Settings"));
        }
    }

    @Override
    protected int getToolbarBackgroundColor() {
        return ThemeUtils.getAppBackgroundColor();
    }

    @Override
    protected Drawable getNavigationIcon() {
        return ToolbarPreferenceFragment.getBackButtonDrawable();
    }

    @Override
    protected View.OnClickListener getNavigationClickListener(Activity activity) {
        return view -> activity.finish();
    }

    @Override
    protected void onPostToolbarSetup(Activity activity, Toolbar toolbar, PreferenceFragment fragment) {
        toolbar.setTitle(localizedString("morphe_wireguard_settings_title"));
        toolbar.setNavigationContentDescription(localizedString("morphe_settings_navigate_up"));
        toolbar.setFilterTouchesWhenObscured(true);
        ToolbarPreferenceFragment.addToolbarButton(toolbar, "morphe_settings_screen_00_about",
                localizedString("morphe_settings_screen_info_title"), () -> new AlertDialog.Builder(this)
                        .setTitle(localizedString("morphe_wireguard_settings_title"))
                        .setMessage(localizedString("morphe_wireguard_intro"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
    }

    private void refreshState() {
        if (settingsFragment != null) settingsFragment.refreshState(this);
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
            showProfileFailure("morphe_wireguard_import_read_failed", null);
            return;
        }
        Context application = getApplicationContext();
        runOperation(() -> {
            ProfileOperations.importProfile(application,
                    () -> application.getContentResolver().openInputStream(uri));
            if (Thread.currentThread().isInterrupted()) return;
            WireGuardProxy.markRestartRequired();
        }, () -> {
            toast("morphe_wireguard_imported");
            refreshState();
        }, "morphe_wireguard_import_failed");
    }

    private void confirmToggle() {
        final boolean enable = !SharedYouTubeSettings.WIREGUARD_ENABLED.get();
        new AlertDialog.Builder(this)
                .setTitle(localizedString("morphe_wireguard_settings_title"))
                .setMessage(localizedString(enable ? "morphe_wireguard_enable_confirm"
                        : "morphe_wireguard_disable_confirm"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(localizedString("morphe_wireguard_apply_restart"), (dialog, which) -> {
                    Context application = getApplicationContext();
                    runOperation(() -> {
                        if (enable) ProfileOperations.validateStoredProfile(application);
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
                .setTitle(localizedString("morphe_wireguard_remove"))
                .setMessage(localizedString("morphe_wireguard_remove_confirm"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(localizedString("morphe_wireguard_apply_restart"), (dialog, which) -> {
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
            ProfileOperations.Failure failure = null;
            try {
                operation.run();
                completed = !Thread.currentThread().isInterrupted();
            } catch (ProfileOperations.Failure ex) {
                failure = ex;
                Logger.printException(() -> "WireGuard profile operation failed: " + ex.resourceKey);
            } catch (Exception | LinkageError ex) {
                // Neither document URIs, profile contents, native errors nor keys are logged.
                Logger.printException(() -> "WireGuard profile operation failed");
            }
            final boolean succeeded = completed;
            ProfileOperations.Failure profileFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                refreshState();
                if (succeeded) success.run();
                else if (profileFailure != null) {
                    showProfileFailure(profileFailure.resourceKey, profileFailure.validationReasonKey);
                } else toast(failureKey);
            });
        });
    }

    private void showProfileFailure(String resourceKey, @Nullable String validationReasonKey) {
        String message = localizedString(resourceKey);
        if (validationReasonKey != null && !validationReasonKey.isEmpty()) {
            message += "\n\n" + localizedString(validationReasonKey);
        }
        new AlertDialog.Builder(this)
                .setTitle(localizedString("morphe_wireguard_profile_error_title"))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void restart() {
        try {
            Utils.restartApp(this);
        } catch (Exception ex) {
            Logger.printException(() -> "WireGuard settings require a manual app restart");
            toast("morphe_wireguard_restart_failed");
        }
    }

    private void showLicenses() {
        StringBuilder notices = new StringBuilder();
        runOperation(() -> appendLicenses(getAssets(), LICENSE_ASSET_PATH, notices), () -> {
            TextView text = new TextView(this);
            text.setText(notices.toString());
            text.setTextIsSelectable(true);
            final int padding = Math.round(PAGE_PADDING_DP * getResources().getDisplayMetrics().density);
            text.setPadding(padding, padding, padding, padding);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(text);
            new AlertDialog.Builder(this)
                    .setTitle(localizedString("morphe_wireguard_licenses"))
                    .setView(scroll)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }, "morphe_wireguard_licenses_failed");
    }

    private static void appendLicenses(AssetManager assets, String path, StringBuilder notices)
            throws IOException {
        String[] children = assets.list(path);
        if (children == null) throw new IOException("License assets are unavailable");
        if (children.length > 0) {
            Arrays.sort(children);
            for (String child : children) appendLicenses(assets, path + "/" + child, notices);
            return;
        }
        notices.append(path.substring(LICENSE_ASSET_PATH.length() + 1)).append("\n\n");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) notices.append(line).append('\n');
        }
        notices.append("\n\n");
    }

    private String localizedString(String key) {
        // Match this screen's locale without reusing process-wide cached string values.
        return getString(ResourceUtils.getIdentifierOrThrow(this, ResourceType.STRING, key));
    }

    private void toast(String key) {
        Toast.makeText(this, localizedString(key), Toast.LENGTH_LONG).show();
    }

    /** Framework-restorable preference list; profile operations remain owned by the Activity. */
    public static final class SettingsFragment extends PreferenceFragment {
        @Nullable
        private RoutingSwitchPreference enabled;
        @Nullable
        private Preference status;
        @Nullable
        private Preference importProfile;
        @Nullable
        private Preference removeProfile;
        @Nullable
        private Preference restartApp;
        @Nullable
        private Preference licenses;

        public SettingsFragment() {
        }

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            WireGuardSettingsActivity activity = (WireGuardSettingsActivity) getActivity();
            activity.settingsFragment = this;
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(activity);
            screen.setOrderingAsAdded(true);

            enabled = new RoutingSwitchPreference(activity);
            enabled.setTitle(activity.localizedString("morphe_wireguard_enabled_title"));
            enabled.setOnPreferenceChangeListener((preference, value) -> {
                activity.confirmToggle();
                // Only validation and the confirmation handler may persist this setting.
                return false;
            });
            screen.addPreference(enabled);

            status = new ActionPreference(activity);
            status.setTitle(activity.localizedString("morphe_wireguard_status_title"));
            status.setSelectable(false);
            screen.addPreference(status);

            importProfile = action(screen, activity, "morphe_wireguard_import", activity::openPicker);
            removeProfile = action(screen, activity, "morphe_wireguard_remove", activity::confirmRemove);
            restartApp = action(screen, activity, "morphe_wireguard_restart", activity::restart);
            licenses = action(screen, activity, "morphe_wireguard_licenses", activity::showLicenses);
            setPreferenceScreen(screen);
            refreshState(activity);
        }

        @Override
        public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            view.setBackgroundColor(ThemeUtils.getAppBackgroundColor());
            view.setFilterTouchesWhenObscured(true);
            View list = view.findViewById(android.R.id.list);
            if (list != null) list.setFilterTouchesWhenObscured(true);
        }

        private static Preference action(PreferenceScreen screen, WireGuardSettingsActivity activity,
                                         String titleKey, Runnable onClick) {
            Preference preference = new ActionPreference(activity);
            preference.setTitle(activity.localizedString(titleKey));
            preference.setOnPreferenceClickListener(clicked -> {
                onClick.run();
                return true;
            });
            screen.addPreference(preference);
            return preference;
        }

        private void refreshState(WireGuardSettingsActivity activity) {
            if (enabled == null || status == null || importProfile == null
                    || removeProfile == null || restartApp == null || licenses == null) return;
            final boolean available = WireGuardProxy.isPatchIncluded();
            final boolean hasProfile = WireGuardConfigStore.hasProfile(activity);
            final boolean savedEnabled = SharedYouTubeSettings.WIREGUARD_ENABLED.get();
            String stateKey = switch (WireGuardProxy.state()) {
                case DISABLED -> "morphe_wireguard_state_disabled";
                case NOT_STARTED -> "morphe_wireguard_state_pending";
                case READY -> "morphe_wireguard_state_ready";
                case BLOCKED -> "morphe_wireguard_state_blocked";
            };
            String state = activity.localizedString(available ? stateKey : "morphe_wireguard_unavailable");
            if (activity.busy) state += "\n\n" + activity.localizedString("morphe_wireguard_busy");
            status.setSummary(state);
            enabled.setChecked(savedEnabled);
            enabled.setSummary(activity.localizedString(savedEnabled ? "morphe_wireguard_saved_enabled"
                    : "morphe_wireguard_saved_disabled"));
            importProfile.setSummary(activity.localizedString(hasProfile ? "morphe_wireguard_profile_present"
                    : "morphe_wireguard_profile_missing"));
            restartApp.setSummary(WireGuardProxy.isRestartRequired()
                    ? activity.localizedString("morphe_wireguard_restart_needed") : null);

            enabled.setEnabled(available && !activity.busy && (hasProfile || savedEnabled));
            importProfile.setEnabled(available && !activity.busy);
            removeProfile.setEnabled(available && !activity.busy && hasProfile);
            restartApp.setEnabled(!activity.busy);
            licenses.setEnabled(available && !activity.busy);
        }
    }

    private static final class ActionPreference extends Preference {
        ActionPreference(Context context) {
            super(context);
            setPersistent(false);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            view.setFilterTouchesWhenObscured(true);
        }
    }

    private static final class RoutingSwitchPreference extends SwitchPreference {
        RoutingSwitchPreference(Context context) {
            super(context);
            setPersistent(false);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            view.setFilterTouchesWhenObscured(true);
        }
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }
}

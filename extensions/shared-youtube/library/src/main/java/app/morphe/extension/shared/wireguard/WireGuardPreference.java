// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import static app.morphe.extension.shared.StringRef.str;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** XML preference entry. The target Activity is explicitly registered as not exported. */
@SuppressWarnings({"unused", "deprecation"})
public final class WireGuardPreference extends Preference {
    public WireGuardPreference(Context context) {
        super(context);
    }

    public WireGuardPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public WireGuardPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public WireGuardPreference(Context context, AttributeSet attrs, int defStyleAttr,
                               int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onClick() {
        try {
            getContext().startActivity(new Intent(getContext(), WireGuardSettingsActivity.class));
        } catch (ActivityNotFoundException ex) {
            Logger.printException(() -> "WireGuard settings activity is unavailable");
            Utils.showToastLong(str("morphe_wireguard_unavailable"));
        }
    }
}

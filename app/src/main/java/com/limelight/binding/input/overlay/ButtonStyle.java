package com.limelight.binding.input.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.limelight.R;

/**
 * Visual style of the gamepad overlay.
 */
public enum ButtonStyle {
    XBOX("xbox", R.string.overlay_style_xbox),
    PLAYSTATION("playstation", R.string.overlay_style_playstation),
    SWITCH("switch", R.string.overlay_style_switch),
    /** The historical drawable based skin. */
    CLASSIC("classic", R.string.overlay_style_classic),
    /** The historical outline ("official") skin. */
    OUTLINE("outline", R.string.overlay_style_outline);

    public static final String PREF_KEY = "list_osc_button_style";
    private static final String LEGACY_OUTLINE_PREF = "checkbox_onscreen_style_official";

    public final String value;
    public final int labelRes;

    ButtonStyle(String value, int labelRes) {
        this.value = value;
        this.labelRes = labelRes;
    }

    /** Styles drawn by {@link GamepadStyleRenderer}. */
    public boolean isVector() {
        return this == XBOX || this == PLAYSTATION || this == SWITCH;
    }

    public static ButtonStyle fromValue(String value) {
        for (ButtonStyle style : values()) {
            if (style.value.equals(value)) {
                return style;
            }
        }
        return null;
    }

    public static ButtonStyle fromPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        ButtonStyle style = fromValue(prefs.getString(PREF_KEY, null));
        if (style != null) {
            return style;
        }
        // Migrate the old "official style" checkbox
        return prefs.getBoolean(LEGACY_OUTLINE_PREF, false) ? OUTLINE : CLASSIC;
    }

    public void save(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_KEY, value)
                .putBoolean(LEGACY_OUTLINE_PREF, this == OUTLINE)
                .apply();
    }
}

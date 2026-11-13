package com.limelight.binding.input.overlay;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings edited in the input overlay editor rather than in the settings screen. They are
 * stored in the default SharedPreferences, like the settings of the settings screen, and are
 * declared here so that the settings backup knows them.
 */
public final class OverlayEditorSettings {
    // Overlay options (editor "Style" dialog)
    public static final String BUTTON_STYLE = "list_osc_button_style";
    /** Legacy "official style" checkbox, kept in sync with {@link #BUTTON_STYLE}. */
    public static final String LEGACY_OUTLINE_STYLE = "checkbox_onscreen_style_official";
    public static final String OPACITY = "seekbar_osc_opacity";
    public static final String SHOW_GUIDE_BUTTON = "checkbox_show_guide_button";
    public static final String FLOATING_STICKS = "checkbox_enable_analog_stick_new";
    public static final String FLOATING_STICKS_OPACITY = "seekbar_osc_free_analog_stick_opacity";
    public static final String SQUARE_KEYS = "checkbox_enable_keyboard_square";

    /** Active gamepad layout slot (the keyboard one is in the settings screen). */
    public static final String GAMEPAD_LAYOUT = "osc_gamepad_layout";

    // Editor preferences
    public static final String EDITOR_SNAP = "overlay_editor_snap";
    public static final String EDITOR_GRID = "overlay_editor_grid";
    public static final String EDITOR_TOOLBAR_POSITION = "overlay_editor_toolbar_position";

    private OverlayEditorSettings() {
    }

    /**
     * Every setting of the overlay editor with the type of its value, using the settings
     * backup type codes: "b" boolean, "i" int, "s" string.
     */
    public static Map<String, String> declared() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(BUTTON_STYLE, "s");
        settings.put(LEGACY_OUTLINE_STYLE, "b");
        settings.put(OPACITY, "i");
        settings.put(SHOW_GUIDE_BUTTON, "b");
        settings.put(FLOATING_STICKS, "b");
        settings.put(FLOATING_STICKS_OPACITY, "i");
        settings.put(SQUARE_KEYS, "b");
        settings.put(GAMEPAD_LAYOUT, "s");
        settings.put(EDITOR_SNAP, "b");
        settings.put(EDITOR_GRID, "b");
        settings.put(EDITOR_TOOLBAR_POSITION, "s");
        return Collections.unmodifiableMap(settings);
    }
}

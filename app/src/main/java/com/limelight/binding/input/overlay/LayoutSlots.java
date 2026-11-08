package com.limelight.binding.input.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.Map;

/**
 * Layout slots: each overlay has {@link #SLOT_COUNT} independent layouts, each one stored in
 * its own SharedPreferences file (one JSON string per element).
 *
 * File names are kept compatible with older builds: slot 1 of the gamepad overlay is the
 * historical "OSC" file, keyboard slots are the historical "OSC_Keyboard[_n]" files.
 */
public final class LayoutSlots {
    public enum Kind { GAMEPAD, KEYBOARD }

    public static final int SLOT_COUNT = 5;

    /** Active gamepad slot (file name). */
    public static final String PREF_GAMEPAD_SLOT = "osc_gamepad_layout";
    /** Active keyboard slot (file name), historical preference key. */
    public static final String PREF_KEYBOARD_SLOT = "keyboard_axi_list";

    private LayoutSlots() {
    }

    public static String fileForSlot(Kind kind, int slot) {
        if (kind == Kind.GAMEPAD) {
            return slot == 0 ? "OSC" : "OSC_" + (slot + 1);
        } else {
            return slot == 0 ? "OSC_Keyboard" : "OSC_Keyboard_" + (slot + 1);
        }
    }

    private static String prefKey(Kind kind) {
        return kind == Kind.GAMEPAD ? PREF_GAMEPAD_SLOT : PREF_KEYBOARD_SLOT;
    }

    public static int getActiveSlot(Context context, Kind kind) {
        String file = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(prefKey(kind), fileForSlot(kind, 0));
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (fileForSlot(kind, i).equals(file)) {
                return i;
            }
        }
        return 0;
    }

    public static void setActiveSlot(Context context, Kind kind, int slot) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(prefKey(kind), fileForSlot(kind, slot))
                .apply();
    }

    public static SharedPreferences open(Context context, Kind kind, int slot) {
        return context.getSharedPreferences(fileForSlot(kind, slot), Context.MODE_PRIVATE);
    }

    public static SharedPreferences openActive(Context context, Kind kind) {
        return open(context, kind, getActiveSlot(context, kind));
    }

    public static boolean isEmpty(Context context, Kind kind, int slot) {
        return open(context, kind, slot).getAll().isEmpty();
    }

    public static void clear(Context context, Kind kind, int slot) {
        open(context, kind, slot).edit().clear().commit();
    }

    public static void copy(Context context, Kind kind, int fromSlot, int toSlot) {
        if (fromSlot == toSlot) {
            return;
        }
        SharedPreferences.Editor editor = open(context, kind, toSlot).edit().clear();
        for (Map.Entry<String, ?> entry : open(context, kind, fromSlot).getAll().entrySet()) {
            if (entry.getValue() instanceof String) {
                editor.putString(entry.getKey(), (String) entry.getValue());
            }
        }
        editor.commit();
    }
}

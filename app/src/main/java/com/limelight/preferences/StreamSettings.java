package com.limelight.preferences;

import static com.limelight.utils.ServerHelper.getActiveDisplay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.MediaCodecInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;
import android.os.Vibrator;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.fragment.app.DialogFragment;
import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.Toast;

import com.google.gson.Gson;
import com.limelight.DebugInfoActivity;
import com.limelight.BuildConfig;
import com.limelight.GameMenu;
import com.limelight.LimeLog;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardControllerConfigurationLoader;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.FileUriUtils;
import com.limelight.utils.PerformanceDataTracker;
import com.limelight.utils.UiHelper;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import androidx.appcompat.app.AlertDialog;

public class StreamSettings extends AppCompatActivity {
    private PreferenceConfiguration previousPrefs;
    private int previousDisplayPixelCount;

    private SettingsFragment prefsFragment;

    // HACK for Android 9
    static DisplayCutout displayCutoutP;

    void reloadSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = getActiveDisplay(StreamSettings.this, previousPrefs).getMode();
            previousDisplayPixelCount = mode.getPhysicalWidth() * mode.getPhysicalHeight();
        }
        prefsFragment = new SettingsFragment(PreferenceConfiguration.readPreferences(
                this,
                PreferenceManager.getDefaultSharedPreferences(this)
        ));
        getSupportFragmentManager().beginTransaction().replace(
                R.id.stream_settings, prefsFragment
        ).commitAllowingStateLoss();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
//        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);

        previousPrefs = PreferenceConfiguration.readPreferences(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_stream_settings);

//        UiHelper.notifyNewRootView(this);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        // We have to use this hack on Android 9 because we don't have Display.getCutout()
        // which was added in Android 10.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            // Insets can be null when the activity is recreated on screen rotation
            // https://stackoverflow.com/questions/61241255/windowinsets-getdisplaycutout-is-null-everywhere-except-within-onattachedtowindo
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                displayCutoutP = insets.getDisplayCutout();
            }
        }

        reloadSettings();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = getActiveDisplay(StreamSettings.this, previousPrefs).getMode();

            // If the display's physical pixel count has changed, we consider that it's a new display
            // and we should reload our settings (which include display-dependent values).
            //
            // NB: We aren't using displayId here because that stays the same (DEFAULT_DISPLAY) when
            // switching between screens on a foldable device.
            if (mode.getPhysicalWidth() * mode.getPhysicalHeight() != previousDisplayPixelCount) {
                reloadSettings();
            }
        }
    }

    @Override
    // NOTE: This will NOT be called on Android 13+ with android:enableOnBackInvokedCallback="true"
    public void onBackPressed() {
        finish();

        // Language changes are handled via configuration changes in Android 13+,
        // so manual activity relaunching is no longer required.
        PreferenceConfiguration newPrefs = PreferenceConfiguration.readPreferences(this);
        if (!newPrefs.language.equals(previousPrefs.language)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Restart the PC view to apply UI changes
                Intent intent = new Intent(this, PcView.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent, null);
            } else {
                if (newPrefs.language == PreferenceConfiguration.DEFAULT_LANGUAGE) {
                    Toast.makeText(this, "Language has been reset to default, please restart the app!", Toast.LENGTH_LONG).show();
                    System.exit(0);
                }
            }
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        // --- UI state listeners ---
        SharedPreferences.OnSharedPreferenceChangeListener lockWatcher =
                (sp, key) -> {
                    if ("checkbox_gpu_path_mode".equals(key)                 // Direct Present
                            || "pref_video_upscale_enable".equals(key)       // FSR
                            || "pref_video_hdr_enable".equals(key)           // HDR (old)
                            || "pref_hdr_enable".equals(key)                 // HDR (legacy)
                            || "pref_hdr_pipeline_enable".equals(key)        // HDR pipeline
                            || "frame_pacing".equals(key)                    // Legacy pacing list
                            || "seekbar_adaptx_mode".equals(key)             // Legacy AdaptX sub-mode
                            || "seekbar_frame_pacing_profile".equals(key)    // Unified pacing profile slider
                            || "pref_low_latency_frame_balance".equals(key)  // LFR toggle (Prefer lower delays)

                    ) {
                        // Re-evaluate UI locks and dependent visibility (including LFR mode slider)
                        updateLocks();
                    }
                };




        private void updateLocks() {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());

            // Persisted GPU Path state
            boolean gpuPathStored = sp.getBoolean("checkbox_gpu_path_mode", false);

            // Aggregate HDR state
            boolean hdrOn =
                    sp.getBoolean("checkbox_enable_hdr", false) ||
                            (sp.contains("pref_video_hdr_enable") && sp.getBoolean("pref_video_hdr_enable", false)) ||
                            (sp.contains("pref_hdr_enable") && sp.getBoolean("pref_hdr_enable", false)) ||
                            (sp.contains("pref_hdr_pipeline_enable") && sp.getBoolean("pref_hdr_pipeline_enable", false));

            // Read current pacing mode
            String pacingStr = sp.getString("frame_pacing", "latency");
            if (pacingStr == null) pacingStr = "latency";

            // LFR preference
            boolean preferLowerDelays = sp.getBoolean("pref_low_latency_frame_balance", false);

            // Map pacing string to flags
            boolean isMinLatency = "latency".equals(pacingStr);
            boolean isGpuRaw     = "gpu-raw".equals(pacingStr);
            boolean isWarp       = "warp".equals(pacingStr);
            boolean isWarp2      = "warp2".equals(pacingStr);

            // Effective GPU Path state
            boolean gpuPath = gpuPathStored;

            // Dependency locks
            boolean lockFsrEn = gpuPath || hdrOn;

            Preference pacingPref  = findPreference("frame_pacing");
            Preference lfrBal      = findPreference("pref_low_latency_frame_balance");
            Preference fsrEn       = findPreference("pref_video_upscale_enable");
            Preference fsrMode     = findPreference("pref_video_upscale_mode");
            Preference sharpness   = findPreference("pref_video_upscale_sharpness");

            // Define LFR visibility scope
            boolean showLfrToggle = isGpuRaw || isMinLatency || isWarp || isWarp2;

            if (lfrBal != null) {
                try {
                    lfrBal.setVisible(showLfrToggle);
                } catch (Throwable ignored) {
                    lfrBal.setEnabled(showLfrToggle);
                }
                lfrBal.setEnabled(showLfrToggle); // Ensure enabled state
            }

            // Determine FSR enabled state
            boolean fsrEnabled = sp.getBoolean("pref_video_upscale_enable", false) && !lockFsrEn;

            // FSR enable toggle
            if (fsrEn != null) {
                fsrEn.setEnabled(!lockFsrEn);
            }

            // FSR mode selector
            if (fsrMode != null) {
                try {
                    fsrMode.setVisible(fsrEnabled);
                } catch (Throwable ignored) {
                    fsrMode.setEnabled(fsrEnabled);
                }
                fsrMode.setEnabled(fsrEnabled);
            }

            // FSR sharpness slider
            if (sharpness != null) {
                try {
                    sharpness.setVisible(fsrEnabled);
                } catch (Throwable ignored) {
                    sharpness.setEnabled(fsrEnabled);
                }
                sharpness.setEnabled(fsrEnabled);
            }

            // Enforce mutual exclusion (disable FSR if locked)
            if (lockFsrEn) {
                if (fsrEn instanceof CheckBoxPreference) {
                    CheckBoxPreference cb = (CheckBoxPreference) fsrEn;
                    if (cb.isChecked()) {
                        sp.edit().putBoolean("pref_video_upscale_enable", false).apply();
                        cb.setChecked(false);
                    }
                } else {
                    if (sp.getBoolean("pref_video_upscale_enable", false)) {
                        sp.edit().putBoolean("pref_video_upscale_enable", false).apply();
                    }
                }
            }
        }

@Override
public void onResume() {
    super.onResume();
    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
    sp.registerOnSharedPreferenceChangeListener(lockWatcher);
    updateLocks();
}

@Override
public void onPause() {
    super.onPause();
    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
    sp.unregisterOnSharedPreferenceChangeListener(lockWatcher);
}

        private int nativeResolutionStartIndex = Integer.MAX_VALUE;
        // Track injected native/custom resolution values so warnings keep working even if the list is re-ordered.
        private final HashSet<String> nativeResolutionValues = new HashSet<>();

        private boolean nativeFramerateShown = false;

        private PreferenceConfiguration prevPrefConfig;

        public SettingsFragment(PreferenceConfiguration prefCfg) {
            prevPrefConfig = prefCfg;
        }

        protected SharedPreferences getPrefs() {
            return getPreferenceManager().getSharedPreferences();
        }

        private void setValue(String preferenceKey, String value) {
            ListPreference pref = (ListPreference) findPreference(preferenceKey);

            pref.setValue(value);
        }

        private void appendPreferenceEntry(ListPreference pref, String newEntryName, String newEntryValue) {
            CharSequence[] newEntries = Arrays.copyOf(pref.getEntries(), pref.getEntries().length + 1);
            CharSequence[] newValues = Arrays.copyOf(pref.getEntryValues(), pref.getEntryValues().length + 1);

            // Add the new option
            newEntries[newEntries.length - 1] = newEntryName;
            newValues[newValues.length - 1] = newEntryValue;

            pref.setEntries(newEntries);
            pref.setEntryValues(newValues);
        }

        private void addNativeResolutionEntry(int nativeWidth, int nativeHeight, boolean insetsRemoved, boolean portrait, boolean is_custom) {
            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);

            String newName;

            if (insetsRemoved) {
                newName = getResources().getString(R.string.resolution_prefix_native_fullscreen);
            }
            else {
                newName = is_custom ? getResources().getString(R.string.resolution_prefix_custom) : getResources().getString(R.string.resolution_prefix_native);
            }

            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                if (portrait) {
                    newName += " " + getResources().getString(R.string.resolution_prefix_native_portrait);
                }
                else {
                    newName += " " + getResources().getString(R.string.resolution_prefix_native_landscape);
                }
            }

            newName += " ("+nativeWidth+"x"+nativeHeight+")";

            String newValue = nativeWidth+"x"+nativeHeight;

            // Check if the native resolution is already present
            for (CharSequence value : pref.getEntryValues()) {
                if (newValue.equals(value.toString())) {
                    // It is present in the default list, so don't add it again
                    return;
                }
            }

            if (pref.getEntryValues().length < nativeResolutionStartIndex) {
                nativeResolutionStartIndex = pref.getEntryValues().length;
            }
            appendPreferenceEntry(pref, newName, newValue);
            nativeResolutionValues.add(newValue);
        }

        private void addNativeResolutionEntries(int nativeWidth, int nativeHeight, boolean insetsRemoved, boolean is_custom) {
            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                addNativeResolutionEntry(nativeHeight, nativeWidth, insetsRemoved, true, is_custom);
            }
            addNativeResolutionEntry(nativeWidth, nativeHeight, insetsRemoved, false, is_custom);
        }

        private void addNativeFrameRateEntry(float framerate, boolean is_custom) {
            if (!is_custom) {
                framerate = Math.round(framerate);
                if (framerate == 0) {
                    return;
                }
            }

            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.FPS_PREF_STRING);
            String fpsValue = is_custom ? Float.toString(framerate) : Integer.toString(Math.round(framerate));
            String fpsName = (is_custom ? getResources().getString(R.string.resolution_prefix_custom) : getResources().getString(R.string.resolution_prefix_native)) +
                    " (" + fpsValue + " " + getResources().getString(R.string.fps_suffix_fps) + ")";

            // Check if the native frame rate is already present
            for (CharSequence value : pref.getEntryValues()) {
                if (fpsValue.equals(value.toString())) {
                    // It is present in the default list, so don't add it again
                    nativeFramerateShown = false;
                    return;
                }
            }

            appendPreferenceEntry(pref, fpsName, fpsValue);
            nativeFramerateShown = true;
        }
        // --- Suggested resolution selector (aspect-matched, up to 5 lower-than-display options) ---
        private static final int MAX_SUGGESTED_RESOLUTIONS = 5;
        private static final int MIN_SUGGESTED_HEIGHT = 360;

        // Common widths (descending) used as "nice" candidates when generating aspect-matched resolutions.
        private static final int[] COMMON_SUGGESTED_WIDTHS = new int[] {
                3840, 3440, 3200, 2560, 2400, 2160, 2048, 1920, 1680, 1600, 1536, 1440, 1366, 1280,
                1200, 1080, 1024, 960, 854, 800, 720, 640, 540
        };

        // Fallback scaling factors if COMMON_SUGGESTED_WIDTHS doesn't yield enough unique entries.
        private static final double[] FALLBACK_SCALES = new double[] {
                0.90, 0.83, 0.75, 0.66, 0.50, 0.40, 0.33
        };

        private void updateSuggestedResolutionSelector(Display display) {
            ListPreference suggestedPref = findPreference(PreferenceConfiguration.SUGGESTED_RESOLUTION_PREF_STRING);
            if (suggestedPref == null || display == null) {
                return;
            }

            int[] wh = getDisplayLandscapeSize(display);
            int displayW = wh[0];
            int displayH = wh[1];
            if (displayW <= 0 || displayH <= 0) {
                hidePreferenceBestEffort(suggestedPref);
                return;
            }

            List<String> suggestions = buildSuggestedResolutions(displayW, displayH, MAX_SUGGESTED_RESOLUTIONS);
            sortResolutionValueListAscending(suggestions);

            if (suggestions.isEmpty()) {
                hidePreferenceBestEffort(suggestedPref);
                return;
            }

            CharSequence[] entries = new CharSequence[suggestions.size()];
            CharSequence[] values  = new CharSequence[suggestions.size()];

            for (int i = 0; i < suggestions.size(); i++) {
                String v = suggestions.get(i);
                entries[i] = buildSuggestedEntryLabel(v, displayW, displayH);
                values[i]  = v;
            }

            suggestedPref.setEntries(entries);
            suggestedPref.setEntryValues(values);

            // Ensure the currently stored value is still valid (display mode changes can invalidate it).
            String cur = suggestedPref.getValue();
            if (cur != null && !cur.isEmpty()) {
                boolean valid = false;
                for (String v : suggestions) {
                    if (v.equals(cur)) {
                        valid = true;
                        break;
                    }
                }
                if (!valid) {
                    suggestedPref.setValueIndex(0);
                }
            }

            suggestedPref.setOnPreferenceChangeListener((preference, newValue) -> {
                if (!(newValue instanceof String)) {
                    return false;
                }

                String res = (String) newValue;
                int[] parsed = parseResolutionWxH(res);
                if (parsed == null) {
                    return false;
                }

                applySuggestedResolution(res);
                Toast.makeText(getActivity(), getString(R.string.pref_set_success), Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        private void hidePreferenceBestEffort(Preference pref) {
            try {
                pref.setVisible(false);
            } catch (Throwable ignored) {
                pref.setEnabled(false);
            }
        }

        private List<String> buildSuggestedResolutions(int displayW, int displayH, int maxCount) {
            double aspect = (double) displayW / (double) displayH;
            LinkedHashSet<String> out = new LinkedHashSet<>();

            // 0) Prefer standard-looking presets when the display aspect ratio is close to a common ratio.
            String[] preset = getPresetResolutionsForAspect(aspect);
            if (preset != null && preset.length > 0) {
                for (String v : preset) {
                    int[] wh = parseResolutionWxH(v);
                    if (wh == null) {
                        continue;
                    }

                    int w = wh[0];
                    int h = wh[1];

                    if (w >= displayW || h >= displayH) {
                        continue;
                    }

                    if (h < MIN_SUGGESTED_HEIGHT) {
                        continue;
                    }

                    out.add(w + "x" + h);
                    if (out.size() >= maxCount) {
                        return new ArrayList<>(out);
                    }
                }
            }

            // 1) Nice widths list
            for (int wCandidate : COMMON_SUGGESTED_WIDTHS) {
                if (wCandidate >= displayW) {
                    continue;
                }

                int hCandidate = (int) Math.round((double) wCandidate / aspect);

                // Keep it strictly smaller than the active display
                if (hCandidate >= displayH) {
                    continue;
                }

                if (hCandidate < MIN_SUGGESTED_HEIGHT) {
                    continue;
                }

                int wEven = (wCandidate / 2) * 2;
                int hEven = (hCandidate / 2) * 2;
                out.add(wEven + "x" + hEven);

                if (out.size() >= maxCount) {
                    return new ArrayList<>(out);
                }
            }

            // 2) Fallback: scale down from display size
            for (double s : FALLBACK_SCALES) {
                int wCandidate = (int) Math.round(displayW * s);
                int hCandidate = (int) Math.round((double) wCandidate / aspect);

                if (wCandidate >= displayW || hCandidate >= displayH) {
                    continue;
                }

                if (hCandidate < MIN_SUGGESTED_HEIGHT) {
                    continue;
                }

                int wEven = (wCandidate / 2) * 2;
                int hEven = (hCandidate / 2) * 2;
                out.add(wEven + "x" + hEven);

                if (out.size() >= maxCount) {
                    break;
                }
            }

            return new ArrayList<>(out);
        }

        private static final double ASPECT_TOLERANCE = 0.03;

        private static boolean isAspectNear(double aspect, int num, int den) {
            double target = (double) num / (double) den;
            double relErr = Math.abs(aspect - target) / target;
            return relErr <= ASPECT_TOLERANCE;
        }

        private static String[] getPresetResolutionsForAspect(double aspect) {
            if (isAspectNear(aspect, 16, 9)) {
                return new String[] { "3840x2160", "2560x1440", "1920x1080", "1600x900", "1280x720", "960x540", "854x480", "640x360" };
            }
            if (isAspectNear(aspect, 16, 10)) {
                return new String[] { "2560x1600", "1920x1200", "1680x1050", "1440x900", "1280x800", "960x600", "800x500" };
            }
            if (isAspectNear(aspect, 4, 3)) {
                return new String[] { "2048x1536", "1600x1200", "1440x1080", "1280x960", "1024x768", "800x600", "640x480" };
            }
            // Common ultrawide: ~21:9 (64:27)
            if (isAspectNear(aspect, 64, 27)) {
                return new String[] { "3440x1440", "2560x1080", "1920x810", "1600x675", "1280x540", "960x405" };
            }
            // Common phones: 20:9 and 18:9
            if (isAspectNear(aspect, 20, 9)) {
                return new String[] { "2400x1080", "2160x972", "1920x864", "1600x720", "1280x576", "960x432", "800x360" };
            }
            if (isAspectNear(aspect, 18, 9)) {
                return new String[] { "2160x1080", "1920x960", "1600x800", "1280x640", "960x480", "800x400" };
            }

            return null;
        }

        private static int[] getDisplayLandscapeSize(Display display) {
            int w;
            int h;

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Display.Mode mode = display.getMode();
                    w = mode.getPhysicalWidth();
                    h = mode.getPhysicalHeight();
                } else {
                    DisplayMetrics metrics = new DisplayMetrics();
                    display.getRealMetrics(metrics);
                    w = metrics.widthPixels;
                    h = metrics.heightPixels;
                }
            } catch (Throwable t) {
                w = 0;
                h = 0;
            }

            int ww = Math.max(w, h);
            int hh = Math.min(w, h);
            return new int[] { ww, hh };
        }

        private static int[] parseResolutionWxH(String value) {
            if (value == null) {
                return null;
            }

            String[] parts = value.split("x");
            if (parts.length != 2) {
                return null;
            }

            try {
                int w = Integer.parseInt(parts[0]);
                int h = Integer.parseInt(parts[1]);
                if (w <= 0 || h <= 0) {
                    return null;
                }
                return new int[] { w, h };
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String buildSuggestedEntryLabel(String value, int displayW, int displayH) {
            int[] wh = parseResolutionWxH(value);
            if (wh == null) {
                return value;
            }

            long area = (long) wh[0] * (long) wh[1];
            long base = (long) displayW * (long) displayH;

            int pct = 0;
            if (base > 0L) {
                pct = (int) Math.round((area * 100.0) / (double) base);
            }

            return wh[0] + "x" + wh[1] + " (" + pct + "%)";
        }
        private static long resolutionArea(String value) {
            int[] wh = parseResolutionWxH(value);
            if (wh == null) {
                return Long.MAX_VALUE;
            }
            return (long) wh[0] * (long) wh[1];
        }

        private static void sortResolutionValueListAscending(List<String> values) {
            if (values == null || values.size() <= 1) {
                return;
            }

            Collections.sort(values, (a, b) -> {
                long aa = resolutionArea(a);
                long bb = resolutionArea(b);
                if (aa != bb) {
                    return Long.compare(aa, bb);
                }

                int[] awh = parseResolutionWxH(a);
                int[] bwh = parseResolutionWxH(b);
                if (awh != null && bwh != null) {
                    if (awh[0] != bwh[0]) {
                        return Integer.compare(awh[0], bwh[0]);
                    }
                    if (awh[1] != bwh[1]) {
                        return Integer.compare(awh[1], bwh[1]);
                    }
                }

                return a.compareTo(b);
            });
        }

        private void sortResolutionListAscending() {
            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);
            if (pref == null) {
                return;
            }

            CharSequence[] entries = pref.getEntries();
            CharSequence[] values = pref.getEntryValues();
            if (entries == null || values == null || entries.length != values.length || values.length <= 1) {
                return;
            }

            ArrayList<Integer> idx = new ArrayList<>(values.length);
            for (int i = 0; i < values.length; i++) {
                idx.add(i);
            }

            Collections.sort(idx, (i1, i2) -> {
                String v1 = values[i1].toString();
                String v2 = values[i2].toString();

                long a1 = resolutionArea(v1);
                long a2 = resolutionArea(v2);
                if (a1 != a2) {
                    return Long.compare(a1, a2);
                }

                int[] r1 = parseResolutionWxH(v1);
                int[] r2 = parseResolutionWxH(v2);
                if (r1 != null && r2 != null) {
                    if (r1[0] != r2[0]) {
                        return Integer.compare(r1[0], r2[0]);
                    }
                    if (r1[1] != r2[1]) {
                        return Integer.compare(r1[1], r2[1]);
                    }
                }

                return entries[i1].toString().compareToIgnoreCase(entries[i2].toString());
            });

            CharSequence[] newEntries = new CharSequence[entries.length];
            CharSequence[] newValues  = new CharSequence[values.length];
            for (int o = 0; o < idx.size(); o++) {
                int i = idx.get(o);
                newEntries[o] = entries[i];
                newValues[o]  = values[i];
            }

            pref.setEntries(newEntries);
            pref.setEntryValues(newValues);
        }

        private void autoAdjustBitrateIfAtDefault(SharedPreferences prefs,
                                                  String oldRes, String oldFps,
                                                  String newRes, String newFps) {
            if (prefs == null) {
                return;
            }
            if (oldRes == null) oldRes = PreferenceConfiguration.DEFAULT_RESOLUTION;
            if (oldFps == null) oldFps = PreferenceConfiguration.DEFAULT_FPS;
            if (newRes == null) newRes = oldRes;
            if (newFps == null) newFps = oldFps;

            int oldDefault = PreferenceConfiguration.getDefaultBitrate(oldRes, oldFps);
            int cur = prefs.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, oldDefault);

            // Only change bitrate when it is still at the old auto default.
            if (cur == oldDefault) {
                int newDefault = PreferenceConfiguration.getDefaultBitrate(newRes, newFps);
                prefs.edit().putInt(PreferenceConfiguration.BITRATE_PREF_STRING, newDefault).apply();
            }
        }

        private static boolean isHdrEnabled(SharedPreferences sp) {
            if (sp == null) {
                return false;
            }
            return sp.getBoolean("checkbox_enable_hdr", false)
                    || (sp.contains("pref_video_hdr_enable") && sp.getBoolean("pref_video_hdr_enable", false))
                    || (sp.contains("pref_hdr_enable") && sp.getBoolean("pref_hdr_enable", false))
                    || (sp.contains("pref_hdr_pipeline_enable") && sp.getBoolean("pref_hdr_pipeline_enable", false));
        }

        private void maybePromptEnableUpscalingForResolution(String newRes, Runnable after) {
            if (after == null) {
                after = () -> {};
            }

            if (TextUtils.isEmpty(newRes)) {
                after.run();
                return;
            }

            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
            if (sp.getBoolean("pref_video_upscale_enable", false)) {
                after.run();
                return;
            }

            Display display = requireActivity().getWindowManager().getDefaultDisplay();
            int[] dwh = getDisplayLandscapeSize(display);
            int displayW = dwh[0];
            int displayH = dwh[1];

            int[] rwh = parseResolutionWxH(newRes);
            if (rwh == null || displayW <= 0 || displayH <= 0) {
                after.run();
                return;
            }

            long area = (long) rwh[0] * (long) rwh[1];
            long base = (long) displayW * (long) displayH;

            // Only prompt when streaming below the display resolution.
            if (area >= base) {
                after.run();
                return;
            }

            Runnable finalAfter = after;
            Runnable finalAfter1 = after;
            Runnable    finalAfter2 = after;
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.title_enable_upscaling_dialog)
                    .setMessage(R.string.text_enable_upscaling_dialog)
                    .setPositiveButton(R.string.button_enable_upscaling, (d, w) -> {
                        boolean gpuPath = sp.getBoolean("checkbox_gpu_path_mode", false);
                        boolean hdrOn = isHdrEnabled(sp);

                        if (gpuPath || hdrOn) {
                            new AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.title_upscaling_unavailable_dialog)
                                    .setMessage(R.string.text_upscaling_unavailable_dialog)
                                    .setPositiveButton(R.string.button_ok, null)
                                    .show();
                        } else {
                            sp.edit().putBoolean("pref_video_upscale_enable", true).apply();

                            Preference p = findPreference("pref_video_upscale_enable");
                            if (p instanceof CheckBoxPreference) {
                                ((CheckBoxPreference) p).setChecked(true);
                            }

                            updateLocks();
                        }

                        finalAfter.run();
                    })
                    .setNegativeButton(R.string.button_not_now, (d, w) -> finalAfter2.run())
                    .setOnCancelListener(d -> finalAfter1.run())
                    .show();
        }

        private void applySuggestedResolution(String resolution) {
            SharedPreferences prefs = getPrefs();

            String oldRes = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
            String oldFps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);

            // Apply the new resolution selection (both "custom" and primary resolution keys are updated).
            prefs.edit()
                    .putString(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING, resolution)
                    .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, resolution)
                    .apply();

            // Keep user-selected bitrate stable. Only auto-adjust when bitrate is still at the old default.
            autoAdjustBitrateIfAtDefault(prefs, oldRes, oldFps, resolution, oldFps);

            // Offer upscaling when streaming below the display resolution.
            maybePromptEnableUpscalingForResolution(resolution, this::reloadSettings);
        }

        private void removeValue(String preferenceKey, String value, Runnable onMatched) {
            int matchingCount = 0;

            ListPreference pref = (ListPreference) findPreference(preferenceKey);

            // Count the number of matching entries we'll be removing
            for (CharSequence seq : pref.getEntryValues()) {
                if (seq.toString().equalsIgnoreCase(value)) {
                    matchingCount++;
                }
            }

            // Create the new arrays
            CharSequence[] entries = new CharSequence[pref.getEntries().length-matchingCount];
            CharSequence[] entryValues = new CharSequence[pref.getEntryValues().length-matchingCount];
            int outIndex = 0;
            for (int i = 0; i < pref.getEntryValues().length; i++) {
                if (pref.getEntryValues()[i].toString().equalsIgnoreCase(value)) {
                    // Skip matching values
                    continue;
                }

                entries[outIndex] = pref.getEntries()[i];
                entryValues[outIndex] = pref.getEntryValues()[i];
                outIndex++;
            }

            if (pref.getValue().equalsIgnoreCase(value)) {
                onMatched.run();
            }

            // Update the preference with the new list
            pref.setEntries(entries);
            pref.setEntryValues(entryValues);
        }

        private void resetBitrateToDefault(SharedPreferences prefs, String res, String fps) {
            if (res == null) {
                res = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
            }
            if (fps == null) {
                fps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);
            }

            prefs.edit()
                    .putInt(PreferenceConfiguration.BITRATE_PREF_STRING,
                            PreferenceConfiguration.getDefaultBitrate(res, fps))
                    .apply();
        }

        @NonNull
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);
            UiHelper.applyStatusBarPadding(view);
            return view;
        }

        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState, boolean unused) {
            return super.onCreateView(inflater, container, savedInstanceState);
        }

        @Override
        public void onCreatePreferences(Bundle bundle, String s) {
            initializePreferences();
        }

        public void initializePreferences() {
            addPreferencesFromResource(R.xml.preferences);
            PreferenceScreen screen = getPreferenceScreen();

            AppCompatActivity activity = (AppCompatActivity) requireActivity();
            PackageManager pm = activity.getPackageManager();

            // hide on-screen controls category on non touch screen devices
            if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                PreferenceCategory category = findPreference("category_onscreen_controls");
                if (category != null) {
                    screen.removePreference(category);
                }
                category = findPreference("category_special_key_layout");
                if (category != null) {
                    screen.removePreference(category);
                }
            }

            // Hide remote desktop mouse mode on pre-Oreo (which doesn't have pointer capture)
            // and NVIDIA SHIELD devices (which support raw mouse input in pointer capture mode)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    getActivity().getPackageManager().hasSystemFeature("com.nvidia.feature.shield")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_input_settings");
                category.removePreference(findPreference("checkbox_absolute_mouse_mode"));
            }

            // Hide gamepad motion sensor option when running on OSes before Android 12.
            // Support for motion, LED, battery, and other extensions were introduced in S.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_gamepad_motion_sensors"));
            }

            // Hide gamepad motion sensor fallback option if the device has no gyro or accelerometer
            if (!pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
                    !activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_force_device_motion"));
                category.removePreference(findPreference("checkbox_gamepad_motion_fallback"));
            }

            // Hide USB driver options on devices without USB host support
            if (!pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_usb_bind_all"));
                category.removePreference(findPreference("checkbox_usb_driver"));
            }

            // Remove PiP mode on devices pre-Oreo, where the feature is not available (some low RAM devices),
            // and on Fire OS where it violates the Amazon App Store guidelines for some reason.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !pm.hasSystemFeature("android.software.picture_in_picture") ||
                    pm.hasSystemFeature("com.amazon.software.fireos")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_ui_settings");
                category.removePreference(findPreference("checkbox_enable_pip"));
            }

            // Fire TV apps are not allowed to use WebViews or browsers, so hide the Help category
            /*if (getActivity().getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_help");
                screen.removePreference(category);
            }*/
            PreferenceCategory category_gamepad_settings =
                    (PreferenceCategory) findPreference("category_gamepad_settings");
            // Remove the vibration options if the device can't vibrate
            if (!((Vibrator)getActivity().getSystemService(Context.VIBRATOR_SERVICE)).hasVibrator()) {
                category_gamepad_settings.removePreference(findPreference("checkbox_vibrate_fallback"));
                category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"));
                // The entire OSC category may have already been removed by the touchscreen check above
                PreferenceCategory category = findPreference("category_onscreen_controls");
                if (category != null) {
                    category.removePreference(findPreference("checkbox_vibrate_osc"));
                }
                category = findPreference("category_special_key_layout");
                if (category != null) {
                    category.removePreference(findPreference("checkbox_vibrate_keyboard"));
                }
                category = findPreference("category_gamepad_settings");
                if (category != null) {
                    category.removePreference(findPreference("checkbox_enable_device_rumble"));
                }
            }
            else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !((Vibrator)getActivity().getSystemService(Context.VIBRATOR_SERVICE)).hasAmplitudeControl()) {
                // Remove the vibration strength selector of the device doesn't have amplitude control
                category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"));
            }

            // Check custom resolution
            String customResStr = prevPrefConfig.customResolution;
            if(customResStr != null && !customResStr.isEmpty()){
                String[] resolutionSegments = customResStr.split("x");
                if(resolutionSegments.length == 2){
                    try {
                        addNativeResolutionEntries(Integer.parseInt(resolutionSegments[0]), Integer.parseInt(resolutionSegments[1]), false, true);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            
            // Check custom refresh rate
            String customRefreshRateStr = prevPrefConfig.customRefreshRate;
            if (customRefreshRateStr != null && !customRefreshRateStr.isEmpty()) {
                try {
                    float customRefreshRateValue = Float.parseFloat(customRefreshRateStr);
                    if (customRefreshRateValue > 0) {
                        addNativeFrameRateEntry(customRefreshRateValue, true);
                    }
                } catch (NumberFormatException e) {
                    getPrefs().edit().remove(PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING).apply();
                }
            }

            Display display = activity.getWindowManager().getDefaultDisplay();
            updateSuggestedResolutionSelector(display);
            float maxSupportedFps = display.getRefreshRate();

            // Hide non-supported resolution/FPS combinations
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int maxSupportedResW = 0;

                // Add a native resolution with any insets included for users that don't want content
                // behind the notch of their display
                boolean hasInsets = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Use the much nicer Display.getCutout() API on Android 10+
                        cutout = display.getCutout();
                    }
                    else {
                        // Android 9 only
                        cutout = displayCutoutP;
                    }

                    if (cutout != null) {
                        int widthInsets = cutout.getSafeInsetLeft() + cutout.getSafeInsetRight();
                        int heightInsets = cutout.getSafeInsetBottom() + cutout.getSafeInsetTop();

                        if (widthInsets != 0 || heightInsets != 0) {
                            DisplayMetrics metrics = new DisplayMetrics();
                            display.getRealMetrics(metrics);

                            int width = Math.max(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);
                            int height = Math.min(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);

                            addNativeResolutionEntries(width, height, false, false);
                            hasInsets = true;
                        }
                    }
                }

                // Always allow resolutions that are smaller or equal to the active
                // display resolution because decoders can report total non-sense to us.
                // For example, a p201 device reports:
                // AVC Decoder: OMX.amlogic.avc.decoder.awesome
                // HEVC Decoder: OMX.amlogic.hevc.decoder.awesome
                // AVC supported width range: 64 - 384
                // HEVC supported width range: 64 - 544
                for (Display.Mode candidate : display.getSupportedModes()) {
                    // Some devices report their dimensions in the portrait orientation
                    // where height > width. Normalize these to the conventional width > height
                    // arrangement before we process them.

                    int width = Math.max(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());
                    int height = Math.min(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());

                    // Some TVs report strange values here, so let's avoid native resolutions on a TV
                    // unless they report greater than 4K resolutions.
                    if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                            (width > 3840 || height > 2160)) {
                        addNativeResolutionEntries(width, height, hasInsets, false);
                    }

                    if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840;
                    }
                    else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                        maxSupportedResW = 2560;
                    }
                    else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920;
                    }

                    if (candidate.getRefreshRate() > maxSupportedFps) {
                        maxSupportedFps = candidate.getRefreshRate();
                    }
                }

                // This must be called to do runtime initialization before calling functions that evaluate
                // decoder lists.
                MediaCodecHelper.initialize(getContext(), GlPreferences.readPreferences(requireContext()).glRenderer);

                MediaCodecInfo avcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1);
                MediaCodecInfo hevcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1);

                if (avcDecoder != null) {
                    Range<Integer> avcWidthRange = avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();

                    LimeLog.info("AVC supported width range: "+avcWidthRange.getLower()+" - "+avcWidthRange.getUpper());

                    // If 720p is not reported as supported, ignore all results from this API
                    if (avcWidthRange.contains(1280)) {
                        if (avcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840;
                        }
                        else if (avcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920;
                        }
                        else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280;
                        }
                    }
                }

                if (hevcDecoder != null) {
                    Range<Integer> hevcWidthRange = hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();

                    LimeLog.info("HEVC supported width range: "+hevcWidthRange.getLower()+" - "+hevcWidthRange.getUpper());

                    // If 720p is not reported as supported, ignore all results from this API
                    if (hevcWidthRange.contains(1280)) {
                        if (hevcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840;
                        }
                        else if (hevcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920;
                        }
                        else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280;
                        }
                    }
                }

                LimeLog.info("Maximum resolution slot: "+maxSupportedResW);

                if (maxSupportedResW != 0) {
                    if (maxSupportedResW < 3840) {
                        // 4K is unsupported
                        removeEntryFromListAndSetValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_4K, PreferenceConfiguration.RES_1440P);
                    }
                    if (maxSupportedResW < 2560) {
                        // 1440p is unsupported
                        removeEntryFromListAndSetValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P, PreferenceConfiguration.RES_1080P);
                    }
                    if (maxSupportedResW < 1920) {
                        // 1080p is unsupported
                        removeEntryFromListAndSetValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P, PreferenceConfiguration.RES_720P);
                    }
                    // Never remove 720p
                }
            }
            else {
                // We can get the true metrics via the getRealMetrics() function (unlike the lies
                // that getWidth() and getHeight() tell to us).
                DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                int width = Math.max(metrics.widthPixels, metrics.heightPixels);
                int height = Math.min(metrics.widthPixels, metrics.heightPixels);
                addNativeResolutionEntries(width, height, false, false);
            }

            if (!PreferenceConfiguration.readPreferences(this.getActivity()).unlockFps) {
                // We give some extra room in case the FPS is rounded down
                if (maxSupportedFps < 118) {
                    removeEntryFromListAndSetValue(PreferenceConfiguration.FPS_PREF_STRING, "120", "90");
                }
                if (maxSupportedFps < 88) {
                    // 1080p is unsupported
                    removeEntryFromListAndSetValue(PreferenceConfiguration.FPS_PREF_STRING, "90", "60");
                }
                // Never remove 30 FPS or 60 FPS
            }
            addNativeFrameRateEntry(maxSupportedFps, false);

            // Android L introduces the drop duplicate behavior of releaseOutputBuffer()
            // that the unlock FPS option relies on to not massively increase latency.
            findPreference(PreferenceConfiguration.UNLOCK_FPS_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    // HACK: We need to let the preference change succeed before reinitializing to ensure
                    // it's reflected in the new layout.
                    reloadSettings();

                    // Allow the original preference change to take place
                    return true;
                }
            });

            // Remove HDR preference for devices below Nougat
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                LimeLog.info("Excluding HDR toggle based on OS");
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_video_settings");
                category.removePreference(findPreference("checkbox_enable_hdr"));
            }
            else {
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();
                Log.d("HDR CAP", display + "");
                // We must now ensure our display is compatible with HDR10
                boolean foundHdr10 = false;
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            foundHdr10 = true;
                            break;
                        }
                    }
                }

                if (!foundHdr10) {
                    LimeLog.info("Excluding HDR toggle based on display capabilities");
                    PreferenceCategory category =
                            (PreferenceCategory) findPreference("category_video_settings");
                    category.removePreference(findPreference("checkbox_enable_hdr"));
                }
                else if (PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
                    LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware");
                    PreferenceCategory category =
                            (PreferenceCategory) findPreference("category_video_settings");
                    CheckBoxPreference hdrPref = (CheckBoxPreference) category.findPreference("checkbox_enable_hdr");
                    hdrPref.setEnabled(false);
                    hdrPref.setChecked(false);
                    hdrPref.setSummary("Update the firmware on your NVIDIA SHIELD Android TV to enable HDR");
                }
            }
            sortResolutionListAscending();

            // Add a listener to the FPS and resolution preference
            // so the bitrate can be auto-adjusted
            findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    SharedPreferences prefs = getPrefs();
                    String valueStr = (String) newValue;

                    // If this is a non-standard injected (native/custom) resolution, show the warning dialog.
                    if (nativeResolutionValues.contains(valueStr)) {
                        Dialog.displayDialog(getActivity(),
                                getResources().getString(R.string.title_native_res_dialog),
                                getResources().getString(R.string.text_native_res_dialog),
                                false);
                    }

                    String oldRes = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
                    String oldFps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);

                    // Keep user-selected bitrate stable. Only auto-adjust when bitrate is still at the old default.
                    autoAdjustBitrateIfAtDefault(prefs, oldRes, oldFps, valueStr, oldFps);

                    // Offer upscaling when streaming below the display resolution.
                    maybePromptEnableUpscalingForResolution(valueStr, null);

                    return true;
                }
            });
            findPreference(PreferenceConfiguration.FPS_PREF_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    SharedPreferences prefs = getPrefs();
                    String valueStr = (String) newValue;

                    // If this is native frame rate, show the warning dialog
                    CharSequence[] values = ((ListPreference)preference).getEntryValues();
                    if (nativeFramerateShown && values[values.length - 1].toString().equals(newValue.toString())) {
                        Dialog.displayDialog(getActivity(),
                                getResources().getString(R.string.title_native_fps_dialog),
                                getResources().getString(R.string.text_native_res_dialog),
                                false);
                    }

                    String oldRes = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
                    String oldFps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);

                    // Keep user-selected bitrate stable. Only auto-adjust when bitrate is still at the old default.
                    autoAdjustBitrateIfAtDefault(prefs, oldRes, oldFps, oldRes, valueStr);

                    return true;
                }
            });

            findPreference("checkbox_enable_perf_logging").setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    Boolean loggingEnabled = (Boolean) newValue;

                    if(!loggingEnabled) {
                        new PerformanceDataTracker().clearLogs(preference.getContext());
                    }

                    // Allow the original preference change to take place
                    return true;
                }
            });

            Preference _pref;
            _pref = findPreference("import_keyboard_file");
            if (_pref != null) {
                _pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/json");
                        startActivityForResult(intent, READ_REQUEST_CODE);
                        return false;
                    }
                });
            }

            _pref = findPreference("import_special_button_file");
            if (_pref != null) {
                _pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/json");
                        startActivityForResult(intent, READ_REQUEST_SPECIAL_CODE);
                        return false;
                    }
                });
            }

            _pref = findPreference("share_performance_logs");
            if (_pref != null) {
                _pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        Context context = preference.getContext();
                        PerformanceDataTracker tracker = new PerformanceDataTracker();
                        String logs = tracker.getLog(context);

                        if (logs == null || logs.trim().isEmpty()) {
                            Toast.makeText(context, context.getString(R.string.toast_no_logs), Toast.LENGTH_SHORT).show();
                            return false;
                        }

                        String prefixMessage = context.getString(R.string.email_prefix_message);
                        String emailRecipient = context.getString(R.string.email_recipient);
                        String emailSubject = context.getString(R.string.email_subject);
                        String chooserTitle = context.getString(R.string.email_chooser_title);
                        String noEmailClientsMsg = context.getString(R.string.toast_no_email_clients);

                        try {
                            File cacheDir = context.getCacheDir();
                            File logFile = new File(cacheDir, "artemistics_logs.txt");
                            try (FileOutputStream fos = new FileOutputStream(logFile)) {
                                fos.write(logs.getBytes(StandardCharsets.UTF_8));
                            }

                            Uri logFileUri = FileProvider.getUriForFile(context,
                                    context.getPackageName() + ".fileprovider",
                                    logFile);

                            Intent emailIntent = new Intent(Intent.ACTION_SEND);
                            emailIntent.setType("text/plain");
                            emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{emailRecipient});
                            emailIntent.putExtra(Intent.EXTRA_SUBJECT, emailSubject);
                            emailIntent.putExtra(Intent.EXTRA_TEXT, prefixMessage);
                            emailIntent.putExtra(Intent.EXTRA_STREAM, logFileUri);

                            emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                            context.startActivity(Intent.createChooser(emailIntent, chooserTitle));
                        } catch (IOException e) {
                            Log.d("PerformanceDataTracker", "Error creating log file");
                        } catch (android.content.ActivityNotFoundException ex) {
                            Toast.makeText(context, noEmailClientsMsg, Toast.LENGTH_SHORT).show();
                        }
                        return false;
                    }
                });
            }

            _pref = findPreference("export_keyboard_file");
            if (_pref != null) {
                _pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        File file = new File(requireActivity().getExternalCacheDir(),"export_settings");
                        if(!file.exists()){
                            file.mkdir();
                        }
                        File file1= getJsonContent(requireActivity(),file);
                        if(file1==null){
                            Toast.makeText(requireActivity(),getString(R.string.pref_error_occurred),Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        Uri uri;
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        String authority= BuildConfig.APPLICATION_ID+".fileprovider";
                        uri = FileProvider.getUriForFile(requireActivity(),authority,file1);
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.setType("application/json");
                        startActivity(Intent.createChooser(intent,getString(R.string.pref_save_keyboard_profile)));
                        return false;
                    }
                });
            }

            _pref = findPreference("pref_debug_info");
            if (_pref != null) {
                _pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(@NonNull Preference preference) {
                        Intent intent=new Intent(requireActivity(), DebugInfoActivity.class);
                        requireActivity().startActivity(intent);
                        return false;
                    }
                });
            }

            EditTextPreference bitrateEditPref = findPreference(PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING);
            if (bitrateEditPref != null) {
                bitrateEditPref.setOnBindEditTextListener((EditText editText) -> {
                    editText.setInputType(InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
                });

                bitrateEditPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = (String) newValue;
                    if (TextUtils.isEmpty(value)) {
                        Toast.makeText(getActivity(), getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    float bitrateValue = Float.parseFloat(value) * 1000;
                    int bitrate = (int) bitrateValue;
                    SharedPreferences prefs = getPrefs();
                    prefs.edit().putInt(PreferenceConfiguration.BITRATE_PREF_STRING, bitrate).apply();
                    Toast.makeText(getActivity(), getString(R.string.pref_set_success), Toast.LENGTH_SHORT).show();
                    return true;
                });
            }

            EditTextPreference resolutionEditPref = findPreference(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING);
            if (resolutionEditPref != null) {
                resolutionEditPref.setOnBindEditTextListener((EditText editText) -> {
                    editText.setInputType(InputType.TYPE_CLASS_TEXT);
                    editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(11)});
                });

                resolutionEditPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = (String) newValue;
                    if (TextUtils.isEmpty(value)) {
                        Toast.makeText(getActivity(), getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    // Verify format: [width]x[height]
                    String[] resolutionSegments = value.split("x");
                    if (resolutionSegments.length != 2) {
                        Toast.makeText(getActivity(), getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    try {
                        int width = Integer.parseInt(resolutionSegments[0]);
                        int height = Integer.parseInt(resolutionSegments[1]);
                        
                        if (width <= 0 || height <= 0) {
                            Toast.makeText(getActivity(), getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show();
                            return false;
                        }

                        // Save the value and reload settings
                        editAndReload(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING, value);

                        return true;
                    } catch (NumberFormatException e) {
                        Toast.makeText(getActivity(), getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show();
                        return false;
                    }
                });
            }

            EditTextPreference customRefreshRatePref = findPreference(PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING);
            if (customRefreshRatePref != null) {
                customRefreshRatePref.setOnBindEditTextListener((EditText editText) -> {
                    editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
                });

                customRefreshRatePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = (String) newValue;
                    if (TextUtils.isEmpty(value)) {
                        Toast.makeText(getActivity(), getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    
                    try {
                        float refreshRate = Float.parseFloat(value);
                        if (refreshRate <= 0) {
                            Toast.makeText(getActivity(), getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        
                        // Format to max 3 decimal places
                        String formattedValue = String.format("%.3f", refreshRate);
                        // Remove trailing zeros
                        formattedValue = formattedValue.replaceAll("0+$", "").replaceAll("\\.$", "");

                        editAndReload(PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING, formattedValue);

                        return true;
                    } catch (NumberFormatException e) {
                        Toast.makeText(getActivity(), getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show();
                        return false;
                    }
                });
            }
            // Apply locks after building the screen
            updateLocks();


            // Mutual exclusion between Lite and Mini overlay modes
            CheckBoxPreference litePref = findPreference("checkbox_enable_perf_overlay_lite");
            CheckBoxPreference miniPref = findPreference("checkbox_enable_perf_overlay_mini");

            if (litePref != null && miniPref != null) {
                litePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        Boolean isEnabled = (Boolean) newValue;
                        if (isEnabled && miniPref.isChecked()) {
                            // Disable mini when enabling lite
                            miniPref.setChecked(false);
                        }
                        return true;
                    }
                });

                miniPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        Boolean isEnabled = (Boolean) newValue;
                        if (isEnabled && litePref.isChecked()) {
                            // Disable lite when enabling mini
                            litePref.setChecked(false);
                        }
                        return true;
                    }
                });
            }
        }

        private void removeEntryFromListAndSetValue(String resolutionPrefString, String entryToRemove, String nextDefault) {
            removeValue(resolutionPrefString, entryToRemove, new Runnable() {
                @Override
                public void run() {
                    SharedPreferences prefs = getPrefs();

                    String oldRes = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
                    String oldFps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);

                    setValue(resolutionPrefString, nextDefault);

                    String newRes = oldRes;
                    String newFps = oldFps;
                    if (PreferenceConfiguration.RESOLUTION_PREF_STRING.equals(resolutionPrefString)) {
                        newRes = nextDefault;
                    } else if (PreferenceConfiguration.FPS_PREF_STRING.equals(resolutionPrefString)) {
                        newFps = nextDefault;
                    }

                    // Keep user-selected bitrate stable. Only auto-adjust when bitrate is still at the old default.
                    autoAdjustBitrateIfAtDefault(prefs, oldRes, oldFps, newRes, newFps);
                }
            });
        }

        private void editAndReload(String prefKey, String newVal) {
            SharedPreferences prefs = getPrefs();
            prefs.edit().putString(prefKey, newVal).apply();

            reloadSettings();
        }

        protected void reloadSettings() {
            // HACK: We need to let the preference change succeed before reinitializing to ensure
            // it's reflected in the new layout.
            final Handler h = new Handler();
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Ensure the activity is still open when this timeout expires
                    StreamSettings settingsActivity = (StreamSettings) SettingsFragment.this.getActivity();
                    if (settingsActivity != null) {
                        settingsActivity.reloadSettings();
                    }
                }
            }, 500);
        }

        int READ_REQUEST_CODE = 1001;
        int READ_REQUEST_SPECIAL_CODE = 1002;

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK && data.getData() != null) {
                try {
                    Uri uri = data.getData();
                    String json = FileUriUtils.openUriForRead(getActivity(), uri);
                    if (TextUtils.isEmpty(json)) {
                        Toast.makeText(getActivity(), getString(R.string.pref_empty_file), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String name = getPrefs().getString(KeyBoardControllerConfigurationLoader.OSC_PREFERENCE, KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE);
                    SharedPreferences.Editor prefEditor = requireActivity().getSharedPreferences(name, Activity.MODE_PRIVATE).edit();
                    JSONObject object = new JSONObject(json);
                    Iterator it = object.keys();
                    prefEditor.clear();
                    while (it.hasNext()) {
                        String key = (String) it.next();// 获得key
                        String value = object.getString(key);// 获得value
                        prefEditor.putString(key, value);
                    }
                    prefEditor.apply();
                    Toast.makeText(getActivity(), getString(R.string.pref_import_success), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(getActivity(), getString(R.string.pref_error_occurred) + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
                return;
            }

            if (requestCode == READ_REQUEST_SPECIAL_CODE && resultCode == Activity.RESULT_OK && data.getData() != null) {
                try {
                    Uri uri = data.getData();
                    String json = FileUriUtils.openUriForRead(getActivity(), uri);
                    if (TextUtils.isEmpty(json)) {
                        Toast.makeText(getActivity(), getString(R.string.pref_empty_file), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    SharedPreferences.Editor prefEditor = getActivity().getSharedPreferences(GameMenu.PREF_NAME, Activity.MODE_PRIVATE).edit();
                    prefEditor.putString(GameMenu.KEY_NAME, json);
                    prefEditor.apply();
                    Toast.makeText(getActivity(), getString(R.string.pref_import_success), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(getActivity(), getString(R.string.pref_error_occurred) + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }

        @Override
        public void onDisplayPreferenceDialog(@NonNull Preference preference) {
            if (preference instanceof ConfirmDeleteOscPreference) {
                DialogFragment dialogFragment = ConfirmDeleteOscPreference.DialogFragmentCompat.newInstance(preference.getKey());
                dialogFragment.setTargetFragment(this, 0);
                dialogFragment.show(getFragmentManager(), null);
            } else if (preference instanceof ConfirmDeleteKeyboardPreference) {
                DialogFragment dialogFragment = ConfirmDeleteKeyboardPreference.DialogFragmentCompat.newInstance(preference.getKey());
                dialogFragment.setTargetFragment(this, 0);
                dialogFragment.show(getFragmentManager(), null);
            } else super.onDisplayPreferenceDialog(preference);
        }

        private File getJsonContent(Context context,File file){
            String name = getPrefs().getString(KeyBoardControllerConfigurationLoader.OSC_PREFERENCE, KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE);
            SharedPreferences pref = context.getSharedPreferences(name, Activity.MODE_PRIVATE);
            Map<String,?> map = pref.getAll();
            File file1= new File(file,name+".json");
            String jsonStr=new Gson().toJson(map);
            if(!FileUriUtils.writerFileString(file1,jsonStr)){
                return null;
            }
            return file1;
        }

        //获取所有设置项配置文件
        private File getAllJsonData(File file){
            SharedPreferences pref = getPrefs();
            Map<String,?> map = pref.getAll();
            //获取适配电脑的数据库信息
//            List<ComputerDetails> map= new ComputerDatabaseManager(context).getAllComputers();
            File file1= new File(file,"allJSON.json");
            String jsonStr=new Gson().toJson(map);
            if(!FileUriUtils.writerFileString(file1,jsonStr)){
                return null;
            }
            return file1;
        }
    }
}


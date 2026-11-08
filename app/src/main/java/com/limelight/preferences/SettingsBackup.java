package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.util.Base64;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Exports / imports everything the user configured to a single JSON file:
 * <ul>
 *     <li>every SharedPreferences file: all settings, input overlay layouts (all slots),
 *     special keys, hidden apps...</li>
 *     <li>settings profiles</li>
 * </ul>
 * Paired computers and the pairing identity (certificate and private key) are never
 * exported, and an import keeps the current ones. Device specific caches are left out too. The file uses logical names, so it can be
 * imported in another build of the app (different application id / signature).
 *
 * <p>Importing only applies the settings of the settings screen (read from
 * res/xml/preferences.xml, with the value type of each one), the settings of the input overlay
 * editor ({@link com.limelight.binding.input.overlay.OverlayEditorSettings}) and the known data groups:
 * {@link #analyze} lists everything else (settings of a newer version, internal state,
 * incompatible values, unknown files and sections) and {@link #restore} skips it. If the
 * import fails midway, the previous configuration is put back.</p>
 */
public final class SettingsBackup {
    public static final String FORMAT = "artemis-settings";
    public static final int VERSION = 3;

    /** Logical name of the default SharedPreferences (its file name depends on the package). */
    private static final String DEFAULT_PREFS = "default";

    /** Not user settings (caches, crash detection, library markers): silently ignored in backups. */
    private static final Set<String> EXCLUDED_PREFS = new HashSet<>(Arrays.asList(
            "GlPreferences", "DecoderTombstone",
            // Internal files of Android / androidx that older builds exported
            "_has_set_default_values", "android.app.ActivityThread.IDS"));

    /**
     * Data stored outside of the settings screen that backups carry, besides the settings
     * (each one is a SharedPreferences file).
     */
    private static final Set<String> KNOWN_PREF_FILES = new HashSet<>(Arrays.asList(
            // Gamepad / keyboard overlay layouts
            "OSC", "OSC_2", "OSC_3", "OSC_4", "OSC_5",
            "OSC_Keyboard", "OSC_Keyboard_2", "OSC_Keyboard_3", "OSC_Keyboard_4", "OSC_Keyboard_5",
            // Special keys, hidden apps
            "specialPrefs", "HiddenApps"));

    /** Top level sections of a backup file this version understands. */
    private static final Set<String> KNOWN_SECTIONS = new HashSet<>(Arrays.asList(
            "format", "version", "appId", "appVersion", "exportedAt", "prefs", "data", "files"));

    /** Files carried by backups, relative to the app's data directory. */
    private static final List<String> DATA_FILES = Collections.singletonList(
            "files/profiles/profiles.json");

    /** Version 1 stored the profiles file under this name, as text. */
    private static final String V1_PROFILES_FILE = "profiles/profiles.json";

    private SettingsBackup() {
    }

    public static String suggestedFileName() {
        return "artemis-settings-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json";
    }

    private static File dataDir(Context context) {
        return context.getFilesDir().getParentFile();
    }

    private static String defaultPrefsFile(Context context) {
        return context.getPackageName() + "_preferences";
    }

    private static SharedPreferences openPrefs(Context context, String logicalName) {
        return context.getSharedPreferences(
                DEFAULT_PREFS.equals(logicalName) ? defaultPrefsFile(context) : logicalName, Context.MODE_PRIVATE);
    }

    /**
     * The SharedPreferences files a backup carries: the settings and the known data groups.
     * Other files of the app (caches, markers of libraries...) are never exported nor touched
     * by an import.
     */
    private static Set<String> supportedGroups() {
        Set<String> names = new HashSet<>(KNOWN_PREF_FILES);
        names.add(DEFAULT_PREFS);
        return names;
    }

    // ------------------------------------------------------------------------------------
    // Known settings
    // ------------------------------------------------------------------------------------

    /** Type of a setting value in a backup file. */
    static final String T_BOOLEAN = "b", T_INT = "i", T_LONG = "l", T_FLOAT = "f", T_STRING = "s", T_SET = "ss";

    private static String typeOf(Object value) {
        if (value instanceof Boolean) return T_BOOLEAN;
        if (value instanceof Integer) return T_INT;
        if (value instanceof Long) return T_LONG;
        if (value instanceof Float) return T_FLOAT;
        if (value instanceof String) return T_STRING;
        if (value instanceof Set) return T_SET;
        return null;
    }

    /**
     * Settings declared in the settings screen, with the value type their widget stores
     * (null when the widget doesn't store a value or its type is unknown).
     */
    static Map<String, String> declaredSettings(Context context) {
        Map<String, String> settings = new HashMap<>();
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.preferences)) {
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                String key = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "key");
                if (key != null) {
                    settings.put(key, widgetType(parser.getName()));
                }
            }
        } catch (Exception e) {
            LimeLog.warning("Unable to read the declared settings: " + e);
        }
        return settings;
    }

    private static String widgetType(String tag) {
        String name = tag.substring(tag.lastIndexOf('.') + 1);
        switch (name) {
            case "CheckBoxPreference":
            case "SwitchPreference":
            case "SwitchPreferenceCompat":
            case "SmallIconCheckboxPreference":
                return T_BOOLEAN;
            case "SeekBarPreference":
                return T_INT;
            case "ListPreference":
            case "EditTextPreference":
            case "LanguagePreference":
                return T_STRING;
            case "MultiSelectListPreference":
                return T_SET;
            default:
                return null;
        }
    }

    /**
     * The settings an import can apply: the ones of the settings screen. Anything else found
     * in a backup (settings of a newer version, internal state...) is reported and skipped.
     */
    static Map<String, String> supportedSettings(Context context) {
        Map<String, String> settings = declaredSettings(context);
        // The input overlay editor is the other settings screen
        settings.putAll(com.limelight.binding.input.overlay.OverlayEditorSettings.declared());
        return settings;
    }

    // ------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------

    public static JSONObject export(Context context) throws JSONException, IOException {
        JSONObject prefs = new JSONObject();
        for (String name : supportedGroups()) {
            SharedPreferences sp = openPrefs(context, name);
            if (!sp.getAll().isEmpty()) {
                prefs.put(name, exportPrefs(sp));
            }
        }

        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("appId", BuildConfig.APPLICATION_ID);
        root.put("appVersion", BuildConfig.VERSION_NAME);
        root.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
        root.put("prefs", prefs);
        root.put("data", exportFiles(context, DATA_FILES));
        return root;
    }

    private static JSONObject exportFiles(Context context, List<String> paths) throws JSONException, IOException {
        JSONObject files = new JSONObject();
        for (String path : paths) {
            File file = new File(dataDir(context), path);
            if (file.isFile()) {
                try (InputStream in = new FileInputStream(file)) {
                    files.put(path, Base64.encodeToString(readAll(in), Base64.NO_WRAP));
                }
            }
        }
        return files;
    }

    private static JSONObject exportPrefs(SharedPreferences prefs) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            String type = typeOf(value);
            if (type == null) {
                continue;
            }
            JSONObject typed = new JSONObject();
            typed.put("t", type);
            if (value instanceof Float) {
                typed.put("v", ((Float) value).doubleValue());
            } else if (value instanceof Set) {
                JSONArray array = new JSONArray();
                for (Object item : (Set<?>) value) {
                    array.put(String.valueOf(item));
                }
                typed.put("v", array);
            } else {
                typed.put("v", value);
            }
            out.put(entry.getKey(), typed);
        }
        return out;
    }

    public static void write(JSONObject backup, OutputStream out) throws IOException, JSONException {
        out.write(backup.toString(2).getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ------------------------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------------------------

    /**
     * Parses a backup file. Files made by newer versions are accepted: {@link #analyze} tells
     * what can't be imported.
     */
    public static JSONObject read(InputStream in) throws IOException, JSONException {
        JSONObject root;
        try {
            root = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new JSONException("Not a settings backup file");
        }
        if (!FORMAT.equals(root.optString("format")) || root.optJSONObject("prefs") == null
                || root.optInt("version", 0) < 1) {
            throw new JSONException("Not a settings backup file");
        }
        return root;
    }

    /** What an import will apply, and what it has to skip. */
    public static final class ImportPlan {
        /** Version of the backup format. */
        public final int formatVersion;
        /** Version name of the app that made the file, if known. */
        public final String appVersion;
        /** Human readable description of every skipped item, sorted. */
        public final List<String> unsupported = new ArrayList<>();

        final Map<String, Set<String>> acceptedKeys = new HashMap<>();
        final Set<String> acceptedData = new HashSet<>();

        ImportPlan(int formatVersion, String appVersion) {
            this.formatVersion = formatVersion;
            this.appVersion = appVersion;
        }

        /** The file was made by a newer version of the app (its format is newer). */
        public boolean isFromNewerVersion() {
            return formatVersion > VERSION;
        }

        public boolean hasUnsupported() {
            return !unsupported.isEmpty();
        }
    }

    private static boolean isValidValue(JSONObject typed) {
        if (typed == null || !typed.has("v")) {
            return false;
        }
        Object v = typed.opt("v");
        switch (typed.optString("t", "")) {
            case T_BOOLEAN:
                return v instanceof Boolean;
            case T_INT:
                return v instanceof Number && ((Number) v).doubleValue() == ((Number) v).intValue();
            case T_LONG:
                return v instanceof Number && ((Number) v).doubleValue() == ((Number) v).longValue();
            case T_FLOAT:
                return v instanceof Number;
            case T_STRING:
                return v instanceof String;
            case T_SET: {
                if (!(v instanceof JSONArray)) {
                    return false;
                }
                JSONArray array = (JSONArray) v;
                for (int i = 0; i < array.length(); i++) {
                    if (!(array.opt(i) instanceof String)) {
                        return false;
                    }
                }
                return true;
            }
            default:
                return false;
        }
    }

    private static boolean isBase64(String value) {
        try {
            Base64.decode(value, Base64.NO_WRAP);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Compares the backup with what this version of the app supports.
     */
    public static ImportPlan analyze(Context context, JSONObject backup) {
        ImportPlan plan = new ImportPlan(backup.optInt("version", 1), backup.optString("appVersion", null));
        Set<String> unsupported = new TreeSet<>();

        Iterator<String> sections = backup.keys();
        while (sections.hasNext()) {
            String section = sections.next();
            if ("pairing".equals(section)) {
                // Paired computers (made by a build that exported them): never imported
                unsupported.add(context.getString(R.string.backup_unsupported_pairing));
            } else if (!KNOWN_SECTIONS.contains(section)) {
                unsupported.add(context.getString(R.string.backup_unsupported_section, section));
            }
        }

        // Settings: only the ones of the settings screen, and the known data groups
        Map<String, String> supported = supportedSettings(context);

        JSONObject prefs = backup.optJSONObject("prefs");
        Iterator<String> names = prefs.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (EXCLUDED_PREFS.contains(name)) {
                continue;
            }
            JSONObject values = prefs.optJSONObject(name);
            if (values == null || (!DEFAULT_PREFS.equals(name) && !KNOWN_PREF_FILES.contains(name))) {
                unsupported.add(context.getString(R.string.backup_unsupported_group, name));
                continue;
            }
            Map<String, ?> local = openPrefs(context, name).getAll();
            Set<String> accepted = new HashSet<>();
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String label = DEFAULT_PREFS.equals(name) ? key : name + " / " + key;
                JSONObject typed = values.optJSONObject(key);
                if (DEFAULT_PREFS.equals(name) && !supported.containsKey(key)) {
                    unsupported.add(context.getString(R.string.backup_unsupported_setting, label));
                    continue;
                }
                if (!isValidValue(typed)) {
                    unsupported.add(context.getString(R.string.backup_unsupported_value, label));
                    continue;
                }
                // The value must have the type this version stores for that setting
                String expected = DEFAULT_PREFS.equals(name) ? supported.get(key)
                        : local.containsKey(key) ? typeOf(local.get(key)) : null;
                if (expected != null && !expected.equals(typed.optString("t"))) {
                    unsupported.add(context.getString(R.string.backup_unsupported_value, label));
                    continue;
                }
                accepted.add(key);
            }
            plan.acceptedKeys.put(name, accepted);
        }

        // Files
        JSONObject data = backup.optJSONObject("data");
        if (data != null) {
            Iterator<String> paths = data.keys();
            while (paths.hasNext()) {
                String path = paths.next();
                if (!DATA_FILES.contains(path)) {
                    unsupported.add(context.getString(R.string.backup_unsupported_data, path));
                } else if (!isBase64(data.optString(path, null))) {
                    unsupported.add(context.getString(R.string.backup_unsupported_data, path));
                } else {
                    plan.acceptedData.add(path);
                }
            }
        }

        plan.unsupported.addAll(unsupported);
        return plan;
    }

    /**
     * Replaces the current configuration with what the plan accepts from the backup. The app
     * must be restarted right afterwards (open databases and in-memory state are stale).
     * Paired computers are left untouched. If anything fails, the previous configuration is
     * restored and an exception is thrown.
     */
    public static void restore(Context context, JSONObject backup, ImportPlan plan) throws IOException {
        // Keep the current configuration to roll back if something goes wrong
        JSONObject previous;
        try {
            previous = export(context);
        } catch (Exception e) {
            throw new IOException("Unable to save the current settings: " + e.getMessage(), e);
        }

        try {
            apply(context, backup, plan);
        } catch (Exception e) {
            LimeLog.warning("Settings import failed, rolling back: " + e);
            try {
                apply(context, previous, analyze(context, previous));
            } catch (Exception rollback) {
                LimeLog.severe("Settings rollback failed: " + rollback);
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    private static void apply(Context context, JSONObject backup, ImportPlan plan)
            throws JSONException, IOException {
        JSONObject prefs = backup.getJSONObject("prefs");

        // Every supported settings file is replaced, even the ones that aren't in the backup
        for (String name : supportedGroups()) {
            SharedPreferences.Editor editor = openPrefs(context, name).edit().clear();
            JSONObject values = prefs.optJSONObject(name);
            Set<String> accepted = plan.acceptedKeys.get(name);
            if (values != null && accepted != null) {
                for (String key : accepted) {
                    putTyped(editor, key, values.getJSONObject(key));
                }
            }
            if (!editor.commit()) {
                throw new IOException("Unable to write the settings (" + name + ")");
            }
        }

        JSONObject data = backup.optJSONObject("data");
        if (data != null) {
            for (String path : DATA_FILES) {
                writeOrDelete(context, path, plan.acceptedData.contains(path) ? data.getString(path) : null);
            }
        } else {
            // Version 1: only the profiles file, as text
            JSONObject files = backup.optJSONObject("files");
            if (files != null && files.has(V1_PROFILES_FILE)) {
                writeFile(new File(context.getFilesDir(), V1_PROFILES_FILE),
                        files.getString(V1_PROFILES_FILE).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void writeOrDelete(Context context, String path, String base64) throws IOException {
        File file = new File(dataDir(context), path);
        if (base64 != null) {
            writeFile(file, Base64.decode(base64, Base64.NO_WRAP));
        } else if (file.exists() && !file.delete()) {
            throw new IOException("Unable to delete " + path);
        }
    }

    private static void writeFile(File file, byte[] content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create " + parent);
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
    }

    private static void putTyped(SharedPreferences.Editor editor, String key, JSONObject typed) throws JSONException {
        switch (typed.getString("t")) {
            case T_BOOLEAN:
                editor.putBoolean(key, typed.getBoolean("v"));
                break;
            case T_INT:
                editor.putInt(key, typed.getInt("v"));
                break;
            case T_LONG:
                editor.putLong(key, typed.getLong("v"));
                break;
            case T_FLOAT:
                editor.putFloat(key, (float) typed.getDouble("v"));
                break;
            case T_SET: {
                JSONArray array = typed.getJSONArray("v");
                Set<String> set = new HashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    set.add(array.getString(i));
                }
                editor.putStringSet(key, set);
                break;
            }
            default:
                editor.putString(key, typed.getString("v"));
                break;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }
}

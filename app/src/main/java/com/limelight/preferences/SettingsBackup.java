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
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Exports / imports everything the user configured to a single JSON file:
 * <ul>
 *     <li>every SharedPreferences file: all settings, input overlay layouts (all slots),
 *     special keys, hidden apps...</li>
 *     <li>settings profiles</li>
 *     <li>optionally, paired computers and this app's pairing identity (certificate and
 *     private key), so that an imported installation is already paired with the same PCs.
 *     This part can be encrypted with a password (PBKDF2 + AES-GCM).</li>
 * </ul>
 * Only device specific caches are left out. The file uses logical names, so it can be
 * imported in another build of the app (different application id / signature).
 *
 * <p>Importing only applies what this version of the app knows: {@link #analyze} lists the
 * settings, files and sections that are unknown (e.g. made by a newer version) or that have
 * an incompatible value, and {@link #restore} skips them. If the import fails midway, the
 * previous configuration is put back.</p>
 */
public final class SettingsBackup {
    public static final String FORMAT = "artemis-settings";
    public static final int VERSION = 3;

    /** Logical name of the default SharedPreferences (its file name depends on the package). */
    private static final String DEFAULT_PREFS = "default";

    /** Device specific caches / crash detection state, not user settings. */
    private static final Set<String> EXCLUDED_PREFS = new HashSet<>(Arrays.asList(
            "GlPreferences", "DecoderTombstone"));

    /** SharedPreferences files this version knows, besides the default one. */
    private static final Set<String> KNOWN_PREF_FILES = new HashSet<>(Arrays.asList(
            // Gamepad / keyboard overlay layouts
            "OSC", "OSC_2", "OSC_3", "OSC_4", "OSC_5",
            "OSC_Keyboard", "OSC_Keyboard_2", "OSC_Keyboard_3", "OSC_Keyboard_4", "OSC_Keyboard_5",
            // Special keys, hidden apps
            "specialPrefs", "HiddenApps"));

    /**
     * Settings stored in the default SharedPreferences that are not declared in
     * res/xml/preferences.xml (internal state, values migrated from older versions...).
     * Keep in sync with the code: SettingsBackupTest checks that every key read or written
     * by the app is known.
     */
    private static final Set<String> CODE_ONLY_KEYS = new HashSet<>(Arrays.asList(
            "ArtemisStreaming", "CrashCount", "LastNotifiedCrashCount",
            "OSC", "special_key",
            "checkbox_51_surround", "checkbox_disable_frame_drop", "checkbox_enforce_refresh_rate",
            "checkbox_stretch_video", "checkbox_touchscreen_trackpad",
            "list_resolution_fps", "seekbar_bitrate",
            "number_pan_offset_x", "number_pan_offset_y", "number_zoom_scale",
            "performance_log", "pref_cpu_warmup_enable",
            "pref_hdr_enable", "pref_hdr_pipeline_enable", "pref_video_hdr_enable",
            "pref_low_latency_frame_balance", "runtimeOutputDequeueTimeoutCustomEnabled",
            // Not settings (bundle / codec / fragment keys), listed so that the code scan passes
            "key", "low-latency", "android.content.extra.IS_SENSITIVE", "Fingerprint", "Renderer"));

    /** Top level sections of a backup file this version understands. */
    private static final Set<String> KNOWN_SECTIONS = new HashSet<>(Arrays.asList(
            "format", "version", "appId", "appVersion", "exportedAt", "prefs", "data", "files", "pairing"));

    /** Non sensitive files, relative to the app's data directory. */
    private static final List<String> DATA_FILES = Collections.singletonList(
            "files/profiles/profiles.json");

    /** Paired computers: pairing identity and computer database. */
    private static final List<String> PAIRING_FILES = Arrays.asList(
            "files/client.crt",
            "files/client.key",
            "databases/computers4.db",
            "databases/computers4.db-journal",
            "databases/computers4.db-wal",
            "databases/computers4.db-shm");

    /** Version 1 stored the profiles file under this name, as text. */
    private static final String V1_PROFILES_FILE = "profiles/profiles.json";

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int KDF_ITERATIONS = 150_000;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    private SettingsBackup() {
    }

    /** Thrown when the password of the paired computers section is wrong. */
    public static class WrongPasswordException extends Exception {
        WrongPasswordException(Throwable cause) {
            super("Wrong password", cause);
        }
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

    private static String logicalPrefsName(Context context, String fileName) {
        return fileName.equals(defaultPrefsFile(context)) ? DEFAULT_PREFS : fileName;
    }

    private static SharedPreferences openPrefs(Context context, String logicalName) {
        return context.getSharedPreferences(
                DEFAULT_PREFS.equals(logicalName) ? defaultPrefsFile(context) : logicalName, Context.MODE_PRIVATE);
    }

    /** Logical names of the SharedPreferences files present on this installation. */
    private static Set<String> localPrefs(Context context) {
        Set<String> names = new HashSet<>();
        names.add(DEFAULT_PREFS);
        File[] files = new File(dataDir(context), "shared_prefs").listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".xml")) {
                    names.add(logicalPrefsName(context, name.substring(0, name.length() - 4)));
                }
            }
        }
        names.removeAll(EXCLUDED_PREFS);
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

    /** Every setting key of the default SharedPreferences this version knows. */
    static Set<String> knownSettings(Context context) {
        Set<String> keys = new HashSet<>(declaredSettings(context).keySet());
        keys.addAll(CODE_ONLY_KEYS);
        return keys;
    }

    // ------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------

    /**
     * @param includePairing include paired computers and the pairing identity
     * @param password       encrypts the paired computers section; null or empty for none
     */
    public static JSONObject export(Context context, boolean includePairing, String password)
            throws JSONException, IOException, GeneralSecurityException {
        JSONObject prefs = new JSONObject();
        for (String name : localPrefs(context)) {
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

        if (includePairing) {
            JSONObject files = exportFiles(context, PAIRING_FILES);
            JSONObject pairing = new JSONObject();
            if (password == null || password.isEmpty()) {
                pairing.put("encrypted", false);
                pairing.put("files", files);
            } else {
                encrypt(pairing, files.toString().getBytes(StandardCharsets.UTF_8), password);
            }
            root.put("pairing", pairing);
        }
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
    // Encryption
    // ------------------------------------------------------------------------------------

    private static SecretKeySpec deriveKey(String password, byte[] salt, String kdf, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance(kdf).generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static String availableKdf() {
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return "PBKDF2WithHmacSHA256";
        } catch (NoSuchAlgorithmException e) {
            // Android < 8
            return "PBKDF2WithHmacSHA1";
        }
    }

    private static void encrypt(JSONObject out, byte[] plaintext, String password)
            throws GeneralSecurityException, JSONException {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(iv);
        String kdf = availableKdf();

        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, kdf, KDF_ITERATIONS),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        out.put("encrypted", true);
        out.put("cipher", CIPHER);
        out.put("kdf", kdf);
        out.put("iterations", KDF_ITERATIONS);
        out.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        out.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        out.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
    }

    /** Whether this device can decrypt a protected paired computers section. */
    private static boolean canDecrypt(JSONObject pairing) {
        if (!CIPHER.equals(pairing.optString("cipher", CIPHER))
                || pairing.optInt("iterations", 0) <= 0
                || !pairing.has("salt") || !pairing.has("iv") || !pairing.has("ciphertext")) {
            return false;
        }
        try {
            SecretKeyFactory.getInstance(pairing.getString("kdf"));
            Cipher.getInstance(CIPHER);
            return true;
        } catch (Exception e) {
            return false;
        }
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
        public boolean pairingSupported = true;

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
            if (!KNOWN_SECTIONS.contains(section)) {
                unsupported.add(context.getString(R.string.backup_unsupported_section, section));
            }
        }

        // Settings
        Set<String> localNames = localPrefs(context);
        Map<String, String> declared = declaredSettings(context);
        Set<String> known = new HashSet<>(declared.keySet());
        known.addAll(CODE_ONLY_KEYS);

        JSONObject prefs = backup.optJSONObject("prefs");
        Iterator<String> names = prefs.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (EXCLUDED_PREFS.contains(name)) {
                continue;
            }
            JSONObject values = prefs.optJSONObject(name);
            if (values == null || (!DEFAULT_PREFS.equals(name) && !KNOWN_PREF_FILES.contains(name)
                    && !localNames.contains(name))) {
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
                if (DEFAULT_PREFS.equals(name) && !known.contains(key) && !local.containsKey(key)) {
                    unsupported.add(context.getString(R.string.backup_unsupported_setting, label));
                    continue;
                }
                if (!isValidValue(typed)) {
                    unsupported.add(context.getString(R.string.backup_unsupported_value, label));
                    continue;
                }
                // The value must have the type this version stores for that setting
                String expected = local.containsKey(key) ? typeOf(local.get(key))
                        : DEFAULT_PREFS.equals(name) ? declared.get(key) : null;
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
                if (PAIRING_FILES.contains(path)) {
                    // Version 2 stored the paired computers with the other files
                    continue;
                }
                if (!DATA_FILES.contains(path)) {
                    unsupported.add(context.getString(R.string.backup_unsupported_data, path));
                } else if (!isBase64(data.optString(path, null))) {
                    unsupported.add(context.getString(R.string.backup_unsupported_data, path));
                } else {
                    plan.acceptedData.add(path);
                }
            }
        }

        // Paired computers
        JSONObject pairing = backup.optJSONObject("pairing");
        if (pairing != null) {
            if (pairing.optBoolean("encrypted", false)) {
                if (!canDecrypt(pairing)) {
                    plan.pairingSupported = false;
                    unsupported.add(context.getString(R.string.backup_unsupported_pairing));
                }
            } else {
                JSONObject files = pairing.optJSONObject("files");
                if (files == null) {
                    plan.pairingSupported = false;
                    unsupported.add(context.getString(R.string.backup_unsupported_pairing));
                } else if (!files.has("files/client.key") || !files.has("files/client.crt")) {
                    plan.pairingSupported = false;
                    unsupported.add(context.getString(R.string.backup_unsupported_pairing));
                } else {
                    Iterator<String> paths = files.keys();
                    while (paths.hasNext()) {
                        String path = paths.next();
                        if (!PAIRING_FILES.contains(path)) {
                            unsupported.add(context.getString(R.string.backup_unsupported_data, path));
                        }
                    }
                }
            }
        } else if (backup.has("pairing")) {
            unsupported.add(context.getString(R.string.backup_unsupported_pairing));
        }

        plan.unsupported.addAll(unsupported);
        return plan;
    }

    /** Whether the backup contains paired computers. */
    public static boolean hasPairing(JSONObject backup) {
        if (backup.optJSONObject("pairing") != null) {
            return true;
        }
        // Version 2 stored them unencrypted with the other files
        JSONObject data = backup.optJSONObject("data");
        return data != null && data.has("files/client.key");
    }

    public static boolean isPairingEncrypted(JSONObject backup) {
        JSONObject pairing = backup.optJSONObject("pairing");
        return pairing != null && pairing.optBoolean("encrypted", false);
    }

    /**
     * Returns the paired computers files of the backup, decrypting them if needed. Only the
     * files this version knows are returned.
     *
     * @param password ignored when the section isn't encrypted
     */
    public static JSONObject openPairing(JSONObject backup, String password)
            throws JSONException, WrongPasswordException, GeneralSecurityException {
        JSONObject files;
        JSONObject pairing = backup.optJSONObject("pairing");
        if (pairing == null) {
            // Version 2
            files = backup.optJSONObject("data");
        } else if (!pairing.optBoolean("encrypted", false)) {
            files = pairing.getJSONObject("files");
        } else {
            byte[] salt = Base64.decode(pairing.getString("salt"), Base64.NO_WRAP);
            byte[] iv = Base64.decode(pairing.getString("iv"), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(pairing.getString("ciphertext"), Base64.NO_WRAP);
            SecretKeySpec key = deriveKey(password == null ? "" : password, salt,
                    pairing.getString("kdf"), pairing.getInt("iterations"));
            Cipher cipher = Cipher.getInstance(pairing.optString("cipher", CIPHER));
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext;
            try {
                plaintext = cipher.doFinal(ciphertext);
            } catch (javax.crypto.AEADBadTagException e) {
                throw new WrongPasswordException(e);
            }
            files = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
        }

        JSONObject known = new JSONObject();
        if (files != null) {
            for (String path : PAIRING_FILES) {
                String content = files.optString(path, null);
                if (content != null && isBase64(content)) {
                    known.put(path, content);
                }
            }
        }
        if (!known.has("files/client.key") || !known.has("files/client.crt")) {
            throw new JSONException("Incomplete paired computers data");
        }
        return known;
    }

    /**
     * Replaces the current configuration with what the plan accepts from the backup. The app
     * must be restarted right afterwards (open databases and in-memory state are stale).
     * If anything fails, the previous configuration is restored and an exception is thrown.
     *
     * @param pairingFiles paired computers to restore (from {@link #openPairing}), or null
     *                     to keep the current paired computers
     */
    public static void restore(Context context, JSONObject backup, JSONObject pairingFiles, ImportPlan plan)
            throws IOException {
        // Keep the current configuration to roll back if something goes wrong
        JSONObject previous;
        JSONObject previousPairing;
        try {
            previous = export(context, true, null);
            previousPairing = previous.getJSONObject("pairing").getJSONObject("files");
        } catch (Exception e) {
            throw new IOException("Unable to save the current settings: " + e.getMessage(), e);
        }

        try {
            apply(context, backup, pairingFiles, plan);
        } catch (Exception e) {
            LimeLog.warning("Settings import failed, rolling back: " + e);
            try {
                apply(context, previous, previousPairing.length() > 0 ? previousPairing : null,
                        analyze(context, previous));
            } catch (Exception rollback) {
                LimeLog.severe("Settings rollback failed: " + rollback);
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    private static void apply(Context context, JSONObject backup, JSONObject pairingFiles, ImportPlan plan)
            throws JSONException, IOException {
        JSONObject prefs = backup.getJSONObject("prefs");

        // Every known settings file is replaced, even the ones that aren't in the backup
        Set<String> names = localPrefs(context);
        names.addAll(plan.acceptedKeys.keySet());
        for (String name : names) {
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

        if (pairingFiles != null) {
            for (String path : PAIRING_FILES) {
                writeOrDelete(context, path, pairingFiles.optString(path, null));
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

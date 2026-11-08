package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.limelight.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Exports / imports everything the user configured to a single JSON file:
 * <ul>
 *     <li>every SharedPreferences file: all settings, input overlay layouts (all slots),
 *     special keys, hidden apps, editor preferences...</li>
 *     <li>settings profiles</li>
 *     <li>optionally, paired computers and this app's pairing identity (certificate and
 *     private key), so that an imported installation is already paired with the same PCs.
 *     This part can be encrypted with a password (PBKDF2 + AES-GCM).</li>
 * </ul>
 * Only device specific caches are left out. The file uses logical names, so it can be
 * imported in another build of the app (different application id / signature).
 */
public final class SettingsBackup {
    public static final String FORMAT = "artemis-settings";
    public static final int VERSION = 3;

    /** Logical name of the default SharedPreferences (its file name depends on the package). */
    private static final String DEFAULT_PREFS = "default";

    /** Device specific caches / crash detection state, not user settings. */
    private static final Set<String> EXCLUDED_PREFS = new HashSet<>(Arrays.asList(
            "GlPreferences", "DecoderTombstone"));

    /** Non sensitive files, relative to the app's data directory. */
    private static final List<String> DATA_FILES = Arrays.asList(
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
            JSONObject typed = new JSONObject();
            if (value instanceof Boolean) {
                typed.put("t", "b");
                typed.put("v", value);
            } else if (value instanceof Integer) {
                typed.put("t", "i");
                typed.put("v", value);
            } else if (value instanceof Long) {
                typed.put("t", "l");
                typed.put("v", value);
            } else if (value instanceof Float) {
                typed.put("t", "f");
                typed.put("v", ((Float) value).doubleValue());
            } else if (value instanceof String) {
                typed.put("t", "s");
                typed.put("v", value);
            } else if (value instanceof Set) {
                JSONArray array = new JSONArray();
                for (Object item : (Set<?>) value) {
                    array.put(String.valueOf(item));
                }
                typed.put("t", "ss");
                typed.put("v", array);
            } else {
                continue;
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

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, kdf, KDF_ITERATIONS),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        out.put("encrypted", true);
        out.put("cipher", "AES/GCM/NoPadding");
        out.put("kdf", kdf);
        out.put("iterations", KDF_ITERATIONS);
        out.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        out.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        out.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
    }

    // ------------------------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------------------------

    /** Parses and validates a backup file. */
    public static JSONObject read(InputStream in) throws IOException, JSONException {
        JSONObject root = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        if (!FORMAT.equals(root.optString("format")) || !root.has("prefs")) {
            throw new JSONException("Not a settings backup file");
        }
        if (root.optInt("version", 0) > VERSION) {
            throw new JSONException("This backup was made by a newer version of the app");
        }
        return root;
    }

    /** Whether the backup contains paired computers. */
    public static boolean hasPairing(JSONObject backup) {
        if (backup.has("pairing")) {
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
     * Returns the paired computers files of the backup, decrypting them if needed.
     *
     * @param password ignored when the section isn't encrypted
     */
    public static JSONObject openPairing(JSONObject backup, String password)
            throws JSONException, WrongPasswordException, GeneralSecurityException {
        JSONObject pairing = backup.optJSONObject("pairing");
        if (pairing == null) {
            // Version 2
            JSONObject files = new JSONObject();
            JSONObject data = backup.optJSONObject("data");
            if (data != null) {
                for (String path : PAIRING_FILES) {
                    if (data.has(path)) {
                        files.put(path, data.getString(path));
                    }
                }
            }
            return files;
        }
        if (!pairing.optBoolean("encrypted", false)) {
            return pairing.getJSONObject("files");
        }

        byte[] salt = Base64.decode(pairing.getString("salt"), Base64.NO_WRAP);
        byte[] iv = Base64.decode(pairing.getString("iv"), Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(pairing.getString("ciphertext"), Base64.NO_WRAP);
        SecretKeySpec key = deriveKey(password == null ? "" : password, salt,
                pairing.getString("kdf"), pairing.getInt("iterations"));
        Cipher cipher = Cipher.getInstance(pairing.optString("cipher", "AES/GCM/NoPadding"));
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext;
        try {
            plaintext = cipher.doFinal(ciphertext);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new WrongPasswordException(e);
        }
        return new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
    }

    /**
     * Replaces the current configuration with the backup. The app must be restarted right
     * afterwards (open databases and in-memory state are stale).
     *
     * @param pairingFiles paired computers to restore (from {@link #openPairing}), or null
     *                     to keep the current paired computers
     */
    public static void restore(Context context, JSONObject backup, JSONObject pairingFiles)
            throws JSONException, IOException {
        JSONObject prefs = backup.getJSONObject("prefs");

        Set<String> names = localPrefs(context);
        Iterator<String> backupNames = prefs.keys();
        while (backupNames.hasNext()) {
            String name = backupNames.next();
            if (!EXCLUDED_PREFS.contains(name)) {
                names.add(name);
            }
        }
        for (String name : names) {
            SharedPreferences.Editor editor = openPrefs(context, name).edit().clear();
            JSONObject values = prefs.optJSONObject(name);
            if (values != null) {
                Iterator<String> keys = values.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    putTyped(editor, key, values.getJSONObject(key));
                }
            }
            editor.commit();
        }

        JSONObject data = backup.optJSONObject("data");
        if (data != null) {
            restoreFiles(context, DATA_FILES, data);
        } else {
            // Version 1: only the profiles file, as text
            JSONObject files = backup.optJSONObject("files");
            if (files != null && files.has(V1_PROFILES_FILE)) {
                File profiles = new File(context.getFilesDir(), V1_PROFILES_FILE);
                profiles.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(profiles)) {
                    out.write(files.getString(V1_PROFILES_FILE).getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        if (pairingFiles != null) {
            restoreFiles(context, PAIRING_FILES, pairingFiles);
        }
    }

    /** Writes the files present in the map, deletes the other ones of the list. */
    private static void restoreFiles(Context context, List<String> paths, JSONObject files)
            throws JSONException, IOException {
        for (String path : paths) {
            File file = new File(dataDir(context), path);
            if (files.has(path)) {
                file.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(Base64.decode(files.getString(path), Base64.NO_WRAP));
                }
            } else {
                file.delete();
            }
        }
    }

    private static void putTyped(SharedPreferences.Editor editor, String key, JSONObject typed) throws JSONException {
        switch (typed.getString("t")) {
            case "b":
                editor.putBoolean(key, typed.getBoolean("v"));
                break;
            case "i":
                editor.putInt(key, typed.getInt("v"));
                break;
            case "l":
                editor.putLong(key, typed.getLong("v"));
                break;
            case "f":
                editor.putFloat(key, (float) typed.getDouble("v"));
                break;
            case "ss": {
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

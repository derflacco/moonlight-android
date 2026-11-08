package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.GameMenu;
import com.limelight.TestLogSuppressor;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SettingsBackupTest {

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void exportThenImportRestoresEverything() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences defaults = PreferenceManager.getDefaultSharedPreferences(context);
        defaults.edit()
                .putBoolean("checkbox_show_onscreen_controls", true)
                .putInt("seekbar_osc_opacity", 42)
                .putLong("some_long", 1234567890123L)
                .putFloat("some_float", 1.5f)
                .putString("list_resolution", "1920x1080")
                .putStringSet("some_set", new HashSet<>(Arrays.asList("a", "b")))
                .commit();
        context.getSharedPreferences("OSC_3", Context.MODE_PRIVATE).edit().putString("6", "{\"v\":2}").commit();
        context.getSharedPreferences("OSC_Keyboard", Context.MODE_PRIVATE).edit().putString("key_29", "{\"LEFT\":1}").commit();
        context.getSharedPreferences(GameMenu.PREF_NAME, Context.MODE_PRIVATE).edit().putString(GameMenu.KEY_NAME, "[]").commit();
        File profiles = new File(context.getFilesDir(), "profiles/profiles.json");
        profiles.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(profiles)) {
            out.write("{\"profiles\":[]}".getBytes(StandardCharsets.UTF_8));
        }

        // Any other preferences file is included automatically, caches are not
        context.getSharedPreferences("SomeFutureFeature", Context.MODE_PRIVATE).edit().putString("x", "y").commit();
        context.getSharedPreferences("GlPreferences", Context.MODE_PRIVATE).edit().putString("gl", "cache").commit();
        // Paired PCs: pairing identity and computer database
        File dataDir = context.getFilesDir().getParentFile();
        writeFile(new File(dataDir, "files/client.key"), new byte[]{0, 1, 2, (byte) 0xFF});
        writeFile(new File(dataDir, "files/client.crt"), "CERT".getBytes(StandardCharsets.UTF_8));
        writeFile(new File(dataDir, "databases/computers4.db"), new byte[]{9, 8, 7});

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        SettingsBackup.write(SettingsBackup.export(context, true, null), file);

        // Wipe everything, add junk that the import must remove
        defaults.edit().clear().putBoolean("junk", true).commit();
        context.getSharedPreferences("OSC_3", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("OSC_Keyboard", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(GameMenu.PREF_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        profiles.delete();
        context.getSharedPreferences("SomeFutureFeature", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("GlPreferences", Context.MODE_PRIVATE).edit().putString("gl", "other device").commit();
        new File(dataDir, "files/client.key").delete();
        writeFile(new File(dataDir, "files/client.crt"), "OTHER".getBytes(StandardCharsets.UTF_8));
        new File(dataDir, "databases/computers4.db").delete();

        JSONObject backup = SettingsBackup.read(new ByteArrayInputStream(file.toByteArray()));
        assertTrue(SettingsBackup.hasPairing(backup));
        assertFalse(SettingsBackup.isPairingEncrypted(backup));
        SettingsBackup.restore(context, backup, SettingsBackup.openPairing(backup, null));

        assertTrue(defaults.getBoolean("checkbox_show_onscreen_controls", false));
        assertEquals(42, defaults.getInt("seekbar_osc_opacity", 0));
        assertEquals(1234567890123L, defaults.getLong("some_long", 0));
        assertEquals(1.5f, defaults.getFloat("some_float", 0), 0.0001f);
        assertEquals("1920x1080", defaults.getString("list_resolution", null));
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), defaults.getStringSet("some_set", null));
        assertFalse(defaults.contains("junk"));
        assertEquals("{\"v\":2}", context.getSharedPreferences("OSC_3", Context.MODE_PRIVATE).getString("6", null));
        assertEquals("{\"LEFT\":1}", context.getSharedPreferences("OSC_Keyboard", Context.MODE_PRIVATE).getString("key_29", null));
        assertEquals("[]", context.getSharedPreferences(GameMenu.PREF_NAME, Context.MODE_PRIVATE).getString(GameMenu.KEY_NAME, null));
        assertEquals("{\"profiles\":[]}", new String(Files.readAllBytes(profiles.toPath()), StandardCharsets.UTF_8));
        assertEquals("y", context.getSharedPreferences("SomeFutureFeature", Context.MODE_PRIVATE).getString("x", null));
        assertEquals("other device", context.getSharedPreferences("GlPreferences", Context.MODE_PRIVATE).getString("gl", null));
        assertArrayEquals(new byte[]{0, 1, 2, (byte) 0xFF}, Files.readAllBytes(new File(dataDir, "files/client.key").toPath()));
        assertEquals("CERT", new String(Files.readAllBytes(new File(dataDir, "files/client.crt").toPath()), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(new File(dataDir, "databases/computers4.db").toPath()));
    }

    private static void writeFile(File file, byte[] content) throws Exception {
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
    }

    @Test
    public void version1BackupsStillImport() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        JSONObject v1 = new JSONObject()
                .put("format", SettingsBackup.FORMAT).put("version", 1)
                .put("prefs", new JSONObject().put("default", new JSONObject()
                        .put("list_fps", new JSONObject().put("t", "s").put("v", "120"))))
                .put("files", new JSONObject().put("profiles/profiles.json", "{}"));
        SettingsBackup.restore(context, SettingsBackup.read(new ByteArrayInputStream(v1.toString().getBytes(StandardCharsets.UTF_8))), null);
        assertEquals("120", PreferenceManager.getDefaultSharedPreferences(context).getString("list_fps", null));
        assertEquals("{}", new String(Files.readAllBytes(new File(context.getFilesDir(), "profiles/profiles.json").toPath()), StandardCharsets.UTF_8));
    }

    private static void writePairing(Context context, String key) throws Exception {
        File dataDir = context.getFilesDir().getParentFile();
        writeFile(new File(dataDir, "files/client.key"), key.getBytes(StandardCharsets.UTF_8));
        writeFile(new File(dataDir, "databases/computers4.db"), ("db-" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static String readPairingKey(Context context) throws Exception {
        File dataDir = context.getFilesDir().getParentFile();
        return new String(Files.readAllBytes(new File(dataDir, "files/client.key").toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void pairingIsLeftOutByDefaultAndKeptOnImport() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        writePairing(context, "exported");
        JSONObject backup = SettingsBackup.export(context, false, null);
        assertFalse(SettingsBackup.hasPairing(backup));
        assertFalse(backup.toString().contains(android.util.Base64.encodeToString(
                "exported".getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)));

        writePairing(context, "local");
        SettingsBackup.restore(context, backup, null);
        assertEquals("local", readPairingKey(context));
    }

    @Test
    public void encryptedPairingRoundTrip() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        writePairing(context, "secret-key");
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        SettingsBackup.write(SettingsBackup.export(context, true, "hunter2"), file);
        String text = file.toString("UTF-8");
        // The pairing files are not readable in the file
        assertFalse(text.contains(android.util.Base64.encodeToString(
                "secret-key".getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)));

        JSONObject backup = SettingsBackup.read(new ByteArrayInputStream(file.toByteArray()));
        assertTrue(SettingsBackup.hasPairing(backup));
        assertTrue(SettingsBackup.isPairingEncrypted(backup));

        try {
            SettingsBackup.openPairing(backup, "wrong");
            org.junit.Assert.fail("Wrong password accepted");
        } catch (SettingsBackup.WrongPasswordException expected) {
        }

        writePairing(context, "local");
        SettingsBackup.restore(context, backup, SettingsBackup.openPairing(backup, "hunter2"));
        assertEquals("secret-key", readPairingKey(context));
    }

    @Test(expected = JSONException.class)
    public void rejectsOtherJsonFiles() throws Exception {
        SettingsBackup.read(new ByteArrayInputStream("{\"LEFT\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test(expected = JSONException.class)
    public void rejectsNewerFormat() throws Exception {
        JSONObject newer = new JSONObject().put("format", SettingsBackup.FORMAT).put("version", 99).put("prefs", new JSONObject());
        SettingsBackup.read(new ByteArrayInputStream(newer.toString().getBytes(StandardCharsets.UTF_8)));
    }
}

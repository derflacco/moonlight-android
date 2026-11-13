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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
                .putString("list_resolution", "1920x1080")
                .commit();
        // Every value type, in a known settings group
        SharedPreferences group = context.getSharedPreferences("HiddenApps", Context.MODE_PRIVATE);
        group.edit()
                .putLong("some_long", 1234567890123L)
                .putFloat("some_float", 1.5f)
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

        // Unknown settings groups are exported but not imported, caches are not even exported
        context.getSharedPreferences("SomeFutureFeature", Context.MODE_PRIVATE).edit().putString("x", "y").commit();
        context.getSharedPreferences("GlPreferences", Context.MODE_PRIVATE).edit().putString("gl", "cache").commit();

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        SettingsBackup.write(SettingsBackup.export(context), file);

        // Wipe everything, add junk that the import must remove
        defaults.edit().clear().putBoolean("junk", true).commit();
        group.edit().clear().commit();
        context.getSharedPreferences("OSC_3", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("OSC_Keyboard", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(GameMenu.PREF_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        profiles.delete();
        context.getSharedPreferences("SomeFutureFeature", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("GlPreferences", Context.MODE_PRIVATE).edit().putString("gl", "other device").commit();

        JSONObject backup = SettingsBackup.read(new ByteArrayInputStream(file.toByteArray()));
        SettingsBackup.ImportPlan plan = SettingsBackup.analyze(context, backup);
        assertFalse(String.join("\n", plan.unsupported), plan.hasUnsupported());
        SettingsBackup.restore(context, backup, plan);

        assertTrue(defaults.getBoolean("checkbox_show_onscreen_controls", false));
        assertEquals(42, defaults.getInt("seekbar_osc_opacity", 0));
        assertEquals(1234567890123L, group.getLong("some_long", 0));
        assertEquals(1.5f, group.getFloat("some_float", 0), 0.0001f);
        assertEquals("1920x1080", defaults.getString("list_resolution", null));
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), group.getStringSet("some_set", null));
        assertFalse(defaults.contains("junk"));
        assertEquals("{\"v\":2}", context.getSharedPreferences("OSC_3", Context.MODE_PRIVATE).getString("6", null));
        assertEquals("{\"LEFT\":1}", context.getSharedPreferences("OSC_Keyboard", Context.MODE_PRIVATE).getString("key_29", null));
        assertEquals("[]", context.getSharedPreferences(GameMenu.PREF_NAME, Context.MODE_PRIVATE).getString(GameMenu.KEY_NAME, null));
        assertEquals("{\"profiles\":[]}", new String(Files.readAllBytes(profiles.toPath()), StandardCharsets.UTF_8));
        assertNull(context.getSharedPreferences("SomeFutureFeature", Context.MODE_PRIVATE).getString("x", null));
        assertEquals("other device", context.getSharedPreferences("GlPreferences", Context.MODE_PRIVATE).getString("gl", null));
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
        JSONObject v1Backup = SettingsBackup.read(new ByteArrayInputStream(v1.toString().getBytes(StandardCharsets.UTF_8)));
        SettingsBackup.restore(context, v1Backup, SettingsBackup.analyze(context, v1Backup));
        assertEquals("120", PreferenceManager.getDefaultSharedPreferences(context).getString("list_fps", null));
        assertEquals("{}", new String(Files.readAllBytes(new File(context.getFilesDir(), "profiles/profiles.json").toPath()), StandardCharsets.UTF_8));
    }

    private static void writePairing(Context context, String key) throws Exception {
        File dataDir = context.getFilesDir().getParentFile();
        writeFile(new File(dataDir, "files/client.key"), key.getBytes(StandardCharsets.UTF_8));
        writeFile(new File(dataDir, "files/client.crt"), ("crt-" + key).getBytes(StandardCharsets.UTF_8));
        writeFile(new File(dataDir, "databases/computers4.db"), ("db-" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static String readPairingKey(Context context) throws Exception {
        File dataDir = context.getFilesDir().getParentFile();
        return new String(Files.readAllBytes(new File(dataDir, "files/client.key").toPath()), StandardCharsets.UTF_8);
    }

    private static String base64(String text) {
        return android.util.Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    @Test
    public void pairedComputersAreNeverExportedAndKeptOnImport() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        writePairing(context, "exported");
        JSONObject backup = SettingsBackup.export(context);
        assertFalse(backup.has("pairing"));
        String text = backup.toString();
        assertFalse(text.contains(base64("exported")));
        assertFalse(text.contains(base64("crt-exported")));
        assertFalse(text.contains(base64("db-exported")));
        assertFalse(text.contains("client.key"));
        assertFalse(text.contains("computers4"));

        writePairing(context, "local");
        SettingsBackup.restore(context, backup, SettingsBackup.analyze(context, backup));
        assertEquals("local", readPairingKey(context));
    }

    /** Files made by builds that could export paired computers: everything else is imported. */
    @Test
    public void pairedComputersInAFileAreReportedAndSkipped() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences defaults = PreferenceManager.getDefaultSharedPreferences(context);
        defaults.edit().clear().putString("list_fps", "60").commit();
        writePairing(context, "local");

        JSONObject backup = SettingsBackup.read(new ByteArrayInputStream(new JSONObject()
                .put("format", SettingsBackup.FORMAT).put("version", 3)
                .put("prefs", new JSONObject().put("default", new JSONObject().put("list_fps", typed("s", "120"))))
                .put("pairing", new JSONObject().put("encrypted", false).put("files", new JSONObject()
                        .put("files/client.key", base64("imported"))
                        .put("files/client.crt", base64("crt-imported"))))
                .toString().getBytes(StandardCharsets.UTF_8)));
        SettingsBackup.ImportPlan plan = SettingsBackup.analyze(context, backup);
        assertEquals(java.util.Collections.singletonList(context.getString(com.limelight.R.string.backup_unsupported_pairing)),
                plan.unsupported);

        SettingsBackup.restore(context, backup, plan);
        assertEquals("120", defaults.getString("list_fps", null));
        assertEquals("local", readPairingKey(context));
    }

    @Test(expected = JSONException.class)
    public void rejectsOtherJsonFiles() throws Exception {
        SettingsBackup.read(new ByteArrayInputStream("{\"LEFT\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    private static JSONObject typed(String type, Object value) throws JSONException {
        return new JSONObject().put("t", type).put("v", value);
    }

    @Test
    public void newerVersionOnlyImportsKnownSettings() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences defaults = PreferenceManager.getDefaultSharedPreferences(context);
        defaults.edit().clear().putString("list_fps", "60").commit();

        JSONObject newer = new JSONObject()
                .put("format", SettingsBackup.FORMAT).put("version", 99).put("appVersion", "9.9")
                .put("future_section", new JSONObject())
                .put("prefs", new JSONObject()
                        .put("default", new JSONObject()
                                .put("list_fps", typed("s", "120"))
                                .put("checkbox_show_onscreen_controls", typed("b", true))
                                .put("future_setting", typed("b", true))
                                .put("seekbar_bitrate_kbps", typed("s", "not an int"))
                                .put("future_typed_value", typed("x", 1))
                                .put("list_resolution", new JSONObject().put("t", "s")))
                        .put("FutureFeature", new JSONObject().put("a", typed("s", "b")))
                        .put("OSC_Keyboard", new JSONObject().put("key_29", typed("s", "{}"))))
                .put("data", new JSONObject()
                        .put("files/profiles/profiles.json", android.util.Base64.encodeToString(
                                "{}".getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP))
                        .put("files/future.bin", "AAAA"));

        JSONObject backup = SettingsBackup.read(new ByteArrayInputStream(newer.toString().getBytes(StandardCharsets.UTF_8)));
        SettingsBackup.ImportPlan plan = SettingsBackup.analyze(context, backup);
        assertTrue(plan.isFromNewerVersion());
        assertEquals("9.9", plan.appVersion);
        String report = String.join("\n", plan.unsupported);
        assertTrue(report, report.contains("future_section"));
        assertTrue(report, report.contains("future_setting"));
        assertTrue(report, report.contains("seekbar_bitrate_kbps"));
        assertTrue(report, report.contains("list_resolution"));
        assertTrue(report, report.contains("FutureFeature"));
        assertTrue(report, report.contains("files/future.bin"));
        assertFalse(report, report.contains("list_fps"));
        assertEquals(report, 7, plan.unsupported.size());

        SettingsBackup.restore(context, backup, plan);
        assertEquals("120", defaults.getString("list_fps", null));
        assertTrue(defaults.getBoolean("checkbox_show_onscreen_controls", false));
        assertFalse(defaults.contains("future_setting"));
        assertFalse(defaults.contains("seekbar_bitrate_kbps"));
        assertFalse(defaults.contains("list_resolution"));
        assertTrue(context.getSharedPreferences("FutureFeature", Context.MODE_PRIVATE).getAll().isEmpty());
        assertEquals("{}", context.getSharedPreferences("OSC_Keyboard", Context.MODE_PRIVATE).getString("key_29", null));
        assertFalse(new File(context.getFilesDir(), "future.bin").exists());
    }

    @Test
    public void failedImportRestoresThePreviousSettings() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences defaults = PreferenceManager.getDefaultSharedPreferences(context);
        defaults.edit().clear().putString("list_fps", "60").commit();

        JSONObject backup = SettingsBackup.read(new ByteArrayInputStream(new JSONObject()
                .put("format", SettingsBackup.FORMAT).put("version", 3)
                .put("prefs", new JSONObject().put("default", new JSONObject().put("list_fps", typed("s", "120"))))
                .put("data", new JSONObject().put("files/profiles/profiles.json", base64("{}")))
                .toString().getBytes(StandardCharsets.UTF_8)));
        // A file in the way of the profiles directory makes the import fail after the settings were written
        File blocker = new File(context.getFilesDir(), "profiles");
        deleteRecursively(blocker);
        writeFile(blocker, new byte[]{1});
        try {
            SettingsBackup.restore(context, backup, SettingsBackup.analyze(context, backup));
            org.junit.Assert.fail("The import should have failed");
        } catch (java.io.IOException expected) {
        } finally {
            blocker.delete();
        }
        assertEquals("60", defaults.getString("list_fps", null));
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    /** Files that aren't settings (library markers, caches...) are neither exported nor touched. */
    @Test
    public void otherFilesOfTheAppAreLeftAlone() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("_has_set_default_values", Context.MODE_PRIVATE).edit().putBoolean("_has_set_default_values", true).commit();
        context.getSharedPreferences("SomeLibrary", Context.MODE_PRIVATE).edit().putString("state", "local").commit();
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString("list_fps", "90").commit();

        JSONObject backup = SettingsBackup.read(new ByteArrayInputStream(
                SettingsBackup.export(context).toString().getBytes(StandardCharsets.UTF_8)));
        assertFalse(backup.getJSONObject("prefs").has("_has_set_default_values"));
        assertFalse(backup.getJSONObject("prefs").has("SomeLibrary"));
        SettingsBackup.ImportPlan plan = SettingsBackup.analyze(context, backup);
        assertTrue(plan.unsupported.toString(), plan.unsupported.isEmpty());

        context.getSharedPreferences("SomeLibrary", Context.MODE_PRIVATE).edit().putString("state", "changed").commit();
        SettingsBackup.restore(context, backup, plan);
        assertEquals("changed", context.getSharedPreferences("SomeLibrary", Context.MODE_PRIVATE).getString("state", null));
        assertTrue(context.getSharedPreferences("_has_set_default_values", Context.MODE_PRIVATE).getBoolean("_has_set_default_values", false));
        assertEquals("90", PreferenceManager.getDefaultSharedPreferences(context).getString("list_fps", null));
    }

    /** Every setting of the settings screen survives an export / import, internal state doesn't. */
    @Test
    public void everySettingOfTheSettingsScreenIsImported() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences defaults = PreferenceManager.getDefaultSharedPreferences(context);
        java.util.Map<String, String> screen = SettingsBackup.supportedSettings(context);
        assertTrue("Settings screen not read", screen.size() > 100);

        SharedPreferences.Editor editor = defaults.edit().clear();
        int typed = 0;
        for (java.util.Map.Entry<String, String> setting : screen.entrySet()) {
            String type = setting.getValue();
            if (type == null) {
                continue;
            }
            typed++;
            switch (type) {
                case "b": editor.putBoolean(setting.getKey(), true); break;
                case "i": editor.putInt(setting.getKey(), 7); break;
                case "ss": editor.putStringSet(setting.getKey(), new HashSet<>(Arrays.asList("x"))); break;
                default: editor.putString(setting.getKey(), "imported"); break;
            }
        }
        // State saved by the app itself, not a setting of the screen
        editor.putFloat("number_zoom_scale", 2f);
        editor.commit();
        assertTrue(typed > 100);

        JSONObject backup = SettingsBackup.read(new ByteArrayInputStream(
                SettingsBackup.export(context).toString().getBytes(StandardCharsets.UTF_8)));
        defaults.edit().clear().commit();
        SettingsBackup.ImportPlan plan = SettingsBackup.analyze(context, backup);
        assertEquals(java.util.Collections.singletonList(
                context.getString(com.limelight.R.string.backup_unsupported_setting, "number_zoom_scale")), plan.unsupported);
        SettingsBackup.restore(context, backup, plan);

        for (java.util.Map.Entry<String, String> setting : screen.entrySet()) {
            if (setting.getValue() != null) {
                assertTrue("Not imported: " + setting.getKey(), defaults.contains(setting.getKey()));
            }
        }
        assertFalse(defaults.contains("number_zoom_scale"));
    }
}

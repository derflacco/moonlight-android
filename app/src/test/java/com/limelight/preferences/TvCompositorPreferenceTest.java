package com.limelight.preferences;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = 33, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class TvCompositorPreferenceTest {
    private Context context;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @Test
    public void compositorWorkaround_isOptInAndLoadsFromPreferences() {
        assertFalse(PreferenceConfiguration.readPreferences(context).enableTvCompositorWorkaround);

        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean("checkbox_tv_compositor_workaround", true)
                .commit();

        assertTrue(PreferenceConfiguration.readPreferences(context).enableTvCompositorWorkaround);
    }
}

package com.limelight.overlay;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;
import com.limelight.binding.input.overlay.ElementEditState;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import android.graphics.Rect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Persistence of overlay element geometry: resolution independent format and legacy format.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ElementEditStateTest {

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    private static DisplayMetrics screen(int w, int h) {
        DisplayMetrics m = new DisplayMetrics();
        m.widthPixels = w;
        m.heightPixels = h;
        m.density = 2.75f;
        return m;
    }

    private static View element(Context context, int left, int top, int w, int h) {
        FrameLayout parent = new FrameLayout(context);
        View view = new View(context);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = left;
        lp.topMargin = top;
        parent.addView(view, lp);
        return view;
    }

    @Test
    public void roundTripOnSameScreen() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DisplayMetrics s = screen(2400, 1080);
        ElementEditState state = new ElementEditState();
        state.shown = false;
        state.opacity = 0.6f;
        state.lockAspect = false;

        View source = element(context, 2000, 700, 200, 150);
        JSONObject json = state.toJson(source, s);

        ElementEditState loaded = new ElementEditState();
        View target = element(context, 0, 0, 10, 10);
        loaded.fromJson(target, json, s);

        assertEquals(new Rect(2000, 700, 2200, 850), ElementEditState.getBounds(target));
        assertFalse(loaded.shown);
        assertFalse(loaded.lockAspect);
        assertEquals(0.6f, loaded.opacity, 0.001f);
    }

    @Test
    public void rightAnchoredControlsFollowTheRightEdgeOnWiderScreens() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        // A control 100px from the right edge of a 16:9 screen...
        View source = element(context, 1920 - 100 - 150, 500, 150, 150);
        JSONObject json = new ElementEditState().toJson(source, screen(1920, 1080));

        // ...stays 100px (same height units) from the right edge on a 20:9 screen
        View target = element(context, 0, 0, 10, 10);
        new ElementEditState().fromJson(target, json, screen(2400, 1080));
        Rect b = ElementEditState.getBounds(target);
        assertEquals(2400 - 100, b.right);
        assertEquals(150, b.width());
        assertEquals(500, b.top);
    }

    @Test
    public void sizesScaleWithScreenHeight() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        View source = element(context, 100, 540, 200, 200);
        JSONObject json = new ElementEditState().toJson(source, screen(1920, 1080));

        View target = element(context, 0, 0, 10, 10);
        new ElementEditState().fromJson(target, json, screen(3840, 2160));
        assertEquals(new Rect(200, 1080, 600, 1480), ElementEditState.getBounds(target));
    }

    @Test
    public void legacyPixelLayoutsStillLoad() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        JSONObject legacy = new JSONObject()
                .put("LEFT", 10).put("TOP", 20).put("WIDTH", 300).put("HEIGHT", 120)
                .put("ENABLED", false).put("HIDDEN", true);

        ElementEditState state = new ElementEditState();
        View target = element(context, 0, 0, 10, 10);
        state.fromJson(target, legacy, screen(2400, 1080));

        assertEquals(new Rect(10, 20, 310, 140), ElementEditState.getBounds(target));
        assertFalse(state.shown);
        assertTrue(state.removed);
        assertEquals(View.GONE, target.getVisibility());
    }

    @Test
    public void offscreenLayoutsAreClampedBackOnScreen() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        JSONObject legacy = new JSONObject()
                .put("LEFT", 5000).put("TOP", -40).put("WIDTH", 5).put("HEIGHT", 5).put("ENABLED", true);

        View target = element(context, 0, 0, 10, 10);
        DisplayMetrics s = screen(2400, 1080);
        new ElementEditState().fromJson(target, legacy, s);

        Rect b = ElementEditState.getBounds(target);
        int min = ElementEditState.minElementSize(s);
        assertEquals(min, b.width());
        assertEquals(2400, b.right);
        assertEquals(0, b.top);
    }
}

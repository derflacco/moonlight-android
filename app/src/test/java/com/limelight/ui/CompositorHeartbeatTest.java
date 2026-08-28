package com.limelight.ui;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

@Config(sdk = 33)
@RunWith(RobolectricTestRunner.class)
public class CompositorHeartbeatTest {
    @Test
    public void startAndStop_updatesPixelIndependently() {
        Context context = ApplicationProvider.getApplicationContext();
        View view = new View(context);
        view.setVisibility(View.GONE);
        CompositorHeartbeat heartbeat = new CompositorHeartbeat(view);

        heartbeat.start();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(CompositorHeartbeat.COLOR_PHASE_ONE, backgroundColor(view));

        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(CompositorHeartbeat.INTERVAL_MS, TimeUnit.MILLISECONDS);
        assertEquals(CompositorHeartbeat.COLOR_PHASE_TWO, backgroundColor(view));

        heartbeat.stop();
        assertEquals(View.GONE, view.getVisibility());

        int stoppedColor = backgroundColor(view);
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(CompositorHeartbeat.INTERVAL_MS * 2, TimeUnit.MILLISECONDS);
        assertEquals(stoppedColor, backgroundColor(view));
    }

    private static int backgroundColor(View view) {
        return ((ColorDrawable) view.getBackground()).getColor();
    }
}

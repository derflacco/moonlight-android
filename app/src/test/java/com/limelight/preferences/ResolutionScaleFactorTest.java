package com.limelight.preferences;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ResolutionScaleFactorTest {
    @Test
    public void halfResolutionDoublesHostResolution() {
        assertEquals(200, PreferenceConfiguration.calculateAutoResolutionScaleFactor(
                960, 540, 1920, 1080));
    }

    @Test
    public void lowerResolutionCanExceedManualSliderRange() {
        assertEquals(300, PreferenceConfiguration.calculateAutoResolutionScaleFactor(
                1280, 720, 3840, 2160));
    }

    @Test
    public void portraitStreamUsesPortraitTarget() {
        assertEquals(200, PreferenceConfiguration.calculateAutoResolutionScaleFactor(
                540, 960, 1920, 1080));
    }

    @Test
    public void fullOrHigherStreamResolutionDoesNotDownscaleHost() {
        assertEquals(100, PreferenceConfiguration.calculateAutoResolutionScaleFactor(
                3840, 2160, 1920, 1080));
    }

    @Test
    public void invalidDimensionsFallBackToOneHundredPercent() {
        assertEquals(100, PreferenceConfiguration.calculateAutoResolutionScaleFactor(
                0, 540, 1920, 1080));
    }
}

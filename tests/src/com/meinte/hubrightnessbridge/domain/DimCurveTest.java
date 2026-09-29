// Copyright (C) 2026 The XYAUTO device-3 project
// SPDX-License-Identifier: Apache-2.0
package com.meinte.hubrightnessbridge.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DimCurveTest {
    private static final float MIN = DimCurve.DEFAULT_MIN;
    private static final float MAX = DimCurve.DEFAULT_MAX;

    /**
     * Independent reference: SettingsLib BrightnessUtils.convertLinearToGammaFloat
     * with MathUtils semantics (norm, lerp, float log/sqrt), returning the slider's
     * 0..65535 integer. Written from the framework source, not from DimCurve.
     */
    private static int frameworkSliderInt(float val, float min, float max) {
        final float normalizedVal = (val - min) / (max - min) * 12;
        final float ret;
        if (normalizedVal <= 1f) {
            ret = (float) Math.sqrt(normalizedVal) * 0.5f;
        } else {
            ret = 0.17883277f * (float) Math.log(normalizedVal - 0.28466892f) + 0.55991073f;
        }
        return Math.round(0 + (65535 - 0) * ret);
    }

    @Test
    public void intToFloatMatchesBrightnessSynchronizer() {
        assertEquals(0f, DimCurve.intToFloat(1), 0f);
        assertEquals(1f, DimCurve.intToFloat(255), 0f);
        assertEquals(9f / 254f, DimCurve.intToFloat(10), 1e-7f);
        assertEquals(0f, DimCurve.intToFloat(-5), 0f); // clamped
        assertEquals(1f, DimCurve.intToFloat(999), 0f); // clamped
    }

    @Test
    public void defaultRangeIsConfigXmlTenTo255() {
        assertEquals(9f / 254f, MIN, 1e-7f);
        assertEquals(1f, MAX, 0f);
    }

    @Test
    public void sliderFractionAgreesWithFrameworkTransferForEverySliderInt() {
        for (int b = 10; b <= 255; b++) {
            float lin = DimCurve.intToFloat(b);
            int expected = frameworkSliderInt(lin, MIN, MAX);
            int actual = Math.round(DimCurve.sliderFraction(lin, MIN, MAX, false) * 65535f);
            assertTrue("b=" + b + " framework=" + expected + " bridge=" + actual,
                    Math.abs(expected - actual) <= 1);
        }
    }

    @Test
    public void sliderEndpoints() {
        assertEquals(0f, DimCurve.sliderFraction(MIN, MIN, MAX, false), 0f);
        assertEquals(1f, DimCurve.sliderFraction(MAX, MIN, MAX, false), 1e-4f);
        assertEquals(0f, DimCurve.sliderFraction(0f, MIN, MAX, false), 0f); // below min clamps
    }

    /** Anchors computed independently in Python from the framework formulas. */
    @Test
    public void anchorLevels() {
        int[][] anchors = {
            {10, 100}, {16, 73}, {20, 65}, {32, 48}, {40, 41}, {50, 35}, {112, 16},
            {144, 11}, {176, 7}, {208, 4}, {232, 2}, {244, 1},
        };
        for (int[] a : anchors) {
            DimTarget t = DimCurve.target(a[0], MIN, MAX, false);
            assertEquals("b=" + a[0], DimTarget.on(a[1]), t);
        }
        assertEquals(DimTarget.OFF, DimCurve.target(252, MIN, MAX, false));
        assertEquals(DimTarget.OFF, DimCurve.target(255, MIN, MAX, false));
    }

    @Test
    public void fullBrightnessIsExtraDimOff() {
        assertEquals(DimTarget.OFF, DimCurve.target(255, MIN, MAX, false));
        assertFalse(DimCurve.target(255, MIN, MAX, false).activated);
    }

    @Test
    public void lowestSliderIsMaximumLevel() {
        assertEquals(DimTarget.on(DimCurve.LEVEL_MAX), DimCurve.target(10, MIN, MAX, false));
        // Below the slider minimum (only reachable by a raw settings write) is still bounded.
        assertEquals(DimTarget.on(DimCurve.LEVEL_MAX), DimCurve.target(1, MIN, MAX, false));
    }

    @Test
    public void offBoundarySitsAtTheTopOfTheSlider() {
        int firstOff = -1;
        for (int b = 10; b <= 255; b++) {
            if (!DimCurve.target(b, MIN, MAX, false).activated) {
                firstOff = b;
                break;
            }
        }
        // Python puts it at 249; 248/249 are within 0.05 of the .5 rounding edge.
        assertTrue("first off b=" + firstOff, firstOff >= 247 && firstOff <= 250);
        for (int b = firstOff; b <= 255; b++) {
            assertEquals("b=" + b, DimTarget.OFF, DimCurve.target(b, MIN, MAX, false));
        }
    }

    @Test
    public void monotonicAndBoundedOverEveryInt() {
        int previous = Integer.MAX_VALUE;
        for (int b = 1; b <= 255; b++) {
            DimTarget t = DimCurve.target(b, MIN, MAX, false);
            int level = t.activated ? t.level : 0;
            assertTrue("b=" + b + " level=" + level, level >= 0 && level <= DimCurve.LEVEL_MAX);
            assertTrue("not monotonic at b=" + b, level <= previous);
            assertEquals("activated iff level>=1 at b=" + b, level >= 1, t.activated);
            previous = level;
        }
    }

    @Test
    public void nonSliderValuesChangeNothing() {
        assertNull(DimCurve.target(0, MIN, MAX, false)); // BRIGHTNESS_OFF
        assertNull(DimCurve.target(-1, MIN, MAX, false)); // BRIGHTNESS_INVALID / unset
    }

    @Test
    public void aboveRangeIsTreatedAsFull() {
        assertEquals(DimTarget.OFF, DimCurve.target(4095, MIN, MAX, false));
    }

    @Test
    public void invalidRangeFallsBackToDefaults() {
        for (int b = 1; b <= 255; b += 7) {
            DimTarget expected = DimCurve.target(b, MIN, MAX, false);
            assertEquals(expected, DimCurve.target(b, Float.NaN, MAX, false));
            assertEquals(expected, DimCurve.target(b, 0.5f, 0.5f, false));
            assertEquals(expected, DimCurve.target(b, -0.1f, MAX, false));
            assertEquals(expected, DimCurve.target(b, MIN, 1.5f, false));
        }
    }

    @Test
    public void linearSliderModeIsLinear() {
        // sys.brightness.disable_gamma_conversion: position = normalized linear value.
        float mid = MIN + (MAX - MIN) / 2f;
        assertEquals(0.5f, DimCurve.sliderFraction(mid, MIN, MAX, true), 1e-6f);
        assertEquals(DimTarget.on(100), DimCurve.target(10, MIN, MAX, true));
        assertEquals(DimTarget.OFF, DimCurve.target(255, MIN, MAX, true));
        int previous = Integer.MAX_VALUE;
        for (int b = 10; b <= 255; b++) {
            DimTarget t = DimCurve.target(b, MIN, MAX, true);
            int level = t.activated ? t.level : 0;
            assertTrue(level <= previous);
            previous = level;
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void onRejectsZero() {
        DimTarget.on(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void onRejectsAboveMax() {
        DimTarget.on(101);
    }
}

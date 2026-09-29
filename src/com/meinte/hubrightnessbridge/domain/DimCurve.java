// Copyright (C) 2026 The XYAUTO device-3 project
// SPDX-License-Identifier: Apache-2.0
package com.meinte.hubrightnessbridge.domain;

/**
 * Maps the Android brightness setting onto AOSP Extra Dim. Pure arithmetic, no
 * android.*, so the whole policy is provable on the host.
 *
 * <p>WHY A CURVE AT ALL: device 3 has no reachable backlight dimmer
 * (docs/research/2026-09-10-mcu-backlight-brightness.md), so the slider value is
 * stored and nothing acts on it. The bridge turns "slider position" into an
 * Extra Dim strength: the image is dimmed by a SurfaceFlinger color matrix.
 *
 * <p>THE CURVE, in three steps, each copied from the framework it must agree with:
 * <ol>
 *   <li>{@code screen_brightness} int 1..255 to linear float: BrightnessSynchronizer
 *       .brightnessIntToFloat, i.e. (b - 1) / 254.</li>
 *   <li>Linear float to slider position g in [0, 1]: SettingsLib BrightnessUtils
 *       .convertLinearToGammaFloat (HLG, constants R/A/B/C below) over the display's
 *       [min, max]. That is the transfer the QS/Settings slider itself uses, so g is
 *       where the user's thumb is. With sys.brightness.disable_gamma_conversion the
 *       slider is linear and so is g.</li>
 *   <li>level = round(100 * (1 - g)). Level 0 means Extra Dim OFF. Full brightness
 *       is therefore Extra Dim off, the lowest slider position is level 100, and
 *       the mapping is monotonic.</li>
 * </ol>
 * The framework then maps level 0..100 onto [config_reduceBrightColorsStrengthMin,
 * Max] (the bengal3 overlay sets Min=0 for continuity at the top; Max stays 90) and
 * scales every channel by a*x^2 + b*x + c. At level 100 that scale is 0.140
 * (linear coefficients) or 0.403 (nonlinear), so the glass is never fully black.
 */
public final class DimCurve {
    /** BrightnessSynchronizer int range: PowerManager.BRIGHTNESS_OFF + 1 .. BRIGHTNESS_ON. */
    public static final int INT_MIN = 1;
    public static final int INT_MAX = 255;

    /** reduce_bright_colors_level is a percentage (ColorDisplayService PERCENTAGE_MAX). */
    public static final int LEVEL_MAX = 100;

    // SettingsLib BrightnessUtils HLG constants, verbatim.
    static final float R = 0.5f;
    static final float A = 0.17883277f;
    static final float B = 0.28466892f;
    static final float C = 0.55991073f;

    /**
     * The slider range when config_screenBrightnessSettingMinimum/MaximumFloat are
     * -2 (unset), which is bengal3's case: DisplayDeviceConfig falls back to the int
     * configs 10 and 255 through brightnessIntToFloat.
     */
    public static final float DEFAULT_MIN = intToFloat(10);
    public static final float DEFAULT_MAX = intToFloat(255);

    private DimCurve() {}

    /** BrightnessSynchronizer.brightnessIntToFloat for the 1..255 range, clamped. */
    public static float intToFloat(int brightnessInt) {
        int b = Math.max(INT_MIN, Math.min(INT_MAX, brightnessInt));
        return (b - INT_MIN) / (float) (INT_MAX - INT_MIN);
    }

    /**
     * Slider position in [0, 1] for a linear brightness, exactly as the slider
     * computes it before scaling to 0..65535. Out-of-range input is clamped.
     */
    public static float sliderFraction(float linear, float min, float max, boolean linearSlider) {
        float norm = (linear - min) / (max - min);
        norm = clamp01(norm);
        if (linearSlider) {
            return norm;
        }
        // "For some reason, HLG normalizes to the range [0, 12] rather than [0, 1]"
        float v = norm * 12f;
        float ret = v <= 1f ? (float) Math.sqrt(v) * R : A * (float) Math.log(v - B) + C;
        return clamp01(ret);
    }

    /**
     * The Extra Dim target for a {@code screen_brightness} value, or {@code null}
     * when the value is not a slider position (0 = off, negative = unset/invalid);
     * the bridge then leaves Extra Dim alone.
     *
     * @param min slider minimum as linear float; invalid ranges fall back to defaults
     * @param max slider maximum as linear float
     * @param linearSlider true when sys.brightness.disable_gamma_conversion is set
     */
    public static DimTarget target(int brightnessInt, float min, float max, boolean linearSlider) {
        if (brightnessInt < INT_MIN) {
            return null;
        }
        if (!validRange(min, max)) {
            min = DEFAULT_MIN;
            max = DEFAULT_MAX;
        }
        float g = sliderFraction(intToFloat(brightnessInt), min, max, linearSlider);
        int level = Math.round(LEVEL_MAX * (1f - g));
        level = Math.max(0, Math.min(LEVEL_MAX, level));
        return level == 0 ? DimTarget.OFF : DimTarget.on(level);
    }

    static boolean validRange(float min, float max) {
        return !Float.isNaN(min) && !Float.isNaN(max) && min >= 0f && max <= 1f && max > min;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v) || v < 0f) {
            return 0f;
        }
        return v > 1f ? 1f : v;
    }
}

// Copyright (C) 2026 The XYAUTO device-3 project
// SPDX-License-Identifier: Apache-2.0
package com.meinte.hubrightnessbridge;

import android.app.Application;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;
import com.meinte.hubrightnessbridge.domain.BridgePolicy;
import com.meinte.hubrightnessbridge.domain.DimCurve;
import com.meinte.hubrightnessbridge.domain.DimTarget;
import java.util.List;

/**
 * The Android edge of the brightness-to-Extra-Dim bridge. It reads screen_brightness,
 * asks the pure domain what Extra Dim should be, and writes the Settings.Secure keys
 * ColorDisplayService already observes. Everything else (curve, ordering, who wins)
 * lives in domain/ and is tested on the host.
 *
 * <p>Lifetime: a persistent, direct-boot-aware application, so AMS starts it at boot
 * and keeps it alive. There is no activity, service or receiver.
 *
 * <p>Crash-proof by construction: a persistent process that throws is restarted by
 * AMS in a loop. RescueParty is off on bengal3 (device.mk persist.sys.disable_rescue),
 * but a brightness helper still has no business crash-looping. Every entry point
 * catches Throwable and logs.
 *
 * <p>Uses only public SDK API. The three reduce_bright_colors keys are @hide constants,
 * so they are string literals here; the desk gate pins them to Settings.java. A
 * platform-signed system app may read @hide settings (SettingsProvider
 * enforceSettingReadable) and writes them under WRITE_SECURE_SETTINGS.
 */
public final class BridgeApplication extends Application {
    static final String TAG = "HuBrightnessBridge";

    /** Settle time: the slider's release can land as more than one settings write. */
    private static final long COALESCE_MS = 100;

    /** DisplayDeviceConfig's INVALID_BRIGHTNESS_IN_CONFIG. */
    private static final float INVALID_BRIGHTNESS_IN_CONFIG = -2f;

    private Handler mHandler;
    private final Runnable mApply = this::applySafely;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            start();
        } catch (Throwable t) {
            Log.e(TAG, "bridge: start failed, staying inert", t);
        }
    }

    private void start() {
        // Defence in depth. The package is only installed with BENGAL_EXTRA_DIM=1, but
        // if the overlay is ever missing, ColorDisplayService would ignore our writes
        // and a toggle would sit in Settings doing nothing. Then do nothing at all.
        if (!frameworkBool("config_setColorTransformAccelerated")) {
            Log.i(TAG, "bridge: inert: config_setColorTransformAccelerated=false");
            return;
        }
        HandlerThread thread = new HandlerThread("brightness-bridge");
        thread.start();
        mHandler = new Handler(thread.getLooper());
        // ONE observer, on the brightness setting only. Never on the
        // reduce_bright_colors keys: that is what makes "most recent user action
        // wins" true (BridgePolicy javadoc) and keeps the bridge from fighting the
        // accessibility page.
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        schedule();
                    }
                });
        float[] range = sliderRange();
        Log.i(TAG, "bridge: start range=[" + range[0] + "," + range[1] + "] gamma=hlg");
        schedule(); // boot re-assert of the persisted slider value
    }

    private void schedule() {
        mHandler.removeCallbacks(mApply);
        mHandler.postDelayed(mApply, COALESCE_MS);
    }

    private void applySafely() {
        try {
            applyOnce();
        } catch (Throwable t) {
            Log.e(TAG, "bridge: apply failed", t);
        }
    }

    private void applyOnce() {
        ContentResolver cr = getContentResolver();
        int brightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1);
        float[] range = sliderRange();
        DimTarget target = DimCurve.target(brightness, range[0], range[1], false);
        BridgePolicy.State state = new BridgePolicy.State(
                Settings.Secure.getInt(cr, BridgePolicy.KEY_ACTIVATED, BridgePolicy.UNSET),
                Settings.Secure.getInt(cr, BridgePolicy.KEY_LEVEL, BridgePolicy.UNSET),
                Settings.Secure.getInt(cr, BridgePolicy.KEY_PERSIST, BridgePolicy.UNSET));
        List<BridgePolicy.Write> writes = BridgePolicy.plan(state, target);
        for (BridgePolicy.Write w : writes) {
            Settings.Secure.putInt(cr, w.key, w.value);
        }
        Log.i(TAG, "bridge: brightness=" + brightness + " -> "
                + (target == null ? "no-op (not a slider value)" : target.toString())
                + " writes=" + writes);
    }

    /**
     * The slider's [min, max] as linear floats, with DisplayDeviceConfig's config.xml
     * precedence (loadBrightnessConstraintsFromConfigXml): the float dimens when both
     * are set, otherwise the int configs through brightnessIntToFloat. bengal3 ships
     * no displayconfig XML, which is what makes this the slider's real range.
     */
    private float[] sliderRange() {
        float min = frameworkFloat("config_screenBrightnessSettingMinimumFloat");
        float max = frameworkFloat("config_screenBrightnessSettingMaximumFloat");
        if (min == INVALID_BRIGHTNESS_IN_CONFIG || max == INVALID_BRIGHTNESS_IN_CONFIG
                || Float.isNaN(min) || Float.isNaN(max)) {
            min = DimCurve.intToFloat(frameworkInt("config_screenBrightnessSettingMinimum", 10));
            max = DimCurve.intToFloat(frameworkInt("config_screenBrightnessSettingMaximum", 255));
        }
        return new float[] {min, max};
    }

    private static boolean frameworkBool(String name) {
        Resources r = Resources.getSystem();
        int id = r.getIdentifier(name, "bool", "android");
        return id != 0 && r.getBoolean(id);
    }

    private static int frameworkInt(String name, int fallback) {
        Resources r = Resources.getSystem();
        int id = r.getIdentifier(name, "integer", "android");
        return id != 0 ? r.getInteger(id) : fallback;
    }

    private static float frameworkFloat(String name) {
        Resources r = Resources.getSystem();
        int id = r.getIdentifier(name, "dimen", "android");
        return id != 0 ? r.getFloat(id) : Float.NaN;
    }
}

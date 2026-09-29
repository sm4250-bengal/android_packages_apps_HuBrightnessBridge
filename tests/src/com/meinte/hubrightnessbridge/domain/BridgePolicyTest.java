// Copyright (C) 2026 The XYAUTO device-3 project
// SPDX-License-Identifier: Apache-2.0
package com.meinte.hubrightnessbridge.domain;

import static com.meinte.hubrightnessbridge.domain.BridgePolicy.KEY_ACTIVATED;
import static com.meinte.hubrightnessbridge.domain.BridgePolicy.KEY_LEVEL;
import static com.meinte.hubrightnessbridge.domain.BridgePolicy.KEY_PERSIST;
import static com.meinte.hubrightnessbridge.domain.BridgePolicy.UNSET;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.meinte.hubrightnessbridge.domain.BridgePolicy.State;
import com.meinte.hubrightnessbridge.domain.BridgePolicy.Write;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class BridgePolicyTest {
    private static Write w(String k, int v) {
        return new Write(k, v);
    }

    @Test
    public void keysAreTheFrameworkStrings() {
        assertEquals("reduce_bright_colors_activated", KEY_ACTIVATED);
        assertEquals("reduce_bright_colors_level", KEY_LEVEL);
        assertEquals("reduce_bright_colors_persist_across_reboots", KEY_PERSIST);
    }

    @Test
    public void nullTargetWritesNothing() {
        assertEquals(Collections.emptyList(),
                BridgePolicy.plan(new State(UNSET, UNSET, UNSET), null));
    }

    @Test
    public void freshDeviceGetsPersistThenLevelThenActivated() {
        List<Write> p = BridgePolicy.plan(new State(UNSET, UNSET, UNSET), DimTarget.on(40));
        assertEquals(Arrays.asList(w(KEY_PERSIST, 1), w(KEY_LEVEL, 40), w(KEY_ACTIVATED, 1)), p);
    }

    @Test
    public void alreadyInPlaceIsIdempotent() {
        assertEquals(Collections.emptyList(),
                BridgePolicy.plan(new State(1, 40, 1), DimTarget.on(40)));
        assertEquals(Collections.emptyList(),
                BridgePolicy.plan(new State(0, 40, 1), DimTarget.OFF));
    }

    @Test
    public void levelChangeWhileOnWritesOnlyTheLevel() {
        assertEquals(Collections.singletonList(w(KEY_LEVEL, 41)),
                BridgePolicy.plan(new State(1, 40, 1), DimTarget.on(41)));
    }

    @Test
    public void turningOnFromOffKeepsLevelBeforeActivation() {
        assertEquals(Arrays.asList(w(KEY_LEVEL, 73), w(KEY_ACTIVATED, 1)),
                BridgePolicy.plan(new State(0, 20, 1), DimTarget.on(73)));
    }

    @Test
    public void offWritesOnlyActivatedAndKeepsTheLevel() {
        assertEquals(Collections.singletonList(w(KEY_ACTIVATED, 0)),
                BridgePolicy.plan(new State(1, 40, 1), DimTarget.OFF));
    }

    @Test
    public void missingPersistIsRepairedAlone() {
        assertEquals(Collections.singletonList(w(KEY_PERSIST, 1)),
                BridgePolicy.plan(new State(1, 40, 0), DimTarget.on(40)));
    }

    @Test
    public void manualAccessibilityStateIsOverriddenOnlyWhenPlanIsCalled() {
        // The user switched Extra Dim off by hand while the slider says level 40.
        State manual = new State(0, 40, 1);
        // The edge calls plan() only on a slider change or at boot; when it does,
        // the slider re-asserts. That is the documented "most recent action wins".
        assertEquals(Collections.singletonList(w(KEY_ACTIVATED, 1)),
                BridgePolicy.plan(manual, DimTarget.on(40)));
    }

    @Test
    public void neverWritesScreenBrightness() {
        State[] states = {
            new State(UNSET, UNSET, UNSET), new State(0, 0, 0), new State(1, 100, 1),
        };
        DimTarget[] targets = {DimTarget.OFF, DimTarget.on(1), DimTarget.on(100), null};
        for (State s : states) {
            for (DimTarget t : targets) {
                for (Write write : BridgePolicy.plan(s, t)) {
                    assertFalse("loop hazard: " + write, write.key.startsWith("screen_brightness"));
                    assertTrue(write.key.startsWith("reduce_bright_colors_"));
                }
            }
        }
    }

    @Test
    public void everyPlanReachesTheTarget() {
        int[] vals = {UNSET, 0, 1, 40, 100};
        DimTarget[] targets = {DimTarget.OFF, DimTarget.on(1), DimTarget.on(55), DimTarget.on(100)};
        for (int a : vals) {
            for (int l : vals) {
                for (int p : vals) {
                    for (DimTarget t : targets) {
                        int na = a;
                        int nl = l;
                        int np = p;
                        for (Write write : BridgePolicy.plan(new State(a, l, p), t)) {
                            if (write.key.equals(KEY_ACTIVATED)) {
                                na = write.value;
                            } else if (write.key.equals(KEY_LEVEL)) {
                                nl = write.value;
                            } else {
                                np = write.value;
                            }
                        }
                        assertEquals(1, np);
                        assertEquals(t.activated ? 1 : 0, na);
                        if (t.activated) {
                            assertEquals(t.level, nl);
                        } else {
                            assertEquals("off must not touch the level", l, nl);
                        }
                        // Applying the plan again is a no-op.
                        assertEquals(Collections.emptyList(),
                                BridgePolicy.plan(new State(na, nl, np), t));
                    }
                }
            }
        }
    }
}

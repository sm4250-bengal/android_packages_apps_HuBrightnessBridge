// Copyright (C) 2026 The XYAUTO device-3 project
// SPDX-License-Identifier: Apache-2.0
package com.meinte.hubrightnessbridge.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides which Settings.Secure writes move Extra Dim from its current state to a
 * {@link DimTarget}. Pure; the Android edge only reads the current values in and
 * performs the returned writes in order.
 *
 * <p>WHO WINS AGAINST THE ACCESSIBILITY TOGGLE. The edge calls {@link #plan} only
 * when screen_brightness changes and once when the bridge starts (boot). It never
 * observes the reduce_bright_colors keys, so it cannot undo a manual Extra Dim
 * change on its own. Result: the most recent user action wins. A manual toggle or
 * intensity change holds until the user next moves the brightness slider or the
 * unit reboots; then the slider, which is the primary brightness control on this
 * head unit, re-asserts. There is no background tug-of-war because only one side
 * ever reacts.
 *
 * <p>WHY PERSIST IS WRITTEN. ColorDisplayService.resetReduceBrightColors() switches
 * Extra Dim off once at user setup unless reduce_bright_colors_persist_across_reboots
 * is 1. The bridge is a persistent app and may start before or after that setup,
 * so without persist its boot re-assert races the reset. Writing persist first
 * removes the race. Side effect: the Extra Dim page shows "Keep on after restart" on.
 *
 * <p>ORDER: persist, then level, then activated, so activation happens with the final
 * level already set and the first matrix is the right one.
 *
 * <p>NEVER WRITTEN: screen_brightness. The bridge only reads it, which is what keeps
 * the observer loop-free (a write there would re-trigger the observer).
 */
public final class BridgePolicy {
    /** Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED (@hide), verbatim. */
    public static final String KEY_ACTIVATED = "reduce_bright_colors_activated";
    /** Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL (@hide), verbatim. */
    public static final String KEY_LEVEL = "reduce_bright_colors_level";
    /** Settings.Secure.REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS (@hide), verbatim. */
    public static final String KEY_PERSIST = "reduce_bright_colors_persist_across_reboots";

    /** Value of a setting that is absent. */
    public static final int UNSET = -1;

    /** The three settings as currently stored; {@link #UNSET} when absent. */
    public static final class State {
        public final int activated;
        public final int level;
        public final int persist;

        public State(int activated, int level, int persist) {
            this.activated = activated;
            this.level = level;
            this.persist = persist;
        }
    }

    /** One Settings.Secure.putInt. */
    public static final class Write {
        public final String key;
        public final int value;

        public Write(String key, int value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Write)) {
                return false;
            }
            Write w = (Write) o;
            return key.equals(w.key) && value == w.value;
        }

        @Override
        public int hashCode() {
            return key.hashCode() * 31 + value;
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    private BridgePolicy() {}

    /** Minimal ordered writes; empty when {@code target} is null or already in place. */
    public static List<Write> plan(State current, DimTarget target) {
        if (target == null) {
            return Collections.emptyList();
        }
        List<Write> writes = new ArrayList<>(3);
        if (current.persist != 1) {
            writes.add(new Write(KEY_PERSIST, 1));
        }
        if (target.activated && current.level != target.level) {
            writes.add(new Write(KEY_LEVEL, target.level));
        }
        int wantActivated = target.activated ? 1 : 0;
        if (current.activated != wantActivated) {
            writes.add(new Write(KEY_ACTIVATED, wantActivated));
        }
        return Collections.unmodifiableList(writes);
    }
}

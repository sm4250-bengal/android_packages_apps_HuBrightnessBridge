// Copyright (C) 2026 The XYAUTO device-3 project
// SPDX-License-Identifier: Apache-2.0
package com.meinte.hubrightnessbridge.domain;

/**
 * The Extra Dim state the brightness slider asks for. Immutable; no android.*.
 *
 * <p>{@link #OFF} deliberately carries no level: switching Extra Dim off leaves
 * reduce_bright_colors_level untouched, so the accessibility intensity keeps
 * the last value and the bridge writes one key instead of two.
 */
public final class DimTarget {
    /** Level value meaning "do not write the level". */
    public static final int NO_LEVEL = -1;

    public static final DimTarget OFF = new DimTarget(false, NO_LEVEL);

    public final boolean activated;
    /** reduce_bright_colors_level percentage 1..100 when activated, else {@link #NO_LEVEL}. */
    public final int level;

    private DimTarget(boolean activated, int level) {
        this.activated = activated;
        this.level = level;
    }

    /** An activated target; {@code level} must be 1..{@link DimCurve#LEVEL_MAX}. */
    public static DimTarget on(int level) {
        if (level < 1 || level > DimCurve.LEVEL_MAX) {
            throw new IllegalArgumentException("level out of range: " + level);
        }
        return new DimTarget(true, level);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DimTarget)) {
            return false;
        }
        DimTarget t = (DimTarget) o;
        return activated == t.activated && level == t.level;
    }

    @Override
    public int hashCode() {
        return (activated ? 1000 : 0) + level;
    }

    @Override
    public String toString() {
        return activated ? "extra-dim on level=" + level : "extra-dim off";
    }
}

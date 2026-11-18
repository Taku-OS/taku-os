package org.agentos.surfaces;

import java.util.function.BooleanSupplier;

/** Query current device state; waking without a keyguard does not send USER_PRESENT. */
final class SurfaceVisibility {
    private final BooleanSupplier interactive;
    private final BooleanSupplier locked;

    SurfaceVisibility(BooleanSupplier interactive, BooleanSupplier locked) {
        this.interactive=interactive;
        this.locked=locked;
    }

    boolean shouldShow(boolean hasEntries) {
        return hasEntries && interactive.getAsBoolean() && !locked.getAsBoolean();
    }
}

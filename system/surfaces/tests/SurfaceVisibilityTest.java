package org.agentos.surfaces;

/** Lifecycle regressions for a service that survives display sleep and wake. */
public final class SurfaceVisibilityTest {
    private static int assertions;
    private static void check(boolean value) {
        assertions++;
        if (!value) throw new AssertionError("Visibility check "+assertions);
    }
    public static void main(String[] args) {
        boolean[] device={true,false};
        SurfaceVisibility visibility=new SurfaceVisibility(()->device[0],()->device[1]);
        check(visibility.shouldShow(true));
        device[0]=false; // SCREEN_OFF while the bound service remains alive.
        check(!visibility.shouldShow(true));
        device[0]=true; // Wake on an unlocked device: no USER_PRESENT event follows.
        check(visibility.shouldShow(true));
        device[0]=false;
        device[1]=true;
        check(!visibility.shouldShow(true));
        device[0]=true; // SCREEN_ON must never expose a card over the keyguard.
        check(!visibility.shouldShow(true));
        device[1]=false;
        check(visibility.shouldShow(true));
        check(!visibility.shouldShow(false)); // Expired or withdrawn while asleep.
        device[0]=false;
        check(!visibility.shouldShow(false));
        device[0]=true;
        check(!visibility.shouldShow(false));
        check(visibility.shouldShow(true)); // A new publication reads the current state.
        System.out.println(assertions+" surface visibility assertions passed");
    }
}

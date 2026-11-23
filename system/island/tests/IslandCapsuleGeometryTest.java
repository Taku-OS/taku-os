/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
import com.android.systemui.taku.island.IslandCapsuleGeometry;

public final class IslandCapsuleGeometryTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // Synthetic px fixture for a 430px Reading image capsule and a crowded 261px end group.
        int maximum = IslandCapsuleGeometry.maximumWidth(64, 1016, 540, 143, 143, 261, 169, 33);
        check(maximum >= 430, "collapsible icons must not prevent the reading capsule from fitting");
        check(maximum == IslandCapsuleGeometry.maximumWidth(64, 1016, 540, 143, 143, 461, 369, 33),
                "adding collapsible icons must not reduce the capsule budget");
        check(maximum == IslandCapsuleGeometry.maximumWidth(64, 1016, 540, 143, 143, 92, 0, 33),
                "native icon collapse must not feed back into the capsule budget");

        int reserved = IslandCapsuleGeometry.reservationWidth(64, 1016, 540, 143, 430);
        check(540 - reserved / 2 <= 540 - 143 && 540 + reserved / 2 >= 540 - 143 + 430,
                "centered native reservation must contain the asymmetric capsule");
        check(540 - reserved / 2 >= 64 + 143 + 33 && 540 + reserved / 2 <= 1016 - 92 - 33,
                "clock and fixed end content must remain outside the reservation");
        check(IslandCapsuleGeometry.maximumWidth(64, 1016, 540, 143, 143, 469, 169, 33) < maximum,
                "a larger non-collapsible end region must reduce the budget");
        check(IslandCapsuleGeometry.maximumWidth(0, 0, 0, 143, 143, 261, 169, 33) == 0,
                "an unmeasured bar must not show the capsule");
        check(IslandCapsuleGeometry.maximumWidth(64, 1016, 100, 143, 143, 261, 169, 33) == 0,
                "camera disc must not overlap protected edge content");
        check(IslandCapsuleGeometry.reservationWidth(64, 1016, 540, 143, 0) == 0,
                "hidden capsule must release its reservation");

        // Safe insets/unequal padding and odd dimensions must preserve the physical camera anchor.
        for (int right : new int[] {1001, 1016}) {
            int left = 81, camera = 540, disc = 143, clock = 143, fixed = 92, gap = 33;
            int width = IslandCapsuleGeometry.maximumWidth(left, right, camera, disc, clock, fixed, 0, gap);
            int probe = IslandCapsuleGeometry.reservationWidth(left, right, camera, disc, width);
            int center = left + (right - left) / 2;
            check(width > 0 && center - probe / 2 <= camera - disc
                            && center + probe / 2 >= camera - disc + width,
                    "padded native slot must cover the camera-anchored capsule");
            check(center - probe / 2 >= left + clock + gap && center + probe / 2 <= right - fixed - gap,
                    "padded slot must leave mandatory content visible");
            check(width == IslandCapsuleGeometry.maximumWidth(left, right, camera, disc, fixed, clock, 0, gap),
                    "reversing start/end in RTL must preserve the symmetric budget");
        }
        System.out.println("IslandCapsuleGeometryTest: passed");
    }
}

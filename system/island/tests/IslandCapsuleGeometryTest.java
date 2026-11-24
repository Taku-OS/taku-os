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
        // The generated neck reproduces Figma's MetaballShape1 for the design's 24 dp camera:
        // disc r16 at x=0, camera r12 at x=36, 4 dp insets. Authored path (box 16.0743 × 21.3333,
        // centred at y=10.6667, starting at x=12): M16.0743 1.72239 C10.9102 6.34134 6.1586 6.88552 0 0.
        float[] n = IslandCapsuleGeometry.neck(16, 12, 36, 4);
        float[] authored = {0, -10.6667f, 6.1586f, 6.88552f - 10.6667f, 10.9102f, 6.34134f - 10.6667f, 16.0743f, 1.72239f - 10.6667f};
        for (int i = 0; i < 8; i++) {
            float actual = n[i] - (i % 2 == 0 ? 12 : 0);
            check(Math.abs(actual - authored[i]) < 0.25f, "neck point " + i + " is " + actual + ", design " + authored[i]);
        }
        // A genuinely large physical hole (46 dp) keeps an open, smooth neck at both circles.
        float[] big = IslandCapsuleGeometry.neck(16, 23, 47, 4);
        check(big[0] < big[2] && big[2] < big[6] && big[4] < big[6], "handles stay between the circles");
        check(big[3] < 0 && big[5] < 0, "the neck never pinches through the centre line");
        check(Math.abs(big[7] + Math.sqrt(23 * 23 - 19 * 19)) < 0.01, "the neck ends on the camera circle");
        System.out.println("IslandCapsuleGeometryTest: passed");
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** A dedicated handle gesture never becomes a stop, resize, or paid-start action. */
final class ShoppingIslandGesture {
    private ShoppingIslandGesture() {}
    static boolean collapse(float dx, float dy, float threshold) {
        return dy <= -Math.max(1, threshold) && Math.abs(dy) > Math.abs(dx) * 1.2f;
    }
    static boolean expand(float dx, float dy, float threshold) {
        return dy >= Math.max(1, threshold) && Math.abs(dy) > Math.abs(dx) * 1.2f;
    }
}

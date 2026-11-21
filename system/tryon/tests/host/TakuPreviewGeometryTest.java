// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

public final class TakuPreviewGeometryTest {
    private static int assertions;
    public static void main(String[] args) {
        // The framework already makes the sensor upright. A second 90/270 degree turn is a regression.
        for (int sensor : new int[]{0, 90, 180, 270}) {
            float[] upright = TakuPreviewGeometry.contain(400, 500, 640, 480, sensor, 0, false);
            near(upright[1], 0); near(upright[3], 0); check(upright[0] > 0 && upright[4] > 0);
        }
        float[] portrait = TakuPreviewGeometry.contain(400, 500, 640, 480, 90, 0, false);
        near(x(portrait, 0, 0), 12.5f); near(x(portrait, 400, 500), 387.5f);
        near(y(portrait, 0, 0), 0); near(y(portrait, 400, 500), 500);
        float[] mirrored = TakuPreviewGeometry.contain(400, 500, 640, 480, 270, 0, true);
        near(x(mirrored, 0, 0), 387.5f); near(x(mirrored, 400, 500), 12.5f);
        near(y(mirrored, 0, 0), 0); near(y(mirrored, 400, 500), 500);
        for (int sensor : new int[]{0, 90, 180, 270}) for (int rotation : new int[]{0, 90, 180, 270}) {
            for (int[] size : new int[][]{{400, 500}, {500, 400}, {300, 800}, {800, 300}}) for (boolean mirror : new boolean[]{false, true}) {
                int width = size[0], height = size[1];
                float[] transform = TakuPreviewGeometry.contain(width, height, 640, 480, sensor, rotation, mirror);
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int[] corner : new int[][]{{0, 0}, {width, 0}, {0, height}, {width, height}}) {
                    float x = x(transform, corner[0], corner[1]), y = y(transform, corner[0], corner[1]);
                    check(x >= -.001 && x <= width + .001 && y >= -.001 && y <= height + .001);
                    minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
                near((minX + maxX) / 2, width / 2f); near((minY + maxY) / 2, height / 2f);
                // Whole image fits, touches at least one boundary pair and retains the expected aspect ratio.
                check(Math.abs(maxX - minX - width) < .001 || Math.abs(maxY - minY - height) < .001);
                near((maxX - minX) / (maxY - minY), (sensor - rotation + 360) % 180 == 0 ? 4f / 3f : 3f / 4f);
                float determinant = transform[0] * transform[4] - transform[1] * transform[3];
                check(mirror ? determinant < 0 : determinant > 0);
            }
        }
        float[] rotate = TakuPreviewGeometry.contain(500, 400, 640, 480, 90, 90, false);
        check(rotate[1] > 0 && rotate[3] < 0); // Display is compensated counterclockwise only.
        boolean invalid = false;
        try { TakuPreviewGeometry.contain(0, 1, 640, 480, 90, 0, false); }
        catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid);
        System.out.println("TakuPreviewGeometryTest: " + assertions + " assertions passed");
    }
    private static float x(float[] matrix, float x, float y) { return matrix[0] * x + matrix[1] * y + matrix[2]; }
    private static float y(float[] matrix, float x, float y) { return matrix[3] * x + matrix[4] * y + matrix[5]; }
    private static void near(float actual, float expected) { check(Math.abs(actual - expected) < .001f); }
    private static void check(boolean condition) { assertions++; if (!condition) throw new AssertionError("assertion " + assertions); }
}

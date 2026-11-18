package org.agentos.island;

/** Native port of the designer's dot-matrix-loader/pose.ts thinking loop.
 * Shared by SystemUI and Compose; geometry is fixed, only opacity/scale change.
 * Source/provenance and the verbatim reference are in system/island/design/dot-matrix-loader.
 */
public final class DotMatrixLoader {
    public static final long CYCLE_MS = 1000;
    private static final int[] CLOCKWISE = {1, 2, 5, 8, 7, 6, 3, 0};
    private static final double[] REST = {.92, .48, .24, .92, .8, .64, .92, .48, .24};
    public static final class Pose {
        public final double[] opacity = new double[9];
        public final double[] scale = new double[9];
    }
    private DotMatrixLoader() {}
    public static Pose rest(Pose out) {
        for (int i = 0; i < 9; i++) { out.opacity[i] = REST[i]; out.scale[i] = 1; }
        return out;
    }
    public static Pose thinking(double timeMs, Pose out) {
        double now = wrap(timeMs);
        for (int i = 0; i < 9; i++) { out.opacity[i] = i == 4 ? .86 : .08; out.scale[i] = 1; }
        for (int step = 0; step < 8; step++) {
            double since = wrap(now - step * 125);
            hit(out, CLOCKWISE[step], since, .66, 40, 50, 110, .03);
            hit(out, 4, since, .92, 40, 0, 80, 0);
        }
        for (int i = 0; i < 9; i++) out.scale[i] = Math.min(1.1, Math.max(.85, out.scale[i]));
        return out;
    }
    private static double wrap(double value) { return ((value % CYCLE_MS) + CYCLE_MS) % CYCLE_MS; }
    private static void hit(Pose out, int dot, double since, double peak, double attack,
            double hold, double decay, double grow) {
        double level;
        if (since < attack) { double x = since / attack; level = 1 - (1 - x) * (1 - x); }
        else {
            double fading = since - attack - hold;
            double x = fading / decay;
            level = fading <= 0 ? 1 : x < 1 ? Math.pow(1 - x, 1.6) : 0;
        }
        if (level <= 0) return;
        double base = dot == 4 ? .86 : .08;
        out.opacity[dot] = Math.max(out.opacity[dot], base + (peak - base) * level);
        out.scale[dot] = Math.max(out.scale[dot], 1 + grow * level);
    }
}

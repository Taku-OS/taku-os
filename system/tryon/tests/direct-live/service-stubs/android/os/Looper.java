// SPDX-License-Identifier: Apache-2.0
package android.os;

public final class Looper {
    public static boolean main = true;
    private static final Looper MAIN = new Looper(), OTHER = new Looper();
    public static Looper myLooper() { return main ? MAIN : OTHER; }
    public static Looper getMainLooper() { return MAIN; }
}

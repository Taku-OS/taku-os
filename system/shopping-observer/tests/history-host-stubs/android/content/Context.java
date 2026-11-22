// SPDX-License-Identifier: Apache-2.0
package android.content;

/** Minimal host adapter for the production history class, not an Android runtime. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}

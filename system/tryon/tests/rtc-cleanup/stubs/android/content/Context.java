// SPDX-License-Identifier: Apache-2.0
package android.content;
/** Test-only application context. No device storage or credentials. */
public class Context {
    public Context getApplicationContext() { return this; }
    public java.io.File getFilesDir() { throw new AssertionError("device persistence is not part of this actor fixture"); }
}

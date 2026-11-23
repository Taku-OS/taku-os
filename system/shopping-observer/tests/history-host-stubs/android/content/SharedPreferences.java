// SPDX-License-Identifier: Apache-2.0
package android.content;

/** Only the operations used by ObservationHistory are needed in this host fixture. */
public interface SharedPreferences {
    long getLong(String key, long fallback);
    String getString(String key, String fallback);
    default boolean getBoolean(String key, boolean fallback) { return fallback; }
    Editor edit();
    interface Editor {
        default Editor putBoolean(String key, boolean value) { throw new UnsupportedOperationException(); }
        Editor putLong(String key, long value);
        Editor putString(String key, String value);
        void apply();
        boolean commit();
    }
}

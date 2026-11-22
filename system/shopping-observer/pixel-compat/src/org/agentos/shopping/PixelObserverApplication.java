// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

/** Local compatibility build only. The normal app keeps the native notification implementation. */
public final class PixelObserverApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        LiveFrameProgressHost.installFactory(new PixelProgressHost.Factory());
    }
}

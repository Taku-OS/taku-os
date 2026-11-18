/* SPDX-License-Identifier: Apache-2.0 */
package com.android.server.pm;

import android.content.Intent;
import android.content.pm.ResolveInfo;

import java.util.List;

/** Product HOME policy, evaluated against the caller/user's already filtered candidates. */
final class TakuHomePolicy {
    static final String PACKAGE = "org.agentos.agent";
    static final String ACTIVITY = "org.agentos.agent.home.HomeActivity";

    private TakuHomePolicy() {}

    static ResolveInfo resolve(String configuredPackage, Intent intent, String resolvedType,
            List<ResolveInfo> candidates) {
        if (!PACKAGE.equals(configuredPackage) || candidates == null
                || !Intent.ACTION_MAIN.equals(intent.getAction())
                || !intent.hasCategory(Intent.CATEGORY_HOME)
                || intent.getComponent() != null || intent.getPackage() != null
                || intent.getSelector() != null || intent.getData() != null
                || resolvedType != null) {
            return null;
        }
        // Never redirect explicit/secondary-home requests or unrelated MAIN categories.
        for (String category : intent.getCategories()) {
            if (!Intent.CATEGORY_HOME.equals(category)
                    && !Intent.CATEGORY_DEFAULT.equals(category)) return null;
        }
        ResolveInfo home = null;
        int highestPriority = Integer.MIN_VALUE;
        for (ResolveInfo candidate : candidates) {
            highestPriority = Math.max(highestPriority, candidate.priority);
            if (candidate.activityInfo != null
                    && PACKAGE.equals(candidate.activityInfo.packageName)
                    && ACTIVITY.equals(candidate.activityInfo.name)
                    && candidate.activityInfo.exported && candidate.activityInfo.enabled
                    && candidate.activityInfo.applicationInfo.enabled) {
                home = candidate;
            }
        }
        // Preserve setup wizard's higher-priority HOME and locked-user FallbackHome.
        // If Taku is absent/disabled/not visible to this user, leave Android recovery intact.
        return home != null && home.priority >= highestPriority ? home : null;
    }
}

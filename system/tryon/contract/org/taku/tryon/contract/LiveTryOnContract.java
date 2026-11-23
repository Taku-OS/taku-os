// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.contract;

/** App-owned LIVE handoff. Tickets are source-issued, single-use and never part of public status. */
public final class LiveTryOnContract {
    public static final String SOURCE_PACKAGE = "org.agentos.shopping";
    public static final String TARGET_PACKAGE = "org.taku.tryon";
    public static final String SOURCE_AUTHORITY = "org.agentos.shopping.api";
    public static final String SOURCE_API = "content://" + SOURCE_AUTHORITY;
    public static final String ACTION_TRY_ON = "org.taku.tryon.action.LIVE_TRY_ON";
    public static final String REDEEM = "redeem";
    public static final String REFERENCE_URI = "referenceUri";
    public static final String ADMISSION_TOKEN = "admissionToken";
    public static final String REQUEST_ID = "requestId";
    public static final String EXPIRES_AT_ELAPSED_MS = "expiresAtElapsedMs";
    public static final String CATEGORY = "category";
    public static final String PROMPT = "prompt";
    public static final long ADMISSION_TTL_MS = 30_000L;
    public static final long REFERENCE_TTL_MS = 120_000L;
    private LiveTryOnContract() {}
}

// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.util.Map;

/** One media segment. The service owns camera leases, handoff, and the overall deadline. */
interface RealtimeCallTransport {
    void start(String credential, String prompt, String referenceDataUriOrNull);
    boolean update(String prompt, String referenceDataUriOrNull, CallTryOnRtc.UpdateListener completion);
    String updateAvailability();
    void sendFrame(byte[] jpeg, int width, int height, long copiedNs);
    void stop();
    Map<String, Object> diagnosticSnapshot();
}

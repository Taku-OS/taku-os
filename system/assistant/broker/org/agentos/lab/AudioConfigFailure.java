// SPDX-License-Identifier: Apache-2.0
package org.agentos.lab;

import java.io.IOException;

/** A config-socket failure with an Audio Engine reason code (e.g. PROVIDER_NOT_CONFIGURED, POLICY_REVOKED). */
final class AudioConfigFailure extends IOException {
    final String code;
    AudioConfigFailure(String code, String message) { super(message); this.code = code; }
}

// SPDX-License-Identifier: Apache-2.0
export async function run(client) {
  // Real responses only. Preserve per-method failure instead of fabricating success.
  const report = {extension: 'device-report', generatedAt: new Date().toISOString(), observations: {}, errors: {}, boundaries: ['emulator evidence only', 'no model task submitted', 'no app prompts/results collected', 'not an exported ROM or physical-device acceptance']};
  for (const method of ['system.info', 'displays.status', 'tasks.status']) {
    try { report.observations[method] = await client.call(method); }
    catch (error) { report.errors[method] = {code: error.code, message: error.message, retryable: error.retryable}; }
  }
  return report;
}

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');
const process = require('node:process');

const appRoot = path.resolve(__dirname, '..');
const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

// No advisory exceptions: accept only a complete report with zero findings at every severity.
function evaluateAudit(report) {
  const findings = report?.vulnerabilities;
  const counts = report?.metadata?.vulnerabilities;
  const levels = ['info', 'low', 'moderate', 'high', 'critical'];
  if ((report && Object.hasOwn(report, 'error')) || report?.auditReportVersion !== 2 || !findings || Array.isArray(findings) ||
      typeof findings !== 'object' || !counts ||
      levels.some((level) => !Number.isInteger(counts[level]) || counts[level] < 0) ||
      counts.total !== levels.reduce((sum, level) => sum + counts[level], 0) ||
      counts.total !== Object.keys(findings).length) {
    throw new Error('Incomplete or unsupported npm audit report; failing closed.');
  }
  if (counts.total !== 0) throw new Error(`npm audit reported ${counts.total} vulnerabilities; no exceptions are allowed.`);
  return {reported: 0, waived: 0};
}

function runAudit() {
  const output = path.join(appRoot, 'out/security');
  fs.mkdirSync(output, {recursive: true});
  for (const file of ['npm-audit-decision.json', 'android-audit-boundary.json']) {
    fs.rmSync(path.join(output, file), {force: true});
  }
  const result = spawnSync('npm', ['audit', '--json', '--package-lock-only',
    '--include=dev', '--include=optional', '--include=peer', '--registry=https://registry.npmjs.org'],
  {cwd: appRoot, encoding: 'utf8', timeout: 120000, maxBuffer: 20 * 1024 * 1024});
  // Preserve npm's complete, unfiltered report even when the command fails.
  fs.writeFileSync(path.join(output, 'npm-audit.json'), result.stdout ?? '');
  if (result.error || result.signal || ![0, 1].includes(result.status)) {
    throw new Error('npm audit did not complete successfully.');
  }
  const report = JSON.parse(result.stdout);
  const lockBytes = fs.readFileSync(path.join(appRoot, 'package-lock.json'));
  const decision = evaluateAudit(report);
  if (result.status !== 0) throw new Error('npm audit returned a nonzero exit status for a clean report.');
  fs.writeFileSync(path.join(output, 'npm-audit-decision.json'), JSON.stringify({
    ...decision, checkedAt: new Date().toISOString(), lockfileSha256: sha256(lockBytes),
    reportSha256: sha256(result.stdout),
  }, null, 2) + '\n');
  console.log(JSON.stringify(decision, null, 2));
}

if (require.main === module) {
  try { runAudit(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = {appRoot, evaluateAudit, sha256};

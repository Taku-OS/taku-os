const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');
const process = require('node:process');

const appRoot = path.resolve(__dirname, '..');
const advisory = 'https://github.com/advisories/GHSA-fc5q-39xc-vjpf';
const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

// Ordering in npm's dependency graph is not significant.
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).sort().join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function normalizedFindings(findings) {
  return Object.fromEntries(Object.entries(findings).map(([name, finding]) => {
    // npm's cached transitive affected ranges and suggested downgrades vary
    // between runs. Actual installed versions are bound by the lock digest.
    // Preserve every direct advisory field (including its affected range),
    // affected package path, severity, and dependency edge; deduplicate edges.
    const fields = ['name', 'severity', 'isDirect', 'via', 'effects', 'nodes', 'range', 'fixAvailable'];
    if (!finding || finding.name !== name || typeof finding.isDirect !== 'boolean' ||
        Object.keys(finding).some((key) => !fields.includes(key)) ||
        !Array.isArray(finding.via) || !finding.via.length ||
        !Array.isArray(finding.nodes) || !finding.nodes.length ||
        !Array.isArray(finding.effects) ||
        [...finding.nodes, ...finding.effects].some((value) => typeof value !== 'string')) {
      throw new Error('Incomplete or unsupported npm audit finding.');
    }
    return [name, {name, severity: finding.severity, isDirect: finding.isDirect,
      nodes: [...new Set(finding.nodes)], effects: [...new Set(finding.effects)],
      via: [...new Set(finding.via.map(canonical))].map((value) => JSON.parse(value)),
    }];
  }));
}

function checkException(policy, lockBytes, now = Date.now()) {
  if (!Number.isFinite(now) || policy.advisory !== advisory || policy.accepted !== true ||
      !Number.isFinite(Date.parse(policy.acceptedAt)) || !Number.isFinite(Date.parse(policy.expiresAt)) ||
      Date.parse(policy.expiresAt) <= Date.parse(policy.acceptedAt) ||
      Date.parse(policy.expiresAt) - Date.parse(policy.acceptedAt) > 31 * 86400000 ||
      now < Date.parse(policy.acceptedAt) || now >= Date.parse(policy.expiresAt)) {
    throw new Error('Audit exception is invalid, not accepted, or expired; explicit review is required.');
  }
  if (sha256(lockBytes) !== policy.lockfileSha256) {
    throw new Error('Dependency lock changed; the audit exception requires a new review.');
  }
}

function evaluateAudit(report, lockBytes, policy, now = Date.now()) {
  checkException(policy, lockBytes, now);
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
  if (counts.total === 0) return {reported: 0, waived: 0};
  const direct = Object.values(findings).flatMap((finding) => finding.via ?? [])
    .filter((cause) => cause && typeof cause === 'object');
  if (!direct.length || direct.some((cause) => cause.url !== advisory || cause.name !== 'picomatch') ||
      counts.total !== policy.affectedEntries || sha256(canonical(normalizedFindings(findings))) !== policy.findingsSha256) {
    throw new Error('npm audit findings differ from the accepted picomatch-only graph; review required.');
  }
  return {reported: counts.total, waived: counts.total, advisory, expiresAt: policy.expiresAt};
}

function readException() {
  return JSON.parse(fs.readFileSync(path.join(appRoot, 'security/npm-audit-exception.json'), 'utf8'));
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
    throw new Error('npm audit did not complete successfully; no exception applied.');
  }
  const report = JSON.parse(result.stdout);
  const lockBytes = fs.readFileSync(path.join(appRoot, 'package-lock.json'));
  const decision = evaluateAudit(report, lockBytes, readException());
  fs.writeFileSync(path.join(output, 'npm-audit-decision.json'), JSON.stringify({
    ...decision, checkedAt: new Date().toISOString(), lockfileSha256: sha256(lockBytes),
    reportSha256: sha256(result.stdout), findingsSha256: sha256(canonical(normalizedFindings(report.vulnerabilities))),
  }, null, 2) + '\n');
  console.log(JSON.stringify(decision, null, 2));
  if (decision.waived) console.warn('Known tooling risk accepted temporarily; this is not a clean vulnerability report. Android bundle validation is also required.');
}

if (require.main === module) {
  try { runAudit(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = {appRoot, canonical, checkException, evaluateAudit, normalizedFindings, readException, sha256};

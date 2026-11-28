const assert = require('node:assert/strict');
const {test} = require('node:test');
const {canonical, evaluateAudit, normalizedFindings, sha256} = require('./audit-dependencies.cjs');

const now = Date.parse('2026-11-19T00:00:00Z');
const lock = 'reviewed lock bytes';
const advisory = 'https://github.com/advisories/GHSA-fc5q-39xc-vjpf';
function fixture() {
  const report = {auditReportVersion: 2, vulnerabilities: {
    picomatch: {name: 'picomatch', severity: 'high', isDirect: false, effects: ['consumer'], via: [{name: 'picomatch', url: advisory, range: '<=4.0.7'}], nodes: ['node_modules/picomatch']},
    consumer: {name: 'consumer', severity: 'high', isDirect: true, effects: [], via: ['picomatch'], nodes: ['node_modules/consumer']},
  }, metadata: {vulnerabilities: {info: 0, low: 0, moderate: 0, high: 2, critical: 0, total: 2}}};
  const policy = {advisory, accepted: true, acceptedAt: '2026-11-18T00:00:00Z',
    expiresAt: '2026-12-18T00:00:00Z', lockfileSha256: sha256(lock),
    findingsSha256: sha256(canonical(normalizedFindings(report.vulnerabilities))), affectedEntries: 2};
  return {report, policy};
}

test('waives only the reviewed graph and preserves its reported count', () => {
  const {report, policy} = fixture();
  assert.deepEqual(evaluateAudit(report, lock, policy, now), {reported: 2, waived: 2, advisory, expiresAt: policy.expiresAt});
});

test('finding order does not change the accepted graph', () => {
  const {report, policy} = fixture();
  report.vulnerabilities = Object.fromEntries(Object.entries(report.vulnerabilities).reverse());
  assert.equal(evaluateAudit(report, lock, policy, now).waived, 2);
});

test('normalizes duplicate edges and cached transitive upgrade advice only', () => {
  const {report, policy} = fixture();
  report.vulnerabilities.consumer.via.push('picomatch');
  report.vulnerabilities.consumer.range = '>=0.80.0';
  report.vulnerabilities.consumer.fixAvailable = {name: 'consumer', version: '0.1.0', isSemVerMajor: true};
  assert.equal(evaluateAudit(report, lock, policy, now).waived, 2);
});

test('rejects a new advisory even on the same package or at low severity', () => {
  for (const severity of ['low', 'high', 'critical']) {
    const {report, policy} = fixture();
    report.vulnerabilities.picomatch.via.push({name: 'picomatch', url: 'https://github.com/advisories/GHSA-new-finding', severity});
    assert.throws(() => evaluateAudit(report, lock, policy, now), /differ/);
  }
});

test('rejects changed affected paths or changed advisory details', () => {
  for (const change of [
    (r) => r.vulnerabilities.picomatch.nodes.push('node_modules/new/picomatch'),
    (r) => { r.vulnerabilities.picomatch.via[0].range = '*'; },
    (r) => { r.vulnerabilities.consumer.via = ['another-package']; },
  ]) {
    const {report, policy} = fixture(); change(report);
    assert.throws(() => evaluateAudit(report, lock, policy, now), /differ/);
  }
});

test('rejects any dependency-lock change', () => {
  const {report, policy} = fixture();
  assert.throws(() => evaluateAudit(report, lock + '\n', policy, now), /lock changed/);
});

test('expires at the exact UTC deadline and does not allow future or unaccepted policies', () => {
  const {report, policy} = fixture();
  assert.equal(evaluateAudit(report, lock, policy, Date.parse(policy.expiresAt) - 1).waived, 2);
  for (const date of [Date.parse(policy.expiresAt), Date.parse(policy.expiresAt) + 1, Date.parse(policy.acceptedAt) - 1]) {
    assert.throws(() => evaluateAudit(report, lock, policy, date), /expired/);
  }
  for (const changes of [{accepted: false}, {expiresAt: 'invalid'}, {expiresAt: '2027-12-18T00:00:00Z'}, {advisory: 'another'}]) {
    assert.throws(() => evaluateAudit(report, lock, {...policy, ...changes}, now), /invalid/);
  }
});

test('fails closed on registry errors, partial reports, and inconsistent counts', () => {
  const {report, policy} = fixture();
  for (const bad of [null, {}, {error: {code: 'EAUDITNOLOCK'}}, {...report, error: {code: 'E503'}},
    {...report, auditReportVersion: 1}, {...report, vulnerabilities: []},
    {...report, metadata: {vulnerabilities: {...report.metadata.vulnerabilities, total: 0}}},
    {...report, metadata: {vulnerabilities: {...report.metadata.vulnerabilities, high: -1}}}]) {
    assert.throws(() => evaluateAudit(bad, lock, policy, now), /Incomplete/);
  }
});

test('a genuinely clean report is not described as a waiver', () => {
  const {policy} = fixture();
  const report = {auditReportVersion: 2, vulnerabilities: {}, metadata: {
    vulnerabilities: {info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0},
  }};
  assert.deepEqual(evaluateAudit(report, lock, policy, now), {reported: 0, waived: 0});
});

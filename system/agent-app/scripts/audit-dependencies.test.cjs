const assert = require('node:assert/strict');
const {test} = require('node:test');
const {evaluateAudit} = require('./audit-dependencies.cjs');

function cleanReport() {
  return {auditReportVersion: 2, vulnerabilities: {}, metadata: {
    vulnerabilities: {info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0},
  }};
}

test('accepts a complete zero-finding report without applying a waiver', () => {
  assert.deepEqual(evaluateAudit(cleanReport()), {reported: 0, waived: 0});
});

test('rejects every severity, including the formerly waived picomatch advisory', () => {
  for (const severity of ['info', 'low', 'moderate', 'high', 'critical']) {
    const report = cleanReport();
    report.vulnerabilities.picomatch = {name: 'picomatch', severity, isDirect: false,
      via: [{name: 'picomatch', url: 'https://github.com/advisories/GHSA-fc5q-39xc-vjpf'}]};
    report.metadata.vulnerabilities[severity] = 1;
    report.metadata.vulnerabilities.total = 1;
    assert.throws(() => evaluateAudit(report), /no exceptions are allowed/);
  }
});

test('fails closed on registry errors and incomplete or unsupported reports', () => {
  const report = cleanReport();
  for (const bad of [null, {}, {error: {code: 'EAUDITNOLOCK'}}, {...report, error: {code: 'E503'}},
    {...report, auditReportVersion: 1}, {...report, vulnerabilities: []},
    {...report, vulnerabilities: null}, {...report, vulnerabilities: 'empty'},
    {...report, metadata: {}}, {...report, metadata: {vulnerabilities: {total: 0}}}]) {
    assert.throws(() => evaluateAudit(bad), /Incomplete/);
  }
});

test('rejects inconsistent, negative, fractional and nonnumeric counts', () => {
  for (const count of [-1, 0.5, '0', null, NaN, Infinity, 1]) {
    const report = cleanReport();
    report.metadata.vulnerabilities.high = count;
    assert.throws(() => evaluateAudit(report), /Incomplete/);
  }
  const report = cleanReport();
  report.vulnerabilities.unexpected = {severity: 'high'};
  assert.throws(() => evaluateAudit(report), /Incomplete/);
});

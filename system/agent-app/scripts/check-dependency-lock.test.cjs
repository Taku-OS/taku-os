const assert = require('node:assert/strict');
const {Buffer} = require('node:buffer');
const {test} = require('node:test');
const {checkDependencyLock} = require('./check-dependency-lock.cjs');

const fixture = (entry = {}) => ({lockfileVersion: 3, packages: {
  '': {name: 'fixture'},
  'node_modules/example': {
    version: '1.0.0',
    resolved: 'https://registry.npmjs.org/example/-/example-1.0.0.tgz',
    integrity: `sha512-${Buffer.alloc(64).toString('base64')}`,
    ...entry,
  },
}});

test('accepts hash-pinned registry packages', () => {
  assert.equal(checkDependencyLock(fixture()), 1);
});

test('rejects missing hashes, weak hashes, and malformed hashes', () => {
  for (const integrity of [undefined, 'sha1-abc', 'sha512-abc']) {
    assert.throws(() => checkDependencyLock(fixture({integrity})), /SHA-512/);
  }
});

test('rejects alternate sources, deceptive hosts, credentials, and local links', () => {
  for (const resolved of [undefined, 'file:../example', 'git+https://github.com/example/repo',
    'http://registry.npmjs.org/example.tgz', 'https://registry.npmjs.org.evil.test/example.tgz',
    'https://registry.npmjs.org@evil.test/example.tgz',
    'https://user:password@registry.npmjs.org/example.tgz',
    'https://registry.npmjs.org/example.tgz?redirect=elsewhere']) {
    assert.throws(() => checkDependencyLock(fixture({resolved})), /registry tarball/);
  }
  assert.throws(() => checkDependencyLock(fixture({link: true})), /registry tarball/);
});

test('rejects empty and unsupported lockfiles', () => {
  assert.throws(() => checkDependencyLock({lockfileVersion: 2}), /v3/);
  assert.throws(() => checkDependencyLock({lockfileVersion: 3, packages: {'': {}}}), /empty/);
});

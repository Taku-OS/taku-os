const { test } = require('node:test');
const assert = require('node:assert/strict');
const { configureProject, configureProperties, configureWrapper, GRADLE_SHA256 } = require('./withTakuBuildTools')._test;

test('keeps default SDK versions unless an override is explicit and removes stale experiment settings', () => {
  const original = [{ type: 'property', key: 'android.useAndroidX', value: 'true' }];
  const experiment = configureProperties(original, {
    TAKU_NDK_VERSION: '28.0.13004108', TAKU_CMAKE_VERSION: '3.31.6',
  });
  assert.equal(experiment.find((item) => item.key === 'taku.ndkVersion').value, '28.0.13004108');
  assert.deepEqual(configureProperties(experiment, {}), original);
  assert.throws(() => configureProperties(original, { TAKU_NDK_VERSION: '../ndk' }));
});

test('pins the official archive checksum and refuses a distribution change without review', () => {
  const template = 'distributionUrl=https\\://services.gradle.org/distributions/gradle-9.4.1-bin.zip\nnetworkTimeout=10000\n';
  const configured = configureWrapper(template);
  assert.ok(configured.includes(`distributionSha256Sum=${GRADLE_SHA256}`));
  assert.ok(configured.includes('networkTimeout=120000'));
  assert.equal(configureWrapper(configured), configured);
  assert.throws(() => configureWrapper(template.replace('9.4.1', '9.6.1')));
});

test('applies the root override before Expo resolves defaults and repeated prebuild is idempotent', () => {
  const template = 'buildscript { }\napply plugin: "expo-root-project"\n';
  const configured = configureProject(template);
  assert.ok(configured.indexOf('ext.ndkVersion = takuNdkVersion') < configured.indexOf('apply plugin: "expo-root-project"'));
  assert.equal(configureProject(configured), configured);
  assert.throws(() => configureProject('plugins { id("other") }'));
});

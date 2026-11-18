const { test } = require('node:test');
const assert = require('node:assert/strict');
const { configureReactHost } = require('./withTakuReactHost')._test;

test('wraps the one application host while retaining package registration and factory options', () => {
  const factory = `ExpoReactHostFactory.getDefaultReactHost(
      context = applicationContext,
      packageList = PackageList(this).packages.apply { add(CustomPackage()) },
      // A comment can contain an unmatched opening parenthesis: (
      jsBundleAssetPath = "app).bundle",
    )`;
  const source = `override val reactHost: ReactHost by lazy {\n    ${factory}\n  }`;
  const result = configureReactHost(source);
  assert.equal(result, source.replace(factory, `org.agentos.agent.home.TakuReactHost(${factory})`));
  assert.equal(configureReactHost(result), result);
});

test('fails closed when the Expo template no longer provides one factory expression', () => {
  assert.throws(() => configureReactHost('val reactHost = CustomHost()'), /expected one Expo host/);
  assert.throws(() => configureReactHost('ExpoReactHostFactory.getDefaultReactHost(\n'), /unterminated/);
  assert.throws(() => configureReactHost('ExpoReactHostFactory.getDefaultReactHost()\nExpoReactHostFactory.getDefaultReactHost()'), /expected one Expo host/);
});

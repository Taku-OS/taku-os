const fs = require('node:fs/promises');
const path = require('node:path');

// Expo SDK 58 template distribution. The checksum matches services.gradle.org's .sha256,
// the gradle.org release-checksums page, and a SHA-256 of the downloaded archive.
const GRADLE_URL = 'https://services.gradle.org/distributions/gradle-9.4.1-bin.zip';
const GRADLE_SHA256 = '2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb';
const START = '// @generated begin taku-build-tools';
const END = '// @generated end taku-build-tools';

function readVersion(value, name) {
  if (value == null || value === '') return undefined;
  if (!/^\d+\.\d+\.\d+$/.test(value)) {
    throw new Error(`${name} must be an explicit numeric SDK version`);
  }
  return value;
}

function configureProject(contents) {
  const withoutPrevious = contents.replace(
    /\/\/ @generated begin taku-build-tools[\s\S]*?\/\/ @generated end taku-build-tools\n*/g,
    '',
  );
  const anchor = /^apply plugin: ["']expo-root-project["']/m;
  if (!anchor.test(withoutPrevious)) {
    throw new Error('Taku build tools: Expo root plugin anchor changed; review the generated template.');
  }
  const block = `${START}
// Explicit overrides only. Without them, Expo keeps its template toolchain versions.
def takuNdkVersion = findProperty('taku.ndkVersion') ?: System.getenv('TAKU_NDK_VERSION')
def takuCmakeVersion = findProperty('taku.cmakeVersion') ?: System.getenv('TAKU_CMAKE_VERSION')
[takuNdkVersion, takuCmakeVersion].each { version ->
  if (version && !(version ==~ /[0-9]+\\.[0-9]+\\.[0-9]+/)) {
    throw new GradleException('Taku toolchain overrides must be explicit numeric SDK versions')
  }
}
if (takuNdkVersion) ext.ndkVersion = takuNdkVersion
subprojects { child ->
  ['com.android.application', 'com.android.library'].each { pluginId ->
    child.plugins.withId(pluginId) {
      child.extensions.getByName('androidComponents').finalizeDsl { androidDsl ->
        // Libraries otherwise inherit AGP's older SDK default even when Expo selected
        // a newer installed Build Tools version for the application.
        androidDsl.buildToolsVersion = rootProject.ext.buildToolsVersion
        if (takuCmakeVersion) {
          androidDsl.externalNativeBuild.cmake.version = takuCmakeVersion
        }
      }
    }
  }
}
${END}

`;
  return withoutPrevious.replace(anchor, (match) => block + match);
}

function configureProperties(properties, env) {
  const overrides = {
    'taku.ndkVersion': readVersion(env.TAKU_NDK_VERSION, 'TAKU_NDK_VERSION'),
    'taku.cmakeVersion': readVersion(env.TAKU_CMAKE_VERSION, 'TAKU_CMAKE_VERSION'),
  };
  // A new prebuild without overrides must not silently retain an earlier experiment.
  const result = properties.filter((item) => !(item.type === 'property' && item.key in overrides));
  for (const [key, value] of Object.entries(overrides)) {
    if (value) result.push({ type: 'property', key, value });
  }
  return result;
}

function configureWrapper(contents) {
  const distribution = contents.match(/^distributionUrl=(.+)$/m)?.[1].trim().replace(/\\:/g, ':');
  if (distribution !== GRADLE_URL) {
    throw new Error('Taku build tools: Gradle distribution changed; verify its official checksum before updating this plugin.');
  }
  const previousTimeout = Number(contents.match(/^networkTimeout=(\d+)$/m)?.[1] || 0);
  const settings = {
    distributionSha256Sum: GRADLE_SHA256,
    networkTimeout: String(Math.max(120000, previousTimeout)),
    validateDistributionUrl: 'true',
  };
  let result = contents;
  for (const [key, value] of Object.entries(settings)) {
    result = result.replace(new RegExp(`^${key}=.*\\r?\\n?`, 'gm'), '');
    result = result.replace(/\s*$/, '\n') + `${key}=${value}\n`;
  }
  return result;
}

function withTakuBuildTools(config) {
  const { withProjectBuildGradle, withGradleProperties, withDangerousMod } = require('@expo/config-plugins');
  config = withProjectBuildGradle(config, (result) => {
    if (result.modResults.language !== 'groovy') throw new Error('Taku build tools require the Expo Groovy template.');
    result.modResults.contents = configureProject(result.modResults.contents);
    return result;
  });
  config = withGradleProperties(config, (result) => {
    result.modResults = configureProperties(result.modResults, process.env);
    return result;
  });
  return withDangerousMod(config, ['android', async (result) => {
    const wrapper = path.join(result.modRequest.platformProjectRoot, 'gradle/wrapper/gradle-wrapper.properties');
    await fs.writeFile(wrapper, configureWrapper(await fs.readFile(wrapper, 'utf8')));
    return result;
  }]);
}

module.exports = withTakuBuildTools;
module.exports._test = { configureProject, configureProperties, configureWrapper, GRADLE_SHA256 };

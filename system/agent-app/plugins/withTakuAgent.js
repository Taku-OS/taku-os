const { withAndroidManifest, withAndroidStyles, withDangerousMod, withMainActivity } = require('@expo/config-plugins');
const fs = require('node:fs/promises');
const path = require('node:path');

function configureTranslucentThemes(styles) {
  // The launch and runtime themes must both keep the previous Activity available for blur.
  for (const name of ['AppTheme', 'Theme.App.SplashScreen']) {
    const theme = styles.resources.style?.find((style) => style.$.name === name);
    if (!theme) throw new Error(`Taku Agent: expected Android theme ${name}; review the Expo template.`);
    theme.item ??= [];
    for (const [attribute, value] of Object.entries({
      'android:windowIsTranslucent': 'true',
      'android:windowBackground': '@color/taku_conversation_background',
      'android:windowLightStatusBar': 'true',
    })) {
      const item = theme.item.find((item) => item.$.name === attribute);
      if (item) item._ = value;
      else theme.item.push({ $: { name: attribute }, _: value });
    }
  }
  return styles;
}

function configureConversationActivity(contents) {
  return configurePickerState(configureConversationRoutes(contents));
}

function configureConversationRoutes(contents) {
  if (contents.includes('PanelLaunchIntents.restore(')) return contents;
  const activity = 'class MainActivity : ReactActivity() {';
  const create = '  override fun onCreate(savedInstanceState: Bundle?) {';
  const capture = '    ConversationDrafts.offer(intent.getStringExtra(ConversationDrafts.EXTRA_DRAFT))\n' +
    '    intent.removeExtra(ConversationDrafts.EXTRA_DRAFT)';
  if (!contents.includes('import android.os.Bundle') || !contents.includes(activity) || !contents.includes(create)) {
    throw new Error('Taku routes: expected Expo activity lifecycle hooks; review the generated template.');
  }
  if (contents.includes('ConversationDrafts.offer(')) {
    const previous = `  override fun onNewIntent(intent: Intent) {\n${capture}\n` +
      '    if (!intent.getStringExtra(TakuAgentModule.EXTRA_THREAD_ID).isNullOrBlank()) setIntent(intent)\n' +
      '    super.onNewIntent(intent)\n  }\n';
    if (!contents.includes(previous) || !contents.includes(`${create}\n${capture}`)) {
      throw new Error('Taku routes: expected the previous draft lifecycle hooks.');
    }
    contents = contents.replace(previous, '').replace(`${create}\n${capture}`, create);
  } else {
    contents = contents.replace('import android.os.Bundle',
      'import android.os.Bundle\nimport android.content.Intent\nimport org.agentos.agent.bridge.ConversationDrafts\nimport org.agentos.agent.bridge.TakuAgentModule');
  }
  contents = contents.replace('import org.agentos.agent.bridge.TakuAgentModule',
    'import org.agentos.agent.bridge.TakuAgentModule\nimport org.agentos.agent.bridge.PanelLaunchIntents');
  contents = contents.replace(activity, `${activity}\n  override fun onNewIntent(intent: Intent) {\n${capture}\n` +
    '    if (!intent.getStringExtra(TakuAgentModule.EXTRA_THREAD_ID).isNullOrBlank()) {\n' +
    '      PanelLaunchIntents.prepare(intent)\n' +
    '      PanelLaunchIntents.discardConsumed(this, intent)\n' +
    '      setIntent(intent)\n' +
    '    } else if (intent.data != null || getIntent()?.data != null) {\n' +
    '      setIntent(intent)\n    }\n    super.onNewIntent(intent)\n  }\n\n' +
    '  override fun onSaveInstanceState(outState: Bundle) {\n' +
    '    PanelLaunchIntents.save(this, outState)\n    super.onSaveInstanceState(outState)\n  }\n');
  return contents.replace(create, `${create}\n    PanelLaunchIntents.restore(this, savedInstanceState)\n${capture}`);
}

function configurePickerState(contents) {
  if (contents.includes('ComposerPickerState.restore(')) return contents;
  if (!contents.includes('super.onCreate(null)')) {
    throw new Error('Taku picker: expected Expo null saved-state hook; review activity result restoration.');
  }
  // Expo intentionally does not restore React navigation fragments. Preserve the Android
  // result registry separately so external camera/document results survive process death.
  contents = contents.replace('import android.os.Bundle',
    'import android.os.Bundle\nimport org.agentos.agent.bridge.ComposerPickerState');
  contents = contents.replace('super.onCreate(null)',
    'ComposerPickerState.restore(this, savedInstanceState)\n    super.onCreate(null)');
  const save = '  override fun onSaveInstanceState(outState: Bundle) {';
  if (contents.includes(save)) return contents.replace(save, `${save}\n    ComposerPickerState.save(this, outState)`);
  // The conversation hook below creates onSaveInstanceState on first generation.
  return contents;
}

module.exports = function withTakuAgent(config) {
  config = withMainActivity(config, (result) => {
    if (result.modResults.language !== 'kt') throw new Error('Taku drafts require the Kotlin Expo activity template.');
    result.modResults.contents = configureConversationActivity(result.modResults.contents);
    return result;
  });
  config = withDangerousMod(config, ['android', async (result) => {
    // Keep the existing Taku Logo Kit adaptive icon, including monochrome support.
    const source = path.resolve(result.modRequest.projectRoot, '../assistant/res');
    const target = path.join(result.modRequest.platformProjectRoot, 'app/src/main/res');
    for (const relative of [
      'drawable/ic_launcher_foreground.xml', 'drawable/ic_launcher_monochrome.xml',
      'values/ic_launcher_background.xml',
      'mipmap-anydpi-v26/ic_launcher.xml', 'mipmap-anydpi-v26/ic_launcher_round.xml',
    ]) {
      await fs.mkdir(path.dirname(path.join(target, relative)), {recursive: true});
      await fs.copyFile(path.join(source, relative), path.join(target, relative));
    }
    return result;
  }]);
  config = withAndroidManifest(config, (result) => {
    const manifest = result.modResults.manifest;
    manifest.queries ??= [];
    if (!manifest.queries.length) manifest.queries.push({});
    manifest.queries[0].package ??= [];
    if (!manifest.queries[0].package.some((p) => p.$?.['android:name'] === 'org.agentos.assistant')) {
      manifest.queries[0].package.push({ $: { 'android:name': 'org.agentos.assistant' } });
    }
    const app = manifest.application?.[0];
    if (app) {
      app.$['android:allowBackup'] = 'false';
      app.$['android:usesCleartextTraffic'] = 'false';
    }
    return result;
  });
  return withAndroidStyles(config, (result) => {
    result.modResults = configureTranslucentThemes(result.modResults);
    return result;
  });
};

module.exports._test = { configureTranslucentThemes, configureConversationActivity };

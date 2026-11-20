const { test } = require('node:test');
const assert = require('node:assert/strict');
const { configureTranslucentThemes, configureConversationActivity } = require('./withTakuAgent')._test;

test('makes the manifest launch theme translucent while preserving the native splash handoff', () => {
  const styles = { resources: { style: [
    { $: { name: 'AppTheme', parent: 'Theme.AppCompat.DayNight.NoActionBar' }, item: [] },
    { $: { name: 'Theme.App.SplashScreen', parent: 'Theme.SplashScreen' }, item: [
      { $: { name: 'android:windowIsTranslucent' }, _: 'false' },
      { $: { name: 'postSplashScreenTheme' }, _: '@style/AppTheme' },
      { $: { name: 'windowSplashScreenAnimatedIcon' }, _: '@drawable/splashscreen_logo' },
    ] },
  ] } };
  configureTranslucentThemes(styles);
  for (const theme of styles.resources.style) {
    assert.equal(theme.item.find((item) => item.$.name === 'android:windowIsTranslucent')._, 'true');
    assert.equal(theme.item.find((item) => item.$.name === 'android:windowBackground')._, '@android:color/transparent');
  }
  const splash = styles.resources.style[1];
  assert.equal(splash.$.parent, 'Theme.SplashScreen');
  assert.equal(splash.item.find((item) => item.$.name === 'postSplashScreenTheme')._, '@style/AppTheme');
  assert.equal(splash.item.find((item) => item.$.name === 'windowSplashScreenAnimatedIcon')._, '@drawable/splashscreen_logo');
  const once = JSON.stringify(styles);
  configureTranslucentThemes(styles);
  assert.equal(JSON.stringify(styles), once);
});

test('requires explicit review when Expo changes the launch theme contract', () => {
  assert.throws(() => configureTranslucentThemes({ resources: { style: [] } }), /expected Android theme/);
});

const activityTemplate = `import android.os.Bundle
class MainActivity : ReactActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(null)
  }
}`;

test('captures drafts and route identity before Expo and restores the saved current intent', () => {
  const result = configureConversationActivity(activityTemplate);
  const onCreate = result.slice(result.indexOf('  override fun onCreate'));
  assert.ok(onCreate.indexOf('PanelLaunchIntents.restore(this, savedInstanceState)') < onCreate.indexOf('ConversationDrafts.offer('));
  assert.ok(onCreate.indexOf('ConversationDrafts.offer(') < onCreate.indexOf('super.onCreate(null)'));
  const onNew = result.slice(result.indexOf('  override fun onNewIntent'), result.indexOf('  override fun onSaveInstanceState'));
  assert.match(onNew, /onNewIntent\(intent: Intent\) \{\s+ChatCameraLaunch.invalidatePrevious\(getIntent\(\), intent\)/);
  assert.ok(onNew.indexOf('ConversationDrafts.offer(') < onNew.indexOf('super.onNewIntent(intent)'));
  assert.match(onNew, /if \(!intent.getStringExtra\(TakuAgentModule.EXTRA_THREAD_ID\).isNullOrBlank\(\)\) \{\s+PanelLaunchIntents.prepare\(intent\)/);
  assert.match(onNew, /\} else if \(intent\.data != null \|\| getIntent\(\)\?\.data != null\) \{\s+setIntent\(intent\)/);
  assert.ok(onNew.indexOf('setIntent(intent)') < onNew.indexOf('super.onNewIntent(intent)'));
  assert.match(result, /PanelLaunchIntents.save\(this, outState\)\s+super.onSaveInstanceState\(outState\)/);
  assert.equal(configureConversationActivity(result), result);
  const previous = result.replace('import org.agentos.agent.bridge.ChatCameraLaunch\n', '')
    .replace('    ChatCameraLaunch.invalidatePrevious(getIntent(), intent)\n', '');
  assert.equal(configureConversationActivity(previous), result);
});

test('upgrades the previously generated activity hooks without duplicating draft delivery', () => {
  const capture = '    ConversationDrafts.offer(intent.getStringExtra(ConversationDrafts.EXTRA_DRAFT))\n' +
    '    intent.removeExtra(ConversationDrafts.EXTRA_DRAFT)';
  const previous = activityTemplate.replace('import android.os.Bundle',
    'import android.os.Bundle\nimport android.content.Intent\nimport org.agentos.agent.bridge.ConversationDrafts\nimport org.agentos.agent.bridge.TakuAgentModule')
    .replace('class MainActivity : ReactActivity() {', 'class MainActivity : ReactActivity() {\n' +
      `  override fun onNewIntent(intent: Intent) {\n${capture}\n` +
      '    if (!intent.getStringExtra(TakuAgentModule.EXTRA_THREAD_ID).isNullOrBlank()) setIntent(intent)\n' +
      '    super.onNewIntent(intent)\n  }\n')
    .replace('  override fun onCreate(savedInstanceState: Bundle?) {', `  override fun onCreate(savedInstanceState: Bundle?) {\n${capture}`);
  const upgraded = configureConversationActivity(previous);
  assert.equal(upgraded.match(/ConversationDrafts.offer\(/g).length, 2);
  assert.equal(upgraded.match(/override fun onNewIntent\(/g).length, 1);
  assert.equal(upgraded.match(/override fun onSaveInstanceState\(/g).length, 1);
  assert.ok(upgraded.includes('PanelLaunchIntents.restore(this, savedInstanceState)'));
});

import test from 'node:test';
import assert from 'node:assert/strict';
import {humanizeToolNames, phoneToolResultText} from './phoneTools.ts';

test('BUG-0033: phone tool results drop JSON and tool identifiers read as labels', () => {
  assert.equal(phoneToolResultText('{"released":["com.xingin.xhs"],"held":[]}'), '');
  assert.equal(phoneToolResultText('[1,2]'), '');
  assert.equal(phoneToolResultText('{"error":{"code":"X","message":"Screen is busy"}}'), 'Screen is busy');
  assert.equal(phoneToolResultText('{"error":"SCREEN_BUSY"}'), 'SCREEN_BUSY');
  assert.equal(phoneToolResultText('{not json'), '{not json');
  assert.equal(phoneToolResultText('Tapped'), 'Tapped');
  const zh = {releaseApps: '释放后台专屏', requestApps: '申请后台专屏', openApp: '打开应用', tap: '点击屏幕', screenStatus: '查看专屏状态'};
  const t = key => zh[key] || key;
  assert.equal(humanizeToolNames('or release_apps it to close it', t), 'or 释放后台专屏 it to close it');
  assert.equal(humanizeToolNames('call `taku_phone__open_app` then mcp_taku_phone_3013b55d_tap', t), 'call 打开应用 then 点击屏幕');
  assert.equal(humanizeToolNames('taku_phone.screen_status() says busy', t), '查看专屏状态 says busy');
  // Plain prose and unrelated identifiers stay.
  assert.equal(humanizeToolNames('tap the apps icon; my_release_apps_v2 and foo_bar stay', t), 'tap the apps icon; my_release_apps_v2 and foo_bar stay');
});

test('BUG-0033: every phone tool label is bilingual', async () => {
  const {phoneToolKeys} = await import('./phoneTools.ts');
  const {textFor} = await import('../i18n/index.ts');
  for (const key of Object.values(phoneToolKeys).concat('phoneTool')) {
    for (const language of ['en', 'zh']) {
      const text = textFor(language)(key);
      assert.ok(text && text !== key, `${language}.${key}`);
    }
  }
});

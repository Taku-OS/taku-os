import assert from 'node:assert/strict';
import test from 'node:test';
import {languageFor} from '../i18n/index.ts';
import {permissionTextFor} from '../i18n/permissions.ts';
import {systemAiTextFor} from '../i18n/systemAi.ts';

/** @type {const} */
const SCENES = [
  'assistant',
  'ime.vision',
  'screen',
  'memory.text',
  'jev',
  'org.agentos.assistant.text',
];

test('system AI settings copy covers both English and Chinese without empty strings', () => {
  const en = systemAiTextFor(languageFor('en-US'));
  const zh = systemAiTextFor(languageFor('zh-CN'));

  assert.equal(en('enableTitle'), 'Enable Taku AI');
  assert.equal(zh('enableTitle'), '启用 Taku AI');
  assert.equal(en('featuresTitle'), 'Features');
  assert.equal(zh('featuresTitle'), '功能路由');
  assert.equal(en('manageTitle'), 'Manage models and providers');
  assert.equal(zh('manageTitle'), '管理模型与服务商');
  assert.equal(en('paused'), 'Paused');
  assert.equal(zh('paused'), '已暂停');

  for (const scene of SCENES) {
    assert.ok(en.scene(scene).length > 0);
    assert.ok(zh.scene(scene).length > 0);
  }
  assert.equal(en.builtInGroup('route-vision'), 'Vision model');
  assert.equal(zh.builtInGroup('route-vision'), '视觉模型');
  assert.equal(zh.builtInGroup('taku-memory-jev'), 'Taku 记忆');
  assert.equal(zh.builtInGroup('unknown-group'), undefined);
});

test('permission copy localizes deterministic Agent auto-review reasons', () => {
  const en = permissionTextFor('en');
  const zh = permissionTextFor('zh');
  const raw = 'Built or installed locally and declares no network access.';
  assert.equal(en.autoReason(raw), raw);
  assert.equal(zh.autoReason(raw), '本地构建或安装，且未声明网络访问权限。');
  assert.equal(zh.autoReason('Custom LLM explanation'), 'Custom LLM explanation');
});

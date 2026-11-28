import test from 'node:test';
import assert from 'node:assert/strict';
import {projectSession, projectThreads, projectWorkspaces, failedTaskDraft, itemStatus, continuedSessionRoot, spokenText, editSpokenText} from './session.ts';
import {retainSessionBlocks} from './sessionStream.ts';

// Run with the project's tsx test runner (or Node 22.18+ type stripping).

test('missing history is unknown rather than an empty new conversation', () => {
  const result = projectSession({thread: {id: 'thread-1', turns: []}, native: {ready: true}});
  assert.equal(result.knownEmpty, false);
  assert.equal(result.historyComplete, false);
  assert.equal(result.historyStatus, 'unknown');
});

test('chat and task records share native identities; commentary belongs to its final-answer segment', () => {
  const result = projectSession({thread: {id: 'thread-1', turns: [{id: 'turn-1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Open the clock'}]},
    {id: 'c', type: 'agentMessage', phase: 'commentary', text: 'Checking available apps.'},
    {id: 'tool', type: 'dynamicToolCall', tool: 'taku_phone.open_app', status: 'completed'},
    {id: 'answer', type: 'agentMessage', phase: 'final_answer', text: 'The Clock app is open.'},
  ]}]}, native: {}, historyComplete: true});
  assert.equal(result.blocks.length, 2);
  assert.equal(result.blocks[0].id, 'turn-1/u');
  assert.equal(result.blocks[1].id, 'turn-1/after/u');
  assert.equal(result.blocks[1].text, 'The Clock app is open.');
  assert.deepEqual(result.blocks[1].activity.map(a => a.id), ['turn-1/c', 'turn-1/tool']);
  assert.equal(result.blocks[1].activity[1].titleKey, 'openApp');
});

test('BUG-0033: release_apps and its sibling phone tools never show a raw name or JSON result', () => {
  const released = '{"released":["com.xingin.xhs"],"held":[]}';
  const result = projectSession({thread: {id: 'thread-1', turns: [{id: 'turn-1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: '查小红书'}]},
    // Codex with its namespace, Codex without one (older record), MCP canonical, MCP bare.
    {id: 'c1', type: 'dynamicToolCall', namespace: 'taku_phone', tool: 'release_apps', arguments: {packages: ['com.xingin.xhs']},
      status: 'completed', success: true, contentItems: [{type: 'inputText', text: released}]},
    {id: 'c2', type: 'dynamicToolCall', tool: 'release_apps', status: 'completed', success: true, contentItems: [{type: 'inputText', text: released}]},
    {id: 'a1', type: 'mcpToolCall', server: 'agy', tool: 'taku_phone__release_apps', namespace: 'taku_phone', status: 'completed',
      result: {content: [{type: 'text', text: released}]}},
    {id: 'a2', type: 'mcpToolCall', server: 'agy', tool: 'release_apps', status: 'completed', result: {content: [{type: 'text', text: released}]}},
    {id: 'c3', type: 'dynamicToolCall', namespace: 'taku_phone', tool: 'screen_status', status: 'completed', contentItems: [{type: 'inputText', text: '{"screens":[]}'}]},
    {id: 'c4', type: 'dynamicToolCall', namespace: 'taku_phone', tool: 'open_app', status: 'failed', success: false,
      contentItems: [{type: 'inputText', text: '{"error":"APP_NOT_HELD","message":"Hold it first: request_apps, then open_app."}'}]},
    {id: 'c5', type: 'dynamicToolCall', namespace: 'taku_phone', tool: 'tap', status: 'completed', contentItems: [{type: 'inputText', text: 'Tapped at 10,20'}]},
    {id: 'c6', type: 'dynamicToolCall', tool: 'taku_phone__future_tool', status: 'completed'},
    {id: 'a', type: 'agentMessage', phase: 'final_answer', text: 'Done.'},
  ]}]}, native: {}, historyComplete: true});
  const activity = result.blocks[1].activity;
  assert.deepEqual(activity.map(a => a.titleKey), ['releaseApps', 'releaseApps', 'releaseApps', 'releaseApps', 'screenStatus', 'openApp', 'tap', 'phoneTool']);
  assert.deepEqual(activity.map(a => a.name), ['', '', '', '', '', '', '', '']);
  for (const a of activity) assert.equal(/[{}]|"released"|packages/.test(a.text), false, `${a.id}: ${a.text}`);
  assert.equal(activity[5].text, 'Hold it first: request_apps, then open_app.');
  assert.equal(activity[6].text, 'Tapped at 10,20');
  // Non-phone tools keep their name and output as is.
  const other = projectSession({thread: {id: 't', turns: [{id: '1', status: 'completed', items: [
    {id: 'x', type: 'mcpToolCall', server: 'github', tool: 'search', status: 'completed', result: {content: [{type: 'text', text: '{"total":3}'}]}},
    {id: 'y', type: 'dynamicToolCall', namespace: 'other', tool: 'open_app', status: 'completed'},
  ]}]}});
  assert.equal(other.blocks[0].activity[0].text, '{"total":3}');
  assert.equal(other.blocks[0].activity[1].name, 'other · open app');
});

test('private reasoning and credential records are never conversation text', () => {
  const result = projectSession({thread: {id: 't', turns: [{id: '1', status: 'completed', items: [
    {id: 'r', type: 'reasoning', text: 'private thought'},
    {id: 'auth', type: 'account/chatgptAuthTokens/refresh', text: 'secret'},
    {id: 'a', type: 'agentMessage', text: 'Visible answer'},
  ]}]}});
  assert.equal(JSON.stringify(result).includes('private thought'), false);
  assert.equal(JSON.stringify(result).includes('secret'), false);
  assert.equal(result.blocks[0].text, 'Visible answer');
});

test('English and historical attachment envelopes preserve labels without exposing local paths', () => {
  for (const envelope of ['User-selected attachment: notes.txt\nLocal path: ', '用户选择的附件：notes.txt\n本机路径：']) {
    const text = `${envelope}/data/user/0/org.agentos.assistant/files/composer-attachments/one/notes.txt`;
    const result = projectSession({thread: {id: 't', turns: [{id: '1', status: 'completed', items: [
      {id: 'u', type: 'userMessage', content: [{type: 'text', text}]},
    ]}]}});
    assert.equal(result.blocks[0].text, '');
    assert.deepEqual(result.blocks[0].attachments, ['notes.txt']);
    assert.equal(JSON.stringify(result).includes('/data/user/0/'), false);
  }
});

test('a plugin attached with @ shows as a label, not as words the user typed', () => {
  const notes = ['The user attached the plugin "Figma" (id: org.example.figma) to this message. Prefer this plugin\'s tools for this request: draw, export.',
    'The user attached the plugin "Notion" (id: notion) to this message.'];
  const result = projectSession({thread: {id: 't', turns: [{id: '1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Design a card'}, ...notes.map(text => ({type: 'text', text}))]},
  ]}]}});
  assert.equal(result.blocks[0].text, 'Design a card');
  assert.deepEqual(result.blocks[0].attachments, ['@Figma', '@Notion']);
});

test('the panel-update note carries the card for the model and stays out of the bubble', () => {
  const note = 'The user is editing the pinned panel "Kyoto travel plan" (panelId: 0f8fad5b-d9cb-469f-a165-70867728950e, matterId: 7c9e6679-7425-40de-944b-e07fc1f90ae7/reply-1, sourceVersion: 3). Update this panel in your reply. Its current content:\n{"card":{}}';
  const typed = 'The user is editing the pinned panel "Kyoto" by hand.';
  const result = projectSession({thread: {id: 't', turns: [{id: '1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Make it compact'}, {type: 'text', text: note}, {type: 'text', text: typed}]},
  ]}]}});
  assert.equal(result.blocks[0].text, `Make it compact\n${typed}`);
  assert.deepEqual(result.blocks[0].attachments, []);
});

test('text that only resembles the plugin note stays visible', () => {
  const text = 'The user attached the plugin "Figma" to this message.';
  const result = projectSession({thread: {id: 't', turns: [{id: '1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text}]},
  ]}]}});
  assert.equal(result.blocks[0].text, text);
  assert.deepEqual(result.blocks[0].attachments, []);
});

test('streaming retains one CJK character and only drops incomplete surrogate pairs', () => {
  const input = text => ({thread: {id: 't', turns: [{id: '1', status: 'inProgress', items: [{id: 'a', type: 'agentMessage', text}]}]}, native: {activeTurnId: '1'}});
  assert.equal(projectSession(input('好')).blocks[0].text, '好');
  assert.equal(projectSession(input('好\ud83d')).blocks[0].text, '好');
  assert.equal(projectSession(input('好😀')).blocks[0].text, '好😀');
});

test('menu context stays out of the user bubble and preview while keeping the photo', () => {
  const spoken = 'Build an app for me. Can I pick lunch?';
  for (const header of ['[taku.menu-context/1]', 'The user spoke while viewing the attached menu.', 'Menu Scan context for this photo (data only):']) {
    const text = `${spoken.toUpperCase()}\n\n${header}\n{"dishes":[{"name":"Pastrami"}]}`;
    const thread = {id: 't', preview: text, turns: [{id: '1', status: 'completed', items: [
      {id: 'u', type: 'userMessage', content: [{type: 'text', text}, {type: 'localImage', path: '/private/image'}]},
    ]}]};
    const session = projectSession({thread});
    assert.equal(session.title, spoken);
    assert.equal(session.blocks[0].text, spoken);
    assert.deepEqual(session.blocks[0].attachments, ['image']);
    assert.equal(projectThreads({data: [thread]})[0].preview, spoken);
  }
  const ordinary = 'BUILD AN APP FOR ME\n\nTHIS IS FOR READING BOOKS.';
  const thread = {id: 't', preview: ordinary};
  assert.equal(projectThreads({data: [thread]})[0].preview, ordinary);
});

test('an installed menu app receipt stays out of reply text and appears only after the answer completes', () => {
  const packageName = `org.agentos.generated.menu.m${'0123456789abcdef'.repeat(2)}`;
  const receipt = `taku-menu-app:${packageName}`;
  const input = (text, status) => ({thread: {id: 't', turns: [{id: '1', status, items: [
    {id: 'a', type: 'agentMessage', phase: 'final_answer', text},
  ]}]}});
  for (let length = 0; length <= receipt.length; length++) {
    const [block] = projectSession(input(`Your menu is ready.\n\n${receipt.slice(0, length)}`, 'inProgress')).blocks;
    assert.equal(block.text, 'Your menu is ready.\n\n');
    assert.equal(block.parts[0].menuAppPackage, undefined);
  }
  const [final] = projectSession(input(`Your menu is ready.\r\n\r\n${receipt}\n`, 'completed')).blocks;
  assert.equal(final.text, 'Your menu is ready.');
  assert.deepEqual(final.parts[0], {id: '1/a', text: 'Your menu is ready.', active: false, menuAppPackage: packageName});
  for (const unfinished of [receipt, receipt.slice(0, -4)]) {
    const [interrupted] = projectSession(input(`Your menu is ready.\n${unfinished}`, 'interrupted')).blocks;
    assert.equal(interrupted.parts[0].menuAppPackage, undefined);
    assert.equal(interrupted.text, 'Your menu is ready.');
  }
});

test('ordinary replies, plans, and user text do not create menu app cards', () => {
  const receipt = `taku-menu-app:org.agentos.generated.menu.m${'ab'.repeat(16)}`;
  const examples = [`See ${receipt}`, `${receipt}\nMore text`, `\`\`\`text\n${receipt}\n\`\`\``, `\`\`\`text\n${receipt}`, `[${receipt}](https://example.com)`,
    `${receipt}/MainActivity`, receipt.toUpperCase(), 'taku-menu-app:https://example.com', 'The menu is ready.'];
  const messages = examples.map((text, index) => ({id: `a${index}`, type: 'agentMessage', phase: 'final_answer', text}));
  const result = projectSession({thread: {id: 't', turns: [{id: '1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: receipt}]},
    {id: 'c', type: 'agentMessage', phase: 'commentary', text: receipt},
    {id: 'p', type: 'plan', text: receipt}, ...messages,
  ]}]}});
  assert.equal(result.blocks[0].text, receipt);
  assert.deepEqual(result.blocks[1].parts.map(part => part.text), [receipt, ...examples]);
  assert.ok(result.blocks[1].parts.every(part => !part.menuAppPackage));
});

test('steering messages keep distinct segments within the same native turn', () => {
  const result = projectSession({thread: {id: 't', turns: [{id: '1', status: 'inProgress', items: [
    {id: 'u1', type: 'userMessage', content: [{type: 'text', text: 'Find a restaurant'}]},
    {id: 't1', type: 'webSearch', status: 'completed'},
    {id: 'u2', type: 'userMessage', content: [{type: 'text', text: 'With a private room'}]},
    {id: 't2', type: 'webSearch', status: 'inProgress'},
  ]}]}, native: {activeTurnId: '1'}});
  assert.equal(result.blocks.length, 4);
  assert.equal(result.blocks[1].active, false);
  assert.equal(result.blocks[3].active, true);
  assert.notEqual(result.blocks[1].id, result.blocks[3].id);
});

test('tools-only, streaming and completed snapshots retain one reply identity and its activity', () => {
  const project = (text, completed) => projectSession({thread: {id: 'thread-1', turns: [{
    id: 'turn-1', status: completed ? 'completed' : 'inProgress', items: [
      {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Check the clock'}]},
      {id: 'c', type: 'agentMessage', phase: 'commentary', text: 'Reading the current screen.'},
      {id: 'tool', type: 'dynamicToolCall', tool: 'taku_phone.observe_background', status: completed ? 'completed' : 'inProgress'},
      ...(text === null ? [] : [{id: 'answer', type: 'agentMessage', phase: 'final_answer', text}]),
    ],
  }]}, native: completed ? {} : {activeTurnId: 'turn-1'}});
  const snapshots = [project(null, false), project('The Clock', false), project('The Clock app shows no running timer.', true)];
  for (const snapshot of snapshots) {
    assert.deepEqual(snapshot.blocks.map(block => block.id), ['turn-1/u', 'turn-1/after/u']);
    assert.deepEqual(snapshot.blocks[1].activity.map(item => item.id), ['turn-1/c', 'turn-1/tool']);
    assert.equal(snapshot.blocks[0].activity.length, 0);
  }
  assert.deepEqual(snapshots.map(snapshot => snapshot.blocks[1].text), ['', 'The Clock', 'The Clock app shows no running timer.']);
  assert.deepEqual(snapshots.map(snapshot => snapshot.blocks[1].active), [true, true, false]);
  assert.deepEqual(snapshots.map(snapshot => snapshot.blocks[1].activity[1].status), ['running', 'running', 'completed']);
});

test('a same-turn steering message does not move earlier tools into the newer reply', () => {
  const first = [
    {id: 'u1', type: 'userMessage', content: [{type: 'text', text: 'Find a restaurant'}]},
    {id: 'c1', type: 'agentMessage', phase: 'commentary', text: 'Checking nearby restaurants.'},
    {id: 't1', type: 'webSearch', status: 'completed'},
    {id: 'a1', type: 'agentMessage', phase: 'final_answer', text: 'Found two restaurants.'},
  ];
  const second = [
    {id: 'u2', type: 'userMessage', content: [{type: 'text', text: 'With a private room'}]},
    {id: 'c2', type: 'agentMessage', phase: 'commentary', text: 'Checking private rooms.'},
    {id: 't2', type: 'webSearch', status: 'completed'},
    {id: 'a2', type: 'agentMessage', phase: 'final_answer', text: 'One restaurant lists a private room.'},
  ];
  const project = (items, completed = false) => projectSession({thread: {id: 'thread-1', turns: [{
    id: 'turn-1', status: completed ? 'completed' : 'inProgress', items,
  }]}, native: completed ? {} : {activeTurnId: 'turn-1'}}).blocks;
  const beforeSteering = project(first)[1];
  for (const completed of [false, true]) {
    const blocks = project([...first, ...second], completed);
    assert.deepEqual(blocks.map(block => block.id), ['turn-1/u1', 'turn-1/after/u1', 'turn-1/u2', 'turn-1/after/u2']);
    assert.equal(blocks[1].text, beforeSteering.text);
    assert.deepEqual(blocks[1].activity, beforeSteering.activity);
    assert.deepEqual(blocks[1].activity.map(item => item.id), ['turn-1/c1', 'turn-1/t1']);
    assert.deepEqual(blocks[3].activity.map(item => item.id), ['turn-1/c2', 'turn-1/t2']);
    assert.equal(blocks[3].text, 'One restaurant lists a private room.');
    assert.equal(blocks[1].active, false);
    assert.equal(blocks[3].active, !completed);
  }
});

test('multiple final answers in one native segment remain one reply with one activity collection', () => {
  const session = projectSession({thread: {id: 'thread-1', turns: [{id: 'turn-1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Check the clock and calendar'}]},
    {id: 't1', type: 'dynamicToolCall', tool: 'taku_phone.observe_background', status: 'completed'},
    {id: 'a1', type: 'agentMessage', phase: 'final_answer', text: 'The Clock app has no running timer.'},
    {id: 't2', type: 'dynamicToolCall', tool: 'taku_phone.open_app', status: 'completed'},
    {id: 'a2', type: 'agentMessage', phase: 'final_answer', text: 'The Calendar app is open.'},
  ]}]}});
  const replies = session.blocks.filter(block => block.role === 'assistant');
  assert.equal(replies.length, 1);
  assert.equal(replies[0].id, 'turn-1/after/u');
  assert.equal(replies[0].text, 'The Clock app has no running timer.\n\nThe Calendar app is open.');
  assert.deepEqual(replies[0].activity.map(item => item.id), ['turn-1/t1', 'turn-1/t2']);
  assert.equal(replies[0].active, false);
});

test('unknown status does not become success and exit failures remain failures', () => {
  assert.equal(itemStatus({}), 'unknown');
  assert.equal(itemStatus({status: 'futureState'}), 'unknown');
  assert.equal(itemStatus({type: 'commandExecution', status: 'completed', exitCode: 1}), 'failed');
  assert.equal(itemStatus({type: 'dynamicToolCall', status: 'completed', success: false}), 'failed');
});

test('pending questions are scoped to the current session', () => {
  const result = projectSession({thread: {id: 'a', turns: []}, native: {requests: [
    {key: 'a-key', method: 'phone/askUser', params: {threadId: 'a'}},
    {key: 'b-key', method: 'phone/askUser', params: {threadId: 'b'}},
    {key: 'unscoped-key', method: 'phone/askUser', params: {}},
  ]}});
  assert.deepEqual(result.questions.map(q => q.key), ['a-key']);
});

test('workspaces retain exact lease identity and never manufacture previews', () => {
  const result = projectWorkspaces({screens: [{threadId: 'a', screenKey: 'screen-1', generation: 4, packageName: 'com.clock'}, {threadId: 'a'}]});
  assert.equal(result.length, 1);
  assert.equal(result[0].generation, '4');
  assert.equal(result[0].previewUri, '');
  assert.equal(projectThreads({data: [{id: 'a', name: 'null', preview: 'Actual title'}]})[0].title, 'Actual title');
});

test('published queued work blocks duplicate send until the real turn starts', () => {
  const queued = {thread: {id: 't', turns: []}, task: {id: 'task-1', state: 'queued', published: true}};
  const waiting = projectSession(queued);
  assert.equal(waiting.queued, true);
  assert.equal(waiting.running, true);
  assert.equal(waiting.activeTurnId, '');
  assert.equal(waiting.taskId, 'task-1');
  const started = projectSession({...queued, native: {activeTurnId: 'turn-1'}});
  assert.equal(started.queued, false);
  assert.equal(started.running, true);
  assert.equal(projectSession({...queued, task: {...queued.task, published: false}}).queued, false);
  assert.equal(projectSession({...queued, task: {...queued.task, state: 'cancelled'}}).running, false);
});

test('native-only pending requests remain visible without exposing their secret payload', () => {
  const session = projectSession({thread: {id: 't', turns: []}, native: {unsupportedRequestCount: 2}});
  assert.equal(session.nativeOnlyRequests, 2);
  assert.deepEqual(session.questions, []);
});

test('activity details are not silently truncated by the presentation layer', () => {
  const text = 'beginning\n' + 'x'.repeat(18000) + '\nend';
  const session = projectSession({thread: {id: 't', turns: [{id: '1', items: [{id: 'tool', type: 'commandExecution', aggregatedOutput: text}]}]}});
  assert.equal(session.blocks[0].activity[0].text, text);
});

test('a failed published task remains visible even before a native turn exists', () => {
  const result = projectSession({thread: {id: 't', turns: []}, historyComplete: true, knownEmpty: true,
    task: {id: '1793200740276', state: 'failed', published: true, message: 'Check phone connectivity', turnStarted: false},
    request: {prompt: 'Describe the clock screen'}, native: {ready: true}});
  assert.deepEqual(result.taskFailure, {taskId: '1793200740276', message: 'Check phone connectivity', prompt: 'Describe the clock screen', turnStarted: false});
  assert.equal(result.running, false);
  assert.equal(result.queued, false);
  assert.deepEqual(result.blocks, []); // Do not invent a native user message or turn.
  assert.equal(result.totalTurns, 0);
});

test('failure after execution retains real history and never implies safe automatic replay', () => {
  const result = projectSession({thread: {id: 't', turns: [{id: 'turn', status: 'failed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Original request'}]},
  ]}]}, task: {id: '123', state: 'failed', turnStarted: true, message: 'Stopped after an error'},
  request: {prompt: 'Original request'}});
  assert.equal(result.taskFailure.turnStarted, true);
  assert.equal(result.blocks.filter(b => b.role === 'user').length, 1);
  assert.equal(result.taskFailure.prompt, 'Original request');
  assert.equal(result.running, false);
});

test('only a failed task with an identity exposes the gateway-scoped request', () => {
  const base = {thread: {id: 't', turns: []}, request: {prompt: 'Original'}};
  assert.equal(projectSession({...base, task: {id: '123', state: 'completed'}}).taskFailure, null);
  assert.equal(projectSession({...base, task: {state: 'failed'}}).taskFailure, null);
  assert.equal(projectSession({task: {id: '123', state: 'failed'}, request: base.request}).taskFailure, null);
  const noRequest = projectSession({...base, request: undefined, task: {id: '123', state: 'failed', prompt: 'Do not use this state field'}});
  assert.equal(noRequest.taskFailure.prompt, '');
});

test('a failed menu voice request preserves OCR data but shows only the utterance', () => {
  const prompt = 'BUILD A MENU APP\n\n[taku.menu-context/1]\n{"dishes":[{"name":"Pastrami"}]}';
  const result = projectSession({
    thread: {id: 'menu', turns: []},
    task: {id: '123', state: 'failed'},
    request: {prompt},
  });
  assert.equal(result.taskPrompt, 'Build a menu app');
  assert.equal(spokenText(result.taskFailure.prompt), 'Build a menu app');
  assert.equal(failedTaskDraft(result.taskFailure, ''), prompt);
  assert.equal(editSpokenText(prompt, 'Build an ordering app'), 'Build an ordering app' + prompt.slice(prompt.indexOf('\n\n')));
  assert.equal(editSpokenText(prompt, ''), '');
});

test('spokenText recognizes both legacy photo context headers', () => {
  for (const photo of ['this photo', 'the attached photo']) {
    assert.equal(spokenText(`BUILD AN APP\r\n\r\nMenu Scan context for ${photo} (data only):\n{}`), 'Build an app');
  }
});

test('stream reconciliation discards a stale menu App identity after failure and interruption', () => {
  const packageName = 'org.agentos.generated.menu.m0123456789abcdef0123456789abcdef';
  const make = status => projectSession({thread: {id: 'menu', turns: [{id: '1', status, items: [
    {id: 'a', type: 'agentMessage', text: `Your menu is ready.\n\ntaku-menu-app:${packageName}`},
  ]}]}});
  const complete = make('completed');
  assert.equal(complete.blocks[0].parts[0].menuAppPackage, packageName);
  for (const status of ['failed', 'interrupted']) {
    const next = retainSessionBlocks(make(status), complete);
    assert.equal(next.blocks[0].parts[0].menuAppPackage, undefined);
    assert.equal(retainSessionBlocks(make('completed'), next).blocks[0].parts[0].menuAppPackage, packageName);
  }
});

test('Continue hides the failure card at once and a running turn never shows it', () => {
  const failedTurn = {id: 'turn-1', status: 'failed', items: [{id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Original request'}]}]};
  const failed = {thread: {id: 't', turns: [failedTurn]}, native: {ready: true},
    task: {id: '123', state: 'failed', published: true, message: 'Request failed'}, request: {prompt: 'Original request'}, runtime: {turnId: 'turn-1', turnStarted: true}};
  assert.equal(projectSession(failed).taskFailure.taskId, '123');
  // Tapping Continue: the accepted continuation replaces the failed task immediately.
  const continued = projectSession(continuedSessionRoot(failed, '124'));
  assert.equal(continued.taskFailure, null);
  assert.equal(continued.running, true);
  assert.equal(continued.taskId, '124');
  // Field case: the stale receipt still reports the old failed task while the new turn runs.
  const working = {...failed, thread: {id: 't', turns: [failedTurn, {id: 'turn-2', status: 'inProgress', items: []}]}, native: {ready: true, activeTurnId: 'turn-2'}};
  assert.equal(projectSession(working).taskFailure, null);
  assert.equal(projectSession(working).running, true);
  // A newer turn that completed after the failed one supersedes the failure too.
  const succeeded = {...failed, thread: {id: 't', turns: [failedTurn, {id: 'turn-2', status: 'completed', items: []}]}};
  assert.equal(projectSession(succeeded).taskFailure, null);
  // Idle on the failure (the failed turn is still the latest): the card stays.
  assert.equal(projectSession({...failed, native: {ready: true, activeTurnId: ''}}).taskFailure.taskId, '123');
});

test('restoring a failure prepares only a draft and preserves a newer draft', () => {
  const failure = {taskId: '123', message: 'Failed', prompt: 'Original request', turnStarted: true};
  assert.equal(failedTaskDraft(failure, ''), 'Original request');
  assert.equal(failedTaskDraft(failure, 'Original request  '), 'Original request  ');
  assert.equal(failedTaskDraft(failure, 'A new request'), null);
  assert.equal(failedTaskDraft({...failure, prompt: ''}, ''), null);
  assert.deepEqual(failure, {taskId: '123', message: 'Failed', prompt: 'Original request', turnStarted: true});
});

const asked = {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Plan my week'}]};
const thoughtRecord = (id, summary, extra = {}) => ({id, type: 'reasoning', summary, content: ['raw private reasoning'], ...extra});
const thinkingSession = (status, items, native = {}) => projectSession({thread: {id: 'thread-1', turns: [{id: 'turn-1', status, items}]}, native, historyComplete: true});

test('a reply shows its reasoning summary above it, never as reply text and never the raw reasoning', () => {
  const result = thinkingSession('completed', [asked,
    thoughtRecord('r', ['**Weighing the week**', '  ', 'Mornings are free.'], {_streamCompleted: true, startedAtMs: 1000, durationMs: 21500}),
    {id: 'answer', type: 'agentMessage', phase: 'final_answer', text: 'Here is your week.'}]);
  assert.equal(result.blocks.length, 2);
  assert.equal(result.blocks[1].id, 'turn-1/after/u', 'reasoning never changes a reply row identity');
  assert.equal(result.blocks[1].text, 'Here is your week.');
  assert.deepEqual(result.blocks[1].parts, [{id: 'turn-1/answer', text: 'Here is your week.', active: false}]);
  assert.deepEqual(result.blocks[1].activity, []);
  assert.deepEqual(result.blocks[1].thinking, {id: 'turn-1/thinking/u', text: '**Weighing the week**\n\nMornings are free.', active: false, durationMs: 21500});
  assert.equal(JSON.stringify(result).includes('raw private reasoning'), false);
});

test('a reasoning record with nothing to show leaves the reply exactly as it was', () => {
  const items = [asked, {id: 'answer', type: 'agentMessage', text: 'Done.'}];
  const plain = thinkingSession('completed', items);
  for (const record of [{id: 'r', type: 'reasoning'}, thoughtRecord('r', []), thoughtRecord('r', ['', '   ']), {id: 'r', type: 'reasoning', encrypted_content: 'sealed'}])
    assert.deepEqual(thinkingSession('completed', [asked, record, items[1]]), plain);
  assert.equal('thinking' in plain.blocks[1], false);
});

test('reasoning is live only while it is the last thing the running turn has done', () => {
  const running = {activeTurnId: 'turn-1'};
  const writing = thinkingSession('inProgress', [asked, thoughtRecord('r', ['Comparing two rou\ud83d'], {_streamCompleted: false, startedAtMs: 5000})], running);
  assert.equal(writing.blocks.length, 2);
  assert.deepEqual(writing.blocks[1].thinking, {id: 'turn-1/thinking/u', text: 'Comparing two rou', active: true, startedAtMs: 5000});
  assert.equal(writing.blocks[1].active, true);
  assert.equal(writing.running, true);
  // No text yet is still thinking; the runtime may not say when it began.
  assert.deepEqual(thinkingSession('inProgress', [asked, thoughtRecord('r', [])], running).blocks[1].thinking, {id: 'turn-1/thinking/u', text: '', active: true});
  // The record finished, or anything at all followed it: no longer live.
  const finished = thinkingSession('inProgress', [asked, thoughtRecord('r', ['Comparing two routes'], {_streamCompleted: true, durationMs: 4000})], running);
  assert.deepEqual(finished.blocks[1].thinking, {id: 'turn-1/thinking/u', text: 'Comparing two routes', active: false, durationMs: 4000});
  const answering = thinkingSession('inProgress', [asked, thoughtRecord('r', ['Comparing two routes'], {startedAtMs: 5000}), {id: 'answer', type: 'agentMessage', text: ''}], running);
  assert.equal(answering.blocks[1].thinking.active, false);
  const usingTool = thinkingSession('inProgress', [asked, thoughtRecord('r', ['Comparing two routes']), {id: 'tool', type: 'webSearch', status: 'inProgress'}], running);
  assert.equal(usingTool.blocks[1].thinking.active, false);
  // A turn that is over is never live, whatever its records say.
  assert.equal(thinkingSession('interrupted', [asked, thoughtRecord('r', ['Cut short'], {_streamCompleted: false})]).blocks[1].thinking.active, false);
});

test('reasoning between tools joins into one thinking; its time is the sum only when every part is known', () => {
  const running = {activeTurnId: 'turn-1'};
  const steps = [asked, thoughtRecord('r1', ['First thought'], {_streamCompleted: true, durationMs: 3000}),
    {id: 'tool', type: 'webSearch', status: 'completed'}, thoughtRecord('r2', ['Second thought'], {_streamCompleted: true, durationMs: 1500}),
    {id: 'empty', type: 'reasoning', summary: [], _streamCompleted: true},
    {id: 'answer', type: 'agentMessage', phase: 'final_answer', text: 'Found it.'}];
  const done = thinkingSession('completed', steps);
  assert.deepEqual(done.blocks[1].thinking, {id: 'turn-1/thinking/u', text: 'First thought\n\nSecond thought', active: false, durationMs: 4500});
  assert.deepEqual(done.blocks[1].activity.map(a => a.id), ['turn-1/tool']);
  // Thinking again after a tool: live once more, counting on from the time already spent.
  const again = thinkingSession('inProgress', [...steps.slice(0, 3), thoughtRecord('r2', ['Second'], {_streamCompleted: false, startedAtMs: 9000})], running);
  assert.deepEqual(again.blocks[1].thinking, {id: 'turn-1/thinking/u', text: 'First thought\n\nSecond', active: true, startedAtMs: 9000, durationMs: 3000});
  // One finished record with text but no time: the total is not known, so none is claimed.
  const partly = thinkingSession('completed', [asked, thoughtRecord('r1', ['First thought'], {durationMs: 3000}), thoughtRecord('r2', ['Second thought']), steps[5]]);
  assert.deepEqual(partly.blocks[1].thinking, {id: 'turn-1/thinking/u', text: 'First thought\n\nSecond thought', active: false});
  const liveAfterUnknown = thinkingSession('inProgress', [asked, thoughtRecord('r1', ['First thought']), steps[2], thoughtRecord('r2', ['Second'], {startedAtMs: 9000})], running);
  assert.deepEqual(liveAfterUnknown.blocks[1].thinking, {id: 'turn-1/thinking/u', text: 'First thought\n\nSecond', active: true});
  // Time alone, with the text not projected, still says how long it took.
  assert.deepEqual(thinkingSession('completed', [asked, {id: 'r', type: 'reasoning', durationMs: 2000}, steps[5]]).blocks[1].thinking,
    {id: 'turn-1/thinking/u', text: '', active: false, durationMs: 2000});
});

test('reasoning belongs to the request it follows, and a reply that only thought is still a reply', () => {
  const steered = thinkingSession('completed', [asked, thoughtRecord('r1', ['About the week'], {durationMs: 1000}),
    {id: 'a1', type: 'agentMessage', text: 'First answer.'},
    {id: 'u2', type: 'userMessage', content: [{type: 'text', text: 'And the weekend'}]},
    thoughtRecord('r2', ['About the weekend'], {durationMs: 2000}), {id: 'a2', type: 'agentMessage', text: 'Second answer.'}]);
  assert.deepEqual(steered.blocks.map(block => [block.role, block.thinking?.id, block.thinking?.text]), [
    ['user', undefined, undefined], ['assistant', 'turn-1/thinking/u', 'About the week'],
    ['user', undefined, undefined], ['assistant', 'turn-1/thinking/u2', 'About the weekend']]);
  const onlyThought = thinkingSession('interrupted', [asked, thoughtRecord('r', ['Cut short'])]);
  assert.equal(onlyThought.blocks.length, 2);
  assert.equal(onlyThought.blocks[1].status, 'stopped');
  assert.deepEqual(onlyThought.blocks[1].thinking, {id: 'turn-1/thinking/u', text: 'Cut short', active: false});
});

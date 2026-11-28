'use strict';

const root = document.documentElement;
const screen = document.querySelector('#screen');
const sheet = document.querySelector('#sheet');
const status = document.querySelector('#status');
const pageSize = 20;
const batchLimit = 50;
const homePage = () => ({name: 'home', value: '', scroll: 0});
const folderStyles = new Map([
  ['restaurants', {color: 'butter', icon: 'restaurant'}],
  ['open-source', {color: 'lilac', icon: 'code'}],
  ['wishlist', {color: 'green', icon: 'shopping-cart'}],
  ['watch-later', {color: 'blue', icon: 'play'}],
  ['places-to-go', {color: 'green', icon: 'bookmark'}],
  ['photo-studies', {color: 'lilac', icon: 'bookmark-filled'}],
  ['weekend-recipes', {color: 'butter', icon: 'restaurant'}],
  ['design-notes', {color: 'blue', icon: 'pencil'}],
]);
const folderStyle = {color: 'blue', icon: 'folder'};
let entries = [];
let entriesById = new Map();
let groups = new Map();
let page = homePage();
let history = [];
let loaded = false;
let error = '';
let queryTimer;
let queryRequest = 0;
let matching = null;
let searchError = '';
let selected = null;
let pendingMutation = '';
let poseRequest = 0;
let sheetReturn;
let scrollTimer;
let statusTimer;
let settingsModel = null;

const esc = value => String(value ?? '').replace(/[&<>"']/g, c =>
  ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
const icon = (name, size = 20) => `<i aria-hidden="true" class="icon" style="width:${size}px;height:${size}px"><svg focusable="false"><use href="#icon-${name}"></use></svg></i>`;
const button = (label, action, value = '', css = '') =>
  `<button type="button" class="${css}" data-action="${action}" data-value="${esc(value)}">${label}</button>`;
const circle = (action, value, glyph, label) =>
  `<button type="button" class="roundbtn" data-action="${action}" data-value="${esc(value)}" aria-label="${esc(label)}" title="${esc(label)}">${icon(glyph)}</button>`;
const asset = (entry, size) => `image/${size}/${entry.id}`;
const image = (entry, size, alt = '') =>
  `<img src="${asset(entry, size)}" alt="${esc(alt)}" ${size === 'thumb' ? 'loading="lazy"' : ''}>`;
const count = (n, unit) => `${n} ${unit}${n === 1 ? '' : 's'}`;
const date = time => new Date(time).toLocaleDateString('en-US', {month: 'short', day: 'numeric'});
const kind = 'Screenshot';
const sourceByline = entry => entry.sourceHandle ? `${entry.source} · @${entry.sourceHandle}` : entry.source;
const flow = (title, markup, action = '', footer = '') => {
  const bar = selected
    ? `<header class="bar"><span></span><span id="selection-count" role="status">${selected.size} selected</span>
      ${button('Done', 'cancel-select', '', 'bar-action')}</header>`
    : `<header class="bar">${circle('back', '', 'arrow-left', 'Back')}<span>${esc(title)}</span>${action || '<span></span>'}</header>`;
  return bar + `<main id="flow" tabindex="-1">${markup}</main>`
    + (footer ? `<div class="footaction">${footer}</div>` : '');
};
const empty = (label, description = '', action = '') =>
  `<div class="empty">${icon('bookmark', 30)}<h3>${esc(label)}</h3>${description ? `<p>${esc(description)}</p>` : ''}${action}</div>`;
function groupSavedItems(lists) {
  groups = new Map(lists.map(list => [list.id, {...list,
    ...(folderStyles.get(list.id) || folderStyle), entries: []}]));
  for (const entry of entries) {
    if (!entry.filingPending) groups.get(entry.listId)?.entries.push(entry);
  }
}

function folder(list) {
  const first = list.entries[0];
  const last = list.entries[1] || first;
  const papers = first
    ? `<div class="folder-paper rear">${image(last, 'cover')}</div><div class="folder-paper front">${image(first, 'cover')}</div>`
    : '';
  const layers = `<div class="folder-back"></div>${papers}<div class="folder-glass"></div>
    <span class="folder-mark">${icon(list.icon, 18)}</span>
    <span class="folder-count" aria-label="${count(list.entries.length, 'save')}">${list.entries.length}</span>
    <strong>${esc(list.name)}</strong>`;
  return button(layers, 'folder', list.id, `nb-folder ${list.color}`);
}

function homeHeader() {
  const search = circle('open-search', '', 'search', 'Search Notebook');
  const settings = button(icon('settings', 18) + esc(settingText('Settings', '设置')), 'settings', '', 'home-settings');
  return `<header class="notebook-header">${search}<h1>Taku Notebook</h1>${settings}</header>`;
}

function home() {
  return homeHeader() + `<main id="flow" class="nb-homeflow" aria-label="Notebook">
    <section><header class="nb-section"><h2>Lists</h2>
      ${button(icon('folder-add', 17) + ' Add list', 'edit-list')}</header>
      <div class="nb-folders">${[...groups.values()].map(folder).join('')}</div>
    </section>
  </main>`;
}

const more = items => items.length > limit()
  ? `<div class="archive-link">${button('Load more', 'load-more', '', 'pill')}</div>` : '';
const limit = () => page.limit || pageSize;

function row(entry, withOptions = false) {
  const filing = entry.filingPending ? ' · Organizing…' : '';
  const text = `<span class="thumb">${image(entry, 'thumb')}</span><span class="itemtext">
    <b>${esc(entry.title)}</b><small>${esc(sourceByline(entry))} · ${kind} · ${date(entry.savedAt)}${filing}</small></span>`;
  if (selected) return `<label class="item selectable">${text}<input type="checkbox" name="selected-entry"
    value="${esc(entry.id)}" aria-label="${esc('Select ' + entry.title)}" ${selected.has(entry.id) ? 'checked' : ''}></label>`;
  if (!withOptions) return button(text + icon('chevron-right', 16), 'detail', entry.id, 'item');
  return `<div class="result-row">${button(text, 'detail', entry.id, 'item')}
    ${circle('entry-options', entry.id, 'more', 'Manage ' + entry.title)}</div>`;
}

const selectionButton = (disabled = false) =>
  `<button class="bar-action" type="button" data-action="start-select"${disabled ? ' disabled' : ''}>Select</button>`;

function folderPage() {
  const list = groups.get(page.value);
  if (!list) return missing();
  const [first] = list.entries;
  const heading = `<div class="albumhead"><div class="tintlabel">${icon('bookmark-filled', 14)} List</div>
    <h1>${esc(list.name)}</h1><div class="muted">${count(list.entries.length, 'save')}${first ? ' · Updated ' + date(first.savedAt) : ''}</div>
    ${list.rule ? `<p class="list-rule muted">${esc(list.rule)}</p>` : ''}</div>`;
  const hero = first && !selected ? button(`${image(first, 'cover')}<div class="photoveil"></div>
    <span class="timepill">${kind}</span><div class="herotxt"><small>${esc(sourceByline(first))}</small>
    <h2>${esc(first.title)}</h2></div>`, 'detail', first.id, 'albumhero') : '';
  const body = first ? hero + `<div class="otheritems">${list.entries.slice(selected ? 0 : 1, limit()).map(entry => row(entry)).join('')}${more(list.entries)}</div>`
    : empty('This list is empty');
  return flow('List', heading + body, button('Manage', 'list-options', list.id, 'bar-action'));
}

const item = () => entriesById.get(page.value);
const missing = () => flow('Notebook', empty('Item unavailable', 'It may have been removed.', button('Back to Notebook', 'home', '', 'pill')));

function sourceResolutionHint(entry) {
  const platform = entry.automaticSource;
  if (entry.sourceResolution === 'pending') return `Finding the original on ${platform}…`;
  if (entry.sourceResolution === 'unavailable') return `${platform} source lookup is unavailable.`;
  return `The original on ${platform} couldn’t be verified.`;
}

function sourceDetails(entry) {
  let title = 'Source link';
  let hint = entry.sourceUrl;
  let action = '';
  if (entry.sourceUrl) {
    action = circle('copy-source', entry.id, 'copy', 'Copy source link');
  } else if (entry.automaticSource) {
    hint = sourceResolutionHint(entry);
    if (entry.sourceResolution === 'not_found' || entry.sourceResolution === 'unavailable') {
      action = button('Try again', 'retry-source', entry.id, 'bar-action');
    }
  } else {
    title = 'Screen capture only';
    hint = 'No link was available from the source app.';
    action = circle('attach-source', entry.id, 'link', 'Add source link');
  }
  return `<div class="origin">${icon('link')}<div><b>${title}</b><small>${esc(hint)}</small></div>${action}</div>`;
}

function detail() {
  const entry = item();
  if (!entry) return missing();
  const list = entry.filingPending ? `<div class="tintlabel" role="status">${icon('folder', 15)} Organizing…</div>`
    : button(icon('bookmark', 15) + ' ' + esc(entry.category), 'folder', entry.listId, 'tintlabel');
  const tile = button(`${image(entry, 'cover')}<b>Frame</b><span>Captured frame</span>`, 'frame', entry.id, 'kframe');
  const footer = entry.sourceUrl
    ? button(icon('arrow-up-right', 17) + ' View source', 'open-source', entry.id, 'primary wide')
    : button('View captured frame', 'frame', entry.id, 'primary wide');
  const summary = entry.summary ? `<div class="detailblock"><h3>Summary</h3><div class="summary">${esc(entry.summary)}</div></div>` : '';
  const description = entry.description ? `<section class="detailblock"><h3>Your description</h3><div class="summary">${esc(entry.description)}</div></section>` : '';
  return flow('Saved item', `<div class="detailpad">${list}<h1>${esc(entry.title)}</h1>
    <div class="sourceby">${esc(sourceByline(entry))} · ${kind}</div>
    <div class="keys single">${tile}</div>${summary}${description}${sourceDetails(entry)}
    <p class="fine">Saved ${date(entry.savedAt)}</p></div>`, button('Manage', 'entry-options', entry.id, 'bar-action'), footer);
}

function frame() {
  const entry = item();
  if (!entry) return missing();
  return flow('Key frames', `<div class="frameview">${image(entry, 'original', 'Saved screenshot of ' + entry.title)}
    <div class="secname"><h2>Captured frame</h2><small>1 / 1</small></div>
    ${entry.summary ? `<p>${esc(entry.summary)}</p>` : ''}</div>`);
}

const activeFilters = () => [page.source, page.list, page.period].filter(Boolean).length;

function searchControls() {
  const filtered = activeFilters();
  return button(icon('tune', 17) + (filtered ? ` Filters (${filtered})` : ' Filters'), 'filter', '',
    filtered ? 'pill active' : 'pill') + button(page.oldest ? 'Oldest first ↑' : 'Newest first ↓', 'sort', '', 'quiet');
}

function searchResults() {
  if (searchError) return empty(searchError, '', button('Try again', 'run-search', '', 'pill'));
  if (matching === null) return '';
  const matches = matching.map(id => entriesById.get(id)).filter(Boolean);
  return `<div class="result-count muted">${count(matches.length, 'save')}</div>
    ${matches.length ? matches.slice(0, limit()).map(entry => row(entry, true)).join('') + more(matches)
      : empty('No results', activeFilters() ? 'Try another keyword or clear your filters.' : 'Try another keyword.',
        activeFilters() && !selected ? button('Clear filters', 'clear-filters', '', 'pill') : '')}`;
}

const canSelectResults = () => matching?.some(id => entriesById.has(id)) && !searchError;

function searchPage() {
  return flow('Search saves', `<div class="listpad">
    ${selected ? '' : `<form id="find" class="findbox">${icon('search')}<input type="search" name="q" aria-label="Search terms"
      placeholder="Search titles, sources or descriptions" value="${esc(page.query)}" autocomplete="off"></form>
      <div id="filters">${searchControls()}</div>`}<div id="results">${searchResults()}</div>
  </div>`, selectionButton(!canSelectResults()));
}

const select = (label, name, items, selected, placeholder = '') => `<label class="field">${label}
  <select name="${name}"${placeholder ? ' required' : ''}>
    ${placeholder ? `<option value="" disabled selected>${esc(placeholder)}</option>` : ''}
    ${items.map(([value, title]) =>
      `<option value="${esc(value)}" ${selected === value ? 'selected' : ''}>${esc(title)}</option>`).join('')}</select></label>`;

function filterSheet() {
  const sources = [...new Map(entries.filter(entry => entry.sourceKey).map(entry => [entry.sourceKey, entry.source]))]
    .sort((left, right) => left[1].localeCompare(right[1]));
  const allLists = [...groups.values()].map(list => [list.id, list.name]);
  showSheet('Filter saves', `<form id="filter-form">
    ${select('Saved time', 'period', [['', 'Any time'], ['1', 'Today'], ['7', 'Last 7 days'], ['30', 'Last 30 days']], page.period)}
    ${select('Source', 'source', [['', 'All sources'], ...sources], page.source)}
    ${select('List', 'list', [['', 'All lists'], ...allLists], page.list)}
    <div class="sheet-actions">${activeFilters() ? button('Clear filters', 'clear-filters', '', 'pill') : ''}
      <button class="primary wide" type="submit">Apply filters</button></div>
  </form>`);
}

const menuLine = (glyph, label, action, value = '', css = '') => button(icon(glyph) + `<span>${esc(label)}</span>`
  + icon('chevron-right', 17), action, value, `menuline ${css}`);

const settingText = (english, chinese) =>
  (settingsModel?.language || navigator.language).startsWith('zh') ? chinese : english;
const settingStatus = active => active ? settingText('On', '已开启') : settingText('Off', '已关闭');
const accessStatus = allowed => allowed ? settingText('Allowed', '已允许') : settingText('Not allowed', '未允许');
const settingCopy = (title, subtitle = '') => `<span class="setting-copy"><b>${esc(title)}</b>`
  + (subtitle ? `<small>${esc(subtitle)}</small>` : '') + '</span>';
const settingRow = (title, subtitle, action) => button(settingCopy(title, subtitle) + icon('chevron-right', 17),
  'change-setting', action, 'menuline setting-row');
const settingSwitch = (title, subtitle, checked, action) =>
  `<button type="button" class="menuline setting-row" role="switch" aria-checked="${checked}" aria-label="${esc(title)}"
    data-action="change-setting" data-value="${action}">${settingCopy(title, subtitle)}
    <span class="setting-switch" aria-hidden="true"></span></button>`;
const settingSection = (name, rows, description = '') => `<section class="setting-section"><h2>${esc(name)}</h2>
  ${description ? `<p>${esc(description)}</p>` : ''}<div class="setting-card">${rows}</div></section>`;

function settingsPage() {
  return flow(settingText('Settings', '设置'), `<div id="settings-content" class="settings-content">${settingsContent()}</div>`);
}

function reminderPermissionsPage() {
  return flow(settingText('Location and notifications', '定位与通知'),
    `<div id="settings-content" class="settings-content">${settingsContent()}</div>`);
}

function settingsContent() {
  const t = settingText;
  const state = settingsModel;
  if (!state) return `<p class="setting-description" role="status">${esc(t('Loading settings…', '正在读取设置…'))}</p>`;
  const reminderDescription = t('Notebook uses your location to bring up relevant saves when you arrive somewhere.',
    'Notebook 会使用你的位置，在你到达相关地点时提醒你查看以前的收藏。');
  if (page.name === 'reminder-permissions') {
    const permissions = settingRow(t('Phone location', '手机定位'), accessStatus(state.locationEnabled), 'open-location-services')
      + settingRow(t('Notifications', '通知'), accessStatus(state.notificationsEnabled), 'open-reminder-android-settings');
    const serviceIssue = /^[A-Z_]+$/.test(state.arrivalHostStatus);
    return `<p class="setting-description">${esc(reminderDescription)}</p>`
      + settingSection(t('Android settings', 'Android 设置'), permissions)
      + (serviceIssue ? `<p role="status">${esc(t('Taku Arrival is unavailable. Reminders will resume when it is available.',
        'Taku 到达服务暂不可用，恢复后会继续提醒。'))}</p>` : '');
  }
  const themeOptions = [['system', t('System', '跟随系统')], ['light', t('Light', '浅色')], ['dark', t('Dark', '深色')]];
  const appearance = `<div class="setting-options" role="group" aria-label="${esc(t('Appearance', '外观'))}">
    ${themeOptions.map(([value, label]) => `<button class="pill" data-action="set-theme" data-value="${value}"
      type="button" aria-pressed="${value === state.appearance}">${esc(label)}</button>`).join('')}</div>`;
  const reminders = settingSwitch(t('Remind me when I arrive', '到达时提醒我'), settingStatus(state.remindersEnabled),
    state.remindersEnabled, state.remindersEnabled ? 'disable-reminders' : 'enable-reminders')
    + button(settingCopy(t('Location and notifications', '定位与通知'), accessStatus(state.locationEnabled))
      + icon('chevron-right', 17), 'reminder-permissions', '', 'menuline setting-row');
  const screenshots = settingSwitch(t('Show screenshot actions', '显示截图操作'), settingStatus(state.screenshotsEnabled),
    state.screenshotsEnabled, 'toggle-screen-observer')
    + settingRow(t('Screenshot access', '截图读取权限'), accessStatus(state.photoAccess),
      state.photoAccess ? 'open-reminder-android-settings' : 'request-photos')
    + settingRow(t('Accessibility', '无障碍服务'), accessStatus(state.accessibilityConnected), 'open-accessibility');
  const ai = settingRow(t('Open Taku AI settings', '打开 Taku AI 设置'), '', 'open-ai-settings');
  return settingSection(t('Saved item reminders', '收藏到达提醒'), reminders, reminderDescription)
    + settingSection(t('Appearance', '外观'), appearance)
    + settingSection(t('Screenshot actions', '截图操作'), screenshots)
    + settingSection('Taku AI', ai, t('After you save a screenshot, Notebook can use Taku AI to summarize it, sort it into a list, and help find the place or original post.',
      '保存截图后，Notebook 可使用 Taku AI 生成摘要、自动归类，并帮助识别地点和查找原帖。'));
}

function readSettings() {
  if (page.name === 'settings' || page.name === 'reminder-permissions') Notebook.readSettings();
}

function entryOptions(id) {
  const entry = entriesById.get(id);
  if (!entry) return;
  showSheet('Manage saved item', `<p class="sheet-context muted">${esc(entry.title)}</p>`
    + menuLine('pencil', 'Edit', 'edit-entry', id)
    + menuLine('folder', 'Move to list', 'move-entry', id)
    + menuLine('trash', 'Delete', 'delete-entry', id, 'destructive'));
}

function listOptions(id) {
  const list = groups.get(id);
  if (!list) return;
  showSheet(list.name, menuLine('pencil', 'Edit list name and rule', 'edit-list', list.id)
    + menuLine('search', 'Search this list', 'find-in-list', list.id)
    + (list.entries.length ? menuLine('list-bullet', 'Select saved items', 'start-select') : ''));
}

function mutationForm(method, markup, verb, {confirmDelete = false, disabled = false} = {}) {
  return `<form id="mutation-form" data-method="${method}">${markup}
    <p class="error" role="alert"></p><div class="sheet-actions">
      ${confirmDelete ? button('Cancel', 'dismiss-sheet', '', 'pill') : ''}
      <button type="submit" class="${confirmDelete ? 'danger' : 'primary'} wide"${disabled ? ' disabled' : ''}>${verb}</button>
    </div></form>`;
}

const hidden = (name, value) => `<input type="hidden" name="${name}" value="${esc(value)}">`;
const input = (label, name, value, max) => `<label class="field">${label}
  <input type="text" name="${name}" value="${esc(value)}" maxlength="${max}" required></label>`;
const textarea = (label, name, value, max, hint = '') => `<div class="field"><label>${label}
  <textarea name="${name}" maxlength="${max}" rows="3"${hint ? ` aria-describedby="field-help"` : ''}>${esc(value)}</textarea>
  </label>${hint ? `<small id="field-help">${esc(hint)}</small>` : ''}</div>`;

function editList(id) {
  const list = groups.get(id);
  if (id && !list) return;
  showSheet(list ? 'Edit list' : 'Add list', mutationForm(list ? 'updateList' : 'createList',
    (list ? hidden('id', id) : '') + input('List name', 'name', list?.name, 100)
    + textarea('List rule (optional)', 'rule', list?.rule, 4000, 'Describe what belongs in this list.'),
    list ? 'Save changes' : 'Create list'));
}

function editEntry(id, noteOnly = false) {
  const entry = entriesById.get(id);
  if (!entry) return;
  const titleField = noteOnly ? `<p class="sheet-context muted">${esc(entry.title)}</p>`
    : input('Title', 'title', entry.title, 300);
  const descriptionLabel = noteOnly ? 'Note'
    : entry.description ? 'Add to description (optional)' : 'Description (optional)';
  showSheet(noteOnly ? 'Add note' : 'Edit saved item', mutationForm('editEntry', hidden('id', id)
    + titleField + textarea(descriptionLabel,
      'appendDescription', '', 4000, entry.description ? 'New text is added to your existing description.' : ''),
    noteOnly ? 'Save note' : 'Save changes', {disabled: true}));
}

function entryEdits(form) {
  const {title, appendDescription} = form.elements;
  const originalTitle = title?.defaultValue.replace(/[\r\n]/g, '');
  const titleValue = title?.value;
  return {
    title: titleValue === originalTitle ? undefined : titleValue,
    appendDescription: appendDescription.value.trim() ? appendDescription.value : undefined,
  };
}

const targetIds = id => id ? [id] : [...(selected || [])];
const batchIds = ids => ids.map(id => hidden('ids', id)).join('');

function moveEntries(id) {
  const ids = targetIds(id);
  if (!ids.length) return;
  const entry = id && entriesById.get(id);
  if (id && !entry) return;
  const options = [...groups.values()].filter(list => entry?.filingPending || list.id !== entry?.listId)
    .map(list => [list.id, list.name]);
  if (!options.length) return;
  showSheet('Move to list', mutationForm(id ? 'editEntry' : 'batchEntries',
    (id ? hidden('id', id) : hidden('operation', 'move') + batchIds(ids))
    + `<p class="sheet-context muted">${entry ? esc(entry.title) : count(ids.length, 'saved item')}</p>`
    + select('Destination', 'listId', options, '', 'Choose a list'), 'Move'));
}

function deleteEntries(id) {
  const ids = targetIds(id);
  if (!ids.length) return;
  const text = id ? `“${esc(entriesById.get(id)?.title)}” will be removed from Notebook.`
    : `${count(ids.length, 'saved item')} will be removed from Notebook.`;
  showSheet(ids.length === 1 ? 'Delete saved item?' : 'Delete saved items?', mutationForm('batchEntries', hidden('operation', 'delete')
    + batchIds(ids) + `<p class="sheetdesc">${text}</p>`, 'Delete', {confirmDelete: true}));
}

function selectionBar() {
  return `<div class="selection-bar">${button(icon('folder', 17) + ' Move to list', 'move-entry', '', 'primary selection-action')}
    ${button(icon('trash', 17) + ' Delete', 'delete-entry', '', 'pill selection-action destructive')}</div>`;
}

function updateSelectionBar() {
  if (!selected) return;
  screen.querySelector('#selection-count').textContent = `${selected.size} selected`;
  screen.querySelectorAll('.selection-action').forEach(button => button.disabled = !selected.size);
}

function showSheet(title, markup) {
  if (sheet.hidden) sheetReturn = document.activeElement;
  clearTimeout(statusTimer);
  status.textContent = '';
  sheet.innerHTML = `<button type="button" class="veil" data-action="dismiss-sheet" aria-label="Close dialog" tabindex="-1"></button>
    <section class="sheetcard" role="dialog" aria-modal="true" aria-labelledby="sheet-title">
    <header class="sheetbar"><h2 id="sheet-title">${esc(title)}</h2>${circle('dismiss-sheet', '', 'close', 'Close')}</header>${markup}</section>`;
  sheet.hidden = false;
  screen.inert = true;
  sheet.querySelector('.sheetbar button').focus({preventScroll: true});
}

function dismissSheet() {
  if (pendingMutation) return;
  sheet.hidden = true;
  sheet.innerHTML = '';
  screen.inert = false;
  sheetReturn?.focus({preventScroll: true});
  sheetReturn = null;
}

const pages = {home, folder: folderPage, detail, frame, search: searchPage,
  settings: settingsPage, 'reminder-permissions': reminderPermissionsPage};
const isSearchPage = () => page.name === 'search';

function persistView() {
  page.scroll = screen.querySelector('#flow')?.scrollTop || 0;
  Notebook.saveView(JSON.stringify({page, history}));
}

function requestSearch(delay = 0, preserveResults = false) {
  clearTimeout(queryTimer);
  const request = ++queryRequest;
  if (!isSearchPage()) return;
  if (!preserveResults) matching = null;
  searchError = '';
  renderResults();
  const query = {
    query: page.query?.trim() || undefined, source: page.source || undefined, listId: page.list || undefined,
    sort: page.oldest ? 'oldest' : 'newest',
  };
  if (page.period) {
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    const end = new Date(start);
    end.setDate(end.getDate() + 1);
    start.setDate(start.getDate() - Number(page.period) + 1);
    query.from = start.getTime();
    query.to = end.getTime();
  }
  queryTimer = setTimeout(() => Notebook.search(JSON.stringify(query), request), delay);
}

function renderResults() {
  const results = screen.querySelector('#results');
  if (results) results.innerHTML = searchResults();
  const select = screen.querySelector('.bar [data-action="start-select"]');
  if (select && isSearchPage()) select.disabled = !canSelectResults();
}

function applyFilters({source = '', list = '', period = ''}) {
  Object.assign(page, {source, list, period, limit: pageSize});
  dismissSheet();
  renderSearchControls('filter');
  requestSearch();
  persistView();
}

function renderSearchControls(focusAction) {
  const controls = screen.querySelector('#filters');
  controls.innerHTML = searchControls();
  controls.querySelector(`[data-action="${focusAction}"]`).focus({preventScroll: true});
}

function render() {
  if (!loaded) return;
  const request = ++poseRequest;
  const position = page.scroll || 0;
  const body = error ? flow('Notebook', empty(error)) : pages[page.name]();
  screen.className = `screen app ${page.name}`;
  screen.innerHTML = body + (selected ? selectionBar() : '');
  updateSelectionBar();
  const main = screen.querySelector('#flow');
  main.scrollTop = position;
  main.addEventListener('scroll', () => {
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(persistView, 150);
  }, {passive: true});
  if (!error && page.name === 'detail' && item()) Notebook.loadPoseAction(page.value, request);
}

function navigate(name, value = '', options = {}) {
  persistView();
  if (name === 'home') history = [];
  else history.push(page);
  page = {name, value, scroll: 0, ...options};
  matching = null;
  selected = null;
  render();
  readSettings();
  requestSearch();
  persistView();
}

function toggleSelection(enabled) {
  persistView();
  selected = enabled ? new Set() : null;
  render();
  screen.querySelector(`[data-action="${enabled ? 'cancel-select' : 'start-select'}"], .bar [data-action="list-options"]`)
    ?.focus({preventScroll: true});
}

function back() {
  if (!sheet.hidden) {
    dismissSheet();
    return true;
  }
  if (selected) {
    toggleSelection(false);
    return true;
  }
  if (page.name === 'home') return false;
  page = history.pop() || homePage();
  matching = null;
  render();
  requestSearch();
  persistView();
  return true;
}

function theme(value) {
  root.dataset.theme = value;
}

window.notebook = {
  update(model, serializedView, entryId, showSettings, savedAction) {
    const searchHadFocus = !entryId && !showSettings && document.activeElement?.matches('#find input');
    if (loaded) persistView();
    else if (serializedView) {
      const saved = JSON.parse(serializedView);
      if (Object.hasOwn(pages, saved.page?.name)) {
        page = saved.page;
        history = saved.history.filter(visit => Object.hasOwn(pages, visit.name));
      }
    }
    error = model.error || '';
    entries = model.entries.sort((a, b) => b.savedAt - a.savedAt || a.id.localeCompare(b.id));
    entriesById = new Map(entries.map(entry => [entry.id, entry]));
    groupSavedItems(model.lists);
    if (entryId) {
      pendingMutation = '';
      dismissSheet();
      selected = null;
      history = [];
      const entry = entriesById.get(entryId);
      if (savedAction === 'go_to_notebook' && entry && !entry.filingPending && groups.has(entry.listId)) {
        page = {name: 'folder', value: entry.listId, scroll: 0};
      } else page = {name: 'detail', value: entryId, scroll: 0};
    } else if (showSettings && page.name !== 'settings') {
      dismissSheet();
      selected = null;
      history.push(page);
      page = {name: 'settings', value: '', scroll: 0};
    }
    if (selected) selected = new Set([...selected].filter(id => entriesById.has(id)));
    loaded = true;
    render();
    if (savedAction === 'change_list') moveEntries(entryId);
    else if (savedAction === 'add_note') editEntry(entryId, true);
    if (showSettings || !settingsModel) readSettings();
    if (searchHadFocus) screen.querySelector('#find input')?.focus({preventScroll: true});
    requestSearch(0, true);
    persistView();
  },
  settings(model) {
    settingsModel = model;
    const target = screen.querySelector('#settings-content');
    if (!target) return;
    const focus = target.contains(document.activeElement) ? document.activeElement.dataset : null;
    target.innerHTML = settingsContent();
    if (focus?.action) {
      target.querySelector(`[data-action="${CSS.escape(focus.action)}"][data-value="${CSS.escape(focus.value || '')}"]`)
        ?.focus({preventScroll: true});
    }
  },
  searchResult(request, ids, failed) {
    if (request !== queryRequest || !isSearchPage()) return;
    matching = ids;
    searchError = failed ? 'Couldn’t load these saves.' : '';
    renderResults();
  },
  detailActions(request, id, available, placeStatus) {
    if (request !== poseRequest || page.name !== 'detail' || id !== page.value) return;
    const t = settingText;
    if (available) {
      screen.querySelector('.keys.single')?.insertAdjacentHTML('beforeend',
        button(t('Shoot with this pose', '用这个姿势拍'), 'open-pose', id, 'pill'));
    }
    const placeMessages = {
      offline: t('Place lookup will retry when online.', '联网后会重试查找地点。'),
      geocoder_unavailable: t('Place lookup is temporarily unavailable.', '暂时无法查找地点。'),
      not_found: t('Couldn’t verify a location for an arrival reminder.', '暂时无法确认用于到达提醒的地点。'),
      ambiguous: t('More than one place matched this save.', '这条收藏匹配到多个地点。')
    };
    const message = placeMessages[placeStatus];
    if (message) screen.querySelector('.keys.single')?.insertAdjacentHTML('afterend',
      `<p class="setting-description" role="status">${esc(message)}</p>`);
  },
  mutationResult(error) {
    const action = pendingMutation;
    pendingMutation = '';
    if (!action) return;
    const form = sheet.querySelector('#mutation-form');
    if (action === 'retrySource') {
      announce(error ? mutationErrors[error] || 'Couldn’t start the source lookup.' : 'Looking for the original post.');
      return;
    }
    if (error && form) {
      form.removeAttribute('aria-busy');
      form.querySelector('[type="submit"]').disabled = false;
      form.querySelector('.error').textContent = mutationErrors[error] || 'Couldn’t save this change. Try again.';
      return;
    }
    dismissSheet();
    if (selected) toggleSelection(false);
    if (action === 'delete' && page.name === 'detail') back();
    announce(mutationMessages[action]);
  },
  insets({top, bottom, left, right, keyboard}) {
    root.style.setProperty('--status-inset', top + 'px');
    root.style.setProperty('--bottom-inset', bottom + 'px');
    root.style.setProperty('--left-inset', left + 'px');
    root.style.setProperty('--right-inset', right + 'px');
    root.style.setProperty('--keyboard-inset', keyboard + 'px');
  },
  theme,
  back,
};

const mutationMessages = {
  editEntry: 'Saved item updated.', move: 'Items moved.',
  delete: 'Saved items deleted.', createList: 'List created.', updateList: 'List updated.',
};
const mutationErrors = {
  INVALID_TITLE: 'Enter a title up to 300 characters.',
  INVALID_LIST_NAME: 'Enter a list name up to 100 characters.',
  INVALID_LIST_RULE: 'Enter a rule up to 4,000 characters.',
  INVALID_DESCRIPTION: 'Enter a description up to 4,000 characters.',
  DESCRIPTION_TOO_LONG: 'The full description can have up to 4,000 characters.',
  LIST_NAME_EXISTS: 'A list already has that name. Choose a different name.',
  LIST_NOT_FOUND: 'This list is no longer available.',
  ENTRY_NOT_FOUND: 'A selected item is no longer available.',
  SOURCE_ALREADY_SET: 'The source link is already available.',
  SOURCE_RETRY_UNAVAILABLE: 'This saved item cannot be searched for again.',
};

function announce(message) {
  clearTimeout(statusTimer);
  status.textContent = message;
  statusTimer = setTimeout(() => status.textContent = '', 3200);
}

document.addEventListener('click', event => {
  const control = event.target.closest('[data-action]');
  if (!control || !loaded) return;
  const {action, value = ''} = control.dataset;
  if (Object.hasOwn(pages, action)) {
    navigate(action, value);
    return;
  }
  switch (action) {
    case 'back': back(); break;
    case 'open-search':
      navigate('search');
      document.querySelector('#find input').focus();
      break;
    case 'set-theme': Notebook.setTheme(value); break;
    case 'change-setting': Notebook.changeSetting(value); break;
    case 'retry-source':
      if (!pendingMutation) {
        pendingMutation = 'retrySource';
        Notebook.mutate('retrySource', JSON.stringify({id: value}));
      }
      break;
    case 'list-options': listOptions(value); break;
    case 'edit-list': editList(value); break;
    case 'entry-options': entryOptions(value); break;
    case 'edit-entry': editEntry(value); break;
    case 'move-entry': moveEntries(value); break;
    case 'delete-entry': deleteEntries(value); break;
    case 'start-select': dismissSheet(); toggleSelection(true); break;
    case 'cancel-select': toggleSelection(false); break;
    case 'find-in-list': dismissSheet(); navigate('search', '', {list: value}); break;
    case 'filter': filterSheet(); break;
    case 'dismiss-sheet': dismissSheet(); break;
    case 'run-search': requestSearch(); break;
    case 'sort':
      page.oldest = !page.oldest;
      renderSearchControls('sort');
      requestSearch();
      persistView();
      break;
    case 'clear-filters': applyFilters({}); break;
    case 'load-more':
      persistView();
      page.limit = limit() + pageSize;
      render();
      persistView();
      break;
    case 'open-pose': Notebook.openPose(value); break;
    case 'open-source': Notebook.openSource(value); break;
    case 'copy-source': Notebook.copySource(value); break;
    case 'attach-source': Notebook.attachSource(value); break;
  }
});

document.addEventListener('submit', event => {
  event.preventDefault();
  if (event.target.matches('#find')) {
    event.target.q.blur();
  } else if (event.target.matches('#filter-form')) {
    applyFilters(Object.fromEntries(new FormData(event.target)));
  } else if (event.target.matches('#mutation-form') && !pendingMutation) {
    const form = event.target;
    const method = form.dataset.method;
    const fields = new FormData(form);
    const data = Object.fromEntries(fields);
    if (method === 'batchEntries') data.ids = fields.getAll('ids');
    if (method === 'editEntry' && form.elements.appendDescription) {
      const edits = entryEdits(form);
      if (edits.title === undefined && edits.appendDescription === undefined) return;
      Object.assign(data, edits);
    }
    pendingMutation = data.operation || (data.listId ? 'move' : method);
    form.setAttribute('aria-busy', 'true');
    form.querySelector('[type="submit"]').disabled = true;
    form.querySelector('.error').textContent = '';
    Notebook.mutate(method, JSON.stringify(data));
  }
});

document.addEventListener('change', event => {
  if (!selected || event.target.name !== 'selected-entry') return;
  const {value, checked} = event.target;
  if (!checked) selected.delete(value);
  else if (selected.size < batchLimit) selected.add(value);
  else {
    event.target.checked = false;
    announce(`You can select up to ${batchLimit} items at a time.`);
  }
  updateSelectionBar();
});

document.addEventListener('input', event => {
  if (event.target.closest('#find')) {
    page.query = event.target.value;
    page.limit = pageSize;
    requestSearch(120);
    persistView();
    return;
  }
  const form = event.target.closest('#mutation-form');
  if (form?.dataset.method === 'editEntry' && form.elements.appendDescription && !pendingMutation) {
    const {title, appendDescription} = entryEdits(form);
    form.querySelector('[type="submit"]').disabled = title === undefined && appendDescription === undefined;
  }
});

Notebook.ready();

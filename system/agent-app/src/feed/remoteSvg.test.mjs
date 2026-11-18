import test from 'node:test';
import assert from 'node:assert/strict';
import {fetchRemoteSvg, safeRemoteSvg, MAX_REMOTE_SVG} from './remoteSvg.ts';

const drawing = '<svg xmlns="http://www.w3.org/2000/svg"><defs><path id="shape" d="M0 0"/></defs><use href="#shape" fill="url(#color)"/></svg>';
test('remote SVG permits static geometry and local references only', () => {
  assert.equal(safeRemoteSvg(drawing), true);
  for (const body of [
    '<image href="https://tracking.test/pixel"/>', '<script>alert(1)</script>',
    '<foreignObject/>', '<style>rect {fill:red}</style>', '<use href="http://example.test/a.svg#x"/>',
    '<use href=http://example.test/a.svg#x />', '<use href="\nhttps://example.test/a.svg#x"/>', '<rect fill="url(\nhttps://example.test/x)"/>', '<use xlink:href="&#104;ttp://example.test"/>',
    '<rect fill="url(https://example.test/x)"/>', '<rect style="fill:url(https://example.test/x)"/>',
    '<svg onload="x"/>', '<!DOCTYPE svg>', '<rect fill="u\\72l(http://example.test)"/>',
    '<svg>' + ' '.repeat(MAX_REMOTE_SVG) + '</svg>',
  ]) assert.equal(safeRemoteSvg(`<svg>${body}</svg>`), false, body.slice(0, 100));
});

test('remote SVG rejects HTTP without fetching, MIME confusion, redirects and oversized responses', async () => {
  const original = globalThis.fetch;
  let calls = 0, reply, options;
  globalThis.fetch = async (_uri, init) => { calls++; options = init; return reply; };
  const response = (overrides = {}) => ({ok: true, url: 'https://example.test/a.svg', redirected: false,
    headers: new Headers({'Content-Type': 'image/svg+xml; charset=utf-8'}), text: async () => drawing, ...overrides});
  try {
    assert.equal(await fetchRemoteSvg('http://example.test/a.svg'), null);
    assert.equal(calls, 0);
    reply = response();
    assert.equal(await fetchRemoteSvg('https://example.test/a.svg'), drawing);
    assert.equal(options.credentials, 'omit'); assert.equal(options.redirect, 'error');
    for (const overrides of [{ok: false}, {redirected: true}, {url: 'http://example.test/a.svg'},
      {headers: new Headers({'Content-Type': 'text/html'})},
      {headers: new Headers({'Content-Type': 'image/svg+xml', 'Content-Length': String(MAX_REMOTE_SVG + 1)})},
      {text: async () => '<svg><image href="https://other.test/pixel"/></svg>'}]) {
      reply = response(overrides);
      assert.equal(await fetchRemoteSvg('https://example.test/a.svg'), null);
    }
  } finally { globalThis.fetch = original; }
});

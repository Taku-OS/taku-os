import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const icons = JSON.parse(readFileSync(new URL('./icons.json', import.meta.url), 'utf8'));
const attribute = (xml, name) => new RegExp(`android:${name}="([^"]*)"`).exec(xml)?.[1];

test('React icons preserve native path geometry and paint instead of filling open strokes', () => {
  for (const [name, icon] of Object.entries(icons)) {
    const xml = readFileSync(new URL(`../../../assistant/res/drawable/ic_taku_${name}.xml`, import.meta.url), 'utf8');
    assert.equal(icon.viewBox, `0 0 ${attribute(xml, 'viewportWidth')} ${attribute(xml, 'viewportHeight')}`, name);
    const nativePaths = [...xml.matchAll(/<path\s[^>]*\/>/g)].map(match => match[0]);
    assert.equal(icon.paths.length, nativePaths.length, name);
    for (const [index, path] of icon.paths.entries()) {
      const source = nativePaths[index];
      assert.equal(path.d, attribute(source, 'pathData'), `${name}: path`);
      assert.equal(path.fill, attribute(source, 'fillColor') ? 'currentColor' : 'none', `${name}: fill`);
      assert.equal(path.fillRule, attribute(source, 'fillType') === 'evenOdd' ? 'evenodd' : 'nonzero', `${name}: fill rule`);
      if (attribute(source, 'strokeColor')) {
        assert.equal(path.stroke, 'currentColor', `${name}: stroke`);
        assert.equal(path.strokeWidth, Number(attribute(source, 'strokeWidth') || '0'), `${name}: stroke width`);
        assert.equal(path.strokeLinecap, attribute(source, 'strokeLineCap') || 'butt', `${name}: line cap`);
        assert.equal(path.strokeLinejoin, attribute(source, 'strokeLineJoin') || 'miter', `${name}: line join`);
      } else assert.equal(path.stroke, undefined, `${name}: no invented stroke`);
    }
  }
});

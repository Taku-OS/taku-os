import assert from 'node:assert/strict';
import {test} from 'node:test';
await import('../tooling/go2-lab/web/geo-projection.js');
const {pixel,coordinate}=globalThis.go2GeoProjection;
const view={center:[116.4,39.9],zoom:15,width:600,height:400};

test('matches live AMap static-map marker separation, not just an inverse roundtrip',()=>{
 // 2026-10-27 scale=1 response: marker A tip x≈299, B tip x≈438.
 const a=pixel([116.4,39.9],view),b=pixel([116.403,39.9],view);
 assert.deepEqual(a,[300,200]);
 assert.ok(Math.abs((b[0]-a[0])-139.8)<1);
 assert.equal(b[1],200);
});
test('map clicks invert projection at multiple zoom levels and north stays up',()=>{
 for(const zoom of [1,10,15,17])for(const p of [[0,0],[300,200],[599,399]]){
  const v={...view,zoom},back=pixel(coordinate(p,v),v);
  assert.ok(Math.hypot(back[0]-p[0],back[1]-p[1])<1e-6);
 }
 assert.ok(pixel([116.4,39.901],view)[1]<200);
});

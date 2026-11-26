import {test} from 'node:test';
import assert from 'node:assert/strict';
import {candidates,ClothingTracker} from '../tooling/go2-lab/web/follow-tracker.js';
const box=(x=.4,y=.3,w=.15,h=.5)=>({x,y,w,h,fill:.6});
test('tracks selected dark region and reports image scale without meters',()=>{
 const t=new ClothingTracker();assert.ok(t.select([box()],null,100));
 t.update([box(.42,.3,.14,.45)],200);assert.equal(t.view(200).state,'tracking');
 for(let i=0;i<4;i++)t.update([box(.44,.4,.1,.32)],300+i*50);
 assert.equal(t.view(450).distance,'farther');assert.equal(t.view(450).direction,'forward');
 assert.equal(t.view(450).meters,undefined);
});
test('loss and stale frames never silently switch to another target',()=>{
 const t=new ClothingTracker();t.select([box()],null,0);
 for(let i=1;i<=3;i++)assert.equal(t.update([box(.8)],i*100),null);
 assert.equal(t.view(300).state,'lost');assert.equal(t.update([box()],400),null);
 assert.ok(t.select([box()],[.45,.5],500));assert.equal(t.view(1300).state,'lost');
 assert.equal(t.update([box()],1300),null);
});
test('ambiguous targets require explicit selection',()=>{
 const t=new ClothingTracker();assert.equal(t.select([box(.3),box(.55)],null,0),false);
 assert.ok(t.select([box(.3),box(.55)],[.35,.5],0));
});
test('dark trousers segmentation excludes red walls and clipped objects',()=>{
 const w=120,h=80,p=new Uint8ClampedArray(w*h*4).fill(220);
 const fill=(x0,x1,y0,y1,r,g,b)=>{for(let y=y0;y<y1;y++)for(let x=x0;x<x1;x++){const i=(y*w+x)*4;p[i]=r;p[i+1]=g;p[i+2]=b;}};
 fill(50,70,20,70,20,23,27);fill(3,30,10,65,90,20,20);fill(0,7,20,75,10,10,10);
 const list=candidates(p,w,h);assert.equal(list.length,1);assert.ok(Math.abs(list[0].x-50/w)<.01);
});

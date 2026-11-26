import test from 'node:test';
import assert from 'node:assert/strict';
import {PersonFollower,peopleFromLandmarks} from '../tooling/go2-lab/web/follow-person.js';
const person=(x=.4,scale=.4)=>({x,y:.1,w:.2,h:.7,scale,match:true});
test('body detector never invents a person from absent landmarks',()=>{
 assert.deepEqual(peopleFromLandmarks([]),[]);
 const pose=Array.from({length:33},()=>({x:.5,y:.5,visibility:0}));
 assert.deepEqual(peopleFromLandmarks([pose]),[]);
});
test('one target auto-selects; multiple people require an explicit tap',()=>{
 const f=new PersonFollower();assert.equal(f.select([person(),person(.7)],null,0),false);
 assert.equal(f.select([person(),person(.7)],[.5,.4],0),true);
 assert.equal(f.view(100).state,'tracking');
});
test('distance and meters come from measured scale, not a timer',()=>{
 const f=new PersonFollower();f.select([person()],null,0);
 assert.equal(f.view(100).change,0);assert.equal(f.view(100).meters,null);
 assert.equal(f.calibrate(2,100),true);
 for(let i=1;i<=8;i++)f.update([person(.4,.3)],i*100);
 assert.ok(f.view(850).change>30);assert.ok(f.view(850).meters>2.6);
 assert.equal(f.view(850).direction,'forward');
 assert.equal(f.calibrate(NaN,850),false);
});
test('brief missed detections recover without a manual reset or stale distance',()=>{
 const f=new PersonFollower();f.select([person()],null,0);
 for(let i=1;i<=3;i++)f.update([],i*250);
 assert.equal(f.view(800).state,'reacquiring');assert.equal(f.view(800).box,null);
 assert.equal(f.update([person()],900),false);
 assert.equal(f.update([person()],1100),true);
 assert.equal(f.view(1150).state,'tracking');
});
test('longer re-entry needs matching appearance, while ambiguous or expired targets require selection',()=>{
 const target={...person(),appearance:{lower:[1,0,0]}};
 const f=new PersonFollower();f.select([target],null,0);
 assert.equal(f.update([{...person(),appearance:{lower:[0,1,0]}}],2000),false);
 assert.equal(f.update([target],2300),false);assert.equal(f.update([target],2550),true);
 assert.equal(f.view(2600).state,'tracking');
 assert.equal(f.update([target,{...target,x:.41}],4000),false);
 assert.equal(f.view(4100).box,null);
 assert.equal(f.update([target],15000),false);assert.equal(f.view(15000).state,'lost');
 assert.equal(f.select([target],null,15100),true);
});
test('no appearance match means a long disappearance cannot silently pick a nearby stranger',()=>{
 const f=new PersonFollower();f.select([person()],null,0);
 assert.equal(f.update([person()],3000),false);assert.equal(f.update([person()],3300),false);
 assert.equal(f.view(3350).box,null);
});
test('turning suggestion needs stable observations and clears on a new target',()=>{
 const f=new PersonFollower();f.select([person(.2)],null,0);
 f.update([person(.2)],100);assert.equal(f.view(100).direction,'hold');
 f.update([person(.2)],200);assert.equal(f.view(200).direction,'left');
 f.select([person(.6)],null,300);assert.equal(f.view(300).direction,'hold');
});

function pose(){
 const p=Array.from({length:33},()=>({x:.5,y:.5,visibility:0}));
 for(const [i,x,y] of [[11,.4,.2],[12,.6,.2],[23,.43,.45],[24,.57,.45],[25,.43,.7],[26,.57,.7],[27,.43,.92],[28,.57,.92]])p[i]={x,y,visibility:.8};
 return p;
}
test('visible torso and thighs remain detectable when both ankles are outside the frame',()=>{
 const p=pose();p[27].visibility=p[28].visibility=0;
 const found=peopleFromLandmarks([p]);assert.equal(found.length,1);assert.ok(found[0].measurements['11-23']);
});
test('single visible side is enough when a coherent shoulder, hip, knee and ankle are present',()=>{
 const p=pose();for(const i of [12,24,26,28])p[i].visibility=0;
 assert.equal(peopleFromLandmarks([p]).length,1);
});
test('changing visible joints does not change distance when their common body segment stays the same',()=>{
 const f=new PersonFollower();f.select([{...person(),measurements:{torso:.2,thigh:.25}}],null,0);
 f.update([{...person(),measurements:{thigh:.25,shin:.3}}],200);
 assert.equal(f.view(250).change,0);
 f.update([{...person(),measurements:{shin:.3}}],400);assert.equal(f.view(450).change,0);
 f.update([{...person(),measurements:{unrelated:.9}}],600);assert.equal(f.view(650).change,null);
 assert.equal(f.calibrate(2,650),false);
});
test('appearance is sampled from the current image and missing pixels do not create a fingerprint',()=>{
 const p=pose();assert.deepEqual(peopleFromLandmarks([p])[0].appearance,{});
 const data=new Uint8ClampedArray(100*100*4);for(let i=0;i<data.length;i+=4){data[i]=data[i+1]=data[i+2]=20;data[i+3]=255;}
 const found=peopleFromLandmarks([p],data,100,100)[0];assert.equal(found.appearance.lower[0],1);
});

test('continuous spatial tracking tolerates a clothing histogram change caused by exposure',()=>{
 const f=new PersonFollower();f.select([{...person(),appearance:{lower:[1,0,0]}}],null,0);
 assert.equal(f.update([{...person(.42),appearance:{lower:[0,1,0]}}],250),true);
 assert.equal(f.view(300).state,'tracking');
});
test('hip anchor continues tracking when cropping changes the visible bounding box',()=>{
 const f=new PersonFollower();f.select([{...person(),anchor:[.5,.5]}],null,0);
 assert.equal(f.update([{...person(),y:.45,h:.3,anchor:[.51,.5]}],250),true);
 assert.equal(f.view(300).state,'tracking');
});

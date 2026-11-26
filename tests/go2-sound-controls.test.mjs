import test from 'node:test';
import assert from 'node:assert/strict';
import {SoundControls} from '../tooling/go2-lab/web/audio-library-controls.js';
const state={clip:{presetId:'hello'},volume:null,state:'idle',connected:true};
test('play reads volume first and aborts when it is not confirmed',async()=>{
 const calls=[];const c=new SoundControls(async a=>{calls.push(a);return {...state,error:'VOLUME_REJECTED'}},()=>{});c.state=state;
 await c.play();assert.deepEqual(calls,['refresh']);assert.equal(c.error,'VOLUME_REJECTED');
});
test('stop invalidates an in-flight refresh and never queues a late play',async()=>{
 let finish;const calls=[];const c=new SoundControls(a=>{calls.push(a);return a==='refresh'?new Promise(r=>finish=r):Promise.resolve({...state,state:'idle'})},()=>{});c.state=state;
 const playing=c.play();await c.stop();finish({...state,volume:2});await playing;
 assert.deepEqual(calls,['refresh','stop']);assert.equal(c.pending,false);
});
test('selecting another clip ends the current stream before selecting',async()=>{
 const calls=[];const c=new SoundControls(async(a,b)=>{calls.push([a,b]);return {...state,volume:3}},()=>{});c.state={...state,state:'streaming'};
 await c.select('thanks');assert.deepEqual(calls,[['stop',{}],['select',{id:'thanks'}]]);
});
test('volume reports the server readback and propagates mismatch errors',async()=>{
 const c=new SoundControls(async()=>({...state,volume:4,error:'VOLUME_MISMATCH'}),()=>{});c.state={...state,volume:2};
 await c.volume(5);assert.equal(c.state.volume,4);assert.equal(c.error,'VOLUME_MISMATCH');
});

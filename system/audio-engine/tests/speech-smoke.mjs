// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import {TakuClient} from '../../../sdk/client.mjs';
const client=new TakuClient({transport:'adb',serial:process.env.TAKU_OS_SERIAL,adb:process.env.ADB});
const reports=[];
try {
 const caps=await client.call('audio.capabilities');assert.equal(caps.ttsResourcesReady,true);assert.equal(caps.voices.length,6);
 for(const [language,texts] of [['en-US',['Hello.','The meeting is tomorrow at three.']],['zh-CN',['你好。','明天下午三点开会。']]]) {
  const events=client.audioEvents();
  const request={purpose:'SPEAK',language,requestKey:`tts-smoke-${language}`};
  const started=performance.now();const session=await client.call('audio.session.open',request);
  assert.equal((await client.call('audio.session.open',request)).sessionId,session.sessionId);
  for(let index=0;index<texts.length;index++) {
   await client.call('audio.session.append',{sessionId:session.sessionId,index,text:texts[index]});
   await client.call('audio.session.append',{sessionId:session.sessionId,index,text:texts[index]});
  }
  await client.call('audio.session.finish',{sessionId:session.sessionId});
  const chunks=[];let firstPcmMs;let readyMs;let firstAfterReadyMs;let closed=false;
  const timeout=setTimeout(()=>client.close(),60000);
  try {for await(const e of events) {
   const d=e.data;if(d.sessionId!==session.sessionId)continue;
   if(d.kind==='Running')readyMs=performance.now();
   if(d.kind==='Playback' && d.playbackState==='audiblePcm' && firstPcmMs===undefined){firstPcmMs=Math.round(performance.now()-started);firstAfterReadyMs=Math.round(performance.now()-readyMs);}
   if(d.kind==='Playback' && d.playbackState==='chunkComplete'){assert.ok(d.frames>0);assert.ok(d.nonSilent);chunks.push(d.chunkIndex);}
   if(d.kind==='Closed'){assert.equal(d.reason,'completed');assert.equal(d.completion,'complete');assert.equal(d.restored,true);closed=true;break;}
  }} finally {clearTimeout(timeout);}
  assert.ok(closed);assert.deepEqual(chunks,[0,1]);assert.ok(firstPcmMs>0);
  reports.push({language,chunks,firstWrittenNonSilentPcmMs:firstPcmMs,readyToFirstWrittenNonSilentPcmMs:firstAfterReadyMs,complete:true});
 }
 const events=client.audioEvents();
 const canceled=await client.call('audio.session.open',{purpose:'SPEAK',language:'en-US'});
 await client.call('audio.session.append',{sessionId:canceled.sessionId,index:0,text:'This is a longer reading that will be stopped before the entire sentence finishes playing.'});
 let restored=false;const timeout=setTimeout(()=>client.close(),30000);
 try{for await(const e of events){const d=e.data;if(d.sessionId!==canceled.sessionId)continue;if(d.kind==='Playback'&&d.playbackState==='audiblePcm')await client.call('audio.session.close',{sessionId:canceled.sessionId});if(d.kind==='Closed'){assert.equal(d.reason,'canceled');assert.ok(d.restored);restored=true;break;}}}finally{clearTimeout(timeout);}
 assert.ok(restored);
 console.log(JSON.stringify({passed:true,reports,cancelDuringPlayback:true,requestDeduplication:true,appendDeduplication:true,physicalOutputVerified:false,qualityApproved:false,scope:'real Kokoro synthesis and consumed AudioTrack frames; not physical speaker or quality certification'}));
}finally{client.close();}

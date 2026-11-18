import assert from 'node:assert/strict';
import {TakuClient} from '../../../sdk/client.mjs';
const options={transport:'adb',serial:process.env.TAKU_OS_SERIAL,adb:process.env.ADB};
const client=new TakuClient(options), other=new TakuClient(options);
const events=client.audioEvents();
const seen=[];
try {
 const capabilities=await client.call('audio.capabilities');assert.equal(capabilities.enabled,true);assert.equal(capabilities.resourcesReady,true);
 const opened=await client.call('audio.session.open',{purpose:'DICTATION',language:'zh-CN'});
 await assert.rejects(()=>other.call('audio.session.status',{sessionId:opened.sessionId}), e=>e.code==='PERMISSION_DENIED');
 await assert.rejects(()=>other.call('audio.session.open',{}), e=>e.code==='RESOURCE_BUSY');
 const deadline=setTimeout(()=>client.close(),15000);
 try {
  for await(const e of events) {
   const data=e.data;if(data.sessionId!==opened.sessionId)continue;
   seen.push(data.kind);
   if(data.kind==='Running')setTimeout(()=>client.call('audio.session.finish',{sessionId:opened.sessionId}).catch(()=>client.close()),1500);
   if(data.kind==='Closed') {assert.equal(data.restored,true);assert.equal(data.reason,'completed');break;}
  }
 }finally{clearTimeout(deadline);}
 assert.ok(seen.includes('Running'));assert.ok(seen.includes('Draining'));assert.ok(seen.includes('Closed'));
 const reopened=await client.call('audio.session.open',{});
 await client.call('audio.session.close',{sessionId:reopened.sessionId});
 console.log(JSON.stringify({passed:true,events:seen,ownership:'passed',resourceBusy:'passed',reopen:'passed',scope:'actual microphone lifecycle; silence is not recognition quality evidence'}));
}finally{await events.return();client.close();other.close();}

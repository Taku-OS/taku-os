import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {TakuClient} from '../../../sdk/client.mjs';
const adb=process.env.ADB ?? 'adb',serial=process.env.TAKU_OS_SERIAL;
const quote=value=>"'"+value.replaceAll("'","'\\''")+"'";
function settings(action,patch) {
 const request={op:'systemAi',action};if(patch)request.patch=patch;
 const command='CLASSPATH=/data/local/tmp/taku-audio-native-probe.jar app_process /system/bin org.agentos.tests.NativeSocketProbe exchange '+quote(JSON.stringify(request));
 return JSON.parse(execFileSync(adb,['-s',serial,'shell',command],{encoding:'utf8',timeout:15000}));
}
const before=settings('read');assert.equal(before.enabled,true);assert.equal(before.audioEnabled ?? true,true);
const client=new TakuClient({transport:'adb',serial,adb});const events=client.audioEvents();let changed=false;let timer;
try {
 const opened=await client.call('audio.session.open',{});
 timer=setTimeout(()=>client.close(),15000);
 let closed=false;
 for await(const event of events) {
  const data=event.data;if(data.sessionId!==opened.sessionId)continue;
  if(data.kind==='Running'){changed=true;settings('configure',{audioEnabled:false});}
  if(data.kind==='Closed'){assert.equal(data.reason,'POLICY_REVOKED');assert.equal(data.restored,true);closed=true;break;}
 }
 assert.ok(closed);
 await assert.rejects(()=>client.call('audio.session.open',{}),e=>e.code==='POLICY_DISABLED');
 console.log(JSON.stringify({passed:true,revocation:'actual microphone stopped and resources released',newSessionDenied:true}));
}finally{clearTimeout(timer);if(changed)settings('configure',{audioEnabled:before.audioEnabled ?? true});await events.return();client.close();}

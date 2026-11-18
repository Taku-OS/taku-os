// SPDX-License-Identifier: Apache-2.0
// Run only against the disposable review AVD with networking disabled and a dummy key.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
import {TakuClient} from '../../../sdk/client.mjs';
const model=process.argv[2];
assert.ok(['kotoba','grok','whisper','missing'].includes(model));
const client=new TakuClient({transport:'adb',serial:process.env.TAKU_OS_SERIAL,adb:process.env.ADB});
const reports=[];
const deadline=setTimeout(()=>client.close(),45000);
try {
 let caps;
 for(let i=0;i<50;i++) {
  caps=await client.call('audio.capabilities');
  if(model==='missing' ? !caps.falRealtimeAvailable : caps.falRealtimeAvailable&&caps.defaultMediaProfile===`fal-${model}`)break;
  await delay(100);
 }
 assert.equal(caps.falRealtimeAvailable,model!=='missing');
 if(model!=='missing')assert.equal(caps.defaultMediaProfile,`fal-${model}`);
 const adb=(...args)=>execFileSync(process.env.ADB,['-s',process.env.TAKU_OS_SERIAL,'shell',...args],{stdio:'ignore'});
 const base={purpose:'MEDIA_TRANSLATE',language:'en-US',targetLanguage:'zh-CN',targetPackage:'org.taku.callfixture.target'};
 if(model==='missing') {
  await assert.rejects(()=>client.call('audio.session.open',base),error=>error.code==='PROVIDER_NOT_CONFIGURED');
  reports.push({missingKeyReason:'PROVIDER_NOT_CONFIGURED'});
 } else {
  if(model==='grok')base.voiceId='eve';
  if(model==='whisper')await assert.rejects(()=>client.call('audio.session.open',{...base,output:'SPEECH'}),error=>error.code==='INVALID_PARAMS');
  if(model==='kotoba')await assert.rejects(()=>client.call('audio.session.open',{...base,voiceId:'eve'}),error=>error.code==='INVALID_PARAMS');
  for(const purpose of ['MEDIA_TRANSLATE','CALL_TRANSLATE']) {
   if(purpose==='CALL_TRANSLATE')adb('am','start','-W','-n','org.taku.callfixture.target/org.taku.callfixture.CallFixture');
   else adb('am','force-stop','org.taku.callfixture.target');
   adb('am','start','-W','-n','org.agentos.audio/.AudioHostActivity');
   await delay(500);
   const events=client.audioEvents();
   const request={...base,purpose,...(purpose==='CALL_TRANSLATE'?{output:'SPEECH',originalAudio:'REPLACE'}:{})};
   const session=await client.call('audio.session.open',request);
   assert.equal(session.profileId,`fal-${model==='whisper'&&purpose==='CALL_TRANSLATE'?'kotoba':model}`);
   assert.equal(session.outgoingProfileId,session.profileId);
   await client.call('audio.session.close',{sessionId:session.sessionId});
   let closed;
   for await(const event of events) {
    if(event.data.sessionId===session.sessionId&&event.data.kind==='Closed'){closed=event.data;break;}
   }
   assert.ok(closed);assert.equal(closed.restored,true);
   assert.equal((await client.call('audio.capabilities')).resourceState.busy,false);
   if(purpose==='CALL_TRANSLATE')adb('am','force-stop','org.taku.callfixture.target');
   reports.push({purpose,profile:session.profileId,phaseAtOpen:session.phase,closedReason:closed.reason,restored:closed.restored});
  }
 }
 console.log(JSON.stringify({passed:true,model,reports,cloudTranslationVerified:false}));
} finally {clearTimeout(deadline);client.close();}

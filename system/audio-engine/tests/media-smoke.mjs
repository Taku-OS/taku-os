// SPDX-License-Identifier: Apache-2.0
// Paid fal smoke: run only against the separately installed synthetic media fixture.
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {TakuClient} from '../../../sdk/client.mjs';
const client=new TakuClient({transport:'adb',serial:process.env.TAKU_OS_SERIAL,adb:process.env.ADB});
const profile=process.env.TAKU_MEDIA_PROFILE ?? 'fal-kotoba';
assert.ok(['fal-kotoba','fal-whisper'].includes(profile));
const captions=profile==='fal-whisper';
const events=client.audioEvents();let finishTimer,timeout;
try {
 const caps=await client.call('audio.capabilities');assert.ok(caps.falConfigured && caps.mediaRoutingGranted);
 const session=await client.call('audio.session.open',{purpose:'MEDIA_TRANSLATE',profileId:profile,language:'en-US',targetLanguage:'zh-CN',targetPackage:'org.taku.callfixture.target',output:captions?'CAPTIONS':'SPEECH',originalAudio:captions?'KEEP':'REPLACE'});
 let readyMs,firstAudioMs,firstTextMs,text=false,source=false,played=false,closed=false;
 timeout=setTimeout(()=>client.close(),90000);
 for await(const event of events) {
  const d=event.data;if(d.sessionId!==session.sessionId)continue;
  if(d.kind==='Running'){readyMs=d.monotonicMs;finishTimer=setTimeout(()=>client.call('audio.session.finish',{sessionId:session.sessionId}).catch(()=>client.close()),8000);}
  if(d.kind==='Text'){if(d.textKind==='target'){text ||= /[\u3400-\u9fff]/u.test(d.text);firstTextMs ??= d.monotonicMs;}else if(captions && d.textKind==='source')source ||= d.text.length>0;else assert.fail('Unexpected source text');}
  if(captions)assert.notEqual(d.kind,'Playback','Caption profile must not create playback');
  if(d.kind==='Playback' && d.playbackState==='audiblePcm' && firstAudioMs===undefined)firstAudioMs=d.monotonicMs;
  if(d.kind==='Playback' && d.playbackState==='chunkComplete'){played=d.frames>0&&d.nonSilent;}
  if(d.kind==='Closed'){assert.equal(d.reason,'completed');assert.equal(d.completion,'complete');assert.ok(d.restored);closed=true;break;}
 }
 assert.ok(closed && text);
 if(captions)assert.ok(source && !played);else assert.ok(played && firstAudioMs>=readyMs);
 console.log(JSON.stringify({passed:true,profile,source:'separate UID synthetic video',output:captions?'CAPTIONS':'SPEECH',originalAudio:captions?'KEEP':'REPLACE',targetText:true,sourceText:source,consumedNonSilentPcm:played,complete:true,restored:true,readyToFirstTranslationMs:firstTextMs-readyMs,readyToFirstWrittenNonSilentPcmMs:captions?null:firstAudioMs-readyMs,physicalOutputVerified:false,qualityApproved:false}));
}finally{
 clearTimeout(finishTimer);clearTimeout(timeout);
 try{await events.return();client.close();}
 finally{
  // This runner exclusively targets the synthetic fixture. Do not leave its loop audible after exit.
  const serial=process.env.TAKU_OS_SERIAL;
  const args=[...(serial?['-s',serial]:[]),'shell','am','force-stop','org.taku.callfixture.target'];
  const stopped=spawnSync(process.env.ADB??'adb',args,{encoding:'utf8',timeout:10000});
  if(stopped.status!==0){console.error('Synthetic media fixture did not stop; close its player.');process.exitCode=1;}
 }
}

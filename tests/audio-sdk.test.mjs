import test from 'node:test';
import assert from 'node:assert/strict';
import {TakuClient} from '../sdk/client.mjs';
import {TakuFacade} from '../core/facade.mjs';
const request = (method,params) => ({protocol:'taku/0.1',type:'request',id:'test',method,params});
test('audio arguments are validated before transport, existing probes keep closed params',async()=>{
 const facade=new TakuFacade({call(){throw Error('Must not run');}});
 for(const [method,params] of [['audio.session.open',{language:'bad'}],['audio.session.open',{key:'secret'}],['audio.session.close',{}],['system.info',{language:'zh-CN'}]])
  assert.equal((await facade.handle(request(method,params))).error.code,'INVALID_PARAMS');
});

test('reading arguments support bounded, indexed chunks and reject unsupported profiles',async()=>{
 const client=new TakuClient({transportInstance:{async call(method,params){return {method,params};}}});
 for(const [method,params] of [['audio.session.open',{purpose:null}],['audio.session.open',{purpose:'SPEAK',profileId:'zipformer'}],['audio.session.open',{speed:0}],['audio.session.append',{sessionId:'audio-1',index:-1,text:'Hello'}],['audio.session.append',{sessionId:'audio-1',index:0,text:'x'.repeat(501)}]])
  await assert.rejects(()=>client.call(method,params),e=>e.code==='INVALID_PARAMS');
});

test('media translation keeps explicit app/language scope and disallows invalid output modes',async()=>{
 const client=new TakuClient({transportInstance:{async call(method,params){return params;}}});
 const media={purpose:'MEDIA_TRANSLATE',language:'en-US',targetLanguage:'zh-CN',targetPackage:'com.android.chrome',profileId:'fal-kotoba'};
 for(const patch of [{output:'SPEECH'},{purpose:'CALL_TRANSLATE'},{voiceId:'eve'}])await assert.rejects(()=>client.call('audio.session.open',{...media,profileId:'fal-whisper',...patch}),e=>e.code==='INVALID_PARAMS');
 for(const patch of [{targetPackage:''},{targetLanguage:'en-US'},{originalAudio:'REPLACE'},{voiceId:'kokoro'},{speed:1.5},{profileId:'fal-unknown'},{targetLanguage:null}])
  await assert.rejects(()=>client.call('audio.session.open',{...media,...patch}),e=>e.code==='INVALID_PARAMS');
 await assert.rejects(()=>client.call('audio.session.open',{purpose:'DICTATION',targetPackage:'com.android.chrome'}),e=>e.code==='INVALID_PARAMS');
});

 test('call translation preserves independent directions and requires bidirectional speech',async()=>{
 const client=new TakuClient({transportInstance:{async call(method,params){return params;}}});
 const call={purpose:'CALL_TRANSLATE',language:'en-US',targetLanguage:'zh-CN',targetPackage:'jp.naver.line.android',profileId:'local-hybrid',outgoingProfileId:'fal-grok',outgoingVoiceId:'eve',output:'SPEECH',originalAudio:'REPLACE'};
 for(const patch of [{output:'CAPTIONS'},{originalAudio:'KEEP'},{outgoingProfileId:'fal-kotoba',outgoingVoiceId:'eve'}])await assert.rejects(()=>client.call('audio.session.open',{...call,...patch}),e=>e.code==='INVALID_PARAMS');
 });


test('capability filters preserve purpose/language and reject unsupported values',async()=>{
 const client=new TakuClient({transportInstance:{async call(method,params){return params;}}});
 for(const params of [{purpose:'fake'},{language:'xx'},{language:null},{key:'secret'}])await assert.rejects(()=>client.call('audio.capabilities',params),e=>e.code==='INVALID_PARAMS');
});

test('interruptible reading is explicit and limited to SPEAK',async()=>{
 const client=new TakuClient({transportInstance:{async call(method,params){return params;}}});
 for(const params of [{purpose:'DICTATION',interruptible:true},{purpose:'DICTATION',interruptible:false},{purpose:'SPEAK',interruptible:null},{purpose:'SPEAK',interruptible:'true'}])
  await assert.rejects(()=>client.call('audio.session.open',params),e=>e.code==='INVALID_PARAMS');
});

test('scoped dictation rejects backend overrides and unrelated purposes',async()=>{
 const client=new TakuClient({transportInstance:{async call(){throw Error('Invalid scope reached transport');}}});
 const scoped={purpose:'DICTATION',settingsScope:'assistant-entry'};
 for(const [method,params] of [
  ['audio.session.open',{...scoped,language:'zh-CN'}],
  ['audio.session.open',{...scoped,profileId:'zipformer'}],
  ['audio.session.open',{...scoped,purpose:'SPEAK'}],
  ['audio.capabilities',{...scoped,language:'en-US'}],
  ['audio.capabilities',{...scoped,purpose:'MEDIA_TRANSLATE'}],
  ['audio.session.open',{...scoped,settingsScope:'full-chat'}],
  ['audio.capabilities',{...scoped,settingsScope:null}],
 ])await assert.rejects(()=>client.call(method,params),e=>e.code==='INVALID_PARAMS');
});

test('Whisper dictation rejects speech output options and unsupported languages',async()=>{
 const client=new TakuClient({transportInstance:{async call(){throw Error('Invalid dictation reached transport');}}});
 const dictation={purpose:'DICTATION',profileId:'fal-whisper'};
 for(const patch of [{voiceId:'eve'},{speed:1.5},{language:'ja-JP'},{output:'SPEECH'}])
  await assert.rejects(()=>client.call('audio.session.open',{...dictation,...patch}),e=>e.code==='INVALID_PARAMS');
});

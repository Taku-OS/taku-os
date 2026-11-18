// SPDX-License-Identifier: Apache-2.0
import {TakuError} from './protocol.mjs';
import {AUDIO_METHODS} from './audio-method-names.mjs';
export {AUDIO_METHODS};
export function validateAudioParams(method, params) {
  const keys = method === 'audio.session.open' ? ['purpose','settingsScope','language','profileId','voiceId','speed','requestKey','targetLanguage','targetPackage','output','originalAudio','outgoingProfileId','outgoingVoiceId','outgoingSpeed','interruptible'] : method === 'audio.capabilities' ? ['purpose','settingsScope','language'] : method === 'audio.session.append' ? ['sessionId','index','text'] : ['sessionId'];
  if(Object.keys(params).some(key=>!keys.includes(key))) throw new TakuError('INVALID_PARAMS','Unknown audio parameter');
  if(params.settingsScope !== undefined && (params.settingsScope !== 'assistant-entry' || (params.purpose ?? 'DICTATION') !== 'DICTATION' || params.language !== undefined || params.profileId !== undefined)) throw new TakuError('INVALID_PARAMS','Use assistant-entry scope only for DICTATION without a language or profile override');
  if(method === 'audio.capabilities') {
    if(params.purpose!==undefined&&!['DICTATION','SPEAK','MEDIA_TRANSLATE','CALL_TRANSLATE'].includes(params.purpose))throw new TakuError('INVALID_PARAMS','Unsupported audio purpose');
    if(params.language!==undefined&&!['zh-CN','en-US','ja-JP','ko-KR'].includes(params.language))throw new TakuError('INVALID_PARAMS','Unsupported language');
  }
  if(method === 'audio.session.open') {
    const purpose=params.purpose === undefined ? 'DICTATION' : params.purpose;
    if(!['DICTATION','SPEAK','MEDIA_TRANSLATE','CALL_TRANSLATE'].includes(purpose)) throw new TakuError('INVALID_PARAMS','Unsupported audio purpose');
    if(params.interruptible!==undefined && (typeof params.interruptible!=='boolean'||purpose!=='SPEAK'))throw new TakuError('INVALID_PARAMS','Speech interruption requires SPEAK and a boolean');
    const call=purpose==='CALL_TRANSLATE', media=['MEDIA_TRANSLATE','CALL_TRANSLATE'].includes(purpose), languages=media?['zh-CN','en-US','ja-JP','ko-KR']:['zh-CN','en-US'];
    if(params.language !== undefined && !languages.includes(params.language)) throw new TakuError('INVALID_PARAMS','Unsupported language');
    if(params.profileId !== undefined && !(media?['fal-kotoba','fal-grok','local-hybrid','fal-whisper']:purpose==='SPEAK'?['kokoro']:['zipformer','fal-whisper']).includes(params.profileId)) throw new TakuError('INVALID_PARAMS','Profile does not support the selected purpose');
    if(params.voiceId !== undefined && (typeof params.voiceId!=='string'||params.voiceId.length>128)) throw new TakuError('INVALID_PARAMS','Invalid voice ID');
    if(params.speed !== undefined && (!Number.isFinite(params.speed)||params.speed<.5||params.speed>2)) throw new TakuError('INVALID_PARAMS','Speech speed must be 0.5–2');
    if(params.requestKey !== undefined && (typeof params.requestKey!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(params.requestKey))) throw new TakuError('INVALID_PARAMS','Invalid request key');
    if(purpose==='DICTATION' && params.profileId==='fal-whisper' && ((params.voiceId??'')!=='' || (params.speed??1)!==1))throw new TakuError('INVALID_PARAMS','Whisper dictation does not support a voice or speech speed');
    if(media) {
      const {targetLanguage='zh-CN',output='CAPTIONS',originalAudio='KEEP'}=params;
      if(!languages.includes(targetLanguage) || (params.language??'zh-CN')===targetLanguage)throw new TakuError('INVALID_PARAMS','Choose different translation languages');
      if(typeof params.targetPackage!=='string'||!/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/.test(params.targetPackage))throw new TakuError('INVALID_PARAMS','A target package is required');
      if(!['CAPTIONS','SPEECH'].includes(output)||!['KEEP','REPLACE'].includes(originalAudio)||output==='CAPTIONS'&&originalAudio==='REPLACE')throw new TakuError('INVALID_PARAMS','Captions require original audio');
      if(params.profileId==='fal-whisper' && (purpose!=='MEDIA_TRANSLATE'||output!=='CAPTIONS'||(params.voiceId??'')!==''))throw new TakuError('INVALID_PARAMS','Whisper supports segmented media captions only');
      if(params.profileId==='local-hybrid') {
        if(!['en-US','zh-CN'].includes(params.language??'zh-CN')||!['en-US','zh-CN'].includes(targetLanguage))throw new TakuError('INVALID_PARAMS','Local hybrid supports English/Chinese');
        const voices={'zh-CN':['','zh-CN-local-3','zh-CN-local-20','zh-CN-local-58','zh-CN-local-80'],'en-US':['','en-US-local-0','en-US-local-1']};
        if(!voices[targetLanguage].includes(params.voiceId??''))throw new TakuError('INVALID_PARAMS','Choose a voice for the output language');
      } else if((params.speed??1)!==1 || params.profileId==='fal-kotoba' && (params.voiceId??'')!=='' || !['','eve','ara','rex','sal','leo'].includes(params.voiceId??''))throw new TakuError('INVALID_PARAMS','Unsupported voice or speed for this profile');
      if(call) {
        if(output!=='SPEECH'||originalAudio!=='REPLACE')throw new TakuError('INVALID_PARAMS','Call translation requires bidirectional speech replacement');
        validateAudioParams(method,{purpose:'MEDIA_TRANSLATE',language:targetLanguage,targetLanguage:params.language??'zh-CN',targetPackage:params.targetPackage,
          profileId:params.outgoingProfileId??params.profileId,voiceId:params.outgoingVoiceId??'',speed:params.outgoingSpeed??1,output:'SPEECH',originalAudio:'REPLACE'});
      } else if(['outgoingProfileId','outgoingVoiceId','outgoingSpeed'].some(k=>k in params))throw new TakuError('INVALID_PARAMS','Direction settings require CALL_TRANSLATE');
    } else if(['targetLanguage','targetPackage','output','originalAudio','outgoingProfileId','outgoingVoiceId','outgoingSpeed'].some(k=>k in params))throw new TakuError('INVALID_PARAMS','Media parameters require MEDIA_TRANSLATE');
  } else if(method !== 'audio.capabilities') {
    if(typeof params.sessionId !== 'string' || !/^audio-[1-9][0-9]*$/.test(params.sessionId)) throw new TakuError('INVALID_PARAMS','A valid sessionId is required');
    if(method==='audio.session.append' && (!Number.isSafeInteger(params.index)||params.index<0||typeof params.text!=='string'||!params.text.trim()||params.text.length>500)) throw new TakuError('INVALID_PARAMS','Append requires a sequential index and 1–500 characters');
  }
}

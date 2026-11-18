#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {TakuClient} from '../client.mjs';
import {PROTOCOL, failure, response, TakuError} from '../../core/protocol.mjs';
const root = new URL('../../', import.meta.url);
const argv = process.argv.slice(2);
const [operation, name, ...flags] = argv;
const options = {};
let client;
let params = {};
try {
  for (let i = 0; i < flags.length; i += 2) {
    if(flags[i] === '--params') { params = JSON.parse(flags[i+1]);continue; }
    const key = {'--transport': 'transport', '--serial': 'serial', '--adb': 'adb'}[flags[i]];
    if (!key || !flags[i + 1]) throw new TakuError('INVALID_PARAMS', 'Use --transport adb|device --serial ID --adb PATH');
    options[key] = flags[i + 1];
  }
  if (!options.transport && (process.env.TAKU_OS_TRANSPORT || process.env.TAKU_OS_SERIAL)) options.transport = process.env.TAKU_OS_TRANSPORT ?? 'adb';
  client = new TakuClient(options);
  let result;
  if (operation === 'call') result = await client.call(name,params);
  else if(operation === 'audio' && name === 'dictation') {
    const events=client.audioEvents();
    const opened=await client.call('audio.session.open',{purpose:'DICTATION',...params});
    let stopping=false;
    const stop=()=>{if(!stopping){stopping=true;client.call('audio.session.finish',{sessionId:opened.sessionId}).catch(()=>client.close());}};
    process.once('SIGINT',stop);
    console.log(JSON.stringify(response('cli',opened)));
    try {for await(const event of events){if(event.data.sessionId!==opened.sessionId)continue;console.log(JSON.stringify(event));if(event.data.kind==='Closed'){if(!['completed','canceled','client_disconnected'].includes(event.data.reason))throw new TakuError('AUDIO_SESSION_FAILED','Audio session ended: '+event.data.reason);break;}}}
    finally{process.removeListener('SIGINT',stop);await events.return();}
    result={sessionId:opened.sessionId,closed:true};
  }
  else if(operation === 'audio' && name === 'speak') {
    const {text,...profile}=params;
    if(typeof text!=='string'||!text.trim()||text.length>16000)throw new TakuError('INVALID_PARAMS','Provide 1–16000 characters in --params text');
    const points=Array.from(text);const pieces=[];for(let offset=0;offset<points.length;offset+=240)pieces.push(points.slice(offset,offset+240).join(''));
    const events=client.audioEvents();
    const opened=await client.call('audio.session.open',{purpose:'SPEAK',...profile});
    let index=0;let stopping=false;
    const next=async()=>{if(stopping)return;if(index<pieces.length)await client.call('audio.session.append',{sessionId:opened.sessionId,index,text:pieces[index++]});else await client.call('audio.session.finish',{sessionId:opened.sessionId});};
    const stop=()=>{stopping=true;client.call('audio.session.close',{sessionId:opened.sessionId}).catch(()=>client.close());};
    process.once('SIGINT',stop);console.log(JSON.stringify(response('cli',opened)));
    try{for await(const event of events){
      if(event.data.sessionId!==opened.sessionId)continue;console.log(JSON.stringify(event));
      if(event.data.kind==='Running'||event.data.kind==='Playback'&&event.data.playbackState==='chunkComplete')await next();
      if(event.data.kind==='Closed'){if(!['completed','canceled','client_disconnected'].includes(event.data.reason))throw new TakuError('AUDIO_SESSION_FAILED','Audio session ended: '+event.data.reason);break;}
    }}finally{process.removeListener('SIGINT',stop);await events.return();}
    result={sessionId:opened.sessionId,closed:true};
  }
  else if (operation === 'extension') {
    const registry = JSON.parse(await readFile(new URL('spec/extensions.json', root), 'utf8'));
    const extension = registry.find(item => item.id === name);
    if (!extension) throw new TakuError('EXTENSION_NOT_FOUND', 'Extension is not in checked-in registry');
    if (!/^examples\/extensions\/[a-z0-9-]+\/index\.mjs$/.test(extension.entrypoint)) throw new TakuError('INVALID_EXTENSION', 'Registry entrypoint must be a local extension module');
    const module = await import(new URL(extension.entrypoint, root));
    const scopedClient = {call: (method, params = {}) => {
      if (!extension.capabilities.includes(method)) throw new TakuError('CAPABILITY_NOT_DECLARED', 'Extension must declare its method');
      return client.call(method, params);
    }};
    result = await module.run(scopedClient);
    if (Object.keys(result.errors ?? {}).length) process.exitCode = 1;
  } else throw new TakuError('INVALID_PARAMS', 'Usage: taku call METHOD | taku extension ID [--transport adb --serial ID]');
  console.log(JSON.stringify(response('cli', result), null, 2));
} catch (error) { console.log(JSON.stringify(failure('cli', error), null, 2)); process.exitCode = 1; } finally { client?.close(); }

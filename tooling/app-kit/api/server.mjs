import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
import {execFile} from 'node:child_process';
import Ajv from 'ajv';

const kit = process.env.MINI_APP_KIT || path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const stateDir = path.join(kit, 'state');
const port = Number(process.env.MINI_APP_API_PORT || 8765);
fs.mkdirSync(stateDir, {recursive: true});
const ajv = new Ajv({allErrors: true, jsonPointers: true});
const apps = new Map();
const queues = new Map();
const envelope = ajv.compile({type:'object',properties:{method:{type:'string',minLength:1},params:{type:'object'},idempotencyKey:{type:'string',minLength:1,maxLength:128}},required:['method'],additionalProperties:false});
function run(file,args){return new Promise((resolve,reject)=>execFile(file,args,{timeout:5000,encoding:'utf8'},(error,stdout)=>error?reject(error):resolve(stdout)));}
async function postNotification(id,title,body){
  const tag='miniapp.'+id;
  await run('/system/xbin/su',['2000','/system/bin/cmd','notification','post','-t',String(title),tag,String(body)]);
  for(let attempt=0;attempt<15;attempt++){
    const keys=await run('/system/bin/cmd',['notification','list']);
    const key=keys.split('\n').find(line=>line.includes('|com.android.shell|')&&line.endsWith('|'+tag+'|2000'));
    if(key){const record=await run('/system/bin/cmd',['notification','get',key]);if(record.includes(String(title))&&record.includes(String(body)))return{posted:true,verified:true,tag,notificationKey:key};}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw new ApiError('NOTIFY_FAILED','Android notification record was not confirmed',500);
}

class ApiError extends Error {
  constructor(code, message, status=400, details=null){super(message);this.code=code;this.status=status;this.details=details;}
}
function validApp(id){if(!/^[a-zA-Z0-9_-]+$/.test(id))throw new ApiError('INVALID_APP','Invalid app id');return id;}
function canonical(value){if(Array.isArray(value))return value.map(canonical);if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().map(k=>[k,canonical(value[k])]));return value;}
function storeFor(id){
  const filename=path.join(stateDir,id+'.json');
  function read(){try{return JSON.parse(fs.readFileSync(filename,'utf8'));}catch(e){if(e.code==='ENOENT')return{revision:0,data:{},receipts:{}};throw new ApiError('STORAGE_ERROR','Stored state cannot be parsed',500);}}
  function write(value){const tmp=filename+'.tmp';fs.writeFileSync(tmp,JSON.stringify(value),{mode:0o600});fs.renameSync(tmp,filename);}
  return {
    read:()=>structuredClone(read().data),
    revision:()=>read().revision,
    update(fn){const current=read();const data=structuredClone(current.data);const next=fn(data);if(next&&typeof next.then==='function')throw new Error('Store transaction must be synchronous');current.data=next===undefined?data:next;current.revision++;write(current);return structuredClone(current.data);},
    receipt(key){return read().receipts[key];},
    saveReceipt(key,value){const current=read();current.receipts[key]=value;const keys=Object.keys(current.receipts);for(const old of keys.slice(0,Math.max(0,keys.length-100)))delete current.receipts[old];write(current);}
  };
}
async function loadApp(id){
  validApp(id);
  const file=path.join(kit,'apps',id,'api.mjs');
  let stat;try{stat=fs.statSync(file);}catch(e){throw new ApiError('APP_NOT_FOUND','Application API is not registered',404);}
  const signature=stat.mtimeMs+':'+stat.size;
  const previous=apps.get(id);if(previous?.signature===signature)return previous;
  const module=await import(pathToFileURL(file).href+'?version='+encodeURIComponent(signature));
  const store=storeFor(id);
  const app=module.default({store,
    error:(code,message,status=400)=>new ApiError(code,message,status),
    notify:(title,body)=>postNotification(id,title,body)
  });
  if(!app?.methods||typeof app.methods!=='object')throw new ApiError('INVALID_API_MODULE','Application has no methods',500);
  const methods={};
  for(const [name,method]of Object.entries(app.methods)){
    if(typeof method.handler!=='function')throw new ApiError('INVALID_API_MODULE','Method has no handler: '+name,500);
    methods[name]={...method,validate:ajv.compile(method.paramsSchema||{type:'object',additionalProperties:false})};
  }
  const result={id,signature,title:app.title||id,version:app.version||'1',methods,store};apps.set(id,result);return result;
}
function describe(app){return{id:app.id,title:app.title,version:app.version,methods:Object.entries(app.methods).map(([name,m])=>({name,description:m.description||'',mutates:!!m.mutates,paramsSchema:m.paramsSchema||{type:'object',additionalProperties:false},resultSchema:m.resultSchema||{}}))};}
async function listApps(){const ids=fs.readdirSync(path.join(kit,'apps')).filter(id=>/^[a-zA-Z0-9_-]+$/.test(id)&&fs.existsSync(path.join(kit,'apps',id,'api.mjs')));return Promise.all(ids.map(async id=>describe(await loadApp(id))));}
function serialized(id,fn){const previous=queues.get(id)||Promise.resolve();const next=previous.catch(()=>{}).then(fn);queues.set(id,next);return next;}
async function invoke(id,body){
  if(!envelope(body))throw new ApiError('INVALID_REQUEST','Invalid call envelope',400,envelope.errors);
  return serialized(id,async()=>{
    const app=await loadApp(id),method=app.methods[body.method];
    if(!method)throw new ApiError('METHOD_NOT_FOUND','Unknown application method',404);
    const params=body.params||{};
    if(!method.validate(params))throw new ApiError('INVALID_PARAMS','Arguments do not match method schema',400,method.validate.errors);
    const hash=createHash('sha256').update(JSON.stringify(canonical({method:body.method,params}))).digest('hex');
    const key=body.idempotencyKey?createHash('sha256').update(body.idempotencyKey).digest('hex'):null;
    if(key){const receipt=app.store.receipt(key);if(receipt){if(receipt.hash!==hash)throw new ApiError('KEY_CONFLICT','Idempotency key was used for a different request',409);return{...receipt.response,replayed:true};}}
    const result=await method.handler(params);
    const response={ok:true,app:id,method:body.method,result,revision:app.store.revision(),replayed:false};
    if(key)app.store.saveReceipt(key,{hash,response});
    return response;
  });
}
function json(res,status,value){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(value));}
async function bodyOf(req){let length=0,parts=[];for await(const chunk of req){length+=chunk.length;if(length>65536)throw new ApiError('TOO_LARGE','Request exceeds 64 KiB',413);parts.push(chunk);}try{return JSON.parse(Buffer.concat(parts).toString('utf8'));}catch{throw new ApiError('INVALID_JSON','Request must be valid JSON');}}
const server=http.createServer(async(req,res)=>{
  const started=performance.now();
  try{
    const url=new URL(req.url,'http://127.0.0.1:'+port);
    if(req.method==='GET'&&url.pathname==='/health'){json(res,200,{ok:true,pid:process.pid});return;}
    if(req.method==='GET'&&url.pathname==='/v1/apps'){json(res,200,{ok:true,apps:await listApps()});return;}
    if(req.method==='GET'&&url.pathname==='/v1/openapi.json'){
      const all=await listApps();const paths={};
      for(const app of all){paths['/v1/apps/'+app.id+'/call']={post:{summary:'Call '+app.title,operationId:'call_'+app.id,requestBody:{required:true,content:{'application/json':{schema:{oneOf:app.methods.map(m=>({type:'object',properties:{method:{const:m.name},params:m.paramsSchema,idempotencyKey:{type:'string'}},required:['method','params'],additionalProperties:false}))}}}},responses:{'200':{description:'Application result'},'400':{description:'Invalid arguments'}}}};}
      json(res,200,{openapi:'3.1.0',info:{title:'Agent OS application APIs',version:'0.1'},servers:[{url:'http://127.0.0.1:'+port}],paths});return;
    }
    const match=url.pathname.match(/^\/v1\/apps\/([a-zA-Z0-9_-]+)(\/call)?$/);
    if(match&&req.method==='GET'&&!match[2]){json(res,200,{ok:true,app:describe(await loadApp(match[1]))});return;}
    if(match&&req.method==='POST'&&match[2]){const result=await invoke(match[1],await bodyOf(req));json(res,200,{...result,durationMs:Math.round((performance.now()-started)*100)/100});return;}
    throw new ApiError('NOT_FOUND','Unknown endpoint',404);
  }catch(error){json(res,error.status||500,{ok:false,error:{code:error.code||'INTERNAL_ERROR',message:error.message,details:error.details||null}});}
});
server.listen(port,'127.0.0.1',()=>console.log(JSON.stringify({ready:true,port,pid:process.pid})));
process.on('SIGTERM',()=>server.close(()=>process.exit(0)));

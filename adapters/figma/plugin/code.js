// Declarative, additive authoring in a private test file. No eval or external code.
figma.showUI(__html__, {width:360,height:320});
const OWNER='taku.figma-desktop.v1';
function fileKey(value){if(typeof value!=='string'||!/^[-A-Za-z0-9]{1,100}$/.test(value))throw Error('Invalid file key');return value;}
let busy=false;
const receipts=new Map();
function hex(v){if(!/^#[0-9a-fA-F]{6}$/.test(v))throw Error('Invalid color');return {r:parseInt(v.slice(1,3),16)/255,g:parseInt(v.slice(3,5),16)/255,b:parseInt(v.slice(5,7),16)/255};}
function key(v){if(typeof v!=='string'||!/^[-A-Za-z0-9_]{1,60}$/.test(v))throw Error('Invalid symbolic key');return v;}
function findFrame(page,k){const n=page.children.find(n=>n.getPluginData('key')===k);if(!n||n.type!=='FRAME')throw Error('Unknown frame '+k);return n;}
function find(frame,k){if(k==='$frame')return frame;const n=frame.findOne(n=>n.getPluginData('key')===k);if(!n)throw Error('Unknown node '+k);return n;}
async function props(n,v){
 const allowed=['key','type','parent','name','x','y','width','height','fill','radius','text','fontSize','fontWeight','align','opacity','rotation','visible'];
 for(const k of Object.keys(v))if(!allowed.includes(k))throw Error('Unsupported property '+k);
 for(const k of ['x','y','width','height','radius','fontSize','opacity','rotation'])if(v[k]!==undefined&&(!Number.isFinite(v[k])||Math.abs(v[k])>5000))throw Error('Invalid geometry');
 if(v.name!==undefined)n.name=String(v.name).slice(0,160);
 if(v.width!==undefined||v.height!==undefined)n.resize(v.width||n.width,v.height||n.height);
 for(const k of ['x','y','opacity','rotation','visible'])if(v[k]!==undefined)n[k]=v[k];
 if(v.fill)n.fills=[{type:'SOLID',color:hex(v.fill)}];
 if(v.radius!==undefined)n.cornerRadius=v.radius;
 if(n.type==='TEXT'){
  const font={family:'Inter',style:v.fontWeight==='Bold'?'Bold':v.fontWeight==='Medium'?'Medium':'Regular'};
  await figma.loadFontAsync(font);n.fontName=font;
  if(v.fontSize!==undefined)n.fontSize=v.fontSize;
  if(v.text!==undefined)n.characters=String(v.text).slice(0,600);
  if(v.align!==undefined){if(!['LEFT','CENTER','RIGHT'].includes(v.align))throw Error('Invalid text alignment');n.textAlignHorizontal=v.align;}
 }
}
function snapshot(page){return page.children.filter(n=>n.type==='FRAME').map(f=>({key:f.getPluginData('key'),id:f.id,name:f.name,width:f.width,height:f.height,nodes:f.findAll().map(n=>({key:n.getPluginData('key'),id:n.id,type:n.type,name:n.name,text:n.type==='TEXT'?n.characters:undefined,fills:Array.isArray(n.fills)?n.fills:undefined,reactions:n.reactions||undefined}))}));}
async function execute(r){
 const FILE=fileKey(r.fileKey);
 if(!figma.fileKey)throw Error('FILE_IDENTITY_UNAVAILABLE: this Figma plugin context does not expose a file key.');
 if(figma.fileKey!==FILE)throw Error('Open the configured Figma file.');
 let page=figma.root.children.find(p=>p.getPluginData('owner')===OWNER&&p.getPluginData('job')===r.job);
 const b=r.body;if(typeof r.job!=='string'||!/^[-A-Za-z0-9_]{1,80}$/.test(r.job))throw Error('Invalid job identity');
 if(b.action==='begin'){
  if(page)throw Error('Job exists; inspect before retrying with a new request ID.');
  if(!(b.width>=240&&b.width<=720&&b.height>=240&&b.height<=1280))throw Error('Invalid frame dimensions');
  page=figma.createPage();page.name=String(b.name||'Taku live build').slice(0,120);page.setPluginData('owner',OWNER);page.setPluginData('job',r.job);
  await figma.setCurrentPageAsync(page);
  const frame=figma.createFrame();frame.name='Start';frame.setPluginData('key','Start');frame.resize(b.width,b.height);frame.fills=[{type:'SOLID',color:hex(b.fill||'#FFFFFF')}];page.appendChild(frame);
  figma.currentPage.selection=[frame];figma.viewport.scrollAndZoomIntoView([frame]);
 }else{
  if(!page)throw Error('Begin a fresh job first.');await figma.setCurrentPageAsync(page);
  if(b.action==='apply'){
   const frame=findFrame(page,key(b.frame));
   if(!Array.isArray(b.nodes)||b.nodes.length>50)throw Error('Invalid nodes');
   for(const v of b.nodes){key(v.key);let n=frame.findOne(n=>n.getPluginData('key')===v.key);
    if(!n){if(frame.findAll().length>=250)throw Error('Node limit');const makers={FRAME:()=>figma.createFrame(),TEXT:()=>figma.createText(),STAR:()=>figma.createStar(),RECTANGLE:()=>figma.createRectangle(),ELLIPSE:()=>figma.createEllipse()};if(!makers[v.type])throw Error('Unsupported native node type');n=makers[v.type]();n.setPluginData('key',v.key);const parent=v.parent?find(frame,key(v.parent)):frame;if(parent.type!=='FRAME')throw Error('Parent must be a frame');parent.appendChild(n);}
    await props(n,v);
   }
  }else if(b.action==='clone'){
   if(page.children.length>=12)throw Error('Frame limit');key(b.frame);if(page.children.some(n=>n.getPluginData('key')===b.frame))throw Error('Frame exists');
   const n=findFrame(page,key(b.from)).clone();n.setPluginData('key',b.frame);n.name=b.frame;n.x=page.children.length*(n.width+80);n.y=0;
   for(const v of b.overrides||[])await props(find(n,key(v.key)),v);
  }else if(b.action==='link'){
   if(!Array.isArray(b.edges)||b.edges.length>80)throw Error('Invalid edges');
   for(const v of b.edges){const f=findFrame(page,key(v.frame));const n=find(f,v.key==='$frame'?'$frame':key(v.key)),to=findFrame(page,key(v.to));
    if(f.id===to.id)continue; // Clicking the selected state is a no-op, not a forbidden self-navigation.
    const trigger=v.delayMs!==undefined?{type:'AFTER_TIMEOUT',timeout:Math.max(.1,Math.min(10,v.delayMs/1000))}:{type:'ON_CLICK'};
    const transition={type:'SMART_ANIMATE',easing:{type:'EASE_OUT'},duration:.3};
    const reaction={trigger,actions:[{type:'NODE',destinationId:to.id,navigation:'NAVIGATE',transition,preserveScrollPosition:false}]};
    await n.setReactionsAsync([reaction]);
   }
   figma.commitUndo();
  }else if(b.action==='select'){
   const f=findFrame(page,key(b.frame));figma.currentPage.selection=[f];figma.viewport.scrollAndZoomIntoView([f]);
  }else if(b.action!=='inspect')throw Error('Unknown action');
 }
 const start=findFrame(page,'Start');
 // Keep the same Start frame selected during child edits so Mirror can show construction.
 if(b.action!=='select')figma.currentPage.selection=[start];
 const ack={requestId:r.requestId,job:r.job,action:b.action,fileKey:FILE,pageId:page.id,startFrameId:start.id,selectedFrameId:figma.currentPage.selection[0]?.id,executedBy:'desktop-plugin',executedAt:new Date().toISOString(),frames:snapshot(page),designUrl:'https://www.figma.com/design/'+FILE+'?node-id='+start.id.replace(':','-'),prototypeUrl:'https://www.figma.com/proto/'+FILE+'?page-id='+encodeURIComponent(page.id)+'&node-id='+start.id.replace(':','-')+'&starting-point-node-id='+encodeURIComponent(start.id)+'&scaling=scale-down&hide-ui=1'};
 // Preserve execution identity even when a large diagnostic snapshot exceeds the wire budget.
 if(encodeURIComponent(JSON.stringify(ack)).replace(/%[A-F\d]{2}/g,'x').length>240000){ack.frames=[];ack.snapshotTruncated=true;}
 return ack;
}
figma.ui.onmessage=async m=>{
 if(!m||m.type!=='execute')return; if(busy){figma.ui.postMessage({type:'busy',requestId:m.request?.requestId});return;}const r=m.request;if(!r||typeof r.requestId!=='string'||!/^[-A-Za-z0-9_]{1,80}$/.test(r.requestId))return;
 const fingerprint=JSON.stringify(r);
 if(receipts.has(r.requestId)){
  const cached=receipts.get(r.requestId);
  if(cached.fingerprint!==fingerprint){figma.ui.postMessage({type:'error',requestId:r.requestId,error:'Request ID reused with different data.'});return;}
  figma.ui.postMessage(cached.message);return;
 }
 if(receipts.size>=500){figma.ui.postMessage({type:'error',requestId:r.requestId,error:'Plugin session request limit reached.'});return;}
 busy=true;
 let message;
 try{message={type:'ack',ack:await execute(r)};}
 catch(e){message={type:'error',requestId:r.requestId,error:String(e.message||e).slice(0,500)};}
 finally{busy=false;}
 // Errors can follow a partial write. Cache them too; recovery must inspect before a new request.
 receipts.set(r.requestId,{fingerprint,message});figma.ui.postMessage(message);
};

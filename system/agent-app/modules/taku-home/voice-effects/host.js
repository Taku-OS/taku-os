/* Android host for the unmodified designer renderers. Receives measured RMS only. */
const canvas = document.getElementById('effect');
const {waveAt} = require('./waveform');
const {stepLight} = require('./voice');
let props = {kind:'light', target:'listen', level:0, active:true, still:false, flat:false};
let frame = 0, previous = 0, time = 0, state = {flow:0,level:0}, field = null;
let samples = [];
const images = new Map();
function drawStaticLight() {
  canvas.width=412;canvas.height=357;
  const ctx=canvas.getContext('2d');
  for(const layer of require('./light-field').staticLayers[props.target]) {
    if(!layer)continue;
    let img=images.get(layer.src);
    if(!img){img=new Image();img.onload=wake;img.src=layer.src;images.set(layer.src,img);}
    if(!img.complete||!img.naturalWidth)continue;
    ctx.save();ctx.globalCompositeOperation=['source-over','lighter','lighten'][layer.blend];
    ctx.translate(layer.x+layer.w/2,layer.y+layer.h/2-560);
    if(layer.flip)ctx.rotate(Math.PI);
    ctx.drawImage(img,-layer.w/2-58,-layer.h/2-58,layer.w+116,layer.h+116);ctx.restore();
  }
  if(props.target==='listen') {
    const fade=ctx.createLinearGradient(0,145,0,225);fade.addColorStop(0,'transparent');fade.addColorStop(1,'black');
    ctx.globalCompositeOperation='destination-in';ctx.fillStyle=fade;ctx.fillRect(0,0,412,357);
  }
}
function levelAt(t) {
  for (let i=samples.length-1;i>=0;i--) if (samples[i].time<=t) return samples[i].level;
  return 0;
}
function draw() {
  if(props.kind==='wave') {
    canvas.width=132; canvas.height=56;
    const ctx=canvas.getContext('2d'); ctx.scale(2,2);
    ctx.fillStyle=props.target==='live'?'#ffffff':'#1b1b1b';
    waveAt(props.still?0:time,levelAt).forEach((height,i)=>{
      const h=props.flat?6:height;ctx.beginPath();ctx.roundRect(i*7,(28-h)/2,3,h,1.5);ctx.fill();
    });
  } else if(field) field.draw(canvas,props.target,{...state,mix:props.target==='live'?1:0});
  else drawStaticLight();
}
function tick(now) {
  frame=0;
  const dt=previous?Math.min(64,Math.max(0,now-previous)):0;previous=now;
  if(!props.still){time+=dt;state=stepLight(state,props.level,dt);}
  else state={...state,level:props.level};
  samples.push({time,level:props.level});if(samples.length>120)samples.shift();
  draw();if(props.active&&!props.still&&!document.hidden)frame=requestAnimationFrame(tick);
}
function wake(){if(!frame&&props.active&&!document.hidden){previous=0;frame=requestAnimationFrame(tick);}}
let lease=null;
window.setVoiceEffect = next => {
  props={...props,...next};
  if(props.kind==='light'&&!lease){
    try {lease=require('./light-field').acquireLightField(()=>{field=lease.field;wake();},()=>{field=null;document.documentElement.dataset.renderer='lost';});}
    catch {document.documentElement.dataset.renderer='unavailable';}
  }
  if(!props.active){cancelAnimationFrame(frame);frame=0;draw();}else wake();
};
document.addEventListener('visibilitychange',()=>{if(document.hidden){cancelAnimationFrame(frame);frame=0;}else wake();});
window.addEventListener('pagehide',()=>{cancelAnimationFrame(frame);lease?.release();});

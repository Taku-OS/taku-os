// Pose geometry and short-lived clothing appearance. No personal identity recognition.
const visible=p=>p&&Number.isFinite(p.x)&&Number.isFinite(p.y)&&p.x>=0&&p.x<=1&&p.y>=0&&p.y<=1&&(p.visibility??0)>=.35;
const length=(a,b)=>Math.hypot(a.x-b.x,a.y-b.y);
const center=b=>b.anchor||[b.x+b.w/2,b.y+b.h/2];
const median=values=>{const a=values.slice().sort((x,y)=>x-y);return a.length?a[Math.floor(a.length/2)]:null;};
const segments=[[11,23],[12,24],[23,25],[24,26],[25,27],[26,28]];
const bodyIndices=[11,12,13,14,23,24,25,26,27,28];

function appearance(p,pixels,width,height) {
  const result={};if(!pixels||!width||!height)return result;
  for(const [region,indices] of [['lower',[23,24,25,26]],['upper',[11,12,23,24]]]) {
    const points=indices.map(i=>p[i]).filter(visible);if(points.length<3)continue;
    let x0=Math.min(...points.map(q=>q.x)),x1=Math.max(...points.map(q=>q.x));
    const y0=Math.min(...points.map(q=>q.y)),y1=Math.max(...points.map(q=>q.y));
    const inset=(x1-x0)*.18;x0+=inset;x1-=inset;
    if(x1-x0<.025||y1-y0<.06)continue;
    const bins=Array(28).fill(0);let count=0;
    for(let y=y0+(y1-y0)*.15;y<y1;y+=(y1-y0)/16)for(let x=x0;x<x1;x+=(x1-x0)/12){
      const i=(Math.min(height-1,Math.floor(y*height))*width+Math.min(width-1,Math.floor(x*width)))*4;
      const r=pixels[i]/255,g=pixels[i+1]/255,b=pixels[i+2]/255,max=Math.max(r,g,b),min=Math.min(r,g,b),delta=max-min;
      let bin=Math.min(3,Math.floor(max*4));
      if(delta>.12&&delta/(max||1)>.22){
        let hue=max===r?((g-b)/delta+6)%6:max===g?(b-r)/delta+2:(r-g)/delta+4;
        bin=4+Math.floor(hue)*4+Math.min(3,Math.floor(max*4));
      }
      bins[bin]++;count++;
    }
    if(count)result[region]=bins.map(v=>v/count);
  }
  return result;
}
export function peopleFromLandmarks(poses,pixels,width,height) {
  const result=[];
  for(const p of poses) {
    const points=bodyIndices.map(i=>p[i]).filter(visible),measurements={};
    if(points.length<4||![23,24].some(i=>visible(p[i])))continue;
    for(const [a,b] of segments)if(visible(p[a])&&visible(p[b])){
      const size=length(p[a],p[b]);if(size>=.035)measurements[a+'-'+b]=size;
    }
    // A torso or several connected leg joints suffice; ankles need not both be visible.
    if(Object.keys(measurements).length<2)continue;
    const x0=Math.max(0,Math.min(...points.map(q=>q.x))-.035),x1=Math.min(1,Math.max(...points.map(q=>q.x))+.035);
    const all=p.filter(visible),y0=Math.max(0,Math.min(...all.map(q=>q.y))-.035),y1=Math.min(1,Math.max(...points.map(q=>q.y))+.035);
    if(y1-y0<.14||x1-x0<.055)continue;
    const hips=[p[23],p[24]].filter(visible),anchor=[hips.reduce((n,q)=>n+q.x,0)/hips.length,hips.reduce((n,q)=>n+q.y,0)/hips.length];
    result.push({anchor,x:x0,y:y0,w:x1-x0,h:y1-y0,scale:median(Object.values(measurements)),measurements,
      appearance:appearance(p,pixels,width,height),match:true});
  }
  return result;
}

function similarity(a,b) {
  const scores=[];
  for(const key of Object.keys(a||{}))if(b?.[key]?.length===a[key].length){
    scores.push(a[key].reduce((sum,value,i)=>sum+Math.min(value,b[key][i]),0));
  }
  return scores.length?median(scores):null;
}
function measures(p){return p.measurements||{body:p.scale};}

export class PersonFollower {
  constructor(){this.reset();}
  reset(){
    this.box=null;this.reference={};this.lastAt=0;this.misses=0;this.frames=0;this.calibration=null;
    this.ratio=null;this.signature={};this.pending=null;this.lost=false;
    this.direction='hold';this.pendingDirection='hold';this.directionCount=0;this.diagnostics=null;
  }
  select(people,point=null,now=performance.now()) {
    const choices=people.filter(p=>p.match&&(!point||(point[0]>=p.x&&point[0]<=p.x+p.w&&point[1]>=p.y&&point[1]<=p.y+p.h)));
    if(choices.length!==1)return false;
    this.reset();this.box={...choices[0]};this.reference={...measures(this.box)};
    this.signature={...this.box.appearance};this.ratio=1;this.lastAt=now;this.frames=1;return true;
  }
  update(people,now=performance.now()) {
    if(!this.box)return this.select(people,null,now);
    const elapsed=now-this.lastAt;
    if(elapsed>12000){this.lost=true;return false;}
    const c=center(this.box),recovering=elapsed>700||this.misses>0;
    const ranked=people.filter(p=>p.match).map(p=>{
      const d=Math.hypot(center(p)[0]-c[0],center(p)[1]-c[1]);
      const look=similarity(this.signature,p.appearance);
      return {p,d,look,score:d+(look===null?0:(1-look)*.6)};
    }).filter(({p,d,look})=>{
      const old=measures(this.box),next=measures(p),common=Object.keys(next).filter(k=>old[k]);
      const scale=common.length?median(common.map(k=>next[k]/old[k])):1;
      if(scale<.4||scale>2.5||d>.5)return false;
      if(elapsed>1300)return look!==null&&look>=.65;
      // Continuous geometry survives exposure changes; clothing is primarily for re-entry.
      if(look!==null&&look<.3&&d>.18)return false;
      return d<.25+(elapsed/1300)*.1;
    }).sort((a,b)=>a.score-b.score);
    this.diagnostics={elapsed,candidates:people.length,eligible:ranked.length,best:ranked[0]?{distance:ranked[0].d,appearance:ranked[0].look}:null};
    if(!ranked.length||(ranked[1]&&ranked[1].score-ranked[0].score<.12)){
      this.misses++;this.pending=null;return false;
    }
    const p=ranked[0].p;
    if(recovering){
      const old=this.pending,pc=center(p);
      this.pending={point:pc,count:old&&Math.hypot(pc[0]-old.point[0],pc[1]-old.point[1])<.16?old.count+1:1};
      if(this.pending.count<2){this.misses++;return false;}
    }
    this.pending=null;
    const current=measures(p),ratios=Object.keys(current).filter(k=>this.reference[k]).map(k=>this.reference[k]/current[k]);
    const ratio=ratios.length?median(ratios):null;
    this.ratio=ratio===null?null:this.ratio===null?ratio:this.ratio*.65+ratio*.35;
    if(ratio!==null)for(const [key,value] of Object.entries(current))if(!this.reference[key])this.reference[key]=value*ratio;
    // Keep the initial clothing reference; gradually learning it could drift onto a bystander.
    for(const [key,value] of Object.entries(p.appearance||{}))if(!this.signature[key])this.signature[key]=value;
    for(const k of ['x','y','w','h','scale'])this.box[k]=recovering?p[k]:this.box[k]*.35+p[k]*.65;
    this.box.anchor=p.anchor;this.box.measurements=p.measurements;
    this.lastAt=now;this.misses=0;this.frames++;this.lost=false;
    const x=center(this.box)[0],desired=x<.42?'left':x>.58?'right':this.ratio>1.08?'forward':'hold';
    this.directionCount=desired===this.pendingDirection?this.directionCount+1:1;this.pendingDirection=desired;
    if(this.directionCount>=2)this.direction=desired;
    return true;
  }
  calibrate(meters,now=performance.now()) {
    const view=this.view(now);
    if(!Number.isFinite(meters)||meters<.5||meters>10||!view.box||view.ratio===null)return false;
    this.calibration={meters,ratio:view.ratio};return true;
  }
  view(now=performance.now()) {
    if(!this.box)return {state:'waiting',box:null};
    if(this.lost||now-this.lastAt>12000)return {state:'lost',box:null};
    if(this.misses||now-this.lastAt>700)return {state:'reacquiring',box:null};
    const ratio=this.ratio;
    return {state:'tracking',box:this.box,ratio,change:ratio===null?null:(ratio-1)*100,direction:this.direction,
      meters:ratio!==null&&this.calibration?this.calibration.meters*ratio/this.calibration.ratio:null};
  }
}

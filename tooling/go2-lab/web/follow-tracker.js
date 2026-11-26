// Local image-only rehearsal aid. Never imports a robot or navigation client.
// Dark-clothing components are not person identity recognition or metric depth.
export function candidates(rgba, width, height) {
  const mask=new Uint8Array(width*height),seen=new Uint8Array(mask.length),result=[];
  for(let y=Math.floor(height*.12);y<height*.96;y++)for(let x=0;x<width;x++){
    const i=(y*width+x)*4,r=rgba[i],g=rgba[i+1],b=rgba[i+2];
    mask[y*width+x]=Math.max(r,g,b)<88&&r*.299+g*.587+b*.114<62&&Math.max(r,g,b)-Math.min(r,g,b)<52?1:0;
  }
  for(let start=0;start<mask.length;start++){
    if(!mask[start]||seen[start])continue;
    const queue=[start];seen[start]=1;let count=0,x0=width,y0=height,x1=0,y1=0;
    for(let k=0;k<queue.length;k++){
      const p=queue[k],x=p%width,y=Math.floor(p/width);count++;x0=Math.min(x0,x);x1=Math.max(x1,x);y0=Math.min(y0,y);y1=Math.max(y1,y);
      for(const [dx,dy] of [[1,0],[-1,0],[0,1],[0,-1],[1,1],[-1,-1],[1,-1],[-1,1]]){
        const nx=x+dx,ny=y+dy,n=ny*width+nx;
        if(nx>=0&&nx<width&&ny>=0&&ny<height&&!seen[n]&&mask[n]){seen[n]=1;queue.push(n);}
      }
    }
    const w=x1-x0+1,h=y1-y0+1,fill=count/(w*h);
    if(h<height*.18||w<width*.025||w>width*.6||h/w<.8||h/w>8||fill<.25||count<width*height*.004)continue;
    // A clipped person is not a complete target and requires reframing.
    if(x0<2||x1>width-3||y1>height*.94)continue;
    result.push({x:x0/width,y:y0/height,w:w/width,h:h/height,fill});
  }
  return result;
}

const center=b=>[b.x+b.w/2,b.y+b.h/2];
export class ClothingTracker {
  constructor(){this.reset();}
  reset(){this.box=null;this.reference=null;this.lost=false;this.misses=0;this.lastAt=0;this.frames=0;}
  select(boxes,point=null,now=performance.now()){
    const ranked=boxes.map(b=>({b,d:Math.hypot(center(b)[0]-(point?.[0]??.5),center(b)[1]-(point?.[1]??.55))}))
      .filter(v=>!point||(point[0]>=v.b.x-.04&&point[0]<=v.b.x+v.b.w+.04&&point[1]>=v.b.y-.04&&point[1]<=v.b.y+v.b.h+.04))
      .sort((a,b)=>a.d-b.d);
    if(!ranked.length||(!point&&(ranked[0].d>.3||(ranked[1]&&ranked[1].d-ranked[0].d<.12))))return false;
    this.reset();this.box={...ranked[0].b};this.reference=this.box.h;this.lastAt=now;this.frames=1;return true;
  }
  update(boxes,now=performance.now()){
    if(!this.box||this.lost)return null;
    if(now-this.lastAt>750){this.lost=true;return null;}
    const old=center(this.box);
    const ranked=boxes.map(b=>({b,d:Math.hypot(center(b)[0]-old[0],center(b)[1]-old[1]),scale:b.h/this.box.h}))
      .filter(v=>v.d<.12&&v.scale>.67&&v.scale<1.5&&v.b.w/this.box.w>.5&&v.b.w/this.box.w<1.8)
      .sort((a,b)=>a.d-b.d);
    if(!ranked.length||(ranked[1]&&ranked[1].d-ranked[0].d<.04)){
      if(++this.misses>=3)this.lost=true;
      return null;
    }
    this.misses=0;const b=ranked[0].b;
    for(const k of ['x','y','w','h'])this.box[k]=this.box[k]*.35+b[k]*.65;
    this.lastAt=now;this.frames++;return this.box;
  }
  view(now=performance.now()){
    if(!this.box)return {state:'waiting',box:null};
    if(this.lost||this.misses||now-this.lastAt>750)return {state:'lost',box:null};
    const ratio=this.box.h/this.reference,x=center(this.box)[0];
    return {state:'tracking',box:this.box,ratio,
      distance:ratio<.85?'farther':ratio>1.18?'nearer':'steady',
      direction:x<.4?'left':x>.6?'right':ratio<.85?'forward':'hold'};
  }
}

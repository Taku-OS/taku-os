export const MAIN_LINE='en-taunt-enough';
export const BARK='dog-bark';
export class SceneVoice {
 constructor(request,changed){this.request=request;this.changed=changed;this.state=null;this.pending=false;this.error=null;this.player=null;this.selected=MAIN_LINE;this.generation=0;}
 select(id){
  if(![MAIN_LINE,BARK].includes(id)||this.pending||this.state?.busy||this.state?.state==='streaming')return false;
  this.stopPreview();this.selected=id;this.error=null;this.changed();return true;
 }
 async refresh(){if(this.pending)return;try{this.state=await this.request('/api/audio');this.error=null;}catch{this.state=null;}this.changed();}
 async run(action,id=this.selected){
  if(this.pending||this.state?.busy||this.state?.state==='streaming')return;
  if(!this.select(id))return;
  const turn=++this.generation;
  this.pending=true;this.error=null;this.changed();
  try{
   this.state=await this.request('/api/audio/select',{id});
   if(turn!==this.generation)return;
   if(action==='play'&&this.state.volume===null)this.state=await this.request('/api/audio/refresh',{});
   if(turn!==this.generation)return;
   this.state=await this.request('/api/audio/'+action,{});
  }catch(error){this.error=error.message;}finally{this.pending=false;this.changed();}
 }
 async preview(){
  this.stopPreview();this.player=new Audio('/api/audio/preset/'+this.selected);const player=this.player;this.changed();
  player.onended=()=>{if(this.player===player)this.stopPreview();};player.onerror=()=>{if(this.player===player){this.stopPreview();this.error='PREVIEW_FAILED';this.changed();}};
  try{await player.play();}catch{this.stopPreview();this.error='PREVIEW_FAILED';this.changed();}
 }
 stopPreview(){if(this.player)this.player.pause();this.player=null;this.changed();}
 async stop(){this.generation++;this.stopPreview();try{this.state=await this.request('/api/audio/stop',{});this.error=null;}catch(error){this.error=error.message;}this.changed();}
}

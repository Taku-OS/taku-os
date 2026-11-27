// Serialize user actions, invalidate late responses, and never queue a play after Stop.
export class SoundControls {
  constructor(api,changed){this.api=api;this.changed=changed;this.state=null;this.pending=false;this.error=null;this.epoch=0;}
  accept(state){this.state=state;this.error=state.requestError||state.error||null;this.changed();return !this.error;}
  async action(work){
    if(this.pending)return;
    const id=++this.epoch;this.pending=true;this.error=null;this.changed();
    const send=async(action,body={})=>{const result=await this.api(action,body);if(id!==this.epoch)return false;return this.accept(result);};
    try{await work(send,()=>id===this.epoch);}catch{if(id===this.epoch)this.error='REQUEST_FAILED';}
    finally{if(id===this.epoch){this.pending=false;this.changed();}}
  }
  select(id){return this.action(async send=>{
    if(this.state?.state==='streaming'&&!await send('stop'))return;
    await send('select',{id});
  });}
  play(){return this.action(async send=>{
    if(!this.state?.clip){this.error='NO_CLIP';return;}
    if(this.state.volume===null&&!await send('refresh'))return;
    await send('play');
  });}
  volume(value){return this.action(send=>send('volume',{volume:value}));}
  refresh(){return this.action(send=>send('refresh'));}
  async stop(){
    const id=++this.epoch;this.pending=true;this.error=null;this.changed();
    try{const result=await this.api('stop',{});if(id===this.epoch)this.accept(result);}
    catch{if(id===this.epoch)this.error='REQUEST_FAILED';}
    finally{if(id===this.epoch){this.pending=false;this.changed();}}
  }
}

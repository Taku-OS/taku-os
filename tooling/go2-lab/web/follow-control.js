// No raw motor commands: the phone controller owns speed, freshness and stop checks.
export class FollowDrive {
  constructor(json, changed=()=>{}) {
    this.json=json;this.changed=changed;this.token=null;this.starting=false;this.busy=false;
    this.generation=0;this.value={active:false,state:'idle',action:'hold',error:null};
  }
  async start(requestId) {
    const generation=++this.generation;this.starting=true;
    try {
      const result=await this.json('/api/follow/start',{requestId});
      if(generation!==this.generation){await this.json('/api/follow/stop',{token:result.token});return;}
      this.token=result.token;this.value=result;
    }catch(error){if(generation===this.generation)this.value={active:false,state:'stopped',error:error.message};}
    finally{if(generation===this.generation)this.starting=false;this.changed();}
  }
  async sample(frameKey, view, identity) {
    if(!this.token||this.busy)return;
    const generation=this.generation;this.busy=true;
    const target=view.state==='tracking'&&view.box&&Number.isFinite(view.ratio)?{
      x:view.box.anchor?.[0]??view.box.x+view.box.w/2,ratio:view.ratio,id:identity
    }:null;
    try {
      const result=await this.json('/api/follow/observe',{token:this.token,frameKey,target});
      if(generation===this.generation){this.value=result;if(!result.active)this.token=null;}
    }catch(error){
      if(generation===this.generation&&error.message!=='STALE_OBSERVATION'){
        await this.stop();this.value={active:false,state:'stopped',error:error.message};
      }
    }finally{this.busy=false;this.changed();}
  }
  async stop() {
    const hadSession=this.token||this.starting,token=this.token;
    ++this.generation;this.token=null;this.starting=false;
    this.value={active:false,state:'stopped',action:'hold',error:null};this.changed();
    if(hadSession)try{
      const result=await this.json('/api/follow/stop',token?{token}:{});
      if(!this.token)this.value=result;
    }catch{this.value.error='STOP_UNCONFIRMED';}
    this.changed();
  }
}

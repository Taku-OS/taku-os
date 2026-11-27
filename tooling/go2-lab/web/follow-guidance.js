// Advisory output from the existing local pose tracker. No control transport.
const labels={
 en:{waiting:['Waiting for a live view','Connect Go2 to begin reading the scene.','—'],loading:['Reading the scene','Preparing person recognition.','—'],stabilizing:['Reading the target','Checking the target’s position and range.','—'],paused:['Manual control','The live view remains available.','—'],select:['Select your person','Tap the person you want to focus on.','—'],search:['Find the person ahead','Keep the person’s body in the camera view.','—'],lost:['Find the target again','The target is out of view. Reacquire before moving closer.','Hold'],left:['Turn slightly left','The selected person is left of center.','Slow'],right:['Turn slightly right','The selected person is right of center.','Slow'],forward:['Ease forward','The target is centered and appears farther away.','Slow'],back:['Give them some space','The selected person appears too close. Check behind before moving back.','Slow'],hold:['Hold this distance','The target is centered and at the selected range.','Hold'],return:['Head back to your start','Return requested. Check the view as you turn.','Slow'],complete:['All set','Ready for your next instruction.','—'],ready:['Ready when you are','Confirm the task to open the live view.','—']},
 zh:{waiting:['等待实时画面','连接机器狗后开始分析画面。','—'],loading:['正在分析画面','正在准备人体识别。','—'],stabilizing:['正在确认目标','正在确认目标位置与远近。','—'],paused:['人工控制','实时画面保持可用。','—'],select:['请选择关注的人','点击画面中要关注的人物。','—'],search:['寻找前方人物','让目标身体尽量完整入镜。','—'],lost:['重新寻找目标','目标暂时离开画面，找回后再靠近。','保持'],left:['稍向左转','目标位于画面中心左侧。','低速'],right:['稍向右转','目标位于画面中心右侧。','低速'],forward:['缓慢靠近','目标已对准，视觉上距离较远。','低速'],back:['拉开一些距离','目标在视觉上过近，后退前确认后方空间。','低速'],hold:['保持当前距离','目标已对准，并位于选定的范围。','保持'],return:['返回出发方向','已收到返回指令，转身时留意实时画面。','低速'],complete:['已完成','等待你的下一条指令。','—'],ready:['准备就绪','确认任务后打开实时视角。','—']}
};
Object.assign(labels.en,{
 staying:['Stay here','Position held. Ready for the next instruction.','Hold'],
 returnHold:['Stay here','Return paused. Ready when you are.','Hold'],
 circle:['Circle around the target','Keep a wide, steady arc. Tap Stay to hold position.','Slow']
});
Object.assign(labels.zh,{
 staying:['原地停留','保持当前位置，等待下一条指令。','保持'],
 returnHold:['原地停留','返回已暂停，等待下一条指令。','保持'],
 circle:['环绕目标','保持宽缓稳定的弧线，点停留即可保持当前位置。','低速']
});
export function scenePreparation(pending,stage,language='en'){
 if(!['go','return'].includes(pending)||!['locating','planning'].includes(stage))return null;
 const back=pending==='return',planning=stage==='planning';
 const en=language==='en';
 return {
  title:en?(planning?(back?'Planning return route…':'Planning approach route…'):(back?'Acquiring return location…':'Acquiring target location…')):
   (planning?(back?'正在规划返回路线…':'正在规划接近路线…'):(back?'正在获取返回位置…':'正在获取目标位置…')),
  reason:en?(planning?'Preparing the route and movement sequence.':back?'Resolving the starting point for your return.':'Resolving the destination for this task.'):
   (planning?'正在准备路线和行进步骤。':back?'正在确认此次返回的起点位置。':'正在确认此次任务的目的位置。'),
  badge:en?(planning?'Planning':'Locating'):(planning?'规划路线':'获取位置'),
  step:planning?2:1
 };
}
export class VisualGuidance {
 constructor(){this.reset();}
 reset(){this.action=null;this.candidate=null;this.count=0;this.frame=null;}
 update({fresh,model,view={},people=0,phase='running',manual=false,pending='',pendingStage='',frameKey='',approach=false},language='en'){
  if(pending&&!manual){
   const preparation=scenePreparation(pending,pendingStage,language);
   if(preparation)return {action:pendingStage,title:preparation.title,reason:preparation.reason,pace:'—',live:false,basis:'intent',step:preparation.step};
   const en=language==='en';
   const reason=en?({circle:'Preparing to circle around the target.',return:'Preparing your return.',say:'Preparing the voice line.',bark:'Preparing the bark.',stay:'Holding position.',complete:'Wrapping up the task.'}[pending]||'Getting the task ready.'):
    ({circle:'正在准备环绕目标。',return:'正在准备返回。',say:'正在准备台词。',bark:'正在准备狗叫。',stay:'保持当前位置。',complete:'正在结束任务。'}[pending]||'正在准备任务。');
   return {action:'processing',title:en?'Processing your command…':'正在处理指令…',reason,pace:'—',live:false,basis:'intent'};
  }
  let action;
  if(manual)action='paused';
  else if(phase==='confirm')action='ready';
  else if(phase==='complete')action='complete';
  else if(phase==='holding')action='staying';
  else if(phase==='return_hold')action='returnHold';
  else if(!fresh)action='waiting';
  else if(phase==='returning')action='return';
  else if(phase==='circling')action='circle';
  else if(model!=='ready')action='loading';
  else if(!view.box||view.state!=='tracking')action=view.state==='lost'||view.state==='reacquiring'?'lost':people>1?'select':'search';
  else {
   const x=view.box.anchor?.[0]??view.box.x+view.box.w/2;
   if(x<.40)action='left';else if(x>.60)action='right';
   else if(Number.isFinite(view.meters))action=view.meters>2.1?'forward':view.meters<1.5?'back':'hold';
   else if(approach)action=view.box.h<.62?'forward':view.box.h>.84?'back':'hold';
   else action=view.ratio>1.12?'forward':view.ratio<.85?'back':'hold';
  }
  const directional=['left','right','forward','back','hold'];
  if(directional.includes(action)){
   if(frameKey!==this.frame){this.frame=frameKey;this.count=this.candidate===action?this.count+1:1;this.candidate=action;}
   if(this.count>=3)this.action=action;
   // Keep a previously stable recommendation for brief single-frame jitter.
   action=directional.includes(this.action)?this.action:'stabilizing';
  }else{this.action=action;this.candidate=null;this.count=0;this.frame=null;}
  const [title,reason,pace]=labels[language]?.[action]||labels.en[action];
  const intent=['return','circle','staying','returnHold'].includes(action);
  return {action,title,reason,pace,live:!!fresh&&!intent,basis:intent?'intent':view.box&&fresh?'vision':'status'};
 }
}

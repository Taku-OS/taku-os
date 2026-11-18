const empty={type:'object',properties:{},additionalProperties:false};
const entry={type:'object',properties:{id:{type:'integer'},team:{type:'string',enum:['A','B']},delta:{type:'integer',not:{const:0}},at:{type:'string'}},required:['id','team','delta','at'],additionalProperties:false};
const result={type:'object',properties:{scores:{type:'object',properties:{A:{type:'integer'},B:{type:'integer'}},required:['A','B'],additionalProperties:false},history:{type:'array',items:entry}},required:['scores','history'],additionalProperties:false};
export default function createApp({store,error}) {
  if(!store.read().scores)store.update(d=>{d.scores={A:0,B:0};d.history=[];d.nextId=1;});
  const state=()=>{const d=store.read();return {scores:d.scores,history:d.history};};
  return {title:'双队计分',version:'1',methods:{
    'score.state':{description:'读取两队分数及当前可撤销历史',mutates:false,paramsSchema:empty,resultSchema:result,handler:state},
    'score.add':{description:'为A或B队加上非零整数，支持负数',mutates:true,paramsSchema:{type:'object',properties:{team:{type:'string',enum:['A','B']},delta:{type:'integer',not:{const:0}}},required:['team','delta'],additionalProperties:false},resultSchema:result,handler({team,delta}){
      if(!['A','B'].includes(team)||!Number.isSafeInteger(delta)||delta===0)throw error('INVALID_PARAMS','team必须为A/B，delta必须为非零安全整数');
      store.update(d=>{const next=d.scores[team]+delta;if(!Number.isSafeInteger(next))throw error('OUT_OF_RANGE','分数超出安全整数范围');d.scores[team]=next;d.history.push({id:d.nextId++,team,delta,at:new Date().toISOString()});});return state();
    }},
    'score.undo':{description:'撤销最后一次加分并移除该历史；历史为空则拒绝',mutates:true,paramsSchema:empty,resultSchema:result,handler(){store.update(d=>{const last=d.history.pop();if(!last)throw error('EMPTY_HISTORY','没有可撤销的记录',409);const next=d.scores[last.team]-last.delta;if(!Number.isSafeInteger(next))throw error('OUT_OF_RANGE','撤销结果超出安全整数范围');d.scores[last.team]=next;});return state();}}
  }};
}

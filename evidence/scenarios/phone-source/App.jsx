import React,{useState} from 'react';
import {Screen,Card,Button,Field,phone,useAppApiState} from './ui.jsx';
export default function App(){
 const {state,error,reload}=useAppApiState('score.state');
 const [draft,setDraft]=useState({A:'',B:''}),[message,setMessage]=useState(''),[busy,setBusy]=useState(false);
 async function call(method,params){setBusy(true);try{await phone.call(method,params);await reload();setMessage('已更新');}catch(e){setMessage(e.message);}finally{setBusy(false);}}
 return <Screen title="双队计分" subtitle="同一份接口状态 · 支持加分、扣分和撤销">
 <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:12}}>{['A','B'].map(team=><Card key={team} style={{padding:16,textAlign:'center',borderTop:`6px solid ${team==='A'?'#006b56':'#4166b5'}`}}><h2>{team} 队</h2><div style={{fontSize:64,fontWeight:800,margin:'16px 0'}}>{state?.scores[team]??'—'}</div><div style={{display:'flex',gap:8,justifyContent:'center'}}><Button disabled={busy} onClick={()=>call('score.add',{team,delta:1})}>+1</Button><Button secondary disabled={busy} onClick={()=>call('score.add',{team,delta:-1})}>−1</Button></div><Field label="自定义整数（可负数）" value={draft[team]} onChange={e=>setDraft({...draft,[team]:e.target.value})} placeholder="例如 +3 或 -2"/><Button disabled={busy} onClick={()=>call('score.add',{team,delta:/^[+-]?\d+$/.test(draft[team])?Number(draft[team]):draft[team]})}>应用加分</Button></Card>)}</div>
 <Button secondary disabled={busy||!state?.history.length} onClick={()=>call('score.undo',{})}>撤销最后一步</Button>
 <div role="status" style={{color:error?'#a32929':'#536c63'}}>{error||message||'等待操作'}</div>
 <Card><h2>历史记录</h2>{!state?.history.length?<p>暂无记录</p>:<ol style={{paddingLeft:24}}>{[...state.history].reverse().map(h=><li key={h.id} style={{padding:'10px 0'}}>{h.team} 队　{h.delta>0?'+':''}{h.delta}<small style={{display:'block',color:'#647872'}}>{h.at}</small></li>)}</ol>}</Card>
 </Screen>;
}

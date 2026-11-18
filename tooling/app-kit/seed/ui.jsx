import React from 'react';
export function Button({children,onClick,secondary=false,...props}){return <button onClick={onClick} style={{border:0,borderRadius:14,padding:'13px 18px',background:secondary?'#e4ece8':'#006b56',color:secondary?'#006b56':'white',fontWeight:600}} {...props}>{children}</button>}
export function Card({children,style={}}){return <section style={{background:'white',borderRadius:22,padding:20,...style}}>{children}</section>}
export function Screen({title,subtitle,children}){return <main style={{maxWidth:620,margin:'auto',padding:24,display:'grid',gap:20}}><header><h1 style={{fontSize:30,margin:'10px 0'}}>{title}</h1>{subtitle&&<p style={{color:'#647872',lineHeight:1.6}}>{subtitle}</p>}</header>{children}</main>}
export function Row({children}){return <div style={{display:'flex',gap:12,alignItems:'center',flexWrap:'wrap'}}>{children}</div>}
export function Field({label,value,onChange,type='text',placeholder}){return <label style={{display:'grid',gap:10,margin:'12px 0',flex:1}}><span style={{fontSize:14,color:'#536c63'}}>{label}</span><input type={type} value={value} onChange={onChange} placeholder={placeholder} style={{width:'100%',padding:14,border:'1px solid #cbd7cf',borderRadius:12,background:'#fafcf8'}}/></label>}
export function Select({label,value,onChange,options}){return <label style={{display:'grid',gap:10,flex:1}}><span style={{fontSize:14,color:'#536c63'}}>{label}</span><select value={value} onChange={onChange} style={{padding:14,border:'1px solid #cbd7cf',borderRadius:12,background:'#fafcf8'}}>{options.map(o=><option key={o.value} value={o.value}>{o.label}</option>)}</select></label>}
export const phone={
  launchContext(){try{return typeof Phone!=='undefined'&&typeof Phone.launchContext==='function'?JSON.parse(Phone.launchContext()):{route:null}}catch{return{route:null,error:'INVALID_LAUNCH_CONTEXT'}}},
  load(fallback){try{return JSON.parse(Phone.read())}catch{return fallback}},
  save(data){Phone.save(JSON.stringify(data))},
  notify(title,text){Phone.notify(title,text)}
};
phone.call=async function(method,params={},options={}){
  const response=JSON.parse(Phone.callJson(JSON.stringify({method,params,...options})));
  if(!response.ok){const error=new Error(response.error?.message||'App API call failed');error.code=response.error?.code;error.details=response.error?.details;throw error;}
  return response.result;
};
export function useAppApiState(method,params={},interval=750){
  const [state,setState]=React.useState(null),[error,setError]=React.useState(null);
  const signature=JSON.stringify(params);
  const reload=React.useCallback(async()=>{const next=await phone.call(method,JSON.parse(signature));setState(old=>JSON.stringify(old)===JSON.stringify(next)?old:next);setError(null);return next;},[method,signature]);
  React.useEffect(()=>{let active=true;const run=async()=>{try{if(active)await reload();}catch(e){if(active)setError(e.message);}};run();const timer=setInterval(run,interval);return()=>{active=false;clearInterval(timer);};},[reload,interval]);
  return{state,error,reload};
}

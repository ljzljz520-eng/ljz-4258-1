import React, { useEffect, useMemo, useState } from 'react';
import { api, AuthState, flushQueue, getAuth, getQueue, newEventId, queueEvent, setAuth, terminalId } from './api';

type Compartment = { id:string; label:string; capacity:string; current_node_id:string };
type Vehicle = { id:string; plate:string; carrier:string; compartments:Compartment[] };
type Tank = { id:string; tank_code:string; name:string; capacity:string; current_node_id:string; lineage_version:number };
type Farm = {id:string;batch_code:string;farm_name:string;declared_qty:string};
type Event = {event_id:string;type:string;status:string;conflict_reason?:string;payload:any;client_created_at:string};
type Sample = {id:string;sample_code:string;source_node_id:string;farm_batch_id?:string;quantity:string;status:string;current_version:number};
type Release = {application_no:string;expected_node_id:string;expected_lineage_version:number;status:string;invalid_reason?:string};

const Login: React.FC<{onLogin:(a:AuthState)=>void}> = ({onLogin}) => {
  const [username,setUsername]=useState('operator'); const [password,setPassword]=useState('operator123'); const [error,setError]=useState('');
  async function login(e:React.FormEvent) {
    e.preventDefault(); setError('');
    const body=new URLSearchParams({username,password});
    try {
      const r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body});
      if(!r.ok) throw new Error('登录失败'); const t=await r.json();
      const auth={username:t.username,role:t.role,token:t.access_token}; setAuth(auth); onLogin(auth);
    } catch(e:any){setError(e.message)}
  }
  return <form className="card login" onSubmit={login}>
    <h1>原奶收货 PWA</h1><p>终端 {terminalId}</p>
    <label>账号<input value={username} onChange={e=>setUsername(e.target.value)}/></label>
    <label>密码<input type="password" value={password} onChange={e=>setPassword(e.target.value)}/></label>
    <button>登录</button>{error && <b className="bad">{error}</b>}
    <small>种子账号：operator/operator123，lab/lab123，supervisor/supervisor123</small>
  </form>;
};

const Dashboard: React.FC<{auth:AuthState; onLogout:()=>void}> = ({auth,onLogout}) => {
  const [vehicles,setVehicles]=useState<Vehicle[]>([]); const [tanks,setTanks]=useState<Tank[]>([]); const [farms,setFarms]=useState<Farm[]>([]);
  const [events,setEvents]=useState<Event[]>([]); const [samples,setSamples]=useState<Sample[]>([]); const [releases,setReleases]=useState<Release[]>([]);
  const [msg,setMsg]=useState(''); const [online,setOnline]=useState(navigator.onLine); const [,bump]=useState(0);
  const queue=getQueue();
  const load=async()=>{
    const [v,t,f,e,s,r]=await Promise.all([
      api<Vehicle[]>('/api/vehicles'),api<Tank[]>('/api/tanks'),api<Farm[]>('/api/farm-batches'),
      api<Event[]>('/api/events'),api<Sample[]>('/api/samples'),api<Release[]>('/api/releases')]);
    setVehicles(v);setTanks(t);setFarms(f);setEvents(e);setSamples(s);setReleases(r);
  };
  useEffect(()=>{ const h=()=>{setOnline(navigator.onLine);bump(x=>x+1)}; window.addEventListener('online',h); window.addEventListener('offline',h); window.addEventListener('milk-queue',h); if(navigator.onLine)load().catch(err=>setMsg(err.message)); return()=>{window.removeEventListener('online',h);window.removeEventListener('offline',h);window.removeEventListener('milk-queue',h)};},[]);
  async function sync(){try{const r=await flushQueue(auth);setMsg(r?`同步完成：接受 ${r.accepted}，待协调 ${r.quarantined}，重复 ${r.duplicates}`:'无待发事件');await load();}catch(e:any){setMsg('同步失败，事件保留在终端：'+e.message)}}
  return <main>
    <header><div><b>原奶收货</b><span>{auth.username} · {auth.role}</span></div><div className={online?'online':'offline'}>{online?'在线':'离线'} · 待发 {queue.length}</div><button onClick={onLogout}>退出</button></header>
    {msg && <div className="toast" onClick={()=>setMsg('')}>{msg}</div>}
    <button className="primary sync" disabled={!online} onClick={sync}>立即同步离线事件</button>
    <div className="grid">
      {(auth.role==='OPERATOR'||auth.role==='ADMIN')&&<UnloadPanel vehicles={vehicles} tanks={tanks} onDone={sync}/>}
      {(auth.role==='OPERATOR'||auth.role==='ADMIN')&&<RefillPanel vehicles={vehicles} farms={farms} onDone={sync}/>}
      <SamplePanel vehicles={vehicles} tanks={tanks} samples={samples} role={auth.role}/>
      {(auth.role==='LAB'||auth.role==='SUPERVISOR'||auth.role==='ADMIN')&&<LabPanel samples={samples} onDone={load}/>}
      {(auth.role==='SUPERVISOR'||auth.role==='ADMIN')&&<ReleasePanel tanks={tanks} releases={releases} onDone={load}/>}
    </div>
    <section className="card"><h2>当前罐与谱系版本</h2>{tanks.map(t=><div key={t.id} className="row"><b>{t.tank_code} {t.name}</b><span>{t.current_node_id?.slice(0,8)||'空'} · v{t.lineage_version}</span></div>)}</section>
    <section className="card"><h2>事件与协调队列</h2>{events.slice(0,30).map(e=><div key={e.event_id} className={`row ${e.status==='QUARANTINED'?'badrow':''}`}><span>{e.type} · {e.status}</span><small>{e.event_id.slice(0,8)} {e.conflict_reason||''}</small></div>)}</section>
  </main>;
};

const UnloadPanel: React.FC<{vehicles:Vehicle[];tanks:Tank[];onDone:()=>Promise<void>}>=({vehicles,tanks,onDone})=>{
  const first=vehicles[0]?.compartments[0]; const [compartmentId,setCompartmentId]=useState(''); const [source,setSource]=useState(''); const [qty,setQty]=useState(''); const [seal,setSeal]=useState(''); const [tank,setTank]=useState(tanks[0]?.tank_code||''); const [reading,setReading]=useState('');
  useEffect(()=>{const c=vehicles.flatMap(v=>v.compartments).find(c=>c.id===compartmentId)||first;if(c){setCompartmentId(c.id);setSource(c.current_node_id)}},[vehicles]);
  function submit(){ if(!compartmentId||!source||!qty||!seal)return; queueEvent({type:'UNLOAD',payload:{tank_code:tank,lines:[{compartment_id:compartmentId,source_node_id:source,quantity:Number(qty),expected_seal_number:seal,weight_reading_id:reading||null}]}}); setQty(''); }
  return <section className="card"><h2>分仓交接/卸载</h2>
    <select value={compartmentId} onChange={e=>{setCompartmentId(e.target.value);setSource(vehicles.flatMap(v=>v.compartments).find(c=>c.id===e.target.value)?.current_node_id||'')}}>{vehicles.flatMap(v=>v.compartments.map(c=><option key={c.id} value={c.id}>{v.plate}-{c.label}</option>))}</select>
    <input placeholder="源物料节点" value={source} onChange={e=>setSource(e.target.value)}/><select value={tank} onChange={e=>setTank(e.target.value)}>{tanks.map(t=><option key={t.id}>{t.tank_code}</option>)}</select>
    <input placeholder="数量 kg" type="number" value={qty} onChange={e=>setQty(e.target.value)}/><input placeholder="当前封签号" value={seal} onChange={e=>setSeal(e.target.value)}/><input placeholder="称重 reading_id（可选）" value={reading} onChange={e=>setReading(e.target.value)}/>
    <button onClick={submit}>加入离线队列</button><small>部分卸载会产生剩余节点；封签冲突或超额进入待协调，不会覆盖现状。</small></section>;
};

const RefillPanel: React.FC<{vehicles:Vehicle[];farms:Farm[];onDone:()=>Promise<void>}>=({vehicles,farms})=>{
  const c=vehicles[0]?.compartments[0]; const [compartmentId,setCompartmentId]=useState(''); const [batch,setBatch]=useState(farms[2]?.id||''); const [qty,setQty]=useState(''); const [oldSeal,setOldSeal]=useState(''); const [newSeal,setNewSeal]=useState('');
  useEffect(()=>{if(c&&!compartmentId)setCompartmentId(c.id)},[vehicles]);
  function submit(){queueEvent({type:'REFILL',payload:{lines:[{compartment_id:compartmentId,quantity:Number(qty),batch_allocations:{[batch]:Number(qty)}}],expected_seal_number:oldSeal,new_seal_number:newSeal}});setQty('');setOldSeal('');setNewSeal('')}
  return <section className="card"><h2>中途补装</h2><select value={compartmentId} onChange={e=>setCompartmentId(e.target.value)}>{vehicles.flatMap(v=>v.compartments.map(c=><option key={c.id} value={c.id}>{v.plate}-{c.label}</option>))}</select>
    <select value={batch} onChange={e=>setBatch(e.target.value)}>{farms.map(f=><option key={f.id} value={f.id}>{f.batch_code} {f.farm_name}</option>)}</select><input type="number" placeholder="补装数量" value={qty} onChange={e=>setQty(e.target.value)}/><input placeholder="旧封签" value={oldSeal} onChange={e=>setOldSeal(e.target.value)}/><input placeholder="新封签" value={newSeal} onChange={e=>setNewSeal(e.target.value)}/><button onClick={submit}>登记补装</button></section>;
};

const SamplePanel: React.FC<{vehicles:Vehicle[];tanks:Tank[];samples:Sample[];role:string}>=({vehicles,tanks,samples,role})=>{
  const nodes=[...vehicles.flatMap(v=>v.compartments.map(c=>({id:c.current_node_id,name:`${v.plate}-${c.label}`}))),...tanks.map(t=>({id:t.current_node_id||'',name:t.tank_code}))].filter(x=>x.id);
  const [source,setSource]=useState(''); const [code,setCode]=useState(''); const [qty,setQty]=useState('0'); const [impact,setImpact]=useState<any>(null);
  function register(){queueEvent({type:'SAMPLE',payload:{sample_code:code||'S-'+Date.now(),source_node_id:source,quantity:Number(qty),farm_batch_id:null}});setCode('')}
  async function impactOf(s:Sample){if(role==='OPERATOR')return;setImpact(await api(`/api/samples/${s.id}/impact`))}
  return <section className="card"><h2>先卸后取样/仓室采样</h2><select value={source} onChange={e=>setSource(e.target.value)}><option value="">选择物料节点（可为最新罐节点）</option>{nodes.map(n=><option key={n.id+ n.name} value={n.id}>{n.name} {n.id.slice(0,8)}</option>)}</select><input placeholder="样品编号" value={code} onChange={e=>setCode(e.target.value)}/><input type="number" placeholder="采样物理量，0=仅登记" value={qty} onChange={e=>setQty(e.target.value)}/><button onClick={register}>登记样品</button>
    <h3>异常反查</h3><select onChange={e=>e.target.value&&impactOf(samples.find(s=>s.id===e.target.value)!)}><option>选择样品</option>{samples.map(s=><option key={s.id} value={s.id}>{s.sample_code} {s.status}</option>)}</select>{impact&&<pre>{JSON.stringify(impact,null,2)}</pre>}</section>;
};

const LabPanel: React.FC<{samples:Sample[];onDone:()=>Promise<void>}>=({samples,onDone})=>{
  const [sampleId,setSampleId]=useState(''); const [result,setResult]=useState('ACCEPT'); const [note,setNote]=useState('');
  async function submit(){await api(`/api/samples/${sampleId}/results`,{method:'POST',body:JSON.stringify({result,note,components:{}})});setNote('');await onDone()}
  return <section className="card"><h2>实验室迟到修正</h2><select value={sampleId} onChange={e=>setSampleId(e.target.value)}><option value="">样品</option>{samples.map(s=><option key={s.id} value={s.id}>{s.sample_code} v{s.current_version} {s.status}</option>)}</select><select value={result} onChange={e=>setResult(e.target.value)}><option>ACCEPT</option><option>REJECT</option></select><input placeholder="备注" value={note} onChange={e=>setNote(e.target.value)}/><button disabled={!sampleId} onClick={submit}>提交/修正结论</button><small>新版本会提升受影响物理罐谱系版本，并使待放行申请失效。</small></section>;
};

const ReleasePanel: React.FC<{tanks:Tank[];releases:Release[];onDone:()=>Promise<void>}>=({tanks,releases,onDone})=>{
  const tank=tanks[0]; const [tankCode,setTankCode]=useState(tank?.tank_code||''); const selected=tanks.find(t=>t.tank_code===tankCode)||tank;
  async function create(){await api('/api/releases',{method:'POST',body:JSON.stringify({application_no:'R-'+Date.now(),tank_code:selected?.tank_code,expected_node_id:selected?.current_node_id,expected_lineage_version:selected?.lineage_version})});await onDone()}
  async function sign(no:string){try{await api(`/api/releases/${no}/sign`,{method:'POST'});await onDone()}catch(e:any){alert(e.message);await onDone()}}
  return <section className="card"><h2>下游放行</h2><select value={tankCode} onChange={e=>setTankCode(e.target.value)}>{tanks.map(t=><option key={t.id}>{t.tank_code} v{t.lineage_version}</option>)}</select><button onClick={create}>按当前谱系申请</button>{releases.slice(0,10).map(r=><div key={r.application_no} className="row"><span>{r.application_no} {r.status} v{r.expected_lineage_version}</span>{r.status==='PENDING'&&<button onClick={()=>sign(r.application_no)}>主管签署</button>}<small>{r.invalid_reason}</small></div>)}</section>;
};

export default function App(){const [auth,setA]=useState(getAuth());return auth?<Dashboard auth={auth} onLogout={()=>{setAuth(null);setA(null)}}/>:<Login onLogin={setA}/>}

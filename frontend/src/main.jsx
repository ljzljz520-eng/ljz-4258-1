import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { api, getToken, listOffline, login, removeOffline, saveOffline, syncOffline } from './api.js';
import './styles.css';

const nowIso = () => new Date().toISOString();
const uuid = (prefix) => `${prefix}-${crypto.randomUUID ? crypto.randomUUID() : Date.now()}`;

function Login({ onLogin }) {
  const [username, setUsername] = useState('receiver');
  const [password, setPassword] = useState('receiver');
  const [error, setError] = useState('');
  return <section className="card">
    <h2>登录</h2>
    <input value={username} onChange={e => setUsername(e.target.value)} placeholder="用户名" />
    <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="密码" />
    {error && <p className="error">{error}</p>}
    <button onClick={async () => { try { await login(username, password); onLogin(); } catch (e) { setError('登录失败'); } }}>登录</button>
    <small>演示账号：receiver / sampler / lab / quality / supervisor，密码同名。</small>
  </section>;
}

function ArrivalForm() {
  const [form, setForm] = useState({ event_id: uuid('arr'), vehicle_code: 'V-01', arrived_at: nowIso(), items: [{ compartment_code: 'C1', pasture_batch_code: 'B-A-20260930', declared_amount: 100, seal_codes: ['SEAL-C1-NEW'] }] });
  const [msg, setMsg] = useState('');
  const submit = async (online) => {
    try {
      if (online) setMsg(JSON.stringify(await api('/arrivals', { method: 'POST', body: form })));
      else { saveOffline('arrival', form); setMsg('已进入本地离线队列'); }
    } catch (e) { setMsg(String(e)); }
  };
  return <section className="card">
    <h2>车辆/分仓登记与封签</h2>
    <label>事件ID<input value={form.event_id} onChange={e => setForm({ ...form, event_id: e.target.value })} /></label>
    <label>车辆<input value={form.vehicle_code} onChange={e => setForm({ ...form, vehicle_code: e.target.value })} /></label>
    {form.items.map((item, i) => <div className="grid" key={i}>
      <input value={item.compartment_code} onChange={e => updateItem(i, { compartment_code: e.target.value })} placeholder="仓室" />
      <input value={item.pasture_batch_code} onChange={e => updateItem(i, { pasture_batch_code: e.target.value })} placeholder="牧场批" />
      <input type="number" value={item.declared_amount} onChange={e => updateItem(i, { declared_amount: Number(e.target.value) })} placeholder="数量" />
      <input value={item.seal_codes.join(',')} onChange={e => updateItem(i, { seal_codes: e.target.value.split(',') })} placeholder="封签" />
    </div>)}
    <button onClick={() => submit(true)}>在线登记</button><button className="secondary" onClick={() => submit(false)}>离线暂存</button>
    <pre>{msg}</pre>
  </section>;
  function updateItem(i, patch) { const items = form.items.slice(); items[i] = { ...items[i], ...patch }; setForm({ ...form, items }); }
}

function UnloadForm() {
  const [form, setForm] = useState({ event_id: uuid('unl'), vehicle_code: 'V-01', compartment_code: 'C1', tank_code: 'T-1', amount: 50, observed_seal_code: 'SEAL-C1-001', seal_intact: true, temperature_c: 4.0, weight_kg: 50, occurred_at: nowIso(), terminal_id: 'PWA-A' });
  const [msg, setMsg] = useState('');
  const submit = async (online) => {
    try {
      if (online) setMsg(JSON.stringify(await api('/unloads', { method: 'POST', body: form }), null, 2));
      else { saveOffline('unload', form); setMsg('卸载事件已离线暂存；系统不会控制阀门。'); }
    } catch (e) { setMsg(JSON.stringify(e.data || e.message, null, 2)); }
  };
  return <section className="card">
    <h2>分仓卸载交接（部分卸载/混罐）</h2>
    <div className="grid">
      <input value={form.event_id} onChange={e => setForm({ ...form, event_id: e.target.value })} placeholder="稳定事件ID" />
      <input value={form.vehicle_code} onChange={e => setForm({ ...form, vehicle_code: e.target.value })} />
      <input value={form.compartment_code} onChange={e => setForm({ ...form, compartment_code: e.target.value })} />
      <input value={form.tank_code} onChange={e => setForm({ ...form, tank_code: e.target.value })} />
      <input type="number" value={form.amount} onChange={e => setForm({ ...form, amount: Number(e.target.value) })} />
      <input value={form.observed_seal_code} onChange={e => setForm({ ...form, observed_seal_code: e.target.value })} />
      <input type="number" value={form.temperature_c} onChange={e => setForm({ ...form, temperature_c: Number(e.target.value) })} />
      <input type="number" value={form.weight_kg} onChange={e => setForm({ ...form, weight_kg: Number(e.target.value) })} />
    </div>
    <label><input type="checkbox" checked={form.seal_intact} onChange={e => setForm({ ...form, seal_intact: e.target.checked })} /> 现场确认封签完好</label>
    <button onClick={() => submit(true)}>提交卸载</button><button className="secondary" onClick={() => submit(false)}>离线暂存</button>
    <pre>{msg}</pre>
  </section>;
}

function TopUpForm() {
  const [form, setForm] = useState({ event_id: uuid('top'), vehicle_code: 'V-01', compartment_code: 'C1', pasture_batch_code: 'B-A-20260930', amount: 20, temperature_c: 4, occurred_at: nowIso() });
  const [msg, setMsg] = useState('');
  const submit = async () => { try { setMsg(JSON.stringify(await api('/topups', { method: 'POST', body: form }), null, 2)); } catch (e) { setMsg(JSON.stringify(e.data || e.message, null, 2)); } };
  return <section className="card"><h2>中途补装</h2><div className="grid">
    <input value={form.event_id} onChange={e => setForm({ ...form, event_id: e.target.value })} />
    <input value={form.compartment_code} onChange={e => setForm({ ...form, compartment_code: e.target.value })} />
    <input value={form.pasture_batch_code} onChange={e => setForm({ ...form, pasture_batch_code: e.target.value })} />
    <input type="number" value={form.amount} onChange={e => setForm({ ...form, amount: Number(e.target.value) })} />
  </div><button onClick={submit}>补装并入仓室</button><pre>{msg}</pre></section>;
}

function LabPanel() {
  const [sampleCode, setSampleCode] = useState(uuid('S'));
  const [impactCode, setImpactCode] = useState('');
  const [outcome, setOutcome] = useState('pass');
  const [msg, setMsg] = useState('');
  return <section className="card"><h2>采样、交接、实验室</h2>
    <div className="grid">
      <input value={sampleCode} onChange={e => setSampleCode(e.target.value)} />
      <button onClick={async () => setMsg(JSON.stringify(await api('/samples', { method: 'POST', body: { sample_code: sampleCode, vehicle_code: 'V-01', compartment_code: 'C1' }})))}>先/后取样</button>
      <button onClick={() => api('/samples/handover', { method: 'POST', body: { sample_code: sampleCode, phase: 'handover' } }).then(x => setMsg(JSON.stringify(x)))}>交接</button>
      <button onClick={() => api('/samples/handover', { method: 'POST', body: { sample_code: sampleCode, phase: 'lab_receive' } }).then(x => setMsg(JSON.stringify(x)))}>实验室接收</button>
      <select value={outcome} onChange={e => setOutcome(e.target.value)}><option>pass</option><option>fail</option><option>conditional</option></select>
      <button onClick={() => api('/lab/results', { method: 'POST', body: { sample_code: sampleCode, outcome, corrected: true } }).then(x => setMsg(JSON.stringify(x, null, 2)))}>迟到/修正结果</button>
    </div>
    <div className="grid"><input value={impactCode} onChange={e => setImpactCode(e.target.value)} placeholder="样品编号" /><button onClick={() => api(`/samples/${impactCode}/impact`).then(x => setMsg(JSON.stringify(x, null, 2)))}>反查受影响罐与余料</button></div>
    <pre>{msg}</pre>
  </section>;
}

function ReleasePanel() {
  const [requestNo, setRequestNo] = useState(uuid('REL'));
  const [version, setVersion] = useState('');
  const [msg, setMsg] = useState('');
  return <section className="card"><h2>放行申请与签署核对</h2><div className="grid">
    <input value={requestNo} onChange={e => setRequestNo(e.target.value)} />
    <button onClick={async () => { const r = await api('/releases', { method: 'POST', body: { request_no: requestNo, tank_code: 'T-1' } }); setVersion(r.lineage_version); setMsg(JSON.stringify(r, null, 2)); }}>申请T-1放行</button>
    <button onClick={() => api('/releases/decide', { method: 'POST', body: { request_no: requestNo, approve: true, expected_lineage_version: version } }).then(x => setMsg(JSON.stringify(x, null, 2)))}>按快照签署</button>
  </div><pre>{msg}</pre></section>;
}

function SyncPanel() {
  const [items, setItems] = useState([]);
  const [msg, setMsg] = useState('');
  const refresh = () => setItems(listOffline());
  useEffect(refresh, []);
  return <section className="card"><h2>离线队列与协调</h2>
    <button onClick={async () => { setMsg(JSON.stringify(await syncOffline(), null, 2)); refresh(); }}>同步队列</button>
    <button className="secondary" onClick={() => api('/reconciliation/issues').then(x => setMsg(JSON.stringify(x, null, 2)))}>查看待协调</button>
    <button className="secondary" onClick={() => api('/conservation').then(x => setMsg(JSON.stringify(x, null, 2)))}>全局守恒</button>
    <ul>{items.map((x, i) => <li key={i}>{x.type}:{x.payload.event_id}<button onClick={() => { removeOffline(x.type, x.payload.event_id); refresh(); }}>删除</button></li>)}</ul>
    <pre>{msg}</pre>
  </section>;
}

function App() {
  const [authed, setAuthed] = useState(Boolean(getToken()));
  const [online, setOnline] = useState(navigator.onLine);
  useEffect(() => {
    if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');
    const on = () => setOnline(true), off = () => setOnline(false);
    addEventListener('online', on); addEventListener('offline', off);
    return () => { removeEventListener('online', on); removeEventListener('offline', off); };
  }, []);
  const tabs = useMemo(() => [<ArrivalForm/>, <UnloadForm/>, <TopUpForm/>, <LabPanel/>, <ReleasePanel/>, <SyncPanel/>], []);
  const [tab, setTab] = useState(0);
  if (!authed) return <main><Login onLogin={() => setAuthed(true)} /></main>;
  return <main>
    <header><h1>原奶收货全栈平台</h1><span className={online ? 'online' : 'offline'}>{online ? '在线' : '离线可登记'}</span></header>
    <nav>{['登记', '卸载', '补装', '样品', '放行', '同步'].map((x, i) => <button key={x} className={tab === i ? 'active' : ''} onClick={() => setTab(i)}>{x}</button>)}</nav>
    {tabs[tab]}
  </main>;
}

createRoot(document.getElementById('root')).render(<App />);

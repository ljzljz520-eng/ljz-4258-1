export type Role = 'OPERATOR' | 'LAB' | 'SUPERVISOR' | 'ADMIN';
export interface AuthState { username: string; role: Role; token: string; }

const TOKEN_KEY = 'milk.auth';
const QUEUE_KEY = 'milk.queue';
export const terminalId = localStorage.getItem('milk.terminal') || `T-${Math.random().toString(16).slice(2, 8)}`;
localStorage.setItem('milk.terminal', terminalId);

export function getAuth(): AuthState | null {
  const raw = localStorage.getItem(TOKEN_KEY);
  return raw ? JSON.parse(raw) : null;
}
export function setAuth(auth: AuthState | null) {
  if (auth) localStorage.setItem(TOKEN_KEY, JSON.stringify(auth));
  else localStorage.removeItem(TOKEN_KEY);
}

export async function api<T>(path: string, options: RequestInit = {}, auth = getAuth()): Promise<T> {
  const headers = new Headers(options.headers);
  if (!(options.body instanceof FormData) && options.body) headers.set('Content-Type', 'application/json');
  if (auth?.token) headers.set('Authorization', `Bearer ${auth.token}`);
  const response = await fetch(path, { ...options, headers });
  if (!response.ok) throw new Error((await response.json().catch(() => ({detail: response.status}))).detail || response.statusText);
  return response.status === 204 ? undefined as T : response.json();
}

export function newEventId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return `evt-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export interface QueuedEvent { event_id: string; terminal_id: string; type: string; client_created_at: string; payload: any; }
export function queueEvent(ev: Omit<QueuedEvent, 'terminal_id' | 'client_created_at' | 'event_id'> & {event_id?: string}) {
  const queue = getQueue();
  queue.push({ event_id: ev.event_id || newEventId(), terminal_id: terminalId, client_created_at: new Date().toISOString(), ...ev });
  localStorage.setItem(QUEUE_KEY, JSON.stringify(queue));
  window.dispatchEvent(new Event('milk-queue'));
}
export function getQueue(): QueuedEvent[] { return JSON.parse(localStorage.getItem(QUEUE_KEY) || '[]'); }
export function clearQueue(ids: string[]) {
  const remain = getQueue().filter(q => !ids.includes(q.event_id));
  localStorage.setItem(QUEUE_KEY, JSON.stringify(remain));
  window.dispatchEvent(new Event('milk-queue'));
}
export async function flushQueue(auth: AuthState) {
  const events = getQueue();
  if (!events.length) return null;
  const result = await api<{accepted:number; quarantined:number; duplicates:number; partial_failure:boolean; results:any[]}>('/api/sync', {
    method: 'POST', body: JSON.stringify({ events })
  }, auth);
  // Accepted/quarantined/duplicate identities are durably known by the server.
  // REJECTED events stay in local storage for replay when the server recovers.
  const handled = new Set(result.results.filter((r:any) => r.status !== 'REJECTED').map((r:any) => r.event_id));
  clearQueue(events.map(e => e.event_id).filter(id => handled.has(id)));
  return result;
}

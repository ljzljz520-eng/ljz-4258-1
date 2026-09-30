const TOKEN_KEY = 'milk.token';

export function getToken() { return localStorage.getItem(TOKEN_KEY); }
export function setToken(token) { localStorage.setItem(TOKEN_KEY, token); }

export async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (!(options.body instanceof FormData)) headers['Content-Type'] = 'application/json';
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(path, { ...options, headers, body: options.body ? JSON.stringify(options.body) : undefined });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) throw Object.assign(new Error(data?.detail ? JSON.stringify(data.detail) : response.statusText), { data, status: response.status });
  return data;
}

export async function login(username, password) {
  const token = await api('/auth/token', { method: 'POST', body: { username, password } });
  setToken(token.access_token);
  return token;
}

export function queueKey(type, eventId) { return `offline:${type}:${eventId}`; }
export function saveOffline(type, payload) {
  if (!payload.event_id) throw new Error('离线事件必须包含稳定 event_id');
  localStorage.setItem(queueKey(type, payload.event_id), JSON.stringify({ type, payload }));
}
export function listOffline() {
  return Object.keys(localStorage)
    .filter(k => k.startsWith('offline:'))
    .map(k => JSON.parse(localStorage.getItem(k)));
}
export function removeOffline(type, eventId) { localStorage.removeItem(queueKey(type, eventId)); }
export async function syncOffline() {
  const events = listOffline().map(item => item.payload ? { ...item.payload, type: item.type } : item);
  return api('/sync', { method: 'POST', body: { events } });
}

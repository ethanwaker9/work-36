const KEY = "oylama.session";

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export const session = {
  get() {
    try { return JSON.parse(localStorage.getItem(KEY) || "null"); } catch { return null; }
  },
  set(s) { localStorage.setItem(KEY, JSON.stringify(s)); },
  clear() { localStorage.removeItem(KEY); }
};

let onUnauthorized = () => {};
export function setUnauthorizedHandler(fn) { onUnauthorized = fn; }

export async function api(method, path, body) {
  const s = session.get();
  const headers = { "Content-Type": "application/json" };
  if (s && s.token) headers.Authorization = "Bearer " + s.token;
  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new ApiError(0, "Cannot reach the Oylama server. Is it still running?");
  }
  let data = null;
  try { data = await res.json(); } catch { data = null; }
  if (!res.ok) {
    if (res.status === 401 && s && path !== "/api/auth/login") onUnauthorized();
    throw new ApiError(res.status, (data && data.error) || res.statusText || "Request failed");
  }
  return data;
}

export const get = (p) => api("GET", p);
export const post = (p, b) => api("POST", p, b || {});
export const del = (p) => api("DELETE", p);

export class Raw {
  constructor(s) { this.s = s; }
  toString() { return this.s; }
}

export const raw = (s) => new Raw(String(s));

export function esc(v) {
  return String(v === null || v === undefined ? "" : v).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function part(v) {
  if (v instanceof Raw) return v.s;
  if (Array.isArray(v)) return v.map(part).join("");
  if (v === false || v === null || v === undefined) return "";
  return esc(v);
}

export function html(strings, ...values) {
  let out = strings[0];
  for (let i = 0; i < values.length; i++) out += part(values[i]) + strings[i + 1];
  return new Raw(out);
}

const P = {
  check: "M5 12.5l4.5 4.5L19 7.5",
  x: "M6 6l12 12M18 6L6 18",
  plus: "M12 5v14M5 12h14",
  minus: "M5 12h14",
  right: "M5 12h14M13 6l6 6-6 6",
  left: "M19 12H5M11 6l-6 6 6 6",
  chevron: "M9 6l6 6-6 6",
  down: "M6 9l6 6 6-6",
  shield: "M12 3l8 3v6c0 5-3.4 8.3-8 9.5C7.4 20.3 4 17 4 12V6l8-3z",
  shieldcheck: "M12 3l8 3v6c0 5-3.4 8.3-8 9.5C7.4 20.3 4 17 4 12V6l8-3zM8.5 12l2.5 2.5 4.5-5",
  lock: "M6.5 11h11a1.5 1.5 0 011.5 1.5v7a1.5 1.5 0 01-1.5 1.5h-11A1.5 1.5 0 015 19.5v-7A1.5 1.5 0 016.5 11zM8 11V8a4 4 0 018 0v3",
  unlock: "M6.5 11h11a1.5 1.5 0 011.5 1.5v7a1.5 1.5 0 01-1.5 1.5h-11A1.5 1.5 0 015 19.5v-7A1.5 1.5 0 016.5 11zM8 11V8a4 4 0 017.6-1.7",
  key: "M8.5 15.5a4.5 4.5 0 110-9 4.5 4.5 0 010 9zM12.5 11H21M18 11v3.5M21 11v2.5",
  users: "M9 11a3.5 3.5 0 100-7 3.5 3.5 0 000 7zM2.5 20c.4-3.6 3.1-6 6.5-6s6.1 2.4 6.5 6M16 4.3a3.5 3.5 0 010 6.4M18.5 14.4c1.8.8 2.8 2.9 3 5.6",
  user: "M12 12a4 4 0 100-8 4 4 0 000 8zM4.5 20.5c.6-3.9 3.7-6.5 7.5-6.5s6.9 2.6 7.5 6.5",
  ballot: "M4 11h16v9.5a.5.5 0 01-.5.5h-15a.5.5 0 01-.5-.5V11zM7.5 11V4.5a.5.5 0 01.5-.5h8a.5.5 0 01.5.5V11M10 7.8l1.4 1.4 2.8-3M8 15h8",
  box: "M3.5 8L12 3.5 20.5 8v8L12 20.5 3.5 16V8zM3.5 8L12 12.5 20.5 8M12 12.5v8",
  chart: "M4 20V12M10 20V5M16 20v-6M3 20h18",
  eye: "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM12 15a3 3 0 100-6 3 3 0 000 6z",
  eyeoff: "M3 3l18 18M10.6 5.6c.5-.1.9-.1 1.4-.1 6 0 9.5 6.5 9.5 6.5a17 17 0 01-2.6 3.3M6.4 6.4C3.9 8.1 2.5 12 2.5 12S6 18.5 12 18.5c1.7 0 3.2-.5 4.5-1.2M9.9 9.9a3 3 0 004.2 4.2",
  copy: "M9 9h10.5a.5.5 0 01.5.5V20a.5.5 0 01-.5.5H9a.5.5 0 01-.5-.5V9.5A.5.5 0 019 9zM15 9V4.5a.5.5 0 00-.5-.5H4.5a.5.5 0 00-.5.5V15a.5.5 0 00.5.5H8.5",
  refresh: "M20 12a8 8 0 11-2.4-5.7M20 4v5h-5",
  clock: "M12 21a9 9 0 100-18 9 9 0 000 18zM12 7.5V12l3 2",
  server: "M4.5 4h15a.5.5 0 01.5.5v5a.5.5 0 01-.5.5h-15a.5.5 0 01-.5-.5v-5a.5.5 0 01.5-.5zM4.5 14h15a.5.5 0 01.5.5v5a.5.5 0 01-.5.5h-15a.5.5 0 01-.5-.5v-5a.5.5 0 01.5-.5zM8 7h.01M8 17h.01",
  finger: "M7 20c1-2 1.5-4.5 1.5-7.5a3.5 3.5 0 017 0c0 3-.3 5.4-1.2 7.5M11.9 12.5c0 3.5-.6 6-1.9 8M4.8 16.5c.5-1.3.7-2.6.7-4a6.5 6.5 0 0113 0c0 1.4-.1 2.8-.3 4M17.2 20.2c.4-.9.7-1.9.9-2.9",
  logout: "M9 20.5H5.5a1 1 0 01-1-1v-15a1 1 0 011-1H9M16 16.5l4.5-4.5L16 7.5M20.5 12H9.5",
  trash: "M4 7h16M10 11v6M14 11v6M6 7l1 13.2a1 1 0 001 .8h8a1 1 0 001-.8L18 7M9 7V4.5a.5.5 0 01.5-.5h5a.5.5 0 01.5.5V7",
  search: "M10.5 17.5a7 7 0 100-14 7 7 0 000 14zM20.5 20.5l-5-5",
  alert: "M12 3.5l9.5 16.5h-19L12 3.5zM12 10v4.5M12 17.3h.01",
  info: "M12 21a9 9 0 100-18 9 9 0 000 18zM12 11v5.5M12 7.8h.01",
  mail: "M3.5 5.5h17a.5.5 0 01.5.5v12a.5.5 0 01-.5.5h-17a.5.5 0 01-.5-.5V6a.5.5 0 01.5-.5zM3.5 6.5L12 13l8.5-6.5",
  mailopen: "M3 10l9-6.5 9 6.5v9.5a.5.5 0 01-.5.5h-17a.5.5 0 01-.5-.5V10zM3 10l9 6 9-6",
  building: "M3 20.5h18M5 20.5V10M9.5 20.5V10M14.5 20.5V10M19 20.5V10M2.5 10L12 4l9.5 6",
  clipboard: "M9 3.5h6a.5.5 0 01.5.5v2a.5.5 0 01-.5.5H9a.5.5 0 01-.5-.5V4a.5.5 0 01.5-.5zM15.5 5h2a1 1 0 011 1v14a1 1 0 01-1 1h-11a1 1 0 01-1-1V6a1 1 0 011-1h2M9 14l2 2 4-4.5",
  scan: "M4 8V5.5A1.5 1.5 0 015.5 4H8M16 4h2.5A1.5 1.5 0 0120 5.5V8M20 16v2.5a1.5 1.5 0 01-1.5 1.5H16M8 20H5.5A1.5 1.5 0 014 18.5V16M10.5 15.5a4 4 0 100-8 4 4 0 000 8zM13.5 14.5L16 17",
  activity: "M3 12h4l3-7.5 4 15 3-7.5h4",
  zap: "M13 2.5L4.5 13.5H11L10 21.5l8.5-11H12l1-8z",
  mask: "M3 9.5c0-1.5 1-2.5 2.5-2.5 2 0 3.8 1.3 6.5 1.3S16.5 7 18.5 7C20 7 21 8 21 9.5c0 4.2-2.7 7.5-5.5 7.5-1.7 0-2.6-1.6-3.5-1.6s-1.8 1.6-3.5 1.6C5.7 17 3 13.7 3 9.5zM7.5 11h2M14.5 11h2",
  shuffle: "M16 4h4v4M4 20L20 4M20 16v4h-4M14.5 14.5L20 20M4 4l5 5",
  layers: "M12 3.5l9 4.5-9 4.5L3 8l9-4.5zM3 12l9 4.5 9-4.5M3 16l9 4.5 9-4.5",
  grid: "M4 4h6.5v6.5H4zM13.5 4H20v6.5h-6.5zM4 13.5h6.5V20H4zM13.5 13.5H20V20h-6.5z",
  sparkles: "M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6",
  book: "M4 5.5A1.5 1.5 0 015.5 4H11v16H5.5A1.5 1.5 0 014 18.5v-13zM20 5.5A1.5 1.5 0 0018.5 4H13v16h5.5a1.5 1.5 0 001.5-1.5v-13z",
  home: "M3.5 11L12 4l8.5 7M5.5 9.5V20h5v-5.5h3V20h5V9.5",
  list: "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01",
  receipt: "M6 3.5h12a.5.5 0 01.5.5v16.5l-2.5-1.5-2 1.5-2-1.5-2 1.5-2-1.5-2.5 1.5V4a.5.5 0 01.5-.5zM9 8h6M9 12h6M9 16h3",
  play: "M8 5.5v13l10.5-6.5L8 5.5z",
  stop: "M7 7h10v10H7z",
  download: "M12 4v11M7 10.5l5 5 5-5M5 20h14",
  send: "M21 3L10 14M21 3l-6.5 18-4.5-7-7-4.5L21 3z",
  settings: "M12 15a3 3 0 100-6 3 3 0 000 6zM19.4 13.5l1.6 1.2-2 3.4-1.9-.7a7 7 0 01-2 1.2L14.8 21h-4l-.3-2.4a7 7 0 01-2-1.2l-1.9.7-2-3.4 1.6-1.2a7 7 0 010-2.6L4.6 9.6l2-3.4 1.9.7a7 7 0 012-1.2L10.8 3h4l.3 2.4a7 7 0 012 1.2l1.9-.7 2 3.4-1.6 1.2a7 7 0 010 2.6z",
  gavel: "M14.5 3.5l6 6M12 6l6 6M9.5 8.5l6 6M13 11L4 20M16.5 5.5l-4 4",
  hash: "M9 3.5L7 20.5M17 3.5l-2 17M4 9h16.5M3.5 15H20"
};

export function icon(name, cls = "") {
  return raw(`<svg class="i ${cls}" viewBox="0 0 24 24" aria-hidden="true"><path d="${P[name] || P.info}"/></svg>`);
}

const PALETTE = ["#4F46E5", "#0EA5A4", "#7C3AED", "#DB2777", "#EA580C", "#059669", "#2563EB", "#CA8A04"];

export function colorFor(text) {
  let h = 0;
  for (const c of String(text || "")) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

export function initials(name) {
  const parts = String(name || "?").replace(/[^\p{L}\p{N}\s.]/gu, " ").split(/[\s.]+/).filter((x) => x && !/^(prof|dr|mr|ms|mrs)$/i.test(x));
  if (!parts.length) return "?";
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

export function avatar(name, color, cls = "") {
  return html`<span class="avatar ${cls}" style="background:${color || colorFor(name)}">${initials(name)}</span>`;
}

export const STATUS = {
  ceremony: ["Key ceremony", "Trustees are generating the threshold key"],
  registration: ["Registration", "Voters receive credentials from the registrar"],
  voting: ["Voting open", "The bulletin board accepts anonymous ballots"],
  closed: ["Voting closed", "Waiting for trustees to approve the tally"],
  tallying: ["Tallying", "Encrypted cleansing, mixing and decryption in progress"],
  published: ["Result published", "The verified result is public"]
};

export function statusChip(status) {
  const s = STATUS[status] || [status];
  return html`<span class="chip ${status}"><span class="dot"></span>${s[0]}</span>`;
}

export const ROLE_INFO = {
  voter: { label: "Voter", icon: "ballot", desc: "Get a credential, cast an encrypted ballot anonymously, revote, and verify your tracker." },
  authority: { label: "Authority", icon: "building", desc: "Create elections, set candidates and board capacity, and drive every phase." },
  registrar: { label: "Registrar", icon: "clipboard", desc: "Admit eligible voters, encrypt their credentials into the roster and seal delivery." },
  trustee: { label: "Trustee", icon: "key", desc: "Hold a share of the threshold key, join the key ceremony and release shares for the tally." },
  auditor: { label: "Auditor", icon: "scan", desc: "Inspect the bulletin board, roster and transcripts, and re-verify every proof." }
};

export function fmtDate(ts, withTime = true) {
  if (!ts) return "";
  const d = typeof ts === "number" ? new Date(ts * 1000) : new Date(ts);
  if (isNaN(d.getTime())) return String(ts);
  const opts = { year: "numeric", month: "short", day: "numeric" };
  if (withTime) Object.assign(opts, { hour: "2-digit", minute: "2-digit" });
  return d.toLocaleString(undefined, opts);
}

export function ago(ts) {
  if (!ts) return "";
  const s = Math.max(0, Date.now() / 1000 - ts);
  if (s < 45) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return fmtDate(ts, false);
}

export function duration(sec) {
  if (sec === null || sec === undefined) return "";
  sec = Math.max(0, Math.round(sec));
  if (sec < 60) return `${sec}s`;
  const m = Math.floor(sec / 60), s = sec % 60;
  if (m < 60) return `${m}m ${String(s).padStart(2, "0")}s`;
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`;
}

export function groupHex(h, n = 4) {
  const out = [];
  for (let i = 0; i < h.length; i += n) out.push(h.slice(i, i + n));
  return out.join(" ");
}

export function toast(message, kind = "info", ms = 4200) {
  const root = document.getElementById("toasts");
  const el = document.createElement("div");
  el.className = `toast ${kind}`;
  el.innerHTML = String(html`${icon(kind === "ok" ? "check" : kind === "bad" ? "alert" : "info")}<div>${message}</div>`);
  root.appendChild(el);
  setTimeout(() => { el.style.opacity = "0"; el.style.transition = "opacity .3s"; setTimeout(() => el.remove(), 320); }, ms);
}

export function modal({ title, body, actions = [], wide = false, onClose }) {
  const root = document.getElementById("modal-root");
  const back = document.createElement("div");
  back.className = "modal-back";
  back.innerHTML = String(html`<div class="modal ${wide ? "wide" : ""}" role="dialog" aria-modal="true">
    <div class="modal-h"><h3>${title}</h3><button class="x-btn" data-close>${icon("x")}</button></div>
    <div class="modal-b"></div>
    ${actions.length ? html`<div class="modal-f"></div>` : ""}
  </div>`);
  const b = back.querySelector(".modal-b");
  if (body instanceof Node) b.appendChild(body); else b.innerHTML = String(body || "");
  const close = () => { back.remove(); document.removeEventListener("keydown", onKey); if (onClose) onClose(); };
  const onKey = (e) => { if (e.key === "Escape") close(); };
  document.addEventListener("keydown", onKey);
  back.addEventListener("mousedown", (e) => { if (e.target === back) close(); });
  back.querySelector("[data-close]").onclick = close;
  const f = back.querySelector(".modal-f");
  for (const a of actions) {
    const btn = document.createElement("button");
    btn.className = `btn ${a.kind || "btn-outline"}`;
    btn.innerHTML = String(html`${a.icon ? icon(a.icon) : ""}${a.label}`);
    btn.onclick = async () => {
      if (a.onClick) {
        const res = await busy(btn, () => a.onClick({ close, body: b }));
        if (res !== false) close();
      } else close();
    };
    f.appendChild(btn);
  }
  root.appendChild(back);
  return { close, body: b, el: back };
}

export function confirmDialog(title, message, label = "Confirm", kind = "btn-primary") {
  return new Promise((resolve) => {
    let decided = false;
    modal({
      title, body: String(html`<p class="muted">${message}</p>`),
      actions: [
        { label: "Cancel", onClick: () => { decided = true; resolve(false); } },
        { label, kind, onClick: () => { decided = true; resolve(true); } }
      ],
      onClose: () => { if (!decided) resolve(false); }
    });
  });
}

export async function busy(btn, fn) {
  if (!btn) return fn();
  const old = btn.innerHTML;
  btn.disabled = true;
  btn.innerHTML = `<span class="spin"></span>${btn.textContent.trim() ? `<span>${esc(btn.textContent.trim())}</span>` : ""}`;
  try {
    return await fn();
  } catch (e) {
    toast(e.message || String(e), "bad");
    return false;
  } finally {
    if (btn.isConnected) {
      btn.disabled = false;
      btn.innerHTML = old;
    }
  }
}

export async function copyText(text, what = "Copied") {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const ta = document.createElement("textarea");
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand("copy"); } catch {}
    ta.remove();
  }
  toast(`${what} to the clipboard`, "ok", 2200);
}

export function bind(root, handlers) {
  root.addEventListener("click", (e) => {
    const el = e.target.closest("[data-act]");
    if (!el || !root.contains(el)) return;
    const fn = handlers[el.dataset.act];
    if (fn) {
      e.preventDefault();
      fn(el, e);
    }
  });
}

export function capacityMeter(e) {
  const cap = e.capacity || 1;
  const r = (100 * (e.roster || 0)) / cap, b = (100 * (e.ballots || 0)) / cap;
  return html`<div class="meter"><span class="bar-roster" style="width:${r}%"></span><span class="bar-ballots" style="width:${b}%"></span></div>
    <div class="legend mt"><span><i style="background:var(--sky)"></i>${e.roster} roster</span><span><i style="background:var(--primary)"></i>${e.ballots} ballots</span><span><i style="background:var(--surface-3)"></i>${cap - e.roster - e.ballots} free of ${cap}</span></div>`;
}

const ORDER = ["ceremony", "registration", "voting", "closed", "tallying", "published"];

export function timeline(e) {
  const idx = ORDER.indexOf(e.status);
  const labels = [["Key ceremony", "Trustees"], ["Registration", "Registrar"], ["Voting", "Voters"], ["Closed", "Authority"], ["Tally", "Trustees"], ["Published", "Everyone"]];
  return html`<div class="timeline">${ORDER.map((s, i) => {
    const cls = i < idx || e.status === "published" ? "done" : i === idx ? "now" : "";
    return html`<div class="tl-step ${cls}"><div class="tl-dot">${cls === "done" ? icon("check", "sm") : i + 1}</div><b>${labels[i][0]}</b><span>${e.phases && e.phases[s] ? fmtDate(e.phases[s]) : labels[i][1]}</span></div>`;
  })}</div>`;
}

export function empty(title, text, iconName = "box", extra = "") {
  return html`<div class="empty"><div class="icon-tile">${icon(iconName, "lg")}</div><h3>${title}</h3><p>${text}</p>${extra}</div>`;
}

export function plural(n, one, many) {
  return `${n} ${n === 1 ? one : many}`;
}

export function pct(a, b) {
  return b ? Math.round((1000 * a) / b) / 10 : 0;
}

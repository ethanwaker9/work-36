import { get, post, session, setUnauthorizedHandler } from "./api.js";
import { ROLE_INFO, avatar, bind, html, icon, toast } from "./ui.js";
import loginPage from "./pages/login.js";
import homePage from "./pages/home.js";
import electionsPage from "./pages/elections.js";
import electionPage from "./pages/election.js";
import boothPage from "./pages/booth.js";
import credentialPage from "./pages/credential.js";
import tallyPage from "./pages/tally.js";
import createPage from "./pages/create.js";
import trackPage from "./pages/track.js";
import howPage from "./pages/how.js";

const ROUTES = [
  ["login", loginPage, true],
  ["", homePage],
  ["elections", electionsPage],
  ["new", createPage],
  ["e/:id/vote", boothPage],
  ["e/:id/credential", credentialPage],
  ["e/:id/tally", tallyPage],
  ["e/:id", electionPage],
  ["e/:id/:tab", electionPage],
  ["track", trackPage],
  ["track/:tracker", trackPage],
  ["receipts", trackPage],
  ["how", howPage, true]
];

const NAV = {
  voter: [["", "Home", "home"], ["elections", "Elections", "ballot"], ["receipts", "My receipts", "receipt"], ["track", "Track a ballot", "search"], ["how", "How it works", "book"]],
  authority: [["", "Dashboard", "home"], ["elections", "Elections", "list"], ["new", "New election", "plus"], ["how", "How it works", "book"]],
  registrar: [["", "Dashboard", "home"], ["elections", "Elections", "list"], ["how", "How it works", "book"]],
  trustee: [["", "Dashboard", "home"], ["elections", "Elections", "list"], ["how", "How it works", "book"]],
  auditor: [["", "Dashboard", "home"], ["elections", "Elections", "list"], ["track", "Track a ballot", "search"], ["how", "How it works", "book"]]
};

const state = { cleanups: [], system: null, lastPath: null };

export function go(path) {
  const target = "#/" + String(path || "").replace(/^[#/]+/, "");
  if (location.hash === target) render();
  else location.hash = target;
}

function match(path) {
  const segs = path.split("/").filter(Boolean);
  for (const [pat, page, pub] of ROUTES) {
    const ps = pat.split("/").filter(Boolean);
    if (ps.length !== segs.length) continue;
    const params = {};
    let ok = true;
    for (let i = 0; i < ps.length; i++) {
      if (ps[i].startsWith(":")) params[ps[i].slice(1)] = decodeURIComponent(segs[i]);
      else if (ps[i] !== segs[i]) { ok = false; break; }
    }
    if (ok) return { page, params, pub: !!pub, pattern: pat, path };
  }
  return null;
}

async function switchRole(role) {
  const s = session.get();
  if (!s) return;
  const res = await post("/api/auth/login", { email: s.user.email, name: s.user.name, provider: s.user.provider, role });
  session.set(res);
  toast(`You are now signed in as ${ROLE_INFO[role].label.toLowerCase()}`, "ok");
  go("");
}

async function signOut() {
  try { await post("/api/auth/logout"); } catch {}
  session.clear();
  go("login");
}

function shell(app, user, path) {
  const nav = NAV[user.role] || NAV.voter;
  const current = path.split("/")[0];
  app.innerHTML = String(html`
    <header class="topbar">
      <div class="topbar-inner">
        <a class="brand" href="#/">${html`<img src="/img/logo.svg" alt="">`}<span>Oylama</span></a>
        <nav class="nav">${nav.map(([p, label, ic]) => html`<a href="#/${p}" class="${(p === "" ? path === "" : current === p) ? "active" : ""}">${icon(ic, "sm")}${label}</a>`)}</nav>
        <div class="userbox">
          <button class="userbtn" data-act="menu">${avatar(user.name)}<span class="who"><b>${user.name}</b><span>${ROLE_INFO[user.role].label}</span></span>${icon("down", "sm")}</button>
        </div>
      </div>
    </header>
    <main id="main"></main>`);
  const box = app.querySelector(".userbox");
  bind(box, {
    menu: () => {
      const open = box.querySelector(".menu");
      if (open) { open.remove(); return; }
      const m = document.createElement("div");
      m.className = "menu rise";
      m.innerHTML = String(html`
        <div class="menu-head"><b>${user.name}</b><span>${user.email}</span><div class="row mt"><span class="role-pill">${ROLE_INFO[user.role].label}</span><span class="tiny muted">via ${user.provider}</span></div></div>
        <div class="label">Switch role</div>
        ${Object.entries(ROLE_INFO).filter(([k]) => k !== user.role).map(([k, v]) => html`<button data-role="${k}">${icon(v.icon)}${v.label}</button>`)}
        <div class="label">Device</div>
        <button disabled style="cursor:default">${icon("finger")}<span class="small">Device key ${user.device_key ? user.device_key.slice(0, 12) : ""}</span></button>
        <button data-out>${icon("logout")}Sign out</button>`);
      box.appendChild(m);
      m.addEventListener("click", (e) => {
        const r = e.target.closest("[data-role]");
        if (r) { m.remove(); switchRole(r.dataset.role).catch((err) => toast(err.message, "bad")); }
        if (e.target.closest("[data-out]")) signOut();
      });
      setTimeout(() => document.addEventListener("click", function off(ev) {
        if (!box.contains(ev.target)) { m.remove(); document.removeEventListener("click", off); }
      }), 0);
    }
  });
  return app.querySelector("#main");
}

async function render() {
  for (const fn of state.cleanups.splice(0)) { try { fn(); } catch {} }
  const full = location.hash.replace(/^#\/?/, "");
  const [path, qs] = full.split("?");
  const m = match(path) || match("");
  const s = session.get();
  if (!s && !m.pub) { go("login"); return; }
  if (s && m.pattern === "login") { go(""); return; }
  const app = document.getElementById("app");
  let el;
  if (!s || m.pattern === "login") {
    app.innerHTML = "";
    el = app;
  } else {
    el = shell(app, s.user, path);
  }
  const ctx = {
    el, params: m.params, user: s ? s.user : null, system: state.system, go,
    query: new URLSearchParams(qs || ""),
    onCleanup: (fn) => state.cleanups.push(fn),
    rerender: render,
    setSession: (res) => { session.set(res); }
  };
  if (state.lastPath !== path) window.scrollTo(0, 0);
  state.lastPath = path;
  try {
    await m.page(ctx);
  } catch (e) {
    el.innerHTML = String(html`<div class="card pad"><div class="notice bad">${icon("alert")}<div><b>Something went wrong</b><div>${e.message || e}</div></div></div><div class="row mt"><a class="btn btn-outline" href="#/">Back to home</a></div></div>`);
  }
}

setUnauthorizedHandler(() => {
  session.clear();
  toast("Your session ended. Please sign in again.", "info");
  go("login");
});

window.addEventListener("hashchange", render);

(async () => {
  try { state.system = await get("/api/system"); } catch {}
  const s = session.get();
  if (s) {
    try {
      const me = await get("/api/auth/me");
      session.set({ token: s.token, user: me });
    } catch {
      session.clear();
    }
  }
  render();
})();

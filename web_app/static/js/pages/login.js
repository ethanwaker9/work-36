import { post } from "../api.js";
import { ROLE_INFO, avatar, busy, html, icon, modal, toast } from "../ui.js";

const LOGOS = {
  google: `<svg viewBox="0 0 24 24" fill="none" stroke-width="3.6"><circle cx="12" cy="12" r="7.6" stroke="#4285F4" stroke-dasharray="8 100" transform="rotate(0 12 12)"/><circle cx="12" cy="12" r="7.6" stroke="#34A853" stroke-dasharray="12 100" transform="rotate(60 12 12)"/><circle cx="12" cy="12" r="7.6" stroke="#FBBC05" stroke-dasharray="8 100" transform="rotate(150 12 12)"/><circle cx="12" cy="12" r="7.6" stroke="#EA4335" stroke-dasharray="14.7 100" transform="rotate(210 12 12)"/><path d="M12.4 12h7.4" stroke="#4285F4" stroke-width="3.2"/></svg>`,
  microsoft: `<svg viewBox="0 0 24 24"><rect x="3" y="3" width="8.4" height="8.4" fill="#F25022"/><rect x="12.6" y="3" width="8.4" height="8.4" fill="#7FBA00"/><rect x="3" y="12.6" width="8.4" height="8.4" fill="#00A4EF"/><rect x="12.6" y="12.6" width="8.4" height="8.4" fill="#FFB900"/></svg>`,
  apple: `<svg viewBox="0 0 24 24" fill="#111"><path d="M15.2 2.6c.1 1.1-.3 2.2-1 3-.7.8-1.8 1.4-2.8 1.3-.1-1.1.4-2.2 1-2.9.7-.8 1.9-1.4 2.8-1.4zM18.6 16.6c-.5 1.1-.7 1.6-1.4 2.6-.9 1.4-2.2 3.1-3.8 3.1-1.4 0-1.8-.9-3.7-.9s-2.3.9-3.7.9c-1.6 0-2.8-1.6-3.7-3C.1 15.4-.2 10.9 1.3 8.6c1.1-1.6 2.8-2.6 4.4-2.6 1.6 0 2.7.9 4 .9 1.3 0 2.1-.9 4-.9 1.4 0 2.9.8 4 2.1-3.5 1.9-2.9 6.9.9 8.5z" transform="translate(2 0) scale(.92)"/></svg>`,
  passkey: null
};

const PROVIDERS = [
  ["google", "Google", ["alex.morgan@gmail.com", "sam.rivera@gmail.com"]],
  ["microsoft", "Microsoft", ["jordan.lee@outlook.com", "casey.kim@hotmail.com"]],
  ["apple", "Apple", ["taylor.brooks@icloud.com", "hidden-relay-7f2@privaterelay.appleid.com"]],
  ["passkey", "Passkey", ["this.device@oylama.demo"]]
];

const QUICK = [
  ["voter", "alice@oylama.demo", "Alice Johnson", "Voter with a credential"],
  ["voter", "", "", "New voter"],
  ["authority", "authority@oylama.demo", "Election Authority", "Authority"],
  ["registrar", "registrar@oylama.demo", "City Registrar", "Registrar"],
  ["trustee", "ayla.demir@trustees.oylama.demo", "Prof. Ayla Demir", "Trustee"],
  ["auditor", "auditor@oylama.demo", "Independent Auditor", "Auditor"]
];

function lattice() {
  let dots = "";
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 22; x++) {
      const px = 20 + x * 46 + (y % 2) * 23, py = 20 + y * 40;
      const o = (0.08 + 0.3 * Math.abs(Math.sin(x * 0.7 + y * 0.45))).toFixed(2);
      dots += `<circle cx="${px}" cy="${py}" r="2" fill="#fff" opacity="${o}"/>`;
    }
  }
  let lines = "";
  for (let i = 0; i < 9; i++) {
    const y = 60 + i * 80;
    lines += `<path d="M0 ${y} L1040 ${y - 260}" stroke="#fff" stroke-opacity=".05" stroke-width="1"/>`;
  }
  return `<svg class="lattice" viewBox="0 0 1040 660" preserveAspectRatio="xMidYMid slice">${lines}${dots}</svg>`;
}

export default async function login(ctx) {
  let role = ctx.query.get("role") && ROLE_INFO[ctx.query.get("role")] ? ctx.query.get("role") : "voter";

  const doLogin = async (email, provider, name, forcedRole) => {
    const res = await post("/api/auth/login", { email, password: "demo", role: forcedRole || role, provider, name });
    ctx.setSession(res);
    toast(`Welcome, ${res.user.name}`, "ok");
    ctx.go("");
  };

  ctx.el.innerHTML = String(html`
  <div class="login">
    <section class="login-hero">
      ${html([lattice()])}
      <a class="brand" href="#/how"><img src="/img/logo.svg" alt=""><span>Oylama</span></a>
      <div class="pitch">
        <div class="eyebrow" style="color:#c7d2fe">${icon("sparkles", "sm")} Online elections for the post-quantum era</div>
        <h1>Vote from anywhere. <em>No one can read, buy or force your vote.</em></h1>
        <p class="lead">Oylama encrypts every ballot with lattice cryptography, lets a pressured voter hand over a fake credential that looks exactly like the real one, and removes invalid ballots while everything stays encrypted.</p>
        <div class="hero-feats">
          <div class="hero-feat"><b>${icon("shield")} Post-quantum</b><span>Packed lattice encryption with N = 8192, ML-KEM-768 and ML-DSA-65</span></div>
          <div class="hero-feat"><b>${icon("mask")} Coercion-resistant</b><span>Fake credentials cast ballots that silently never count</span></div>
          <div class="hero-feat"><b>${icon("key")} Threshold trust</b><span>Two of three trustees must cooperate to decrypt anything</span></div>
          <div class="hero-feat"><b>${icon("scan")} Verifiable</b><span>Every proof on the bulletin board can be re-checked by anyone</span></div>
        </div>
      </div>
      <div class="foot">Demo mode · any email or username with any password signs you in · <a href="#/how" style="color:#c7d2fe">How Oylama works</a></div>
    </section>
    <section class="login-side">
      <div class="login-card rise">
        <h2>Sign in</h2>
        <p class="muted" style="margin-top:6px">Pick the part you play in the election. You can switch roles later from the account menu.</p>
        <div class="roles mt-lg">${Object.entries(ROLE_INFO).map(([k, v]) => html`<button type="button" class="role ${k === role ? "on" : ""}" data-role="${k}">${icon(v.icon, "lg")}${v.label}</button>`)}</div>
        <p class="role-desc mt" id="role-desc">${ROLE_INFO[role].desc}</p>
        <form class="stack mt" id="login-form" autocomplete="on">
          <div class="field"><label for="email">Email or username</label>
            <div class="input-icon">${icon("user", "sm")}<input id="email" type="text" name="email" placeholder="you@example.com" autocomplete="username"></div></div>
          <div class="field"><label for="password">Password</label>
            <div class="input-icon">${icon("lock", "sm")}<input id="password" type="password" name="password" placeholder="Anything works in demo mode" autocomplete="current-password"></div></div>
          <button class="btn btn-primary btn-lg btn-block" id="submit" type="submit">Sign in as ${ROLE_INFO[role].label}</button>
        </form>
        <div class="divider">or continue with</div>
        <div class="oauth">${PROVIDERS.map(([k, label]) => html`<button type="button" class="btn" data-provider="${k}">${k === "passkey" ? icon("finger") : html([LOGOS[k]])}${label}</button>`)}</div>
        <div class="divider">one-click demo accounts</div>
        <div class="quick">${QUICK.map(([r, email, name, label], i) => html`<button type="button" data-quick="${i}">${icon(ROLE_INFO[r].icon, "sm")}${label}</button>`)}</div>
      </div>
    </section>
  </div>`);

  const el = ctx.el;
  const setRole = (r) => {
    role = r;
    el.querySelectorAll(".role").forEach((b) => b.classList.toggle("on", b.dataset.role === r));
    el.querySelector("#role-desc").textContent = ROLE_INFO[r].desc;
    el.querySelector("#submit").textContent = `Sign in as ${ROLE_INFO[r].label}`;
  };
  el.querySelectorAll(".role").forEach((b) => b.addEventListener("click", () => setRole(b.dataset.role)));

  el.querySelector("#login-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const email = el.querySelector("#email").value.trim();
    await busy(el.querySelector("#submit"), () => doLogin(email, "password"));
  });

  el.querySelectorAll("[data-quick]").forEach((b) => b.addEventListener("click", () => {
    const [r, email, name] = QUICK[Number(b.dataset.quick)];
    busy(b, () => doLogin(email, "demo", name, r));
  }));

  el.querySelectorAll("[data-provider]").forEach((b) => b.addEventListener("click", () => {
    const [key, label, accounts] = PROVIDERS.find((p) => p[0] === b.dataset.provider);
    const typed = el.querySelector("#email").value.trim();
    const list = typed && typed.includes("@") ? [typed, ...accounts] : accounts;
    const body = document.createElement("div");
    body.innerHTML = String(html`
      <div class="center" style="margin-bottom:16px">
        <div style="width:44px;height:44px;margin:0 auto 10px">${key === "passkey" ? icon("finger", "xl") : html([LOGOS[key].replace("<svg", '<svg width="44" height="44"')])}</div>
        <h3>${key === "passkey" ? "Use a passkey" : `Sign in with ${label}`}</h3>
        <p class="muted small" style="margin-top:4px">to continue to <b>Oylama</b> as ${ROLE_INFO[role].label.toLowerCase()}</p>
      </div>
      <div class="stack sm">${list.map((a) => html`<button class="acct" data-acct="${a}">${avatar(a.split("@")[0].replace(/[.\-_]/g, " "))}<div class="grow"><b>${a.split("@")[0].replace(/[.\-_]+/g, " ").replace(/\b\w/g, (c) => c.toUpperCase())}</b><span>${a}</span></div>${icon("chevron", "sm")}</button>`)}</div>
      <div class="field mt"><label>Use another account</label><div class="row"><input type="text" placeholder="name@example.com" data-other><button class="btn btn-primary" data-go>Continue</button></div></div>
      <p class="tiny faint mt center">Simulated sign-in for the demo. No data leaves this Oylama server.</p>`);
    const m = modal({ title: `${label} account`, body });
    body.addEventListener("click", (ev) => {
      const acct = ev.target.closest("[data-acct]");
      const goBtn = ev.target.closest("[data-go]");
      if (acct) { busy(acct, () => doLogin(acct.dataset.acct, key)).then(() => m.close()); }
      if (goBtn) {
        const v = body.querySelector("[data-other]").value.trim();
        busy(goBtn, () => doLogin(v, key)).then(() => m.close());
      }
    });
  }));
}

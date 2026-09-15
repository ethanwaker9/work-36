import { get, post } from "../api.js";
import { credentialFromText } from "../oylama-crypto.js";
import { vault } from "../store.js";
import { avatar, copyText, fmtDate, groupHex, html, icon, toast } from "../ui.js";

const STEPS = [
  ["key", "Download the election public key"],
  ["encode", "Encode your choice and credential into plaintext slots"],
  ["encrypt-vote", "Encrypt the vote with packed lattice encryption"],
  ["encrypt-credential", "Encrypt the credential"],
  ["prove-vote", "Prove knowledge of the vote encryption"],
  ["prove-credential", "Prove knowledge of the credential encryption"],
  ["post", "Post anonymously to the bulletin board"],
  ["verify", "Bulletin board verifies both proofs"]
];

let pkCache = {};
let worker = null;

function getWorker() {
  if (!worker) worker = new Worker("/js/ballot-worker.js", { type: "module" });
  return worker;
}

export default async function booth(ctx) {
  const id = ctx.params.id;
  const u = ctx.user;
  const e = await get(`/api/elections/${id}`);
  const stored = vault.credential(u.email, id);
  let pending = null;
  try { pending = sessionStorage.getItem("oylama.booth.credential"); sessionStorage.removeItem("oylama.booth.credential"); } catch {}
  const s = { step: 0, choice: null, credMode: pending ? "other" : stored ? "stored" : "other", other: pending || "", receipt: null, reveal: false };

  if (e.status !== "voting") {
    ctx.el.innerHTML = String(html`<div class="crumbs"><a href="#/e/${id}">${e.title}</a>${icon("chevron", "sm")}<span>Voting booth</span></div>
      <div class="card pad center"><div class="icon-tile warning" style="margin:0 auto 12px">${icon("clock", "lg")}</div><h2>The booth is closed</h2>
      <p class="muted mt">${e.status === "registration" ? "Voting has not opened yet. Get your credential now so you are ready." : e.status === "ceremony" ? "The trustees are still generating the election key." : "Voting has ended for this election."}</p>
      <div class="row mt-lg" style="justify-content:center"><a class="btn btn-outline" href="#/e/${id}">Back to the election</a>${e.status === "registration" ? html`<a class="btn btn-primary" href="#/e/${id}/credential">${icon("key")}Get my credential</a>` : ""}</div></div>`);
    return;
  }

  const stepper = () => html`<div class="booth-steps">${["Choose", "Credential", "Review", "Encrypt and cast"].map((l, i) => html`<div class="${i === s.step ? "on" : i < s.step ? "done" : ""}"><i>${i < s.step ? icon("check", "sm") : i + 1}</i>${l}</div>`)}</div>`;
  const cand = (idx) => e.candidates.find((c) => c.idx === idx);
  const credText = () => (s.credMode === "stored" ? stored : s.other.trim());

  const draw = () => {
    let body;
    if (s.step === 0) {
      body = html`<div class="card"><div class="card-h"><h3>${icon("ballot")}Choose one option</h3><span class="small muted">${e.candidates.length} options</span></div><div class="card-b stack sm" id="cands">
        ${e.candidates.map((c) => html`<label class="cand ${s.choice === c.idx ? "on" : ""}" data-idx="${c.idx}"><input type="radio" name="c" value="${c.idx}" ${s.choice === c.idx ? "checked" : ""}><span class="bar" style="background:${c.color}"></span>${avatar(c.name, c.color, "lg")}<div class="meta"><b>${c.name}</b><span>${c.party || `Option ${c.idx}`}</span>${c.bio ? html`<div class="bio">${c.bio}</div>` : ""}</div><span class="radio">${icon("check", "sm")}</span></label>`)}
      </div><div class="card-f row between"><a class="btn btn-ghost" href="#/e/${id}">${icon("left", "sm")}Leave the booth</a><button class="btn btn-primary" data-next ${s.choice ? "" : "disabled"}>Continue ${icon("right", "sm")}</button></div></div>`;
    } else if (s.step === 1) {
      body = html`<div class="card"><div class="card-h"><h3>${icon("key")}Which credential signs this ballot?</h3></div><div class="card-b stack">
        <label class="cand ${s.credMode === "stored" ? "on" : ""} ${stored ? "" : "hide"}" data-mode="stored"><input type="radio" name="m"><span class="icon-tile">${icon("finger")}</span><div class="meta"><b>My credential on this device</b><span class="mono">${stored ? (s.reveal ? stored : stored.slice(0, 4) + "-••••-••••-••••-••••-••••-••••-" + stored.slice(-4)) : ""}</span></div><span class="radio">${icon("check", "sm")}</span></label>
        <label class="cand ${s.credMode === "other" ? "on" : ""}" data-mode="other"><input type="radio" name="m"><span class="icon-tile violet">${icon("mask")}</span><div class="meta"><b>Another credential</b><span>Paste a credential, for example one you were handed or a fake one you generated</span></div><span class="radio">${icon("check", "sm")}</span></label>
        <div class="field ${s.credMode === "other" ? "" : "hide"}"><input type="text" class="mono" id="othercred" placeholder="XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX" value="${s.other}"><span class="hint">32 hexadecimal digits. The board cannot tell whether a credential is real.</span></div>
        ${!stored ? html`<div class="notice warn">${icon("info")}<div>No credential is stored on this device for this election. <a href="#/e/${id}/credential">Get or open your credential</a> first, or paste one here.</div></div>` : ""}
        <div class="notice">${icon("shield")}<div class="small">Your credential never leaves this browser in the clear. It is encrypted together with your vote, and only the encrypted cleansing learns whether it matches a roster entry.</div></div>
      </div><div class="card-f row between"><button class="btn btn-ghost" data-back>${icon("left", "sm")}Back</button><button class="btn btn-primary" data-next>Continue ${icon("right", "sm")}</button></div></div>`;
    } else if (s.step === 2) {
      const c = cand(s.choice);
      body = html`<div class="card"><div class="card-h"><h3>${icon("eye")}Review your ballot</h3></div><div class="card-b stack">
        <div class="cand on"><span class="bar" style="background:${c.color}"></span>${avatar(c.name, c.color, "lg")}<div class="meta"><b>${c.name}</b><span>${c.party || ""}</span></div></div>
        <dl class="kv"><dt>Election</dt><dd>${e.title}</dd><dt>Credential</dt><dd>${s.credMode === "stored" ? "My credential on this device" : html`<span class="mono small">${credText()}</span>`}</dd><dt>Encryption</dt><dd>Packed BGV, N = ${e.params.ring_degree}, in this browser</dd><dt>Channel</dt><dd>Anonymous: the board does not record who posts</dd></dl>
        <div class="notice">${icon("refresh")}<div class="small">You can vote again at any time while voting is open. Only the last ballot with your credential is counted, and nobody can see that a ballot was replaced.</div></div>
      </div><div class="card-f row between"><button class="btn btn-ghost" data-back>${icon("left", "sm")}Back</button><button class="btn btn-primary btn-lg" data-cast>${icon("lock")}Encrypt and cast</button></div></div>`;
    } else {
      body = html`<div class="card"><div class="card-h"><h3>${icon("lock")}${s.receipt ? "Ballot cast" : "Encrypting and casting"}</h3><span id="elapsed" class="small muted"></span></div><div class="card-b">
        <div class="progress-steps" id="psteps">${STEPS.map(([k, l]) => html`<div class="ps" data-k="${k}"><span class="ic">${icon("clock", "sm")}</span><div class="grow"><b>${l}</b><span class="d"></span></div><span class="t"></span></div>`)}</div>
        <div id="receipt"></div></div></div>`;
    }
    ctx.el.innerHTML = String(html`<div class="crumbs"><a href="#/elections">Elections</a>${icon("chevron", "sm")}<a href="#/e/${id}">${e.title}</a>${icon("chevron", "sm")}<span>Voting booth</span></div>
      <div class="page-head"><div><div class="eyebrow">${icon("ballot", "sm")} Voting booth</div><h1>${e.title}</h1><p class="sub">Your ballot is encrypted and proven on this device before it is posted to the bulletin board.</p></div>
      <span class="chip voting"><span class="dot"></span>Voting open</span></div>
      ${stepper()}<div class="split"><div>${body}</div>
      <div class="stack lg sticky">
        <div class="card pad"><h3 class="row">${icon("shieldcheck")}What happens to your vote</h3><div class="stack sm mt small muted">
          <div class="row top">${icon("lock", "sm")}<span>Your option becomes ${e.params.vote_bits} bits in slot ${(e.params.index_bits + e.params.credential_bits) * e.capacity} onward of an ${e.params.slots}-slot plaintext, then it is encrypted.</span></div>
          <div class="row top">${icon("key", "sm")}<span>Your credential is encrypted separately; both come with proofs that you know what you encrypted.</span></div>
          <div class="row top">${icon("shuffle", "sm")}<span>At the tally, ballots and the roster are sorted under encryption; only the last ballot of each registered credential survives.</span></div>
          <div class="row top">${icon("users", "sm")}<span>Two mix servers shuffle the survivors and two of three trustees decrypt them.</span></div></div></div>
        <div class="card pad" style="background:linear-gradient(180deg,#fff7ed,#fff)"><h3 class="row">${icon("mask")}Being watched?</h3><p class="small muted mt">Cast the ballot the other person wants with a <a href="#/e/${id}/credential">fake credential</a>. It is accepted on the board and silently discarded. Come back later with your real credential.</p></div>
      </div></div>`);
    wire();
  };

  const wire = () => {
    const el = ctx.el;
    el.querySelectorAll("[data-idx]").forEach((l) => l.addEventListener("click", () => { s.choice = Number(l.dataset.idx); draw(); }));
    el.querySelectorAll("[data-mode]").forEach((l) => l.addEventListener("click", () => { s.credMode = l.dataset.mode; draw(); }));
    const oc = el.querySelector("#othercred");
    if (oc) oc.addEventListener("input", () => { s.other = oc.value; });
    const nx = el.querySelector("[data-next]");
    if (nx) nx.addEventListener("click", () => {
      if (s.step === 1) {
        try { credentialFromText(credText()); } catch (err) { toast(s.credMode === "stored" ? "Get your credential first" : err.message, "bad"); return; }
      }
      s.step++; draw();
    });
    const bk = el.querySelector("[data-back]");
    if (bk) bk.addEventListener("click", () => { s.step--; draw(); });
    const cast = el.querySelector("[data-cast]");
    if (cast) cast.addEventListener("click", () => { s.step = 3; draw(); run(); });
  };

  const mark = (k, state, detail, t) => {
    const row = ctx.el.querySelector(`.ps[data-k="${k}"]`);
    if (!row) return;
    row.className = `ps ${state}`;
    row.querySelector(".ic").innerHTML = String(state === "done" ? icon("check", "sm") : state === "active" ? html`<span class="spin" style="width:14px;height:14px;border-width:2px;color:#fff"></span>` : state === "failed" ? icon("x", "sm") : icon("clock", "sm"));
    if (detail !== undefined) row.querySelector(".d").textContent = detail;
    if (t !== undefined) row.querySelector(".t").textContent = t;
  };

  const run = async () => {
    const t0 = performance.now();
    const clock = setInterval(() => { const x = ctx.el.querySelector("#elapsed"); if (x) x.textContent = `${((performance.now() - t0) / 1000).toFixed(1)} s`; }, 100);
    ctx.onCleanup(() => clearInterval(clock));
    let current = "key";
    const stamps = {};
    const attempts = {};
    const begin = (k, d) => {
      if (current && current !== k && stamps[current]) mark(current, "done", attempts[current] ? `accepted after ${attempts[current]} attempt${attempts[current] > 1 ? "s" : ""}` : undefined, `${Math.round(performance.now() - stamps[current])} ms`);
      current = k; stamps[k] = performance.now(); mark(k, "active", d);
    };
    try {
      begin("key", "N = 8192, five 24-bit primes");
      if (!pkCache[id]) pkCache[id] = await get(`/api/elections/${id}/public-key`);
      const pk = pkCache[id];
      mark("key", "active", `fingerprint ${pk.fingerprint.slice(0, 16)}`);
      const result = await new Promise((resolve, reject) => {
        const w = getWorker();
        w.onmessage = (ev) => {
          const m = ev.data;
          if (m.type === "progress") {
            if (m.step === "setup") mark("key", "active", "building NTT tables for the five primes and the slot map");
            else if (m.attempt) { attempts[m.step] = m.attempt; mark(m.step, "active", `rejection sampling attempt ${m.attempt}`); }
            else begin(m.step, m.detail || "");
          } else if (m.type === "done") resolve(m);
          else reject(new Error(m.message));
        };
        w.onerror = (err) => reject(new Error(err.message || "The encryption worker failed"));
        w.postMessage({ pk, credential: credText(), choice: s.choice });
      });
      attempts["prove-credential"] = result.stats.attempts[1];
      begin("post", `${(JSON.stringify(result.ballot).length / 1048576).toFixed(2)} MB of ciphertexts and proofs`);
      const r = await post(`/api/elections/${id}/ballots`, { ballot: result.ballot });
      begin("verify", `tracker ${r.tracker.slice(0, 16)} at position ${r.position + 1}`);
      mark("verify", "done", `both proofs verified by the board in ${r.verify_ms} ms · tracker matches: ${r.tracker === result.tracker ? "yes" : "no"}`, `${r.verify_ms} ms`);
      clearInterval(clock);
      s.receipt = { election: id, title: e.title, tracker: r.tracker, position: r.position, posted: r.posted, sizeKb: r.size_kb };
      vault.addReceipt(u.email, s.receipt);
      const total = ((performance.now() - t0) / 1000).toFixed(1);
      ctx.el.querySelector("#receipt").innerHTML = String(html`<div class="card tint pad mt-lg rise">
        <div class="row between wrap"><div class="row"><span class="icon-tile success">${icon("check", "lg")}</span><div><h2>Your ballot is on the board</h2><p class="muted small">Encrypted in ${result.stats.encryptMs} ms, proven in ${result.stats.proveMs} ms, cast in ${total} s</p></div></div>
          <span class="chip ok">position ${r.position + 1}</span></div>
        <div class="mt-lg"><div class="tiny muted">Ballot tracker</div><div class="tracker">${groupHex(r.tracker)}</div></div>
        <p class="small muted mt">Save the tracker to find your ballot on the public bulletin board. It reveals nothing about your choice. Oylama does not store your choice anywhere, not even on this device.</p>
        <div class="row wrap mt"><button class="btn btn-outline" data-copyt>${icon("copy", "sm")}Copy tracker</button><a class="btn btn-outline" href="#/e/${id}/board?tracker=${r.tracker}">${icon("search", "sm")}See it on the board</a><a class="btn btn-ghost" href="#/e/${id}">Back to the election</a><button class="btn btn-ghost" data-again>${icon("refresh", "sm")}Vote again</button></div>
        <div class="tiny faint mt">Posted ${fmtDate(r.posted)}</div></div>`);
      ctx.el.querySelector("[data-copyt]").onclick = () => copyText(r.tracker, "Tracker copied");
      ctx.el.querySelector("[data-again]").onclick = () => { s.step = 0; s.choice = null; s.receipt = null; draw(); };
      const head = ctx.el.querySelector(".card-h h3");
      if (head) head.innerHTML = String(html`${icon("check")}Ballot cast`);
      toast("Ballot accepted by the bulletin board", "ok");
    } catch (err) {
      clearInterval(clock);
      mark(current, "failed", err.message);
      const rec = ctx.el.querySelector("#receipt");
      if (rec) {
        rec.innerHTML = String(html`<div class="notice bad mt">${icon("alert")}<div><b>The ballot was not cast.</b> ${err.message}</div></div><div class="row mt"><button class="btn btn-outline" data-retry>${icon("refresh", "sm")}Try again</button></div>`);
        rec.querySelector("[data-retry]").onclick = () => { s.step = 2; draw(); };
      }
    }
  };

  draw();
}

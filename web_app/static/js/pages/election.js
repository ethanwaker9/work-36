import { get, post } from "../api.js";
import { vault } from "../store.js";
import { ROLE_INFO, bind, busy, capacityMeter, duration, fmtDate, html, icon, plural, statusChip, timeline, toast } from "../ui.js";
import { candidateList, voterState } from "./common.js";
import { activityTab, auditTab, boardTab, manageTab, resultsTab, rosterTab, trusteesTab, votersTab } from "./election-tabs.js";

function headerActions(e, u) {
  const out = [];
  if (u.role === "voter") {
    const st = voterState(e, u);
    if (e.status === "voting" && st.key === "ready") out.push(html`<a class="btn btn-primary" href="#/e/${e.id}/vote">${icon("ballot")}Vote now</a>`);
    if (["registration", "voting"].includes(e.status)) out.push(html`<a class="btn btn-outline" href="#/e/${e.id}/credential">${icon("key")}My credential</a>`);
  }
  if (u.role === "authority") out.push(html`<a class="btn btn-primary" href="#/e/${e.id}/manage">${icon("settings")}Control center</a>`);
  if (u.role === "registrar" && ["registration", "voting"].includes(e.status)) out.push(html`<a class="btn btn-primary" href="#/e/${e.id}/voters">${icon("clipboard")}Voter desk</a>`);
  if (u.role === "trustee" && ["ceremony", "closed"].includes(e.status)) out.push(html`<a class="btn btn-primary" href="#/e/${e.id}/trustees">${icon("key")}${e.status === "ceremony" ? "Key ceremony" : "Approve tally"}</a>`);
  if (u.role === "auditor") out.push(html`<a class="btn btn-primary" href="#/e/${e.id}/audit">${icon("scan")}Audit</a>`);
  if (e.status === "tallying") out.push(html`<a class="btn btn-outline" href="#/e/${e.id}/tally">${icon("activity")}Watch the tally</a>`);
  if (e.status === "published") out.push(html`<a class="btn btn-outline" href="#/e/${e.id}/results">${icon("chart")}Results</a>`);
  return out;
}

function voterPanel(e, u) {
  const st = voterState(e, u);
  const receipts = vault.receipts(u.email).filter((r) => r.election === e.id);
  const regOpen = ["registration", "voting"].includes(e.status);
  const step = (n, title, body, done, active) => html`<div class="ck ${done ? "done" : active ? "active" : "pending"}"><span class="ic">${done ? icon("check", "sm") : n}</span><div class="grow"><b>${title}</b><span>${body}</span></div></div>`;
  return html`<div class="card"><div class="card-h"><h3>${icon("ballot")}Your participation</h3>${regOpen ? html`<span class="chip ${st.cls}">${st.label}</span>` : ""}</div>
    <div class="card-b stack">
      <div class="checklist">
        ${step(1, "Credential", st.key === "ready" ? "A credential is stored on this device" : regOpen ? "Ask the registrar for a sealed credential" : "Registration is not open", st.key === "ready", regOpen && st.key !== "ready")}
        ${step(2, "Encrypted ballot", e.status === "voting" ? "Choose, encrypt in your browser, post anonymously" : e.status === "registration" ? "Voting has not opened yet" : "Voting is closed", receipts.length > 0, e.status === "voting" && st.key === "ready" && !receipts.length)}
        ${step(3, "Verify", receipts.length ? `${receipts.length} tracker${receipts.length > 1 ? "s" : ""} saved; find them on the board` : "Your tracker lets you find your ballot on the board", receipts.length > 0 && e.status === "published", false)}
      </div>
      ${e.status === "voting" && st.key === "ready" ? html`<a class="btn btn-primary btn-block" href="#/e/${e.id}/vote">${icon("ballot")}${receipts.length ? "Vote again" : "Enter the voting booth"}</a>` : ""}
      ${regOpen && st.key !== "ready" ? html`<a class="btn btn-primary btn-block" href="#/e/${e.id}/credential">${icon("key")}${st.key === "none" ? "Get a credential" : "Open my credential"}</a>` : ""}
      ${regOpen && st.key === "ready" ? html`<a class="btn btn-outline btn-block" href="#/e/${e.id}/credential">${icon("mask")}Credential and fake credentials</a>` : ""}
      ${e.status === "tallying" ? html`<a class="btn btn-outline btn-block" href="#/e/${e.id}/tally">${icon("activity")}Watch the encrypted tally</a>` : ""}
      ${e.status === "published" ? html`<a class="btn btn-primary btn-block" href="#/e/${e.id}/results">${icon("chart")}See the verified result</a>` : ""}
      ${receipts.length ? html`<a class="btn btn-ghost btn-block" href="#/e/${e.id}/board">${icon("search")}Find my ballot on the board</a>` : ""}
    </div></div>`;
}

function authorityPanel(e) {
  const next = {
    ceremony: [`${e.trustees_joined} of ${e.n_trustees} trustees joined`, html`<button class="btn btn-primary btn-block" data-act="autoCeremony">${icon("users")}Fill seats with demo trustees</button>`],
    registration: ["Key ready. Voters can get credentials.", html`<button class="btn btn-primary btn-block" data-act="openVoting">${icon("play")}Open voting</button>`],
    voting: [`${plural(e.ballots, "ballot", "ballots")} on the board`, html`<button class="btn btn-primary btn-block" data-act="closeVoting">${icon("stop")}Close voting</button>`],
    closed: [`${e.approvals} of ${e.threshold} trustee approvals`, html`<button class="btn btn-primary btn-block" data-act="autoApprove">${icon("unlock")}Approve with demo trustees</button>`],
    tallying: [e.tally && e.tally.total ? `${Math.round((100 * e.tally.done) / e.tally.total)}% done, about ${duration(e.tally.eta)} left` : "Starting", html`<a class="btn btn-primary btn-block" href="#/e/${e.id}/tally">${icon("activity")}Watch the tally</a>`],
    published: ["The verified result is public", html`<a class="btn btn-primary btn-block" href="#/e/${e.id}/results">${icon("chart")}View results</a>`]
  }[e.status];
  return html`<div class="card"><div class="card-h"><h3>${icon("building")}Authority</h3>${statusChip(e.status)}</div><div class="card-b stack">
    <p class="small muted">${next[0]}</p>${next[1]}<a class="btn btn-outline btn-block" href="#/e/${e.id}/manage">${icon("settings")}Open the control center</a></div></div>`;
}

function registrarPanel(e) {
  return html`<div class="card"><div class="card-h"><h3>${icon("clipboard")}Registrar desk</h3></div><div class="card-b stack">
    <div class="grid g2"><div><div class="tiny muted">Waiting</div><b style="font-size:22px">${e.requested}</b></div><div><div class="tiny muted">Roster</div><b style="font-size:22px">${e.roster}</b></div></div>
    ${["registration", "voting"].includes(e.status) ? html`<button class="btn btn-primary btn-block" data-act="issueAll" ${e.requested ? "" : "disabled"}>${icon("send")}Issue all pending</button>` : html`<p class="small muted">Registration is ${e.status === "ceremony" ? "not open yet" : "closed"}.</p>`}
    <a class="btn btn-outline btn-block" href="#/e/${e.id}/voters">${icon("users")}Open the voter desk</a></div></div>`;
}

function trusteePanel(e, u) {
  const mine = e.trustees.filter((t) => t.email === u.email);
  return html`<div class="card"><div class="card-h"><h3>${icon("key")}Trustee</h3><span class="chip outline">${e.threshold} of ${e.n_trustees}</span></div><div class="card-b stack">
    ${mine.length ? html`<p class="small">You hold seat ${mine.map((t) => t.seat).join(", ")}.${mine[0].fingerprint ? html` Share fingerprint <span class="hash">${mine[0].fingerprint.slice(0, 16)}</span>` : ""}</p>` : html`<p class="small muted">You hold no seat in this election.</p>`}
    <a class="btn btn-primary btn-block" href="#/e/${e.id}/trustees">${icon("key")}${e.status === "ceremony" ? "Go to the key ceremony" : e.status === "closed" ? "Release my share" : "Trustee seats"}</a></div></div>`;
}

function auditorPanel(e) {
  return html`<div class="card"><div class="card-h"><h3>${icon("scan")}Auditor</h3></div><div class="card-b stack">
    <p class="small muted">Recompute the roster digests and signature, re-verify every ballot proof and inspect the tally transcript.</p>
    <a class="btn btn-primary btn-block" href="#/e/${e.id}/audit">${icon("shieldcheck")}Run the audit</a>
    <a class="btn btn-outline btn-block" href="#/e/${e.id}/board">${icon("box")}Bulletin board</a></div></div>`;
}

function overviewTab(e, u, sys) {
  const p = e.params;
  const panel = { voter: voterPanel, authority: authorityPanel, registrar: registrarPanel, trustee: trusteePanel, auditor: auditorPanel }[u.role](e, u);
  return html`<div class="split">
    <div class="stack lg">
      <div class="card"><div class="card-h"><h3>${icon("users")}Candidates and options</h3><span class="small muted">${e.candidates.length} on the ballot</span></div><div class="card-b">${candidateList(e)}</div></div>
      <div class="card"><div class="card-h"><h3>${icon("info")}Election facts</h3></div><div class="card-b">
        <dl class="kv">
          <dt>Organization</dt><dd>${e.organization || "Not set"}</dd>
          <dt>Voting window</dt><dd>${e.opens_at ? fmtDate(e.opens_at) : "Opened by the authority"} → ${e.closes_at ? fmtDate(e.closes_at) : "closed by the authority"}</dd>
          <dt>Eligibility</dt><dd>${e.eligibility === "list" ? "Only voters on the registrar's list" : "Any signed-in voter can request a credential"}</dd>
          <dt>Credential issuance</dt><dd>${e.auto_issue ? "Automatic by the registrar service" : "Manual approval by the registrar"}</dd>
          <dt>Revoting</dt><dd>Allowed. The last ballot with a valid credential counts.</dd>
          <dt>Trustees</dt><dd>${e.threshold} of ${e.n_trustees} must cooperate to decrypt</dd>
          <dt>Board capacity</dt><dd>${capacityMeter(e)}<div class="tiny muted mt">Roster entries and ballots share one packed ciphertext of ${e.capacity} entries.</div></dd>
        </dl></div></div>
      <div class="card"><div class="card-h"><h3>${icon("shield")}Cryptography</h3><span class="chip info">post-quantum</span></div><div class="card-b">
        <dl class="kv">
          <dt>Encryption</dt><dd>Packed BGV over R<sub>q</sub> = Z<sub>q</sub>[X]/(X<sup>${p.ring_degree}</sup>+1)</dd>
          <dt>Ciphertext modulus</dt><dd>${p.primes.length} NTT primes, log q ≈ ${p.log_q} bits</dd>
          <dt>Plaintext slots</dt><dd>p = ${p.plaintext_modulus}, ${p.slots} slots per ciphertext</dd>
          <dt>Credentials</dt><dd>${p.credential_bits}-bit uniform strings, fake ones are identical in form</dd>
          <dt>Ballot</dt><dd>2 ciphertexts and 2 proofs of knowledge, about ${p.ballot_kb} KB</dd>
          <dt>Sorting key</dt><dd>${p.credential_bits} credential bits and ${p.index_bits} position bits in ${p.bit_blocks} bit blocks</dd>
          <dt>Proof challenges</dt><dd>Weight κ = ${p.challenge_weight}, SHAKE256 Fiat–Shamir with aborts</dd>
          <dt>Mix-net</dt><dd>${p.mix_servers} verifiable lattice shuffles</dd>
          <dt>Registrar</dt><dd>ML-DSA-65 roster signature, ML-KEM-768 sealed delivery${sys ? html` <span class="chip outline">${sys.backend}</span>` : ""}</dd>
          <dt>Public key</dt><dd>${e.key ? html`<span class="hash">${e.key.fingerprint}</span>` : html`<span class="muted">after the key ceremony</span>`}</dd>
          <dt>Estimated tally</dt><dd>about ${duration(p.estimated_tally_seconds)} on this server</dd>
        </dl></div></div>
    </div>
    <div class="stack lg sticky">${panel}
      <div class="card"><div class="card-h"><h3>${icon("key")}Trustees</h3><a class="small" href="#/e/${e.id}/trustees">Details</a></div><div class="card-b">
        ${e.trustees.map((t) => html`<div class="seat"><span class="avatar" style="background:${t.status === "joined" ? "var(--primary)" : "var(--surface-3)"};color:${t.status === "joined" ? "#fff" : "var(--faint)"}">${t.seat}</span><div class="grow"><b class="small">${t.name || "Open seat"}</b><div class="tiny muted">${t.status === "joined" ? (t.approved ? "Released share for the tally" : "Holds a key share") : "Waiting for a trustee"}</div></div>${t.approved ? icon("unlock", "sm") : t.status === "joined" ? icon("lock", "sm") : ""}</div>`)}
      </div></div>
    </div></div>`;
}

export default async function election(ctx) {
  const id = ctx.params.id;
  const tab = ctx.params.tab || "overview";
  const u = ctx.user;
  const e = await get(`/api/elections/${id}`);
  const tabs = [
    ["overview", "Overview", "info"],
    ["board", "Bulletin board", "box", e.ballots],
    ["roster", "Roster", "users", e.roster],
    ["results", "Results", "chart"],
    ["trustees", "Trustees", "key"],
    u.role === "authority" ? ["manage", "Control center", "settings"] : null,
    ["registrar", "authority"].includes(u.role) ? ["voters", "Voters", "clipboard", e.requested || null] : null,
    ["audit", "Audit", "scan"],
    ["activity", "Activity", "activity"]
  ].filter(Boolean);
  ctx.el.innerHTML = String(html`
    <div class="crumbs"><a href="#/elections">Elections</a>${icon("chevron", "sm")}<span>${e.title}</span></div>
    <div class="page-head">
      <div class="grow"><div class="eyebrow">${icon("building", "sm")} ${e.organization || "Oylama"}</div>
        <div class="row wrap"><h1>${e.title}</h1>${statusChip(e.status)}</div>
        ${e.description ? html`<p class="sub">${e.description}</p>` : ""}</div>
      <div class="row wrap">${headerActions(e, u)}</div>
    </div>
    ${e.status === "tallying" && e.tally ? html`<a class="card pad row mb" href="#/e/${e.id}/tally" style="color:inherit;text-decoration:none"><span class="icon-tile violet">${icon("activity")}</span><div class="grow"><b>Encrypted tally in progress</b><div class="small muted">${e.tally.label || ""}</div><div class="meter mt"><span class="bar-ballots" style="width:${e.tally.total ? (100 * e.tally.done) / e.tally.total : 0}%"></span></div></div><span class="small muted nowrap">${e.tally.total ? `${e.tally.done}/${e.tally.total} · ${duration(e.tally.eta)} left` : ""}</span>${icon("chevron")}</a>` : ""}
    <div class="card pad mb">${timeline(e)}</div>
    <nav class="tabs">${tabs.map(([k, l, ic, n]) => html`<a href="#/e/${e.id}${k === "overview" ? "" : "/" + k}" class="${k === tab ? "on" : ""}">${icon(ic, "sm")}${l}${n ? html`<span class="count">${n}</span>` : ""}</a>`)}</nav>
    <div id="tab"></div>`);
  const box = ctx.el.querySelector("#tab");
  const refresh = () => ctx.rerender();
  const actions = {
    autoCeremony: (b) => busy(b, async () => { await post(`/api/elections/${id}/ceremony/auto`); toast("Key ceremony complete. The threshold key is ready.", "ok"); refresh(); }),
    openVoting: (b) => busy(b, async () => { await post(`/api/elections/${id}/voting/open`); toast("Voting is open", "ok"); refresh(); }),
    closeVoting: (b) => busy(b, async () => { await post(`/api/elections/${id}/voting/close`); toast("Voting closed", "ok"); refresh(); }),
    autoApprove: (b) => busy(b, async () => { await post(`/api/elections/${id}/tally/auto`); toast("Trustees approved. The tally is running.", "ok"); ctx.go(`e/${id}/tally`); }),
    issueAll: (b) => busy(b, async () => { const r = await post(`/api/elections/${id}/voters/issue`, { all: true }); toast(`${r.issued} credentials issued`, "ok"); refresh(); })
  };
  const tabCtx = { ...ctx, e, refresh, actions };
  if (tab === "overview") box.innerHTML = String(overviewTab(e, u, ctx.system));
  else if (tab === "board") await boardTab(box, tabCtx);
  else if (tab === "roster") await rosterTab(box, tabCtx);
  else if (tab === "results") await resultsTab(box, tabCtx);
  else if (tab === "trustees") await trusteesTab(box, tabCtx);
  else if (tab === "manage" && u.role === "authority") await manageTab(box, tabCtx);
  else if (tab === "voters" && ["registrar", "authority"].includes(u.role)) await votersTab(box, tabCtx);
  else if (tab === "audit") await auditTab(box, tabCtx);
  else if (tab === "activity") await activityTab(box, tabCtx);
  else box.innerHTML = String(html`<div class="notice warn">${icon("alert")}<div>This section is not available for the ${ROLE_INFO[u.role].label.toLowerCase()} role.</div></div>`);
  bind(ctx.el, actions);
  if (["tallying", "ceremony", "closed", "voting", "registration"].includes(e.status)) {
    const status = e.status, stamp = `${e.ballots}|${e.roster}|${e.trustees_joined}|${e.approvals}|${e.requested}`;
    const timer = setInterval(async () => {
      try {
        const cur = await get(`/api/elections/${id}`);
        const now = `${cur.ballots}|${cur.roster}|${cur.trustees_joined}|${cur.approvals}|${cur.requested}`;
        const typing = document.activeElement && ["INPUT", "TEXTAREA"].includes(document.activeElement.tagName);
        if (cur.status !== status || (now !== stamp && !typing && !document.querySelector(".modal-back"))) refresh();
      } catch {}
    }, status === "tallying" ? 5000 : 7000);
    ctx.onCleanup(() => clearInterval(timer));
  }
}

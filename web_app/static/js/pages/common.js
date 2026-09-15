import { vault } from "../store.js";
import { STATUS, avatar, capacityMeter, duration, fmtDate, html, icon, plural, statusChip } from "../ui.js";

export function voterState(e, user) {
  const v = e.me && e.me.voter;
  const stored = user ? vault.credential(user.email, e.id) : null;
  if (stored) return { key: "ready", label: "Credential on this device", cls: "ok" };
  if (!v) return { key: "none", label: e.eligibility === "list" ? "Eligibility list" : "Not registered", cls: "outline" };
  if (v.status === "requested") return { key: "requested", label: "Waiting for registrar", cls: "warn" };
  if (v.status === "eligible") return { key: "eligible", label: "Eligible, not issued", cls: "warn" };
  if (v.status === "issued") return { key: "issued", label: "Sealed credential waiting", cls: "info" };
  if (v.status === "opened") return { key: "opened", label: "Credential opened", cls: "info" };
  return { key: v.status, label: v.status, cls: "outline" };
}

export function primaryAction(e, user) {
  const r = user.role;
  if (r === "voter") {
    const st = voterState(e, user);
    if (e.status === "voting" && st.key === "ready") return html`<a class="btn btn-primary btn-sm" href="#/e/${e.id}/vote">${icon("ballot", "sm")}Vote</a>`;
    if (["registration", "voting"].includes(e.status)) return html`<a class="btn btn-outline btn-sm" href="#/e/${e.id}/credential">${icon("key", "sm")}Credential</a>`;
    if (e.status === "published") return html`<a class="btn btn-outline btn-sm" href="#/e/${e.id}/results">${icon("chart", "sm")}Results</a>`;
    if (e.status === "tallying") return html`<a class="btn btn-outline btn-sm" href="#/e/${e.id}/tally">${icon("activity", "sm")}Watch tally</a>`;
    return html`<a class="btn btn-ghost btn-sm" href="#/e/${e.id}">Details</a>`;
  }
  if (r === "authority") return html`<a class="btn btn-outline btn-sm" href="#/e/${e.id}/manage">${icon("settings", "sm")}Manage</a>`;
  if (r === "registrar") return html`<a class="btn btn-outline btn-sm" href="#/e/${e.id}/voters">${icon("clipboard", "sm")}Voters</a>`;
  if (r === "trustee") {
    if (e.status === "ceremony") return html`<a class="btn btn-primary btn-sm" href="#/e/${e.id}/trustees">${icon("key", "sm")}Key ceremony</a>`;
    if (e.status === "closed") return html`<a class="btn btn-primary btn-sm" href="#/e/${e.id}/trustees">${icon("unlock", "sm")}Approve tally</a>`;
    return html`<a class="btn btn-outline btn-sm" href="#/e/${e.id}/trustees">${icon("key", "sm")}Seats</a>`;
  }
  if (r === "auditor") return html`<a class="btn btn-outline btn-sm" href="#/e/${e.id}/audit">${icon("scan", "sm")}Audit</a>`;
  return html`<a class="btn btn-ghost btn-sm" href="#/e/${e.id}">Details</a>`;
}

export function electionCard(e, user) {
  const lead = e.candidates[0] ? e.candidates[0].color : "#4F46E5";
  const extra = [];
  if (user.role === "voter" && ["registration", "voting"].includes(e.status)) {
    const st = voterState(e, user);
    extra.push(html`<span class="chip ${st.cls}">${st.label}</span>`);
  }
  if (e.status === "tallying" && e.tally && e.tally.total) {
    extra.push(html`<span class="chip tallying">${Math.round((100 * (e.tally.done || 0)) / e.tally.total)}% · ${duration(e.tally.eta)} left</span>`);
  }
  if (e.status === "published" && e.results) {
    const max = Math.max(...e.results.counts);
    const win = e.candidates.filter((c, i) => e.results.counts[i] === max && max > 0);
    extra.push(html`<span class="chip published">${icon("chart", "sm")}${win.length === 1 ? `${win[0].name} leads` : plural(e.results.total_valid, "valid vote", "valid votes")}</span>`);
  }
  if (e.status === "ceremony") extra.push(html`<span class="chip warn">${e.trustees_joined}/${e.n_trustees} trustees</span>`);
  if (e.status === "closed") extra.push(html`<span class="chip warn">${e.approvals}/${e.threshold} approvals</span>`);
  return html`<div class="card hover el-card rise">
    <div class="cover" style="background:linear-gradient(90deg, ${lead}, ${e.candidates[1] ? e.candidates[1].color : lead})"></div>
    <div class="body">
      <div class="row between"><span class="org">${e.organization || "Oylama"}</span>${statusChip(e.status)}</div>
      <h3><a href="#/e/${e.id}" style="color:inherit">${e.title}</a></h3>
      <p class="desc">${e.description || STATUS[e.status][1]}</p>
      <div class="row wrap">${extra}</div>
      <div class="mt">${capacityMeter(e)}</div>
    </div>
    <div class="foot">
      <div class="row"><div class="cand-dots">${e.candidates.slice(0, 5).map((c) => avatar(c.name, c.color))}</div><span class="small muted">${e.candidates.length} options</span></div>
      ${primaryAction(e, user)}
    </div>
  </div>`;
}

export function statCard(k, v, d, ic, tone = "") {
  return html`<div class="card stat"><div class="row between"><div class="k">${k}</div><span class="icon-tile ${tone}">${icon(ic)}</span></div><div class="v">${v}</div><div class="d">${d}</div></div>`;
}

export function eventsFeed(events, withTitle = false) {
  if (!events.length) return html`<p class="muted small">No activity yet.</p>`;
  return html`<div class="feed">${events.map((ev) => html`<div class="item"><span class="dot" style="background:${ev.kind === "published" ? "var(--success)" : ev.kind === "ballot" ? "var(--primary)" : ev.kind === "decoy" ? "var(--violet)" : ev.kind === "key" ? "var(--warning)" : "var(--accent)"}"></span><div class="grow"><p>${withTitle && ev.title ? html`<b>${ev.title}</b> · ` : ""}${ev.message}</p><span>${fmtDate(ev.ts)}</span></div></div>`)}</div>`;
}

export function candidateList(e, counts) {
  return html`<div class="stack sm">${e.candidates.map((c, i) => html`<div class="cand"><span class="bar" style="background:${c.color}"></span>${avatar(c.name, c.color, "lg")}<div class="meta"><b>${c.name}</b><span>${c.party || `Option ${c.idx}`}</span>${c.bio ? html`<div class="bio">${c.bio}</div>` : ""}</div>${counts ? html`<b>${counts[i]}</b>` : ""}</div>`)}</div>`;
}

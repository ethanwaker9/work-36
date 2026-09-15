import { get, post } from "../api.js";
import { busy, duration, html, icon, plural, statusChip, toast } from "../ui.js";

function ring(done, total) {
  const r = 70, c = 2 * Math.PI * r;
  const f = total ? done / total : 0;
  return html`<div class="ring"><svg viewBox="0 0 168 168"><circle cx="84" cy="84" r="${r}" stroke="var(--surface-3)" stroke-width="13" fill="none"/>
    <circle cx="84" cy="84" r="${r}" stroke="url(#rg)" stroke-width="13" fill="none" stroke-linecap="round" stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - f)}" style="transition:stroke-dashoffset .6s"/>
    <defs><linearGradient id="rg" x1="0" x2="1"><stop offset="0" stop-color="#6366f1"/><stop offset="1" stop-color="#0ea5a4"/></linearGradient></defs></svg>
    <div class="val"><b>${Math.floor(100 * f)}%</b><span>${done} / ${total} openings</span></div></div>`;
}

export default async function tally(ctx) {
  const id = ctx.params.id;
  const u = ctx.user;
  let since = 0;
  const lines = [];
  let e = await get(`/api/elections/${id}`);

  if (!["tallying", "published", "closed"].includes(e.status)) {
    ctx.el.innerHTML = String(html`<div class="crumbs"><a href="#/e/${id}">${e.title}</a>${icon("chevron", "sm")}<span>Tally</span></div>
      <div class="card pad center"><h2>The tally has not started</h2><p class="muted mt">Voting must close and ${e.threshold} trustees must release their shares first.</p><p class="mt">${statusChip(e.status)}</p><a class="btn btn-outline mt-lg" href="#/e/${id}">Back to the election</a></div>`);
    return;
  }

  ctx.el.innerHTML = String(html`<div class="crumbs"><a href="#/elections">Elections</a>${icon("chevron", "sm")}<a href="#/e/${id}">${e.title}</a>${icon("chevron", "sm")}<span>Tally</span></div>
    <div class="page-head"><div><div class="eyebrow">${icon("activity", "sm")} Encrypted tally</div><h1>${e.title}</h1>
      <p class="sub">Every step below runs on ciphertexts. Trustees only ever open values hidden by fresh random masks, and every decryption share is verified as it is produced.</p></div><div id="st">${statusChip(e.status)}</div></div>
    <div id="top"></div>
    <div class="split mt-lg">
      <div class="stack lg">
        <div class="card"><div class="card-h"><h3>${icon("list")}Pipeline</h3><span class="small muted" id="elapsed"></span></div><div class="card-b checklist" id="steps"></div></div>
        <div class="card"><div class="card-h"><h3>${icon("server")}Tally log</h3><label class="check small"><input type="checkbox" id="follow" checked><span>Follow</span></label></div><div class="card-b" style="padding:0"><div class="console" id="log"></div></div></div>
      </div>
      <div class="stack lg sticky" id="side"></div>
    </div>`);

  const fmtT = (ts) => new Date(ts * 1000).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });

  const drawState = (data) => {
    e.status = data.status;
    const t = data.tally || {};
    const done = t.done || 0, total = t.total || 0;
    ctx.el.querySelector("#st").innerHTML = String(statusChip(data.status));
    const stats = t.stats || {};
    const elapsed = t.started ? ((t.finished || Date.now() / 1000) - t.started) : 0;
    ctx.el.querySelector("#elapsed").textContent = t.started ? `elapsed ${duration(elapsed)}` : "";
    let banner;
    if (data.status === "published") {
      const r = data.results;
      banner = html`<div class="card tint pad rise"><div class="row between wrap"><div class="row"><span class="icon-tile success">${icon("check", "lg")}</span><div><h2>Result published</h2><p class="muted small">${plural(r.total_valid, "valid vote", "valid votes")} from ${plural(r.board, "ballot", "ballots")} · ${r.openings} masked openings · every proof ${r.verified ? "verified" : "checked"} · ${duration(r.seconds)}</p></div></div>
        <a class="btn btn-primary btn-lg" href="#/e/${id}/results">${icon("chart")}See the result</a></div></div>`;
    } else if (t.state === "failed" || data.status === "closed") {
      banner = html`<div class="card pad"><div class="notice ${t.state === "failed" ? "bad" : "warn"}">${icon("alert")}<div><b>${t.state === "failed" ? "The tally stopped" : "Waiting for trustee approvals"}</b> ${t.error || `${e.approvals || 0} of ${e.threshold} trustees have released their shares.`}</div></div>
        ${["authority", "trustee"].includes(u.role) ? html`<div class="row mt"><button class="btn btn-primary" data-approve>${icon("unlock")}Approve with demo trustees</button><a class="btn btn-outline" href="#/e/${id}/trustees">Trustee seats</a></div>` : ""}</div>`;
    } else {
      banner = html`<div class="card pad"><div class="row wrap" style="gap:28px">${ring(done, total)}
        <div class="grow stack sm"><div class="eyebrow">${t.state === "queued" ? "Starting" : "Now"}</div><h2 style="font-size:19px">${t.label || "Preparing"}</h2>
          <div class="meter lg mt"><span class="bar-ballots" style="width:${total ? (100 * done) / total : 0}%;background:linear-gradient(90deg,#6366f1,#0ea5a4)"></span></div>
          <div class="row wrap small muted mt"><span>${icon("clock", "sm")} about ${duration(t.eta)} left</span><span>${icon("users", "sm")} quorum: trustees ${(t.quorum || []).map((j) => j + 1).join(" and ")}</span><span>${icon("box", "sm")} ${plural(t.board, "ballot", "ballots")}, ${plural(t.roster, "roster entry", "roster entries")}</span></div>
        </div></div></div>`;
    }
    ctx.el.querySelector("#top").innerHTML = String(banner);
    const ab = ctx.el.querySelector("[data-approve]");
    if (ab) ab.onclick = () => busy(ab, async () => { await post(`/api/elections/${id}/tally/auto`); toast("Tally started", "ok"); poll(); });

    const steps = t.steps || [];
    ctx.el.querySelector("#steps").innerHTML = String(html`${steps.map((s) => {
      const secs = s.started ? ((s.finished || (s.state === "active" ? Date.now() / 1000 : s.started)) - s.started) : null;
      const cls = s.state === "done" ? "done" : s.state === "active" ? (t.state === "failed" ? "failed" : "active") : "pending";
      return html`<div class="ck ${cls}"><span class="ic">${cls === "done" ? icon("check", "sm") : cls === "active" ? html`<span class="spin" style="width:13px;height:13px;border-width:2px;color:#fff"></span>` : cls === "failed" ? icon("x", "sm") : ""}</span>
        <div class="grow"><b>${s.label}</b><span>${s.detail || ""}</span>${s.total && s.state === "active" ? html`<div class="meter" style="margin-top:6px;height:5px"><span class="bar-ballots" style="width:${(100 * (s.done || 0)) / s.total}%"></span></div>` : ""}</div>
        <span class="t">${secs !== null && s.state !== "pending" ? duration(secs) : ""}</span></div>`;
    })}`);

    ctx.el.querySelector("#side").innerHTML = String(html`
      <div class="grid g2">
        <div class="card stat"><div class="k">Masked openings</div><div class="v">${stats.batches || 0}</div><div class="d">of ${total}</div></div>
        <div class="card stat"><div class="k">Packed multiplications</div><div class="v">${stats.mults || 0}</div><div class="d">${(stats.mults || 0) * 8192} binary gates</div></div>
        <div class="card stat"><div class="k">Shares verified</div><div class="v">${stats.shares_ok || 0}</div><div class="d">of ${stats.shares || 0} decryption shares</div></div>
        <div class="card stat"><div class="k">Mask proofs</div><div class="v">${stats.masks_ok || 0}</div><div class="d">of ${stats.masks || 0} verified</div></div>
      </div>
      <div class="card pad"><h3 class="row">${icon("layers")}Transcript</h3><p class="small muted mt">${(((stats.proof_bits || 0) + (stats.dec_bits || 0)) / 8 / 1048576).toFixed(1)} MB of proofs and decryption shares produced and checked so far.</p></div>
      <div class="card pad"><h3 class="row">${icon("info")}Reading the pipeline</h3><div class="stack sm mt small muted">
        <div><b>Sort.</b> A Batcher network of ${t.stages || ""} stages orders every entry by encrypted credential, then board position.</div>
        <div><b>Deduplicate.</b> An entry survives only if the next entry has the same credential and is the roster record, which keeps exactly the last ballot per real credential.</div>
        <div><b>Gate.</b> Votes of all other entries are multiplied by an encrypted zero.</div>
        <div><b>Mix and decrypt.</b> The surviving slots are shuffled twice and decrypted by the quorum.</div></div></div>`);
  };

  const drawLog = (newLines) => {
    const log = ctx.el.querySelector("#log");
    if (!log) return;
    for (const l of newLines) {
      lines.push(l);
      const div = document.createElement("div");
      const cls = l.level === "error" ? "error" : /verified|published|Verified/.test(l.message) ? "ok" : "";
      div.innerHTML = String(html`<span class="ts">${fmtT(l.ts)}</span><span class="${cls}">${l.message}</span>`);
      log.appendChild(div);
    }
    if (ctx.el.querySelector("#follow") && ctx.el.querySelector("#follow").checked) log.scrollTop = log.scrollHeight;
  };

  let timer = null;
  let stopped = false;
  const poll = async () => {
    if (stopped) return;
    try {
      const data = await get(`/api/elections/${id}/tally?since=${since}`);
      if (data.log.length) since = data.log[data.log.length - 1].id;
      drawState(data);
      drawLog(data.log);
      if (data.status === "published") { stopped = true; return; }
    } catch (err) {
      toast(err.message, "bad");
    }
    timer = setTimeout(poll, e.status === "tallying" ? 1200 : 3000);
  };
  ctx.onCleanup(() => { stopped = true; clearTimeout(timer); });
  poll();
}

import { del, get, post } from "../api.js";
import { vault } from "../store.js";
import { ago, avatar, bind, busy, capacityMeter, confirmDialog, copyText, duration, empty, fmtDate, groupHex, html, icon, modal, pct, plural, statusChip, toast } from "../ui.js";
import { eventsFeed } from "./common.js";

export async function boardTab(box, ctx) {
  const { e, user } = ctx;
  const board = await get(`/api/elections/${e.id}/board`);
  const mine = new Set(vault.receipts(user.email).filter((r) => r.election === e.id).map((r) => r.tracker));
  box.innerHTML = String(html`
    <div class="split">
      <div class="card"><div class="card-h"><h3>${icon("box")}Anonymous ballots</h3>
        <div class="input-icon" style="width:240px">${icon("search", "sm")}<input type="search" id="bq" placeholder="Filter by tracker"></div></div>
        ${board.ballots.length ? html`<div class="table-wrap"><table class="t"><thead><tr><th>#</th><th>Tracker</th><th>Posted</th><th>Size</th><th>Proofs</th><th></th></tr></thead><tbody id="rows">
          ${board.ballots.map((b) => html`<tr class="clickable ${mine.has(b.tracker) ? "hl" : ""}" data-act="ballot" data-pos="${b.position}" data-tracker="${b.tracker}">
            <td><b>${b.position + 1}</b></td>
            <td><span class="mono small">${groupHex(b.tracker.slice(0, 24))}…</span>${mine.has(b.tracker) ? html` <span class="chip info">yours</span>` : ""}</td>
            <td class="small nowrap">${ago(b.posted)}</td><td class="small nowrap">${b.size_kb} KB</td>
            <td><span class="chip ok">${icon("check", "sm")}${b.verify_ms} ms</span></td>
            <td>${icon("chevron", "sm")}</td></tr>`)}
        </tbody></table></div>` : empty("The board is empty", e.status === "voting" ? "Ballots appear here the moment they are posted." : "No ballots were posted.", "box")}
      </div>
      <div class="stack lg sticky">
        <div class="card pad"><h3 class="row">${icon("layers")}Board capacity</h3><div class="mt">${capacityMeter({ capacity: board.capacity, roster: board.roster, ballots: board.ballots.length })}</div>
          <p class="small muted mt">The tally loads every roster entry and every ballot into one packed ciphertext of ${board.capacity} entries, sorts them under encryption and keeps only the last ballot of each registered credential.</p></div>
        <div class="card pad"><h3 class="row">${icon("eyeoff")}What the board knows</h3>
          <div class="stack sm mt small">
            <div class="row top">${icon("check", "sm")}<span>Each ballot carries two ciphertexts and two proofs that the sender knows what they encrypt.</span></div>
            <div class="row top">${icon("check", "sm")}<span>Ballots arrive over an anonymous channel, so none is linked to a voter.</span></div>
            <div class="row top">${icon("check", "sm")}<span>Ballots with fake credentials look exactly like real ones and are accepted.</span></div>
            <div class="row top">${icon("x", "sm")}<span>Nobody can tell which ballots will count until the encrypted cleansing runs.</span></div>
          </div></div>
      </div>
    </div>`);
  const q = box.querySelector("#bq");
  if (q) q.addEventListener("input", () => {
    const v = q.value.replace(/[^0-9a-f]/gi, "").toLowerCase();
    box.querySelectorAll("#rows tr").forEach((tr) => { tr.style.display = !v || tr.dataset.tracker.includes(v) ? "" : "none"; });
  });
  const want = ctx.query.get("tracker");
  if (want && q) { q.value = want; q.dispatchEvent(new Event("input")); }
  bind(box, { ballot: (row) => ballotModal(e, Number(row.dataset.pos)) });
}

export async function ballotModal(e, pos) {
  const body = document.createElement("div");
  body.innerHTML = `<div class="empty"><span class="spin"></span></div>`;
  const m = modal({ title: `Ballot ${pos + 1}`, body, wide: true });
  const d = await get(`/api/elections/${e.id}/board/${pos}`).catch((err) => { toast(err.message, "bad"); m.close(); });
  if (!d) return;
  body.innerHTML = String(html`
    <div class="stack lg">
      <div class="row between wrap"><div><div class="tiny muted">Tracker</div><div class="tracker" style="font-size:16px">${groupHex(d.tracker)}</div></div>
        <button class="btn btn-sm btn-outline" data-copy="${d.tracker}">${icon("copy", "sm")}Copy</button></div>
      <div class="grid g3">
        <div class="card stat"><div class="k">Posted</div><div class="v" style="font-size:16px">${fmtDate(d.posted)}</div></div>
        <div class="card stat"><div class="k">Size</div><div class="v" style="font-size:16px">${d.size_kb} KB</div></div>
        <div class="card stat"><div class="k">Board verification</div><div class="v" style="font-size:16px">${d.verify_ms} ms</div></div>
      </div>
      <div><h4 class="mb">Ciphertexts in R<sub>q</sub><sup>2</sup>, ${d.ring.L} residues × ${d.ring.N} coefficients</h4>
        <div class="stack sm">${d.ciphertexts.map((c) => html`<div class="card pad"><b class="small">${c.name}</b>
          <dl class="kv small mt"><dt>u digest</dt><dd><span class="hash">${c.u_digest}</span></dd><dt>w digest</dt><dd><span class="hash">${c.w_digest}</span></dd>
          <dt>first coefficients of u</dt><dd class="coeffs">${c.preview.join(", ")}, …</dd></dl></div>`)}</div></div>
      <div><h4 class="mb">Proofs of knowledge of the openings</h4>
        <div class="grid g2">${d.proofs.map((p) => html`<div class="card pad"><div class="row between"><b class="small">${p.statement} opening</b><span class="chip outline">${p.size_kb} KB</span></div>
          <dl class="kv small mt" style="grid-template-columns:110px 1fr"><dt>challenge seed</dt><dd><span class="hash">${p.challenge_seed.slice(0, 32)}…</span></dd><dt>σ</dt><dd>${p.sigma.toLocaleString()}</dd>
          <dt>‖z‖∞</dt><dd>${p.max_abs_z.toLocaleString()} of ${p.bound.toLocaleString()}</dd></dl>
          <div class="gauge mt"><span style="width:${Math.min(100, (100 * p.max_abs_z) / p.bound)}%"></span></div></div>`)}</div></div>
      <div class="row between wrap"><span class="small muted">Uniqueness key <span class="hash">${d.uniqueness_key}</span></span>
        <button class="btn btn-primary" data-verify>${icon("shieldcheck")}Re-verify both proofs now</button></div>
      <div id="vres"></div>
    </div>`);
  body.querySelector("[data-copy]").onclick = (ev) => copyText(ev.currentTarget.dataset.copy, "Tracker copied");
  const vb = body.querySelector("[data-verify]");
  vb.onclick = () => busy(vb, async () => {
    const r = await post(`/api/elections/${e.id}/board/${pos}/verify`);
    body.querySelector("#vres").innerHTML = String(html`<div class="notice ${r.proofs_ok && r.tracker_ok ? "ok" : "bad"}">${icon(r.proofs_ok && r.tracker_ok ? "shieldcheck" : "alert")}<div><b>${r.proofs_ok ? "Both proofs verify" : "A proof failed"}</b> · tracker ${r.tracker_ok ? "matches the ciphertexts" : "does not match"} · recomputed in ${r.ms} ms</div></div>`);
  });
}

export async function rosterTab(box, ctx) {
  const { e } = ctx;
  const r = await get(`/api/elections/${e.id}/roster`);
  box.innerHTML = String(html`<div class="split">
    <div class="card"><div class="card-h"><h3>${icon("users")}Encrypted roster</h3><span class="small muted">${r.entries.length} entries</span></div>
      ${r.entries.length ? html`<div class="table-wrap"><table class="t"><thead><tr><th>#</th><th>Voter</th><th>Encrypted credential digest</th><th>Issued</th></tr></thead><tbody>
        ${r.entries.map((x) => html`<tr><td><b>${x.position + 1}</b></td><td>${x.name ? html`<b class="small">${x.name}</b><div class="tiny muted">${x.voter}</div>` : html`<span class="small">${x.voter}</span>`}</td><td><span class="hash">${x.digest.slice(0, 32)}…</span></td><td class="small nowrap">${ago(x.issued)}</td></tr>`)}
      </tbody></table></div>` : empty("No one is registered yet", "Roster entries appear when the registrar issues credentials.", "users")}
    </div>
    <div class="stack lg sticky">
      <div class="card pad"><h3 class="row">${icon("shieldcheck")}Registrar signature</h3>
        ${r.root ? html`<dl class="kv small mt" style="grid-template-columns:110px 1fr"><dt>Algorithm</dt><dd>${r.alg}</dd><dt>Registrar key</dt><dd><span class="hash">${r.registrar_key}</span></dd><dt>Roster root</dt><dd><span class="hash">${r.root.slice(0, 40)}…</span></dd><dt>Signature</dt><dd><span class="hash">${r.signature_preview.slice(0, 40)}…</span> <span class="tiny muted">${r.signature_bytes} bytes</span></dd><dt>Covers</dt><dd>${r.signed_entries} entries</dd></dl>`
        : html`<p class="small muted mt">The registrar signs the roster after issuing the first credential.</p>`}</div>
      <div class="card pad"><h3 class="row">${icon("info")}Why the roster is public</h3><p class="small muted mt">Each entry is an encryption of a uniformly random 128-bit credential. Anyone can see who registered, but not the credential. During the tally every ballot is matched against these ciphertexts without decrypting either.</p></div>
    </div></div>`);
}

function resultsView(e, r) {
  const total = r.total_valid;
  const max = Math.max(...r.counts);
  const winners = e.candidates.filter((c, i) => r.counts[i] === max && max > 0);
  const byIdx = Object.fromEntries(e.candidates.map((c) => [c.idx, c]));
  return html`<div class="split wide">
    <div class="stack lg">
      <div class="card"><div class="card-h"><h3>${icon("chart")}Verified result</h3><span class="small muted">published ${fmtDate(r.published)}</span></div><div class="card-b">
        ${winners.length === 1 ? html`<div class="row mb"><span class="icon-tile success">${icon("sparkles")}</span><div><div class="tiny muted">Most votes</div><h2>${winners[0].name}</h2></div></div>` : winners.length > 1 ? html`<div class="notice mb">${icon("info")}<div>Tie between ${winners.map((w) => w.name).join(" and ")}</div></div>` : ""}
        ${e.candidates.map((c, i) => html`<div class="result-row">${avatar(c.name, c.color, "lg")}<div><b>${c.name}</b>${winners.length === 1 && winners[0] === c ? html`<span class="winner">winner</span>` : ""}<div class="tiny muted">${c.party || ""}</div><div class="track"><span style="width:${pct(r.counts[i], total || 1)}%;background:${c.color}"></span></div></div><div class="num"><b>${r.counts[i]}</b><span>${pct(r.counts[i], total)}%</span></div></div>`)}
      </div></div>
      <div class="card"><div class="card-h"><h3>${icon("grid")}Decrypted vote slots after cleansing and mixing</h3></div><div class="card-b">
        <p class="small muted mb">The ${r.capacity} extracted slots were shuffled by two mix servers before the trustees decrypted them. An empty slot is a roster entry, an unused position, a superseded revote or a ballot with a fake credential; which one is never revealed.</p>
        <div class="slots">${r.votes.map((v) => byIdx[v] ? html`<div class="slot vote" style="background:${byIdx[v].color}" title="${byIdx[v].name}">${byIdx[v].name.slice(0, 3)}<small>vote</small></div>` : html`<div class="slot">∅<small>empty</small></div>`)}</div>
      </div></div>
    </div>
    <div class="stack lg">
      <div class="grid g2">
        <div class="card stat"><div class="k">Valid votes</div><div class="v">${total}</div><div class="d">counted</div></div>
        <div class="card stat"><div class="k">Ballots on board</div><div class="v">${r.board}</div><div class="d">${r.discarded} removed under encryption</div></div>
        <div class="card stat"><div class="k">Roster</div><div class="v">${r.roster}</div><div class="d">registered voters</div></div>
        <div class="card stat"><div class="k">Tally time</div><div class="v" style="font-size:22px">${duration(r.seconds)}</div><div class="d">${r.openings} masked openings</div></div>
      </div>
      <div class="card"><div class="card-h"><h3>${icon("shieldcheck")}Transcript checks</h3><span class="chip ${r.verified ? "ok" : "bad"}">${r.verified ? "all verified" : "failures"}</span></div><div class="card-b checklist">
        <div class="ck done"><span class="ic">${icon("check", "sm")}</span><div class="grow"><b>Decryption shares</b><span>${r.shares_ok} of ${r.shares} shares from trustees ${r.quorum.join(" and ")} verified</span></div></div>
        <div class="ck ${r.masks_ok === r.masks ? "done" : "failed"}"><span class="ic">${icon(r.masks_ok === r.masks ? "check" : "x", "sm")}</span><div class="grow"><b>Mask proofs</b><span>${r.masks_ok} of ${r.masks} mask encryptions proven consistent</span></div></div>
        ${r.mix.map((m) => html`<div class="ck ${m.ok ? "done" : "failed"}"><span class="ic">${icon(m.ok ? "check" : "x", "sm")}</span><div class="grow"><b>Mix server ${m.server}</b><span>shuffle proof of ${(m.bits / 8 / 1048576).toFixed(1)} MB ${m.ok ? "verified" : "invalid"}</span></div></div>`)}
        ${r.decryption.map((d) => html`<div class="ck ${d.ok ? "done" : "failed"}"><span class="ic">${icon(d.ok ? "check" : "x", "sm")}</span><div class="grow"><b>Trustee ${d.trustee} final decryption</b><span>${(d.bits / 8 / 1048576).toFixed(1)} MB of shares ${d.ok ? "verified" : "invalid"}</span></div></div>`)}
      </div><div class="card-f small muted">Transcript ${r.transcript_mb} MB · digest <span class="hash">${r.digest.slice(0, 24)}</span></div></div>
      <a class="btn btn-primary btn-block" href="#/e/${e.id}/audit">${icon("scan")}Run an independent audit</a>
    </div></div>`;
}

export async function resultsTab(box, ctx) {
  const { e } = ctx;
  if (e.status === "published" && e.results_full) {
    box.innerHTML = String(resultsView(e, e.results_full));
    return;
  }
  const msg = {
    tallying: ["The tally is running", "Cleansing, mixing and decryption are in progress under encryption.", html`<a class="btn btn-primary mt" href="#/e/${e.id}/tally">${icon("activity")}Watch it live</a>`],
    closed: ["Waiting for the trustees", `${e.approvals} of ${e.threshold} trustees have released their shares.`, html`<a class="btn btn-primary mt" href="#/e/${e.id}/trustees">${icon("key")}Trustee approvals</a>`]
  }[e.status] || ["No result yet", "Results are published after voting closes and the trustees run the tally.", ""];
  box.innerHTML = String(html`<div class="card">${empty(msg[0], msg[1], "chart", msg[2])}</div>`);
}

export async function trusteesTab(box, ctx) {
  const { e, user } = ctx;
  const iHold = e.trustees.some((t) => t.email === user.email);
  box.innerHTML = String(html`<div class="split">
    <div class="card"><div class="card-h"><h3>${icon("key")}Trustee seats</h3><span class="chip outline">${e.threshold} of ${e.n_trustees} threshold</span></div><div class="card-b">
      ${e.trustees.map((t) => {
        let action = "";
        if (user.role === "trustee" && e.status === "ceremony" && t.status !== "joined" && !iHold && (!t.email || t.email === user.email)) action = html`<button class="btn btn-primary btn-sm" data-act="join" data-idx="${t.idx}">${icon("key", "sm")}Take seat ${t.seat}</button>`;
        if (user.role === "trustee" && e.status === "closed" && t.email === user.email && !t.approved) action = html`<button class="btn btn-primary btn-sm" data-act="approve" data-idx="${t.idx}">${icon("unlock", "sm")}Release my share</button>`;
        return html`<div class="seat">${t.status === "joined" ? avatar(t.name) : html`<span class="avatar" style="background:var(--surface-3);color:var(--faint)">${t.seat}</span>`}
          <div class="grow"><b>${t.name || `Seat ${t.seat} is open`}</b>${t.email === user.email ? html` <span class="chip info">you</span>` : ""}
            <div class="tiny muted">${t.email || (e.status === "ceremony" ? "Any trustee can take this seat" : "")}</div>
            ${t.fingerprint ? html`<div class="tiny muted">share fingerprint <span class="hash">${t.fingerprint.slice(0, 20)}</span></div>` : ""}</div>
          <div class="stack sm" style="align-items:flex-end">${t.status === "joined" ? html`<span class="chip ok">${icon("check", "sm")}joined ${ago(t.joined)}</span>` : html`<span class="chip warn">open</span>`}
            ${t.approved ? html`<span class="chip tallying">${icon("unlock", "sm")}share released</span>` : ""}${action}</div></div>`;
      })}
    </div>
    ${["authority", "trustee"].includes(user.role) && ["ceremony", "closed"].includes(e.status) ? html`<div class="card-f row between wrap"><span class="small muted">${e.status === "ceremony" ? "Testing alone? Let demo trustees take the remaining seats." : "Testing alone? Let demo trustees release their shares."}</span>
      <button class="btn btn-sm btn-outline" data-act="${e.status === "ceremony" ? "autoCeremony" : "autoApprove"}">${icon("users", "sm")}${e.status === "ceremony" ? "Fill with demo trustees" : "Approve with demo trustees"}</button></div>` : ""}
    </div>
    <div class="stack lg sticky">
      <div class="card pad"><h3 class="row">${icon("layers")}Replicated threshold sharing</h3>
        <p class="small muted mt">The secret key s is split into three random pieces, one for each set of trustees that is too small to decrypt. Every trustee receives the two pieces it may hold, so any two trustees together hold all three pieces and one trustee alone holds a uniformly random value.</p>
        ${e.key ? html`<dl class="kv small mt" style="grid-template-columns:110px 1fr"><dt>Public key</dt><dd><span class="hash">${e.key.fingerprint}</span></dd><dt>Generated</dt><dd>${fmtDate(e.key.created)} in ${e.key.seconds}s</dd><dt>Key size</dt><dd>${e.key.size_kb} KB</dd></dl>` : ""}</div>
      <div class="card pad"><h3 class="row">${icon("unlock")}What releasing a share does</h3>
        <p class="small muted mt">During the tally your share is used for hundreds of masked openings. Each opening decrypts only a value hidden by random masks from every trustee, and each decryption share is published with a lattice proof that auditors verify.</p></div>
    </div></div>`);
  bind(box, {
    join: (b) => busy(b, async () => { await post(`/api/elections/${e.id}/trustees/${b.dataset.idx}/join`); toast("You joined the key ceremony", "ok"); ctx.refresh(); }),
    approve: (b) => busy(b, async () => {
      const r = await post(`/api/elections/${e.id}/trustees/${b.dataset.idx}/approve`);
      toast(r.status === "tallying" ? "Threshold reached. The tally has started." : "Share released. Waiting for another trustee.", "ok");
      if (r.status === "tallying") ctx.go(`e/${e.id}/tally`); else ctx.refresh();
    })
  });
}

export async function manageTab(box, ctx) {
  const { e } = ctx;
  const order = ["ceremony", "registration", "voting", "closed", "tallying", "published"];
  const idx = order.indexOf(e.status);
  const info = [
    ["Key ceremony", `${e.trustees_joined}/${e.n_trustees} trustees`],
    ["Registration", plural(e.roster, "credential", "credentials")],
    ["Voting", plural(e.ballots, "ballot", "ballots")],
    ["Closed", `${e.approvals}/${e.threshold} approvals`],
    ["Tally", e.tally && e.tally.total ? `${Math.round((100 * e.tally.done) / e.tally.total)}%` : "encrypted"],
    ["Published", e.results ? plural(e.results.total_valid, "valid vote", "valid votes") : "verified result"]
  ];
  const next = {
    ceremony: ["Waiting for trustees", "Every trustee seat must be filled. Each trustee receives a share of the threshold key when the last one joins.", html`<button class="btn btn-primary" data-act="autoCeremony">${icon("users")}Fill the remaining seats with demo trustees</button> <a class="btn btn-outline" href="#/e/${e.id}/trustees">Seats</a>`],
    registration: ["Open voting", "The key is ready and voters can already get credentials. Opening voting lets the bulletin board accept anonymous ballots; registration stays open.", html`<button class="btn btn-primary" data-act="openVoting">${icon("play")}Open voting</button>`],
    voting: ["Voting is open", "Close voting to freeze the board and the roster. After that, trustees approve the tally.", html`<button class="btn btn-primary" data-act="closeVoting">${icon("stop")}Close voting</button>`],
    closed: ["Waiting for the tally approvals", `The tally starts when ${e.threshold} trustees release their shares.`, html`<button class="btn btn-primary" data-act="autoApprove">${icon("unlock")}Approve with demo trustees and start</button> <a class="btn btn-outline" href="#/e/${e.id}/trustees">Seats</a>`],
    tallying: ["The tally is running", e.tally ? e.tally.label || "" : "", html`<a class="btn btn-primary" href="#/e/${e.id}/tally">${icon("activity")}Watch the tally</a>`],
    published: ["Result published", "The election is complete. Share the results page and invite auditors.", html`<a class="btn btn-primary" href="#/e/${e.id}/results">${icon("chart")}Results</a> <a class="btn btn-outline" href="#/e/${e.id}/audit">${icon("scan")}Audit</a>`]
  }[e.status];
  box.innerHTML = String(html`
    <div class="pipeline mb">${info.map(([l, s], i) => html`<div class="pipe ${i < idx || e.status === "published" ? "done" : i === idx ? "now" : ""}"><span class="n">Step ${i + 1}</span><b>${l}</b><span>${s}</span></div>`)}</div>
    <div class="split">
      <div class="stack lg">
        <div class="card tint pad"><div class="row top"><span class="icon-tile">${icon("zap")}</span><div class="grow"><div class="eyebrow">Next step</div><h2>${next[0]}</h2><p class="muted mt">${next[1]}</p><div class="row wrap mt">${next[2]}</div></div></div></div>
        <div class="card"><div class="card-h"><h3>${icon("mask")}Decoy ballots</h3><span class="small muted">${e.decoys || 0} posted by you</span></div><div class="card-b">
          <p class="small muted">An authority may post ballots under freshly fabricated credentials. They are indistinguishable from real ballots on the board and are removed by the encrypted cleansing, which hides how many coerced or fake ballots there really were.</p>
          <div class="row wrap mt"><button class="btn btn-outline" data-act="decoy" data-n="1" ${e.status === "voting" ? "" : "disabled"}>${icon("plus", "sm")}Post 1 decoy</button><button class="btn btn-outline" data-act="decoy" data-n="2" ${e.status === "voting" ? "" : "disabled"}>${icon("plus", "sm")}Post 2 decoys</button>
          ${e.status !== "voting" ? html`<span class="small muted">available while voting is open</span>` : html`<span class="small muted">${e.capacity - e.used} free entries</span>`}</div></div></div>
        <div class="card"><div class="card-h"><h3>${icon("activity")}Recent activity</h3><a class="small" href="#/e/${e.id}/activity">All</a></div><div class="card-b" id="feed"><span class="spin"></span></div></div>
      </div>
      <div class="stack lg sticky">
        <div class="card pad"><h3>Board</h3><div class="mt">${capacityMeter(e)}</div></div>
        <div class="grid g2">
          <div class="card stat"><div class="k">Requests</div><div class="v">${e.requested}</div><div class="d">waiting</div></div>
          <div class="card stat"><div class="k">Approvals</div><div class="v">${e.approvals}/${e.threshold}</div><div class="d">trustees</div></div>
        </div>
        <div class="card pad"><h3 class="row">${icon("users")}Quick links</h3><div class="stack sm mt">
          <a class="btn btn-outline btn-block" href="#/e/${e.id}/voters">${icon("clipboard")}Voters and registration</a>
          <a class="btn btn-outline btn-block" href="#/e/${e.id}/board">${icon("box")}Bulletin board</a>
          <a class="btn btn-outline btn-block" href="#/e/${e.id}/trustees">${icon("key")}Trustee seats</a></div></div>
        <div class="card danger pad"><h3 class="row" style="color:var(--danger)">${icon("trash")}Danger zone</h3><p class="small muted mt">Deleting removes the keys, roster, board and results of this election.</p>
          <button class="btn btn-danger btn-block mt" data-act="delete" ${e.status === "tallying" ? "disabled" : ""}>${icon("trash")}Delete election</button></div>
      </div>
    </div>`);
  bind(box, {
    decoy: (b) => busy(b, async () => { const r = await post(`/api/elections/${e.id}/decoys`, { count: Number(b.dataset.n) }); toast(`${r.posted.length} decoy ballot${r.posted.length > 1 ? "s" : ""} posted`, "ok"); ctx.refresh(); }),
    delete: async (b) => {
      if (!(await confirmDialog("Delete this election?", `“${e.title}” and all of its cryptographic material will be removed.`, "Delete", "btn-danger"))) return;
      await busy(b, async () => { await del(`/api/elections/${e.id}`); toast("Election deleted", "ok"); ctx.go(""); });
    }
  });
  const ev = await get(`/api/elections/${e.id}/events`).catch(() => []);
  const feed = box.querySelector("#feed");
  if (feed) feed.innerHTML = String(eventsFeed(ev.slice(0, 8)));
}

export async function votersTab(box, ctx) {
  const { e } = ctx;
  const voters = await get(`/api/elections/${e.id}/voters`);
  const open = ["registration", "voting"].includes(e.status);
  const chip = (s) => ({ eligible: html`<span class="chip outline">eligible</span>`, requested: html`<span class="chip warn">requested</span>`, issued: html`<span class="chip info">sealed</span>`, opened: html`<span class="chip ok">opened</span>` }[s] || s);
  box.innerHTML = String(html`<div class="split">
    <div class="card"><div class="card-h"><h3>${icon("users")}Voters</h3><button class="btn btn-sm btn-primary" data-act="issueAll" ${open && voters.some((v) => ["requested", "eligible"].includes(v.status)) ? "" : "disabled"}>${icon("send", "sm")}Issue all pending</button></div>
      ${voters.length ? html`<div class="table-wrap"><table class="t"><thead><tr><th>Voter</th><th>Status</th><th>Roster</th><th>Timeline</th><th></th></tr></thead><tbody>
      ${voters.map((v) => html`<tr><td><div class="row">${avatar(v.name)}<div><b class="small">${v.name}</b><div class="tiny muted">${v.email}</div></div></div></td><td>${chip(v.status)}</td>
        <td>${v.roster_pos !== null && v.roster_pos !== undefined ? html`<b>#${v.roster_pos + 1}</b><div class="tiny muted mono">${v.roster_digest.slice(0, 12)}</div>` : html`<span class="muted small">none</span>`}</td>
        <td class="tiny muted">${v.requested ? html`requested ${ago(v.requested)}<br>` : ""}${v.issued ? html`sealed ${ago(v.issued)}<br>` : ""}${v.opened ? html`opened ${ago(v.opened)}` : ""}</td>
        <td>${["requested", "eligible"].includes(v.status) && open ? html`<button class="btn btn-sm btn-outline" data-act="issueOne" data-email="${v.email}">${icon("send", "sm")}Issue</button>` : ""}</td></tr>`)}
      </tbody></table></div>` : empty("No voters yet", e.eligibility === "open" ? "Voters appear here when they request a credential." : "Add eligible voters with the form.", "users")}
    </div>
    <div class="stack lg sticky">
      <div class="card pad"><h3 class="row">${icon("plus")}Add eligible voters</h3>
        <form class="stack mt" id="addv"><textarea name="emails" placeholder="one email per line or separated by commas"></textarea>
          <label class="check"><input type="checkbox" name="issue" ${open ? "checked" : "disabled"}><span>Issue credentials immediately${open ? "" : " (after the key ceremony)"}</span></label>
          <button class="btn btn-primary">${icon("plus", "sm")}Add voters</button></form></div>
      <div class="card pad"><h3 class="row">${icon("mail")}How a credential is delivered</h3>
        <div class="stack sm mt small muted">
          <div>1. The registrar draws a uniform 128-bit credential.</div>
          <div>2. It encrypts the credential under the election key and appends the ciphertext to the public roster.</div>
          <div>3. It signs the new roster root with ML-DSA-65.</div>
          <div>4. It seals the plaintext credential to the voter's device key with ML-KEM-768 and AES-256-GCM, then forgets it.</div>
        </div></div>
      <div class="card pad"><h3>Board capacity</h3><div class="mt">${capacityMeter(e)}</div></div>
    </div></div>`);
  bind(box, {
    issueOne: (b) => busy(b, async () => { await post(`/api/elections/${e.id}/voters/issue`, { email: b.dataset.email }); toast("Credential sealed and roster signed", "ok"); ctx.refresh(); })
  });
  box.querySelector("#addv").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const f = ev.target;
    await busy(f.querySelector("button"), async () => {
      await post(`/api/elections/${e.id}/voters`, { emails: f.emails.value, issue: f.issue.checked });
      toast("Voters added", "ok");
      ctx.refresh();
    });
  });
}

export async function auditTab(box, ctx) {
  const { e } = ctx;
  box.innerHTML = String(html`<div class="split">
    <div class="card"><div class="card-h"><h3>${icon("scan")}Independent audit</h3><button class="btn btn-primary" data-act="runAudit">${icon("shieldcheck")}Run full audit</button></div>
      <div class="card-b" id="audit">${empty("Ready to audit", "The audit recomputes the roster digests and the registrar signature, re-verifies every proof on the board and checks the published tally transcript.", "scan")}</div></div>
    <div class="stack lg sticky">
      <div class="card pad"><h3>What is checked</h3><div class="stack sm mt small">
        <div class="row top">${icon("key", "sm")}<span>The joint public key produced by the key ceremony</span></div>
        <div class="row top">${icon("users", "sm")}<span>Every roster ciphertext digest and the ML-DSA-65 signature over the roster root</span></div>
        <div class="row top">${icon("box", "sm")}<span>Both proofs of knowledge of every ballot, its tracker and the uniqueness of its ciphertexts</span></div>
        <div class="row top">${icon("layers", "sm")}<span>The board capacity of the packed evaluation</span></div>
        <div class="row top">${icon("shuffle", "sm")}<span>The cleansing transcript, both shuffle proofs and the final decryption shares</span></div>
        <div class="row top">${icon("chart", "sm")}<span>The published counts recomputed from the decrypted slots</span></div></div></div>
    </div></div>`);
  bind(box, {
    runAudit: (b) => busy(b, async () => {
      const out = box.querySelector("#audit");
      out.innerHTML = `<div class="empty"><span class="spin"></span><p class="mt">Re-verifying proofs…</p></div>`;
      const r = await post(`/api/elections/${e.id}/audit`);
      out.innerHTML = String(html`<div class="notice ${r.ok ? "ok" : "bad"} mb">${icon(r.ok ? "shieldcheck" : "alert")}<div><b>${r.ok ? "Every check passed" : "Some checks failed"}</b> · completed in ${r.seconds}s</div></div>
        <div class="checklist">${r.checks.map((c) => html`<div class="ck ${c.ok ? "done" : "failed"}"><span class="ic">${icon(c.ok ? "check" : "x", "sm")}</span><div class="grow"><b>${c.name}</b><span>${c.detail}</span></div></div>`)}</div>`);
    })
  });
}

export async function activityTab(box, ctx) {
  const ev = await get(`/api/elections/${ctx.e.id}/events`);
  box.innerHTML = String(html`<div class="card"><div class="card-h"><h3>${icon("activity")}Election activity</h3><span class="small muted">${ev.length} events</span></div><div class="card-b">${eventsFeed(ev)}</div></div>`);
}

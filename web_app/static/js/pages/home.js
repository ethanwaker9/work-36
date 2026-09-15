import { get } from "../api.js";
import { vault } from "../store.js";
import { ROLE_INFO, ago, bind, duration, empty, html, icon, statusChip, toast } from "../ui.js";
import { electionCard, eventsFeed, statCard, voterState } from "./common.js";

function hello(user, text, actions = "") {
  return html`<div class="page-head">
    <div><div class="eyebrow">${icon(ROLE_INFO[user.role].icon, "sm")} ${ROLE_INFO[user.role].label} dashboard</div>
      <h1>Hello, ${/\b(Authority|Registrar|Auditor)\b/.test(user.name) ? user.name : user.name.replace(/^(prof|dr|mr|mrs|ms)\.?\s+/i, "").split(" ")[0]}</h1><p class="sub">${text}</p></div>
    <div class="row wrap">${actions}</div></div>`;
}

function section(title, sub, items, user, emptyText, cols = "g3") {
  return html`<div class="mt-lg"><div class="row between mb"><div><h2>${title}</h2>${sub ? html`<p class="muted small">${sub}</p>` : ""}</div></div>
    ${items.length ? html`<div class="grid ${cols}">${items.map((e) => electionCard(e, user))}</div>` : html`<div class="card">${empty(emptyText[0], emptyText[1], emptyText[2] || "box")}</div>`}</div>`;
}

async function voterHome(ctx, els) {
  const u = ctx.user;
  const open = els.filter((e) => ["registration", "voting"].includes(e.status));
  const soon = els.filter((e) => e.status === "ceremony");
  const done = els.filter((e) => ["closed", "tallying", "published"].includes(e.status));
  const receipts = vault.receipts(u.email).slice(0, 4);
  const ready = open.filter((e) => e.status === "voting" && voterState(e, u).key === "ready");
  ctx.el.innerHTML = String(html`
    ${hello(u, "Your ballot is encrypted on this device, posted anonymously, and only counted if it carries a valid credential. You can always vote again: the last valid ballot counts.",
      html`<a class="btn btn-outline" href="#/how">${icon("book")}How it works</a><a class="btn btn-primary" href="#/elections">${icon("ballot")}All elections</a>`)}
    <div class="split">
      <div>
        ${ready.length ? html`<div class="card tint pad rise"><div class="row between wrap"><div class="row"><span class="icon-tile">${icon("ballot", "lg")}</span><div><h3>${ready.length === 1 ? "A ballot is waiting for you" : `${ready.length} ballots are waiting for you`}</h3><p class="muted small">${ready.map((e) => e.title).join(" · ")}</p></div></div><a class="btn btn-primary" href="#/e/${ready[0].id}/vote">Open the voting booth ${icon("right", "sm")}</a></div></div>` : ""}
        ${section("Open elections", "Get a credential from the registrar, then cast your encrypted ballot.", open, u, ["No open elections", "When an authority opens registration or voting it appears here.", "ballot"], "g2")}
        ${section("Upcoming", "These elections are still in their key ceremony.", soon, u, ["Nothing upcoming", "New elections show up here first.", "clock"], "g2")}
        ${section("Closed and results", "Tallies run under encryption and are verifiable.", done, u, ["No results yet", "Published results will be listed here.", "chart"], "g2")}
      </div>
      <div class="stack lg sticky">
        <div class="card"><div class="card-h"><h3>${icon("receipt")}Your receipts</h3><a class="small" href="#/receipts">All</a></div><div class="card-b">
          ${receipts.length ? html`<div class="stack sm">${receipts.map((r) => html`<a class="row between" href="#/track/${r.tracker}" style="color:inherit"><div class="grow"><b class="small ellipsis" style="display:block">${r.title}</b><span class="mono tiny muted">${r.tracker.slice(0, 16)}…</span></div><span class="tiny faint">${ago(r.posted)}</span></a>`)}</div>` : html`<p class="muted small">After you vote, your ballot tracker is kept here so you can find it on the bulletin board.</p>`}
        </div></div>
        <div class="card pad"><h3 class="row">${icon("search")}Track a ballot</h3><p class="muted small mt">Paste a tracker to find the ballot on a public bulletin board.</p>
          <form class="row mt" id="track"><input type="text" class="mono" placeholder="e.g. 4f2a9c…" name="t"><button class="btn btn-outline">Find</button></form></div>
        <div class="card pad" style="background:linear-gradient(180deg,#fff7ed,#fff)"><h3 class="row">${icon("mask")}Under pressure?</h3>
          <p class="small muted mt">If someone demands your voting credential, open your credential page and generate a <b>fake credential</b>. It looks identical, the bulletin board accepts ballots made with it, and the encrypted cleansing silently discards them. Later, vote with your real credential.</p></div>
      </div>
    </div>`);
  ctx.el.querySelector("#track").addEventListener("submit", (e) => {
    e.preventDefault();
    const t = e.target.t.value.replace(/[^0-9a-fA-F]/g, "");
    if (t.length >= 8) ctx.go(`track/${t}`); else toast("Enter at least 8 hexadecimal characters", "bad");
  });
}

async function authorityHome(ctx, els) {
  const u = ctx.user;
  const activity = await get("/api/activity").catch(() => []);
  const ballots = els.reduce((s, e) => s + e.ballots, 0);
  const roster = els.reduce((s, e) => s + e.roster, 0);
  ctx.el.innerHTML = String(html`
    ${hello(u, "Create elections, choose the candidates and the size of the encrypted board, then move each election through the key ceremony, registration, voting and the verifiable tally.",
      html`<a class="btn btn-primary" href="#/new">${icon("plus")}New election</a>`)}
    <div class="grid g4">
      ${statCard("Elections", els.length, `${els.filter((e) => e.status === "published").length} published`, "list")}
      ${statCard("Open for voting", els.filter((e) => e.status === "voting").length, `${els.filter((e) => e.status === "registration").length} in registration`, "ballot", "success")}
      ${statCard("Encrypted ballots", ballots, "anonymous, on all boards", "box", "violet")}
      ${statCard("Registered voters", roster, "credentials in encrypted rosters", "users", "accent")}
    </div>
    <div class="split mt-lg">
      <div class="card"><div class="card-h"><h3>${icon("list")}Elections</h3><a class="btn btn-sm btn-outline" href="#/new">${icon("plus", "sm")}Create</a></div>
        ${els.length ? html`<div class="table-wrap"><table class="t"><thead><tr><th>Election</th><th>Phase</th><th>Board</th><th>Trustees</th><th></th></tr></thead><tbody>
        ${els.map((e) => html`<tr class="clickable" data-act="open" data-id="${e.id}"><td><b>${e.title}</b><div class="tiny muted">${e.organization || ""} · ${e.candidates.length} options</div></td>
          <td>${statusChip(e.status)}${e.status === "tallying" && e.tally && e.tally.total ? html`<div class="tiny muted mt">${Math.round((100 * e.tally.done) / e.tally.total)}% · ${duration(e.tally.eta)} left</div>` : ""}</td>
          <td style="min-width:130px"><div class="meter"><span class="bar-roster" style="width:${(100 * e.roster) / e.capacity}%"></span><span class="bar-ballots" style="width:${(100 * e.ballots) / e.capacity}%"></span></div><div class="tiny muted" style="margin-top:4px">${e.roster + e.ballots} / ${e.capacity} entries</div></td>
          <td class="small">${e.status === "ceremony" ? `${e.trustees_joined}/${e.n_trustees} joined` : e.status === "closed" ? `${e.approvals}/${e.threshold} approved` : `${e.threshold} of ${e.n_trustees}`}</td>
          <td><a class="btn btn-sm btn-outline" href="#/e/${e.id}/manage">Manage</a></td></tr>`)}
        </tbody></table></div>` : empty("No elections yet", "Create your first election to get started.", "building", html`<a class="btn btn-primary mt" href="#/new">${icon("plus")}New election</a>`)}
      </div>
      <div class="card"><div class="card-h"><h3>${icon("activity")}Recent activity</h3></div><div class="card-b">${eventsFeed(activity.slice(0, 12), true)}</div></div>
    </div>`);
  bind(ctx.el, { open: (el, ev) => { if (!ev.target.closest("a")) ctx.go(`e/${el.dataset.id}/manage`); } });
}

async function registrarHome(ctx, els) {
  const u = ctx.user;
  const active = els.filter((e) => ["registration", "voting"].includes(e.status));
  const pending = els.reduce((s, e) => s + (e.requested || 0), 0);
  ctx.el.innerHTML = String(html`
    ${hello(u, "Admit eligible voters, generate their random credentials, encrypt each credential into the public roster, and seal the plaintext credential to the voter's device with ML-KEM-768 and AES-256-GCM.")}
    <div class="grid g3">
      ${statCard("Pending requests", pending, "voters waiting for a credential", "mail", pending ? "warning" : "")}
      ${statCard("Roster entries", els.reduce((s, e) => s + e.roster, 0), "encrypted credentials across elections", "users", "accent")}
      ${statCard("Elections registering", active.length, "registration stays open while voting", "clipboard", "sky")}
    </div>
    <div class="card mt-lg"><div class="card-h"><h3>${icon("clipboard")}Registration desks</h3></div>
      ${active.length ? html`<div class="table-wrap"><table class="t"><thead><tr><th>Election</th><th>Phase</th><th>Pending</th><th>Roster</th><th>Mode</th><th></th></tr></thead><tbody>
        ${active.map((e) => html`<tr><td><b>${e.title}</b><div class="tiny muted">${e.organization}</div></td><td>${statusChip(e.status)}</td>
        <td>${e.requested ? html`<span class="chip warn">${e.requested} waiting</span>` : html`<span class="muted small">none</span>`}</td>
        <td>${e.roster} / ${e.capacity - e.ballots} free</td><td class="small">${e.eligibility === "list" ? "Eligibility list" : "Open"}</td>
        <td><a class="btn btn-sm btn-primary" href="#/e/${e.id}/voters">Open desk</a></td></tr>`)}</tbody></table></div>`
      : empty("No election is registering voters", "Registration opens after the trustees finish the key ceremony.", "clipboard")}
    </div>
    ${section("All elections", "", els.filter((e) => !active.includes(e)), u, ["Nothing else", "", "box"])}`);
}

async function trusteeHome(ctx, els) {
  const u = ctx.user;
  const mine = els.filter((e) => e.me && e.me.seats && e.me.seats.length);
  const openCeremony = els.filter((e) => e.status === "ceremony" && e.trustees_joined < e.n_trustees);
  const needApproval = mine.filter((e) => e.status === "closed");
  ctx.el.innerHTML = String(html`
    ${hello(u, "The election key is split among three trustees. Any two of you can decrypt, one alone learns nothing. You join the key ceremony and later release your share so the encrypted cleansing and the final decryption can run.")}
    <div class="grid g3">
      ${statCard("Your seats", mine.reduce((s, e) => s + e.me.seats.length, 0), `${mine.length} elections`, "key")}
      ${statCard("Ceremonies needing trustees", openCeremony.length, "open seats you can take", "users", openCeremony.length ? "warning" : "")}
      ${statCard("Tallies to approve", needApproval.length, "voting closed, waiting for shares", "unlock", needApproval.length ? "violet" : "")}
    </div>
    ${needApproval.length ? html`<div class="notice warn mt-lg">${icon("unlock")}<div><b>Your approval is needed.</b> ${needApproval.map((e) => html`<a href="#/e/${e.id}/trustees">${e.title}</a>`)} closed and needs ${needApproval[0].threshold} trustees to release their shares.</div></div>` : ""}
    ${section("Key ceremonies with open seats", "Take a seat. When all three seats are filled the threshold key is generated.", openCeremony, u, ["No open seats", "All key ceremonies are complete.", "key"])}
    ${section("Elections where you hold a seat", "", mine, u, ["You hold no seats yet", "Join a key ceremony to become a trustee.", "key"])}`);
}

async function auditorHome(ctx, els) {
  const u = ctx.user;
  const ballots = els.reduce((s, e) => s + e.ballots, 0);
  ctx.el.innerHTML = String(html`
    ${hello(u, "Everything Oylama publishes can be re-checked: ballot proofs of knowledge, the registrar's roster signature, the cleansing transcript with its verified decryption shares, both shuffle proofs and the final decryption.")}
    <div class="grid g3">
      ${statCard("Bulletin boards", els.filter((e) => e.ballots).length, `${ballots} ballots to verify`, "box")}
      ${statCard("Published results", els.filter((e) => e.status === "published").length, "with cleansing transcripts", "chart", "success")}
      ${statCard("Tallies running", els.filter((e) => e.status === "tallying").length, "watch them live", "activity", "violet")}
    </div>
    <div class="card mt-lg"><div class="card-h"><h3>${icon("scan")}Audit desk</h3></div>
      <div class="table-wrap"><table class="t"><thead><tr><th>Election</th><th>Phase</th><th>Ballots</th><th>Roster</th><th>Result</th><th></th></tr></thead><tbody>
      ${els.map((e) => html`<tr><td><b>${e.title}</b><div class="tiny muted">${e.organization}</div></td><td>${statusChip(e.status)}</td><td>${e.ballots}</td><td>${e.roster}</td>
        <td>${e.results ? html`<span class="chip ${e.results.verified ? "ok" : "bad"}">${icon(e.results.verified ? "shieldcheck" : "alert", "sm")}${e.results.verified ? "verified" : "check failed"}</span>` : html`<span class="muted small">pending</span>`}</td>
        <td class="nowrap"><a class="btn btn-sm btn-outline" href="#/e/${e.id}/board">Board</a> <a class="btn btn-sm btn-primary" href="#/e/${e.id}/audit">Audit</a></td></tr>`)}
      </tbody></table></div></div>`);
}

export default async function home(ctx) {
  ctx.el.innerHTML = `<div class="empty"><span class="spin"></span></div>`;
  const els = await get("/api/elections");
  const r = ctx.user.role;
  if (r === "authority") return authorityHome(ctx, els);
  if (r === "registrar") return registrarHome(ctx, els);
  if (r === "trustee") return trusteeHome(ctx, els);
  if (r === "auditor") return auditorHome(ctx, els);
  return voterHome(ctx, els);
}

import { get } from "../api.js";
import { vault } from "../store.js";
import { ago, confirmDialog, copyText, empty, fmtDate, groupHex, html, icon, statusChip, toast } from "../ui.js";

export default async function track(ctx) {
  const u = ctx.user;
  const initial = ctx.params.tracker || "";
  const receipts = vault.receipts(u.email);
  const showReceipts = location.hash.startsWith("#/receipts");
  ctx.el.innerHTML = String(html`
    <div class="page-head"><div><div class="eyebrow">${icon(showReceipts ? "receipt" : "search", "sm")} ${showReceipts ? "Receipts" : "Ballot tracking"}</div>
      <h1>${showReceipts ? "My receipts" : "Track a ballot"}</h1><p class="sub">A tracker is a SHAKE256 digest of the ballot's ciphertexts and proof challenges. It proves your ballot is on the board and says nothing about how you voted.</p></div></div>
    <div class="split">
      <div class="stack lg">
        <div class="card pad"><form id="tf" class="row wrap"><div class="input-icon grow" style="min-width:240px">${icon("search", "sm")}<input type="text" class="mono" name="t" placeholder="Paste a tracker" value="${initial}"></div><button class="btn btn-primary">Find ballot</button></form><div id="res" class="mt"></div></div>
        <div class="card"><div class="card-h"><h3>${icon("receipt")}Receipts on this device</h3>${receipts.length ? html`<button class="btn btn-sm btn-ghost" data-clear>${icon("trash", "sm")}Clear</button>` : ""}</div>
          ${receipts.length ? html`<div class="table-wrap"><table class="t"><thead><tr><th>Election</th><th>Tracker</th><th>Position</th><th>Cast</th><th></th></tr></thead><tbody>
            ${receipts.map((r) => html`<tr><td><a href="#/e/${r.election}"><b>${r.title}</b></a></td><td><span class="mono small">${groupHex(r.tracker.slice(0, 20))}…</span></td><td>${r.position + 1}</td><td class="small nowrap">${ago(r.posted)}</td>
            <td class="nowrap"><button class="btn btn-sm btn-ghost" data-copy="${r.tracker}">${icon("copy", "sm")}</button><a class="btn btn-sm btn-outline" href="#/track/${r.tracker}">Check</a></td></tr>`)}
          </tbody></table></div>` : empty("No receipts yet", "Every ballot you cast from this browser leaves a receipt here.", "receipt")}
        </div>
      </div>
      <div class="stack lg sticky">
        <div class="card pad"><h3 class="row">${icon("info")}Receipts and coercion</h3><p class="small muted mt">A receipt shows that a ballot reached the board, not whether it counts. That is deliberate: a ballot made with a fake credential produces an equally valid-looking receipt, so nobody can use receipts to check how or whether you really voted.</p></div>
      </div>
    </div>`);
  const res = ctx.el.querySelector("#res");
  const search = async (t) => {
    const clean = t.replace(/[^0-9a-fA-F]/g, "").toLowerCase();
    if (clean.length < 8) { res.innerHTML = String(html`<div class="notice warn">${icon("info")}<div>Enter at least 8 hexadecimal characters.</div></div>`); return; }
    res.innerHTML = `<span class="spin"></span>`;
    try {
      const rows = await get(`/api/track/${clean}`);
      res.innerHTML = String(rows.length ? html`<div class="stack sm">${rows.map((r) => html`<div class="notice ok">${icon("check")}<div class="grow"><b>Found on the bulletin board of ${r.title}</b><div class="small">Position ${r.position + 1} · posted ${fmtDate(r.posted)} · ${statusChip(r.status)}</div><div class="mono tiny mt">${groupHex(r.tracker)}</div>
        <div class="row mt"><a class="btn btn-sm btn-outline" href="#/e/${r.election}/board?tracker=${r.tracker}">Open the board</a></div></div></div>`)}</div>`
        : html`<div class="notice bad">${icon("alert")}<div><b>No ballot with this tracker.</b> Check that you copied it completely.</div></div>`);
    } catch (e) {
      res.innerHTML = String(html`<div class="notice bad">${icon("alert")}<div>${e.message}</div></div>`);
    }
  };
  ctx.el.querySelector("#tf").addEventListener("submit", (e) => { e.preventDefault(); search(e.target.t.value); });
  ctx.el.querySelectorAll("[data-copy]").forEach((b) => b.onclick = () => copyText(b.dataset.copy, "Tracker copied"));
  const cl = ctx.el.querySelector("[data-clear]");
  if (cl) cl.onclick = async () => {
    if (await confirmDialog("Clear all receipts?", "Trackers stored in this browser will be removed. Ballots stay on the boards.", "Clear", "btn-danger")) {
      vault.clearReceipts(u.email);
      toast("Receipts cleared", "ok");
      ctx.rerender();
    }
  };
  if (initial) search(initial);
}

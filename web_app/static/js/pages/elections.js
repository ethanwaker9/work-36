import { get } from "../api.js";
import { STATUS, empty, html, icon } from "../ui.js";
import { electionCard } from "./common.js";

const FILTERS = [["all", "All"], ["ceremony", "Key ceremony"], ["registration", "Registration"], ["voting", "Voting"], ["closed", "Closed"], ["tallying", "Tallying"], ["published", "Published"]];

export default async function elections(ctx) {
  const els = await get("/api/elections");
  let filter = ctx.query.get("status") || "all";
  let q = "";
  ctx.el.innerHTML = String(html`
    <div class="page-head"><div><div class="eyebrow">${icon("list", "sm")} Elections</div><h1>All elections</h1>
      <p class="sub">Every election on this Oylama server, from key ceremony to published result.</p></div>
      ${ctx.user.role === "authority" ? html`<a class="btn btn-primary" href="#/new">${icon("plus")}New election</a>` : ""}</div>
    <div class="row between wrap mb">
      <div class="seg" id="filters">${FILTERS.map(([k, l]) => html`<button data-f="${k}" class="${k === filter ? "on" : ""}">${l} <span class="faint">${k === "all" ? els.length : els.filter((e) => e.status === k).length}</span></button>`)}</div>
      <div class="input-icon" style="min-width:260px">${icon("search", "sm")}<input type="search" placeholder="Search by title or organization" id="q"></div>
    </div>
    <div id="list"></div>`);
  const list = ctx.el.querySelector("#list");
  const draw = () => {
    const shown = els.filter((e) => (filter === "all" || e.status === filter) && (!q || `${e.title} ${e.organization}`.toLowerCase().includes(q)));
    list.innerHTML = String(shown.length ? html`<div class="grid g3">${shown.map((e) => electionCard(e, ctx.user))}</div>`
      : html`<div class="card">${empty("No elections match", filter === "all" ? "Try another search." : `Nothing is in the ${STATUS[filter][0].toLowerCase()} phase.`, "search")}</div>`);
  };
  ctx.el.querySelector("#filters").addEventListener("click", (e) => {
    const b = e.target.closest("[data-f]");
    if (!b) return;
    filter = b.dataset.f;
    ctx.el.querySelectorAll("#filters button").forEach((x) => x.classList.toggle("on", x === b));
    draw();
  });
  ctx.el.querySelector("#q").addEventListener("input", (e) => { q = e.target.value.trim().toLowerCase(); draw(); });
  draw();
}

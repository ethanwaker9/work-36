import { post } from "../api.js";
import { avatar, busy, duration, html, icon, toast } from "../ui.js";

const COLORS = ["#4F46E5", "#0EA5A4", "#DB2777", "#EA580C", "#059669", "#7C3AED", "#2563EB", "#CA8A04"];
const PLAN = { 4: 206, 8: 342, 16: 529 };

export default async function create(ctx) {
  if (ctx.user.role !== "authority") {
    ctx.el.innerHTML = String(html`<div class="notice warn">${icon("info")}<div>Only an election authority can create elections. Switch roles from the account menu.</div></div>`);
    return;
  }
  const spo = ctx.system ? ctx.system.seconds_per_opening : 0.78;
  const s = {
    title: "", organization: "", description: "", opens_at: "", closes_at: "",
    candidates: [{ name: "", party: "", color: COLORS[0] }, { name: "", party: "", color: COLORS[1] }],
    capacity: 8, eligibility: "open", voters: "", auto_issue: true, trustees: ["", "", ""]
  };

  const preview = () => html`<b>${s.title || "Untitled election"}</b><span class="small muted">${s.organization || ""}</span>
    ${s.candidates.map((c, i) => html`<div class="cand"><span class="bar" style="background:${c.color}"></span>${avatar(c.name || `Option ${i + 1}`, c.color)}<div class="meta"><b>${c.name || `Option ${i + 1}`}</b><span>${c.party}</span></div></div>`)}`;

  const draw = () => {
    ctx.el.innerHTML = String(html`<div class="crumbs"><a href="#/">Dashboard</a>${icon("chevron", "sm")}<span>New election</span></div>
      <div class="page-head"><div><div class="eyebrow">${icon("plus", "sm")} New election</div><h1>Create an election</h1><p class="sub">Define the ballot, the size of the encrypted board and who may vote. The trustees generate the key right after you create it.</p></div></div>
      <form class="split" id="f">
        <div class="stack lg">
          <div class="card"><div class="card-h"><h3>${icon("info")}Basics</h3></div><div class="card-b stack">
            <div class="field"><label>Title</label><input type="text" name="title" value="${s.title}" placeholder="City Council Election 2026" required></div>
            <div class="field"><label>Organization</label><input type="text" name="organization" value="${s.organization}" placeholder="Oylama City"></div>
            <div class="field"><label>Description</label><textarea name="description" placeholder="What is this election about?">${s.description}</textarea></div>
            <div class="grid g2"><div class="field"><label>Voting opens</label><input type="datetime-local" name="opens_at" value="${s.opens_at}"></div><div class="field"><label>Voting closes</label><input type="datetime-local" name="closes_at" value="${s.closes_at}"></div></div>
            <p class="tiny muted">The dates are shown to voters. Phases change when you open and close voting from the control center.</p>
          </div></div>
          <div class="card cand-editor"><div class="card-h"><h3>${icon("users")}Candidates and options</h3><span class="small muted">${s.candidates.length} of 8</span></div><div class="card-b stack">
            ${s.candidates.map((c, i) => html`<div class="cand-row">
              <input type="color" class="color-swatch" data-color="${i}" value="${c.color}" title="Color">
              <input type="text" data-name="${i}" value="${c.name}" placeholder="${i === 0 ? "Name, e.g. Leyla Aydın" : i === 1 ? "Name, e.g. Marcus Chen" : "Name"}">
              <input type="text" class="party" data-party="${i}" value="${c.party}" placeholder="Party or description">
              <button type="button" class="btn btn-ghost" data-remove="${i}" ${s.candidates.length <= 2 ? "disabled" : ""} title="Remove">${icon("trash", "sm")}</button></div>`)}
            <div class="row wrap"><button type="button" class="btn btn-outline btn-sm" data-add ${s.candidates.length >= 8 ? "disabled" : ""}>${icon("plus", "sm")}Add option</button>
              <button type="button" class="btn btn-ghost btn-sm" data-yesno>${icon("zap", "sm")}Use Yes / No</button></div>
          </div></div>
          <div class="card"><div class="card-h"><h3>${icon("layers")}Encrypted board</h3></div><div class="card-b stack">
            <div class="seg" id="cap">${[4, 8, 16].map((c) => html`<button type="button" data-cap="${c}" class="${s.capacity === c ? "on" : ""}">${c} entries</button>`)}</div>
            <p class="small muted">Registered voters and ballots, including revotes and ballots with fake credentials, share one packed ciphertext. A board of ${s.capacity} entries fits, for example, ${s.capacity / 2} registered voters with ${s.capacity / 2} ballots, or ${s.capacity / 2 - 1} voters with ${s.capacity / 2 + 1} ballots when someone revotes. The Batcher sorting network has ${{ 4: 3, 8: 6, 16: 10 }[s.capacity]} stages.</p>
            <div class="notice">${icon("clock")}<div class="small">A full board of ${s.capacity} entries takes about <b>${duration(PLAN[s.capacity] * spo + 8)}</b> to tally on this server: ${PLAN[s.capacity]} masked openings, each with verified decryption shares from two trustees.</div></div>
          </div></div>
          <div class="card"><div class="card-h"><h3>${icon("clipboard")}Eligibility and registration</h3></div><div class="card-b stack">
            <div class="seg" id="elig"><button type="button" data-elig="open" class="${s.eligibility === "open" ? "on" : ""}">Open to any signed-in voter</button><button type="button" data-elig="list" class="${s.eligibility === "list" ? "on" : ""}">Eligibility list</button></div>
            <div class="field ${s.eligibility === "list" ? "" : "hide"}"><label>Eligible voters</label><textarea name="voters" placeholder="alice@example.com, bob@example.com">${s.voters}</textarea><span class="hint">Only these emails can request a credential. Registrars can add more later.</span></div>
            <label class="check"><input type="checkbox" name="auto_issue" ${s.auto_issue ? "checked" : ""}><span><b>Issue credentials automatically</b><br><span class="small muted">When off, a registrar approves every request by hand.</span></span></label>
          </div></div>
          <div class="card"><div class="card-h"><h3>${icon("key")}Trustees</h3><span class="chip outline">2 of 3 threshold</span></div><div class="card-b stack">
            <p class="small muted">Optionally reserve seats for specific trustees. Empty seats can be taken by any trustee, or filled with demo trustees from the control center.</p>
            ${s.trustees.map((t, i) => html`<div class="field"><label>Seat ${i + 1}</label><div class="input-icon">${icon("mail", "sm")}<input type="text" data-trustee="${i}" value="${t}" placeholder="any trustee"></div></div>`)}
          </div></div>
        </div>
        <div class="stack lg sticky">
          <div class="card"><div class="card-h"><h3>${icon("eye")}Ballot preview</h3></div><div class="card-b stack sm" id="preview">${preview()}</div></div>
          <div class="card pad stack"><div class="row between"><span class="small muted">Board</span><b>${s.capacity} entries</b></div><div class="row between"><span class="small muted">Eligibility</span><b>${s.eligibility === "open" ? "Open" : "List"}</b></div><div class="row between"><span class="small muted">Credential bits</span><b>128</b></div><div class="row between"><span class="small muted">Ring degree</span><b>8192</b></div>
            <button class="btn btn-primary btn-lg btn-block" type="submit" id="go">${icon("check")}Create election</button></div>
        </div>
      </form>`);
    wire();
  };

  const sync = () => {
    const f = ctx.el.querySelector("#f");
    if (!f) return;
    s.title = f.title.value; s.organization = f.organization.value; s.description = f.description.value;
    s.opens_at = f.opens_at.value; s.closes_at = f.closes_at.value;
    if (f.voters) s.voters = f.voters.value;
    s.auto_issue = f.auto_issue.checked;
    f.querySelectorAll("[data-name]").forEach((x) => { s.candidates[Number(x.dataset.name)].name = x.value; });
    f.querySelectorAll("[data-party]").forEach((x) => { s.candidates[Number(x.dataset.party)].party = x.value; });
    f.querySelectorAll("[data-color]").forEach((x) => { s.candidates[Number(x.dataset.color)].color = x.value; });
    f.querySelectorAll("[data-trustee]").forEach((x) => { s.trustees[Number(x.dataset.trustee)] = x.value; });
  };

  const wire = () => {
    const el = ctx.el;
    const f = el.querySelector("#f");
    f.addEventListener("input", (ev) => {
      if (ev.target.matches("[data-name], [data-party], [data-color], [name=title], [name=organization]")) {
        sync();
        el.querySelector("#preview").innerHTML = String(preview());
      }
    });
    el.querySelector("[data-add]").onclick = () => { sync(); s.candidates.push({ name: "", party: "", color: COLORS[s.candidates.length % COLORS.length] }); draw(); };
    el.querySelector("[data-yesno]").onclick = () => { sync(); s.candidates = [{ name: "Yes", party: "In favour", color: "#10B981" }, { name: "No", party: "Against", color: "#EF4444" }]; draw(); };
    el.querySelectorAll("[data-remove]").forEach((b) => b.onclick = () => { sync(); s.candidates.splice(Number(b.dataset.remove), 1); draw(); });
    el.querySelectorAll("[data-cap]").forEach((b) => b.onclick = () => { sync(); s.capacity = Number(b.dataset.cap); draw(); });
    el.querySelectorAll("[data-elig]").forEach((b) => b.onclick = () => { sync(); s.eligibility = b.dataset.elig; draw(); });
    f.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      sync();
      await busy(el.querySelector("#go"), async () => {
        const res = await post("/api/elections", {
          title: s.title, organization: s.organization, description: s.description, opens_at: s.opens_at, closes_at: s.closes_at,
          candidates: s.candidates.filter((c) => c.name.trim()), capacity: s.capacity, eligibility: s.eligibility,
          voters: s.voters, auto_issue: s.auto_issue, trustees: s.trustees.map((x) => ({ email: x }))
        });
        toast("Election created. Next: the key ceremony.", "ok");
        ctx.go(`e/${res.id}/manage`);
      });
    });
  };

  draw();
}

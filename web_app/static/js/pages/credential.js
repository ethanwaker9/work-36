import { get, post } from "../api.js";
import { randomCredential } from "../oylama-crypto.js";
import { vault } from "../store.js";
import { ago, busy, confirmDialog, copyText, fmtDate, html, icon, statusChip, toast } from "../ui.js";

function card(text, { fake = false, reveal = false, label, sub }) {
  return html`<div class="cred-card ${fake ? "fake" : ""}">
    <div class="top"><span class="row" style="gap:6px">${icon("key", "sm")}<b>${label}</b></span><span>128-bit credential</span></div>
    <div class="credential ${reveal ? "" : "blur"}" data-cred>${text}</div>
    <div class="bottom"><span>${sub}</span><div class="row"><button class="btn btn-sm" data-toggle>${icon(reveal ? "eyeoff" : "eye", "sm")}${reveal ? "Hide" : "Reveal"}</button><button class="btn btn-sm" data-copy>${icon("copy", "sm")}Copy</button></div></div>
  </div>`;
}

export default async function credential(ctx) {
  const id = ctx.params.id;
  const u = ctx.user;
  const [e, c] = await Promise.all([get(`/api/elections/${id}`), get(`/api/elections/${id}/credential`)]);
  const stored = vault.credential(u.email, id);
  const regOpen = ["registration", "voting"].includes(e.status);
  const state = { reveal: false, fake: null, fakeReveal: true };

  const main = () => {
    if (u.role !== "voter") {
      return html`<div class="notice warn">${icon("info")}<div>Credentials belong to voters. Switch to the voter role from the account menu to request one.</div></div>`;
    }
    if (stored) {
      return html`<div class="stack lg">
        ${card(stored, { reveal: state.reveal, label: "Oylama voting credential", sub: html`Roster entry ${c.roster_pos !== null && c.roster_pos !== undefined ? `#${c.roster_pos + 1}` : ""} · stored on this device` })}
        <div class="row wrap">${e.status === "voting" ? html`<a class="btn btn-primary" href="#/e/${id}/vote">${icon("ballot")}Vote with this credential</a>` : ""}
          <button class="btn btn-ghost" data-act="forget">${icon("trash", "sm")}Forget on this device</button></div>
        <div class="notice">${icon("shield")}<div class="small">Keep this credential private. Anyone who has it can vote for you, but you can always vote again later and only your last ballot counts. If you are pressured, give away a fake credential instead.</div></div>
      </div>`;
    }
    if (c.status === "none" || c.status === "eligible") {
      return html`<div class="card pad center"><div class="icon-tile" style="margin:0 auto 12px;width:56px;height:56px">${icon("mail", "lg")}</div>
        <h2>${c.status === "eligible" ? "You are on the eligibility list" : "Request your voting credential"}</h2>
        <p class="muted mt">The registrar generates a random credential, adds its encryption to the public roster and seals the credential to this account's device key.</p>
        ${regOpen ? html`<button class="btn btn-primary btn-lg mt-lg" data-act="request">${icon("send")}Request credential</button>` : html`<p class="mt-lg">${statusChip(e.status)}</p><p class="small muted mt">${e.status === "ceremony" ? "Registration opens after the key ceremony." : "Registration has closed."}</p>`}
      </div>`;
    }
    if (c.status === "requested") {
      return html`<div class="card pad center"><div class="icon-tile warning" style="margin:0 auto 12px;width:56px;height:56px">${icon("clock", "lg")}</div><h2>Waiting for the registrar</h2>
        <p class="muted mt">Your request from ${ago(c.requested)} is in the registrar's queue. This page updates when your sealed credential arrives.</p></div>`;
    }
    const env = c.envelope || {};
    return html`<div class="envelope">
      <div class="row between wrap"><div class="row"><span class="icon-tile">${icon("mail", "lg")}</span><div><h2>A sealed credential is waiting</h2><p class="small muted">Sealed ${fmtDate(env.sealed)} for device key <span class="hash">${env.device_key || ""}</span></p></div></div>
        <span class="chip info">${icon("lock", "sm")}${env.kem} + ${env.aead}</span></div>
      <dl class="kv small mt-lg"><dt>Encapsulation</dt><dd class="coeffs">${env.kem_ct_preview}…</dd><dt>Ciphertext</dt><dd>${env.blob_bytes} bytes</dd><dt>Roster entry</dt><dd>#${(c.roster_pos || 0) + 1} · <span class="hash">${(c.roster_digest || "").slice(0, 24)}</span></dd></dl>
      <button class="btn btn-primary btn-lg mt-lg" data-act="open">${icon("unlock")}Open on this device</button>
      <p class="tiny faint mt">Decapsulation uses this account's ML-KEM-768 device key. In this demo the device key is kept by the server for convenience.</p>
    </div>`;
  };

  const draw = () => {
    ctx.el.innerHTML = String(html`<div class="crumbs"><a href="#/elections">Elections</a>${icon("chevron", "sm")}<a href="#/e/${id}">${e.title}</a>${icon("chevron", "sm")}<span>Credential</span></div>
      <div class="page-head"><div><div class="eyebrow">${icon("key", "sm")} Credential</div><h1>Your voting credential</h1><p class="sub">${e.title}</p></div>${statusChip(e.status)}</div>
      <div class="split">
        <div class="stack lg">${main()}
          <div class="card"><div class="card-h"><h3>${icon("mask")}Coercion shield: fake credentials</h3><span class="chip warn">use under pressure</span></div><div class="card-b stack">
            <p class="muted small">If someone forces you to reveal your credential or to vote in front of them, generate a fake credential right here. It has exactly the same format as a real one and nothing on the bulletin board can distinguish them. Ballots cast with it are accepted and then removed during the encrypted cleansing. Nothing about fake credentials is stored anywhere.</p>
            ${state.fake ? html`${card(state.fake, { fake: true, reveal: state.fakeReveal, label: "Oylama voting credential", sub: "Hand this over instead of your real one" })}
              <div class="row wrap"><button class="btn btn-outline" data-act="fake">${icon("refresh", "sm")}Generate another</button>${e.status === "voting" ? html`<button class="btn btn-outline" data-act="useFake">${icon("ballot", "sm")}Cast a ballot with it</button>` : ""}</div>`
            : html`<div><button class="btn btn-accent" data-act="fake">${icon("mask")}Generate a fake credential</button></div>`}
          </div></div>
        </div>
        <div class="stack lg sticky">
          <div class="card pad"><h3 class="row">${icon("info")}How your credential works</h3><div class="stack sm mt small">
            <div class="row top">${icon("check", "sm")}<span>A real credential matches one encrypted entry of the roster; a fake one matches none.</span></div>
            <div class="row top">${icon("check", "sm")}<span>The tally sorts all ballots and roster entries by encrypted credential and keeps only the last ballot directly followed by a roster entry.</span></div>
            <div class="row top">${icon("check", "sm")}<span>Nobody, including the authority and the trustees, learns which ballots were removed or why.</span></div>
            <div class="row top">${icon("check", "sm")}<span>Only the counts are published; the number of fake ballots stays hidden among decoys and revotes.</span></div>
          </div></div>
          <div class="card pad"><h3 class="row">${icon("activity")}Registration record</h3>
            <dl class="kv small mt" style="grid-template-columns:100px 1fr"><dt>Status</dt><dd>${c.status}</dd>${c.requested ? html`<dt>Requested</dt><dd>${fmtDate(c.requested)}</dd>` : ""}${c.issued ? html`<dt>Sealed</dt><dd>${fmtDate(c.issued)}</dd>` : ""}${c.opened ? html`<dt>Opened</dt><dd>${fmtDate(c.opened)}</dd>` : ""}</dl></div>
        </div>
      </div>`);
    wire();
  };

  const wire = () => {
    const el = ctx.el;
    el.querySelectorAll(".cred-card").forEach((cc, i) => {
      const isFake = cc.classList.contains("fake");
      const text = isFake ? state.fake : stored;
      cc.querySelector("[data-toggle]").onclick = () => { if (isFake) state.fakeReveal = !state.fakeReveal; else state.reveal = !state.reveal; draw(); };
      cc.querySelector("[data-copy]").onclick = () => copyText(text, "Credential copied");
    });
    el.querySelectorAll("[data-act]").forEach((b) => b.addEventListener("click", async () => {
      const a = b.dataset.act;
      if (a === "request") {
        await busy(b, async () => {
          const r = await post(`/api/elections/${id}/credential/request`);
          toast(r.status === "issued" ? "The registrar sealed your credential" : "Request sent to the registrar", "ok");
          ctx.rerender();
        });
      } else if (a === "open") {
        await busy(b, async () => {
          const r = await post(`/api/elections/${id}/credential/open`);
          vault.saveCredential(u.email, id, r.credential);
          toast("Envelope opened. Your credential is stored on this device.", "ok");
          ctx.rerender();
        });
      } else if (a === "forget") {
        if (await confirmDialog("Forget the credential on this device?", "You can open the sealed envelope again later to restore it.", "Forget", "btn-danger")) {
          vault.forgetCredential(u.email, id);
          ctx.rerender();
        }
      } else if (a === "fake") {
        state.fake = randomCredential();
        state.fakeReveal = true;
        draw();
      } else if (a === "useFake") {
        try { sessionStorage.setItem("oylama.booth.credential", state.fake); } catch {}
        ctx.go(`e/${id}/vote`);
      }
    }));
  };

  draw();
  if (c.status === "requested") {
    const t = setInterval(async () => {
      const cur = await get(`/api/elections/${id}/credential`).catch(() => null);
      if (cur && cur.status !== "requested") ctx.rerender();
    }, 3000);
    ctx.onCleanup(() => clearInterval(t));
  }
}

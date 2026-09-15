import { html, icon, raw } from "../ui.js";

const DIAGRAM = `<svg class="diagram" viewBox="0 0 900 430" font-family="ui-sans-serif, system-ui" font-size="13">
<defs>
<marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="#8b92ad"/></marker>
<linearGradient id="bb" x1="0" x2="1"><stop offset="0" stop-color="#eef0ff"/><stop offset="1" stop-color="#e6f7f7"/></linearGradient>
</defs>
<g stroke="#8b92ad" stroke-width="1.6" fill="none" marker-end="url(#ah)">
<path d="M150 95 L150 175"/>
<path d="M235 222 L355 222"/>
<path d="M545 222 L660 222"/>
<path d="M745 185 L745 105"/>
<path d="M745 262 L745 330"/>
<path d="M450 262 L450 330"/>
<path d="M660 70 L260 70" stroke-dasharray="5 5"/>
<path d="M660 360 L545 360"/>
</g>
<g font-size="11.5" fill="#5f6788">
<text x="160" y="140">sealed credential</text><text x="160" y="155">ML-KEM-768 + AES-GCM</text>
<text x="250" y="210">ballot (C1, C2, π1, π2)</text><text x="250" y="240">anonymous channel</text>
<text x="560" y="210">ballots + roster</text>
<text x="755" y="150">threshold key</text><text x="755" y="165">2 of 3 shares</text>
<text x="755" y="305">masked openings</text>
<text x="460" y="305">recheck proofs</text>
<text x="380" y="60">roster signed with ML-DSA-65</text>
<text x="560" y="350">result + transcript</text>
</g>
<g>
<rect x="60" y="30" width="200" height="64" rx="14" fill="#fff" stroke="#e2e5ef"/><text x="160" y="58" text-anchor="middle" font-weight="700" fill="#0e1330">Registrar</text><text x="160" y="77" text-anchor="middle" fill="#5f6788" font-size="12">issues credentials</text>
<rect x="660" y="30" width="200" height="74" rx="14" fill="#fff" stroke="#e2e5ef"/><text x="760" y="58" text-anchor="middle" font-weight="700" fill="#0e1330">Election authority</text><text x="760" y="77" text-anchor="middle" fill="#5f6788" font-size="12">creates and drives</text><text x="760" y="93" text-anchor="middle" fill="#5f6788" font-size="12">the phases</text>
<rect x="60" y="178" width="175" height="84" rx="14" fill="#fff" stroke="#e2e5ef"/><text x="147" y="212" text-anchor="middle" font-weight="700" fill="#0e1330">Voter</text><text x="147" y="231" text-anchor="middle" fill="#5f6788" font-size="12">encrypts and proves</text><text x="147" y="247" text-anchor="middle" fill="#5f6788" font-size="12">in the browser</text>
<rect x="355" y="178" width="190" height="84" rx="14" fill="url(#bb)" stroke="#c7cbf5"/><text x="450" y="212" text-anchor="middle" font-weight="700" fill="#0e1330">Bulletin board</text><text x="450" y="231" text-anchor="middle" fill="#5f6788" font-size="12">verifies proofs,</text><text x="450" y="247" text-anchor="middle" fill="#5f6788" font-size="12">stores everything</text>
<rect x="660" y="185" width="200" height="77" rx="14" fill="#fff" stroke="#e2e5ef"/><text x="760" y="215" text-anchor="middle" font-weight="700" fill="#0e1330">Trustees 1, 2, 3</text><text x="760" y="234" text-anchor="middle" fill="#5f6788" font-size="12">cleanse, mix and decrypt</text><text x="760" y="250" text-anchor="middle" fill="#5f6788" font-size="12">under encryption</text>
<rect x="355" y="330" width="190" height="64" rx="14" fill="#fff" stroke="#e2e5ef"/><text x="450" y="358" text-anchor="middle" font-weight="700" fill="#0e1330">Auditors</text><text x="450" y="377" text-anchor="middle" fill="#5f6788" font-size="12">anyone can verify</text>
<rect x="660" y="330" width="200" height="64" rx="14" fill="#fff" stroke="#e2e5ef"/><text x="760" y="358" text-anchor="middle" font-weight="700" fill="#0e1330">Mix servers</text><text x="760" y="377" text-anchor="middle" fill="#5f6788" font-size="12">two verifiable shuffles</text>
</g>
</svg>`;

export default async function how(ctx) {
  const signedIn = !!ctx.user;
  const body = html`
    <div class="page-head"><div><div class="eyebrow">${icon("book", "sm")} How it works</div><h1>How Oylama keeps an online election honest</h1>
      <p class="sub">Oylama is a post-quantum, coercion-resistant voting scheme. Ballots are lattice ciphertexts, voters can defeat a coercer with fake credentials, and the tally removes invalid ballots without ever decrypting them.</p></div>
      ${signedIn ? "" : html`<a class="btn btn-primary" href="#/login">${icon("right")}Sign in to try it</a>`}</div>
    <div class="card pad mb">${raw(DIAGRAM)}</div>
    <div class="grid g2">
      <div class="card"><div class="card-h"><h3>${icon("list")}The election, step by step</h3></div><div class="card-b how">
        <div class="h-step"><div><b>Key ceremony.</b><p class="small muted">Three trustees receive a replicated sharing of a packed BGV secret key over Z<sub>q</sub>[X]/(X<sup>8192</sup>+1). Any two can decrypt, one alone cannot.</p></div></div>
        <div class="h-step"><div><b>Registration.</b><p class="small muted">The registrar draws a random 128-bit credential for each voter, appends its encryption to the public roster, signs the roster with ML-DSA-65 and seals the credential to the voter with ML-KEM-768.</p></div></div>
        <div class="h-step"><div><b>Voting.</b><p class="small muted">The browser encrypts the chosen option and the credential into two ciphertexts and proves knowledge of both openings with Fiat–Shamir proofs with aborts. The ballot is posted anonymously; the board checks the proofs.</p></div></div>
        <div class="h-step"><div><b>Cleansing.</b><p class="small muted">All ballots and roster entries are packed into one ciphertext and sorted by encrypted credential with a Batcher network. An entry stays valid only when its successor has the same credential and is the roster record, which keeps the last ballot of each real credential and drops fake ones.</p></div></div>
        <div class="h-step"><div><b>Mixing and decryption.</b><p class="small muted">The cleansed vote slots are shuffled by two mix servers with lattice shuffle proofs, then decrypted by two trustees whose decryption shares carry proofs.</p></div></div>
        <div class="h-step"><div><b>Audit.</b><p class="small muted">Anyone can re-verify ballot proofs, the roster signature, every decryption share of the cleansing, both shuffles and the final counts.</p></div></div>
      </div></div>
      <div class="stack lg">
        <div class="card"><div class="card-h"><h3>${icon("mask")}Coercion resistance</h3></div><div class="card-b stack small">
          <p>Suppose someone demands your credential or watches you vote. On your credential page you generate a fake credential. It is a uniform 128-bit string, exactly like a real one, so nobody can tell them apart.</p>
          <p>Ballots made with it are accepted by the board. During the tally they find no matching roster entry and are gated to an empty vote under encryption. Nobody learns that it happened, not even how many such ballots there were, because authorities also post decoys and revotes are removed the same way.</p>
          <p>Later, with your real credential, you vote as you want. The last valid ballot per credential counts.</p>
        </div></div>
        <div class="card"><div class="card-h"><h3>${icon("zap")}Packed evaluation</h3></div><div class="card-b small">
          <p>With plaintext modulus p = 65537 the ring splits into 8192 slots, so one ciphertext carries 8192 bits. Each multiplication of two encrypted bit vectors is a masked opening: every trustee adds an encryption of a random mask, the quorum decrypts the masked value with verified shares, and the product is rebuilt from public values. One distributed decryption therefore serves 8192 binary gates at once. Rotations of the slots use the automorphism X → X<sup>3<sup>k</sup></sup>.</p>
        </div></div>
        <div class="card"><div class="card-h"><h3>${icon("users")}Who does what in this demo</h3></div><div class="card-b stack sm small">
          <div class="row top">${icon("building", "sm")}<span><b>Election authority</b> creates elections and moves them through the phases, and may post decoy ballots.</span></div>
          <div class="row top">${icon("clipboard", "sm")}<span><b>Registrar</b> admits voters, builds the encrypted roster and seals credentials.</span></div>
          <div class="row top">${icon("key", "sm")}<span><b>Trustees</b> take seats in the key ceremony and release their shares for the tally.</span></div>
          <div class="row top">${icon("ballot", "sm")}<span><b>Voters</b> get credentials, cast, revote and generate fake credentials.</span></div>
          <div class="row top">${icon("scan", "sm")}<span><b>Auditors</b> inspect boards, rosters and transcripts and rerun every check.</span></div>
          <p class="muted mt">Sign-in is simulated: any email or username with any password works, and the Google, Microsoft, Apple and passkey buttons let you pick an account without leaving the server.</p>
        </div></div>
      </div>
    </div>`;
  if (!signedIn) {
    ctx.el.innerHTML = String(html`<header class="topbar"><div class="topbar-inner"><a class="brand" href="#/login"><img src="/img/logo.svg" alt=""><span>Oylama</span></a><div class="grow"></div><a class="btn btn-primary btn-sm" href="#/login">Sign in</a></div></header><main>${body}</main>`);
  } else {
    ctx.el.innerHTML = String(body);
  }
}

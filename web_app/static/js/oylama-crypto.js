const RC = [
  [0x00000001, 0x00000000], [0x00008082, 0x00000000], [0x0000808a, 0x80000000], [0x80008000, 0x80000000],
  [0x0000808b, 0x00000000], [0x80000001, 0x00000000], [0x80008081, 0x80000000], [0x00008009, 0x80000000],
  [0x0000008a, 0x00000000], [0x00000088, 0x00000000], [0x80008009, 0x00000000], [0x8000000a, 0x00000000],
  [0x8000808b, 0x00000000], [0x0000008b, 0x80000000], [0x00008089, 0x80000000], [0x00008003, 0x80000000],
  [0x00008002, 0x80000000], [0x00000080, 0x80000000], [0x0000800a, 0x00000000], [0x8000000a, 0x80000000],
  [0x80008081, 0x80000000], [0x00008080, 0x80000000], [0x80000001, 0x00000000], [0x80008008, 0x80000000]
];
const ROT = [0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14];
const PI = new Int32Array(25);
for (let x = 0; x < 5; x++) for (let y = 0; y < 5; y++) PI[x + 5 * y] = y + 5 * ((2 * x + 3 * y) % 5);

function keccakF(s) {
  const bl = new Int32Array(25), bh = new Int32Array(25), cl = new Int32Array(5), ch = new Int32Array(5);
  for (let r = 0; r < 24; r++) {
    for (let x = 0; x < 5; x++) {
      let l = 0, h = 0;
      for (let y = 0; y < 5; y++) { l ^= s[2 * (x + 5 * y)]; h ^= s[2 * (x + 5 * y) + 1]; }
      cl[x] = l; ch[x] = h;
    }
    for (let x = 0; x < 5; x++) {
      const pl = cl[(x + 4) % 5], ph = ch[(x + 4) % 5];
      const nl = cl[(x + 1) % 5], nh = ch[(x + 1) % 5];
      const dl = pl ^ ((nl << 1) | (nh >>> 31));
      const dh = ph ^ ((nh << 1) | (nl >>> 31));
      for (let y = 0; y < 5; y++) { s[2 * (x + 5 * y)] ^= dl; s[2 * (x + 5 * y) + 1] ^= dh; }
    }
    for (let i = 0; i < 25; i++) {
      const lo = s[2 * i], hi = s[2 * i + 1], n = ROT[i], j = PI[i];
      if (n === 0) { bl[j] = lo; bh[j] = hi; }
      else if (n < 32) { bl[j] = (lo << n) | (hi >>> (32 - n)); bh[j] = (hi << n) | (lo >>> (32 - n)); }
      else if (n === 32) { bl[j] = hi; bh[j] = lo; }
      else { const m = n - 32; bl[j] = (hi << m) | (lo >>> (32 - m)); bh[j] = (lo << m) | (hi >>> (32 - m)); }
    }
    for (let y = 0; y < 5; y++) {
      for (let x = 0; x < 5; x++) {
        const i = x + 5 * y, i1 = (x + 1) % 5 + 5 * y, i2 = (x + 2) % 5 + 5 * y;
        s[2 * i] = bl[i] ^ (~bl[i1] & bl[i2]);
        s[2 * i + 1] = bh[i] ^ (~bh[i1] & bh[i2]);
      }
    }
    s[0] ^= RC[r][0];
    s[1] ^= RC[r][1];
  }
}

export class Shake256 {
  constructor() {
    this.s = new Int32Array(50);
    this.buf = new Uint8Array(136);
    this.pos = 0;
  }
  _absorbBlock(b, o) {
    const s = this.s;
    for (let i = 0; i < 17; i++) {
      const k = o + 8 * i;
      s[2 * i] ^= b[k] | (b[k + 1] << 8) | (b[k + 2] << 16) | (b[k + 3] << 24);
      s[2 * i + 1] ^= b[k + 4] | (b[k + 5] << 8) | (b[k + 6] << 16) | (b[k + 7] << 24);
    }
    keccakF(s);
  }
  update(data) {
    let i = 0;
    const len = data.length;
    if (this.pos > 0) {
      while (i < len && this.pos < 136) this.buf[this.pos++] = data[i++];
      if (this.pos === 136) { this._absorbBlock(this.buf, 0); this.pos = 0; }
    }
    while (len - i >= 136) { this._absorbBlock(data, i); i += 136; }
    while (i < len) this.buf[this.pos++] = data[i++];
    return this;
  }
  digest(outlen) {
    const b = this.buf;
    b.fill(0, this.pos);
    b[this.pos] ^= 0x1f;
    b[135] ^= 0x80;
    this._absorbBlock(b, 0);
    const out = new Uint8Array(outlen);
    const s = this.s;
    let o = 0;
    while (true) {
      for (let i = 0; i < 17 && o < outlen; i++) {
        const lo = s[2 * i], hi = s[2 * i + 1];
        for (let k = 0; k < 4 && o < outlen; k++) out[o++] = (lo >>> (8 * k)) & 255;
        for (let k = 0; k < 4 && o < outlen; k++) out[o++] = (hi >>> (8 * k)) & 255;
      }
      if (o >= outlen) break;
      keccakF(s);
    }
    return out;
  }
}

const enc = new TextEncoder();

export function bytes(x) {
  if (x instanceof Uint8Array) return x;
  if (typeof x === "string") return enc.encode(x);
  if (typeof x === "number") {
    const out = new Uint8Array(8);
    let v = BigInt(x);
    for (let i = 0; i < 8; i++) { out[i] = Number(v & 255n); v >>= 8n; }
    return out;
  }
  throw new Error("unsupported chunk");
}

export function shake256(chunks, outlen = 32) {
  const h = new Shake256();
  const pre = new Uint8Array(4);
  for (const c of chunks) {
    const b = bytes(c);
    const n = b.length;
    pre[0] = n & 255; pre[1] = (n >>> 8) & 255; pre[2] = (n >>> 16) & 255; pre[3] = (n >>> 24) & 255;
    h.update(pre);
    h.update(b);
  }
  return h.digest(outlen);
}

export class XofReader {
  constructor(seed, domain) {
    const d = bytes(domain), s = bytes(seed);
    this.pre = new Uint8Array(d.length + s.length);
    this.pre.set(d, 0);
    this.pre.set(s, d.length);
    this.buf = new Uint8Array(0);
    this.pos = 0;
    this.ctr = 0;
  }
  _more(need) {
    const want = Math.max(need, 16384);
    const parts = [];
    let got = 0;
    while (got < want) {
      const h = new Shake256();
      h.update(this.pre);
      h.update(bytes(this.ctr));
      parts.push(h.digest(16384));
      got += 16384;
      this.ctr++;
    }
    const rest = this.buf.subarray(this.pos);
    const nb = new Uint8Array(rest.length + got);
    nb.set(rest, 0);
    let o = rest.length;
    for (const p of parts) { nb.set(p, o); o += p.length; }
    this.buf = nb;
    this.pos = 0;
  }
  read(n) {
    if (this.pos + n > this.buf.length) this._more(n);
    const out = this.buf.subarray(this.pos, this.pos + n);
    this.pos += n;
    return out;
  }
}

export function challenge(seed, domain, n, weight) {
  const rd = new XofReader(seed, domain);
  const c = new Int8Array(n);
  let placed = 0;
  while (placed < weight) {
    const raw = rd.read(8);
    const idx = ((raw[0] | (raw[1] << 8) | (raw[2] << 16) | (raw[3] << 24)) >>> 0) % n;
    const sgn = (raw[4] & 1) ? 1 : -1;
    if (c[idx] === 0) { c[idx] = sgn; placed++; }
  }
  const idx = [], sgn = [];
  for (let i = 0; i < n; i++) if (c[i] !== 0) { idx.push(i); sgn.push(c[i]); }
  return { idx, sgn };
}

function powmod(b, e, m) {
  let r = 1n, x = BigInt(b) % BigInt(m), y = BigInt(e);
  const M = BigInt(m);
  while (y > 0n) { if (y & 1n) r = (r * x) % M; x = (x * x) % M; y >>= 1n; }
  return Number(r);
}

function brv(i, bits) {
  let r = 0;
  for (let k = 0; k < bits; k++) { r = (r << 1) | (i & 1); i >>= 1; }
  return r;
}

export class NTT {
  constructor(n, q) {
    this.n = n;
    this.q = q;
    this.logn = Math.log2(n) | 0;
    let psi = 0;
    for (let g = 2; g < (1 << 20); g++) {
      const c = powmod(g, (q - 1) / (2 * n), q);
      if (powmod(c, n, q) === q - 1) { psi = c; break; }
    }
    const psiInv = powmod(psi, q - 2, q);
    this.zetas = new Float64Array(n);
    this.izetas = new Float64Array(n);
    let pw = new Float64Array(n), ipw = new Float64Array(n);
    pw[0] = 1; ipw[0] = 1;
    for (let i = 1; i < n; i++) { pw[i] = (pw[i - 1] * psi) % q; ipw[i] = (ipw[i - 1] * psiInv) % q; }
    for (let i = 0; i < n; i++) { const j = brv(i, this.logn); this.zetas[i] = pw[j]; this.izetas[i] = ipw[j]; }
    this.ninv = powmod(n, q - 2, q);
  }
  fwd(a) {
    const q = this.q, n = this.n, z = this.zetas;
    let ln = n >> 1, m = 1;
    while (ln >= 1) {
      for (let j = 0; j < m; j++) {
        const zeta = z[m + j], base = j * 2 * ln;
        for (let k = base; k < base + ln; k++) {
          const t = (a[k + ln] * zeta) % q;
          const u = a[k];
          const hi = u + t;
          a[k] = hi >= q ? hi - q : hi;
          const lo = u - t;
          a[k + ln] = lo < 0 ? lo + q : lo;
        }
      }
      m <<= 1;
      ln >>= 1;
    }
    return a;
  }
  inv(a) {
    const q = this.q, n = this.n, z = this.izetas;
    let ln = 1, m = n >> 1;
    while (m >= 1) {
      for (let j = 0; j < m; j++) {
        const zeta = z[m + j], base = j * 2 * ln;
        for (let k = base; k < base + ln; k++) {
          const u = a[k], v = a[k + ln];
          const hi = u + v;
          a[k] = hi >= q ? hi - q : hi;
          let d = u - v;
          if (d < 0) d += q;
          a[k + ln] = (d * zeta) % q;
        }
      }
      m >>= 1;
      ln <<= 1;
    }
    const ni = this.ninv;
    for (let k = 0; k < n; k++) a[k] = (a[k] * ni) % q;
    return a;
  }
}

export class Slots {
  constructor(n, p) {
    this.n = n;
    this.p = p;
    this.ntt = new NTT(n, p);
    const half = n >> 1, m = 2 * n, logn = Math.log2(n) | 0;
    this.pos = new Int32Array(n);
    for (let eps = 0; eps < 2; eps++) {
      const sgn = eps === 0 ? 1 : m - 1;
      let g = 1;
      for (let k = 0; k < half; k++) {
        const e = (g * sgn) % m;
        this.pos[eps * half + k] = brv((e - 1) / 2, logn);
        g = (g * 3) % m;
      }
    }
  }
  encodeSparse(entries) {
    const w = new Float64Array(this.n);
    for (const [slot, value] of entries) w[this.pos[slot]] = ((value % this.p) + this.p) % this.p;
    return this.ntt.inv(w);
  }
}

function randomUint32(count) {
  const out = new Uint32Array(count);
  for (let o = 0; o < count; o += 16384) crypto.getRandomValues(out.subarray(o, Math.min(count, o + 16384)));
  return out;
}

function ternaryRandom(n) {
  const out = new Float64Array(n);
  let got = 0;
  while (got < n) {
    const raw = new Uint8Array(Math.min(65536, 2 * (n - got) + 64));
    crypto.getRandomValues(raw);
    for (let i = 0; i < raw.length && got < n; i++) {
      const v = raw[i] & 3;
      if (v < 3) out[got++] = v - 1;
    }
  }
  return out;
}

function gaussianRandom(n, sigma) {
  const out = new Float64Array(n);
  const r = randomUint32(2 * n);
  for (let i = 0; i < n; i++) {
    const u1 = (r[2 * i] + 1) / (4294967296 + 2);
    const u2 = r[2 * i + 1] / 4294967296;
    out[i] = Math.round(Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2) * sigma);
  }
  return out;
}

export function b64decode(text) {
  const bin = atob(text);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function b64encode(buf) {
  let s = "";
  for (let i = 0; i < buf.length; i += 32768) s += String.fromCharCode.apply(null, buf.subarray(i, i + 32768));
  return btoa(s);
}

export function hex(buf) {
  return Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function fromHex(text) {
  const clean = text.replace(/[^0-9a-fA-F]/g, "");
  const out = new Uint8Array(clean.length >> 1);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.substr(2 * i, 2), 16);
  return out;
}

export const CREDENTIAL_BYTES = 16;

export function credentialToText(raw) {
  const h = hex(raw).toUpperCase();
  const parts = [];
  for (let i = 0; i < h.length; i += 4) parts.push(h.slice(i, i + 4));
  return parts.join("-");
}

export function credentialFromText(text) {
  const clean = (text || "").replace(/[^0-9a-fA-F]/g, "");
  if (clean.length !== CREDENTIAL_BYTES * 2) throw new Error("A credential has 32 hexadecimal digits");
  return fromHex(clean);
}

export function randomCredential() {
  const raw = new Uint8Array(CREDENTIAL_BYTES);
  crypto.getRandomValues(raw);
  return credentialToText(raw);
}

export class RingCtx {
  constructor(params) {
    this.n = params.N;
    this.primes = params.primes;
    this.L = params.primes.length;
    this.p = params.p;
    this.ntts = this.primes.map((q) => new NTT(this.n, q));
  }
  fromB64(text) {
    const raw = b64decode(text);
    const u = new Uint32Array(raw.buffer, raw.byteOffset, raw.length >> 2);
    const out = new Float64Array(this.L * this.n);
    for (let i = 0; i < out.length; i++) out[i] = u[i];
    return out;
  }
  toB64(a) {
    const u = new Uint32Array(a.length);
    for (let i = 0; i < a.length; i++) u[i] = a[i];
    return b64encode(new Uint8Array(u.buffer));
  }
  fwdSmall(x) {
    const n = this.n, out = new Float64Array(this.L * n);
    for (let i = 0; i < this.L; i++) {
      const q = this.primes[i], row = out.subarray(i * n, (i + 1) * n);
      for (let k = 0; k < n; k++) { const v = x[k] % q; row[k] = v < 0 ? v + q : v; }
      this.ntts[i].fwd(row);
    }
    return out;
  }
  fwd(a) {
    const n = this.n, out = Float64Array.from(a);
    for (let i = 0; i < this.L; i++) this.ntts[i].fwd(out.subarray(i * n, (i + 1) * n));
    return out;
  }
  inv(a) {
    const n = this.n, out = Float64Array.from(a);
    for (let i = 0; i < this.L; i++) this.ntts[i].inv(out.subarray(i * n, (i + 1) * n));
    return out;
  }
  constHat(c) {
    const x = new Float64Array(this.n);
    x[0] = c;
    return this.fwdSmall(x);
  }
}

export function ser(...arrays) {
  let total = 0;
  for (const a of arrays) total += a.length * 8;
  const out = new Uint8Array(total);
  let o = 0;
  for (const a of arrays) {
    for (let i = 0; i < a.length; i++) {
      const v = a[i];
      out[o] = v & 255; out[o + 1] = (v >>> 8) & 255; out[o + 2] = (v >>> 16) & 255; out[o + 3] = (v >>> 24) & 255;
      o += 8;
    }
  }
  return out;
}

function sparseMulAdd(dst, src, n, idx, sgn) {
  for (let t = 0; t < idx.length; t++) {
    const i = idx[t], s = sgn[t];
    for (let k = 0; k < i; k++) dst[k] -= s * src[n - i + k];
    for (let k = i; k < n; k++) dst[k] += s * src[k - i];
  }
}

export class BallotBuilder {
  constructor(pk) {
    this.pk = pk;
    this.R = new RingCtx(pk);
    this.slots = new Slots(pk.N, pk.p);
    this.uid = fromHex(pk.uid);
    const a = this.R.fromB64(pk.a), b = this.R.fromB64(pk.b);
    this.ahat = this.R.fwd(a);
    this.bhat = this.R.fwd(b);
    this.phat = this.R.constHat(pk.p);
    this.onehat = this.R.constHat(1);
    this.kappa = pk.kappa;
  }
  spread(bits, baseBlock) {
    const entries = [];
    bits.forEach((bit, b) => entries.push([(baseBlock + b) * this.pk.mmax, bit]));
    return entries;
  }
  encrypt(poly) {
    const R = this.R, n = R.n, L = R.L, p = this.pk.p;
    const r = ternaryRandom(n), e1 = ternaryRandom(n), e0 = ternaryRandom(n);
    const rhat = R.fwdSmall(r);
    const u = new Float64Array(L * n), w = new Float64Array(L * n);
    for (let i = 0; i < L; i++) {
      const q = R.primes[i], o = i * n;
      const ur = new Float64Array(n), wr = new Float64Array(n);
      for (let k = 0; k < n; k++) { ur[k] = (this.ahat[o + k] * rhat[o + k]) % q; wr[k] = (this.bhat[o + k] * rhat[o + k]) % q; }
      R.ntts[i].inv(ur);
      R.ntts[i].inv(wr);
      for (let k = 0; k < n; k++) {
        let x = (ur[k] + p * e1[k]) % q;
        u[o + k] = x < 0 ? x + q : x;
        let y = (wr[k] + p * e0[k] + poly[k]) % q;
        w[o + k] = y < 0 ? y + q : y;
      }
    }
    const f0 = new Float64Array(n);
    for (let k = 0; k < n; k++) f0[k] = poly[k] + p * e0[k];
    return { u, w, x: [r, e1, f0] };
  }
  prove(tag, ct, onAttempt) {
    const R = this.R, n = R.n, L = R.L;
    let maxabs = 0;
    for (const row of ct.x) for (let k = 0; k < n; k++) maxabs = Math.max(maxabs, Math.abs(row[k]));
    const sigma = 11 * (maxabs + 1) * Math.sqrt(this.kappa * 3 * n);
    const ctx = shake256(["OYLAMA/ballot", this.uid, tag, ser(ct.u, ct.w)]);
    const tu = R.fwd(ct.u), tw = R.fwd(ct.w);
    const serT = ser(tu, tw);
    for (let attempt = 0; attempt < 200; attempt++) {
      if (onAttempt) onAttempt(attempt + 1);
      const y = [gaussianRandom(n, sigma), gaussianRandom(n, sigma), gaussianRandom(n, sigma)];
      const Y = y.map((row) => R.fwdSmall(row));
      const W0 = new Float64Array(L * n), W1 = new Float64Array(L * n);
      for (let i = 0; i < L; i++) {
        const q = R.primes[i], o = i * n;
        for (let k = 0; k < n; k++) {
          const j = o + k;
          W0[j] = ((this.ahat[j] * Y[0][j]) % q + (this.phat[j] * Y[1][j]) % q) % q;
          W1[j] = ((this.bhat[j] * Y[0][j]) % q + (this.onehat[j] * Y[2][j]) % q) % q;
        }
      }
      const dseed = shake256([ctx, serT, ser(W0, W1), "chal"]);
      const d = challenge(dseed, "c", n, this.kappa);
      const z = [], dx = [];
      let dot = 0, nrm = 0, zmax = 0;
      for (let row = 0; row < 3; row++) {
        const acc = new Float64Array(n);
        sparseMulAdd(acc, ct.x[row], n, d.idx, d.sgn);
        const zr = new Float64Array(n);
        for (let k = 0; k < n; k++) {
          zr[k] = y[row][k] + acc[k];
          dot += zr[k] * acc[k];
          nrm += acc[k] * acc[k];
          zmax = Math.max(zmax, Math.abs(zr[k]));
        }
        z.push(zr);
        dx.push(acc);
      }
      const ex = Math.exp(Math.min(50, (-2 * dot + nrm) / (2 * sigma * sigma)));
      const coin = randomUint32(1)[0] / 4294967296;
      if (!(coin < Math.min(1, ex / 3))) continue;
      if (zmax > 6 * sigma) continue;
      return { dseed, z, sigma, attempts: attempt + 1 };
    }
    throw new Error("proof generation did not converge");
  }
  static zToB64(z) {
    const n = z[0].length;
    const buf = new ArrayBuffer(8 * 3 * n);
    const view = new DataView(buf);
    let o = 0;
    for (const row of z) for (let k = 0; k < n; k++) { view.setBigInt64(o, BigInt(row[k]), true); o += 8; }
    return b64encode(new Uint8Array(buf));
  }
  build(credentialText, choice, progress) {
    const pk = this.pk;
    const say = progress || (() => {});
    const credRaw = credentialFromText(credentialText);
    const credBits = [];
    for (let i = 0; i < pk.lam; i++) credBits.push((credRaw[i >> 3] >> (i % 8)) & 1);
    const voteBits = [];
    for (let t = 0; t < pk.vbits; t++) voteBits.push((choice >> t) & 1);
    const t0 = performance.now();
    say({ step: "encode", detail: `vote bits at slots ${voteBits.map((_, t) => (pk.keybits + t) * pk.mmax).join(", ")}; credential bits from slot ${pk.mbits * pk.mmax}` });
    const pv = this.slots.encodeSparse(this.spread(voteBits, pk.keybits));
    const pc = this.slots.encodeSparse(this.spread(credBits, pk.mbits));
    say({ step: "encrypt-vote" });
    const c1 = this.encrypt(pv);
    say({ step: "encrypt-credential" });
    const c2 = this.encrypt(pc);
    const t1 = performance.now();
    say({ step: "prove-vote" });
    const p1 = this.prove("v", c1, (a) => say({ step: "prove-vote", attempt: a }));
    say({ step: "prove-credential" });
    const p2 = this.prove("c", c2, (a) => say({ step: "prove-credential", attempt: a }));
    const t2 = performance.now();
    const R = this.R;
    const ballot = {
      c1: { u: R.toB64(c1.u), w: R.toB64(c1.w) },
      c2: { u: R.toB64(c2.u), w: R.toB64(c2.w) },
      proofs: [
        { d: b64encode(p1.dseed), z: BallotBuilder.zToB64(p1.z), sigma: p1.sigma },
        { d: b64encode(p2.dseed), z: BallotBuilder.zToB64(p2.z), sigma: p2.sigma }
      ]
    };
    const tracker = hex(shake256(["OYLAMA/tracker", this.uid, ser(c1.u, c1.w, c2.u, c2.w), p1.dseed, p2.dseed], 16));
    const bits = 4 * R.n * 120 + [p1, p2].reduce((s, p) => s + 3 * R.n * (Math.max(2, Math.ceil(Math.log2(6 * p.sigma)) + 1)) + 256, 0);
    return {
      ballot, tracker,
      stats: { encryptMs: Math.round(t1 - t0), proveMs: Math.round(t2 - t1), attempts: [p1.attempts, p2.attempts], sizeKb: Math.round(bits / 819.2) / 10 }
    };
  }
}

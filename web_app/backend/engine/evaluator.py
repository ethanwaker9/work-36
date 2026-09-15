import numpy as np

from .proofs import prove_preimage, sigma_for, verify_preimage
from .sample import ternary
from .util import ser
from .xof import shake256


def _row(R, elems, cols):
    row = np.zeros((cols, R.L, R.n), dtype=np.int64)
    for j, e in elems.items():
        row[j] = e
    return row


class Evaluator:
    def __init__(self, pbgv, params, dd, keys, quorum, pk, secret, public,
                 monitor=None, verify=True):
        self.E = pbgv
        self.pp = params
        self.dd = dd
        self.base_keys = keys
        self.quorum = list(quorum)
        self.pk = pk
        self.R = pbgv.R
        self.p = pbgv.p
        self.secret = secret
        self.public = public
        self.monitor = monitor
        self.verify = verify
        self.phase = "setup"
        self.stats = dict(opens=0, batches=0, mults=0, rotations=0, refreshes=0,
                          proof_bits=0, dec_bits=0, shares=0, shares_ok=0,
                          masks=0, masks_ok=0)
        self.digest = shake256(b"OYLAMA/transcript", public)
        self._eqcache = {}
        self._keycache = {}
        self._kc = {}

    def _emit(self, kind, **info):
        if self.monitor is not None:
            self.monitor(kind, self, info)

    def _keys(self, k):
        E = self.E
        if k not in self._keycache:
            pk_k = E.rot_pk(self.pk, k) if k else self.pk
            sh = {j: (E.rot_key(self.base_keys[j], k) if k else self.base_keys[j])
                  for j in self.quorum}
            self._keycache[k] = (pk_k, sh)
        return self._keycache[k]

    def keycom(self, j, k):
        if (j, k) not in self._kc:
            _, sh = self._keys(k)
            self._kc[(j, k)] = self.dd.commit_key(
                sh[j], shake256(self.secret, b"kc", j, k))
        return self._kc[(j, k)]

    def _eq_matrix(self, k):
        if k in self._eqcache:
            return self._eqcache[k]
        R, E = self.R, self.E
        a, b = self.pk
        ak, bk = E.rot_pk(self.pk, k) if k else self.pk
        one = R.zeros()
        one[:, 0] = 1
        pI = R.scalar(one, self.p)
        rows = [
            {1: ak, 2: pI},
            {0: one, 1: bk, 3: pI},
            {4: a, 5: pI},
            {0: one, 4: b, 6: pI},
        ]
        M = np.stack([_row(R, r, 7) for r in rows])
        Mh = np.stack([np.stack([R.fwd(M[i, j]) for j in range(7)])
                       for i in range(M.shape[0])])
        self._eqcache[k] = Mh
        return Mh

    def _mask_pair(self, k, seed, ctx):
        R, E = self.R, self.E
        gam = np.random.default_rng(int.from_bytes(seed, "little")).integers(
            0, self.p, R.n).astype(np.int64)
        gpoly = E.slots.encode(gam)
        pk_k = self._keys(k)[0]
        r1 = np.stack([ternary(shake256(seed, b"g", t), b"m", R.n) for t in range(3)])
        r2 = np.stack([ternary(shake256(seed, b"h", t), b"m", R.n) for t in range(3)])
        G = (R.add(R.mul(pk_k[0], R.from_small(r1[0])),
                   R.scalar(R.from_small(r1[1]), self.p)),
             R.add(R.mul(pk_k[1], R.from_small(r1[0])),
                   R.add(R.scalar(R.from_small(r1[2]), self.p),
                         R.from_small(gpoly))))
        H = (R.add(R.mul(self.pk[0], R.from_small(r2[0])),
                   R.scalar(R.from_small(r2[1]), self.p)),
             R.add(R.mul(self.pk[1], R.from_small(r2[0])),
                   R.add(R.scalar(R.from_small(r2[2]), self.p),
                         R.from_small(gpoly))))
        wit = np.stack([gpoly, r1[0], r1[1], r1[2], r2[0], r2[1], r2[2]])
        tgt = np.stack([R.fwd(G[0]), R.fwd(G[1]), R.fwd(H[0]), R.fwd(H[1])])
        Mh = self._eq_matrix(k)
        sig = sigma_for(self.pp.kappa, self.p // 2, 7 * R.n)
        pr = prove_preimage(R, Mh, wit, tgt, ctx, self.pp.kappa, sig,
                            rnd=shake256(seed, b"pr"))
        self.stats["masks"] += 1
        self.stats["proof_bits"] += pr.size_bits if pr else 0
        if self.verify:
            if verify_preimage(R, Mh, tgt, ctx, self.pp.kappa, sig, pr):
                self.stats["masks_ok"] += 1
        self.digest = shake256(self.digest, b"mask", ctx, pr.data[0] if pr else b"")
        return gam, G, H, pr

    def _open(self, cts, k, ctx, tag):
        E = self.E
        _, sh = self._keys(k)
        shares = []
        oks = []
        for j in self.quorum:
            kc, kr = self.keycom(j, k)
            dctx = shake256(ctx, tag, b"dc", j)
            s = self.dd.share(sh[j], kc, kr, cts, shake256(self.secret, ctx, tag, b"ds", j),
                              dctx, len(self.quorum))
            shares.append(s)
            self.stats["dec_bits"] += s.size_bits
            self.stats["shares"] += 1
            ok = True
            if self.verify:
                ok = self.dd.verify_share(kc, cts, s, dctx, len(self.quorum))
                if ok:
                    self.stats["shares_ok"] += 1
            oks.append(ok)
            self.digest = shake256(self.digest, b"share", j, dctx, ser(*s.data[0]))
        self.stats["opens"] += len(cts)
        self.stats["batches"] += 1
        polys = self.dd.combine(cts, shares)
        self._emit("open", count=len(cts), rotation=k, ok=all(oks))
        return [E.slots.decode(pl) for pl in polys]

    def refresh(self, cts, k, ctx, tag=b"rf"):
        E = self.E
        masked, Hs = [], []
        for i, c in enumerate(cts):
            acc = c
            hs = []
            for j in self.quorum:
                gam, G, H, pr = self._mask_pair(
                    k, shake256(self.secret, ctx, tag, i, j),
                    shake256(ctx, tag, b"p", i, j))
                acc = E.add(acc, G)
                hs.append(H)
            masked.append(acc)
            Hs.append(hs)
        opened = self._open(masked, k, ctx, tag)
        out = []
        for i, m in enumerate(opened):
            c = E.enc_slots(self.pk, shake256(self.secret, ctx, tag, b"re", i), m)
            for H in Hs[i]:
                c = E.sub(c, H)
            out.append(c)
        self.stats["refreshes"] += len(cts)
        return out

    def masked_mult_raw(self, Xs, Ys, rotk, ctx):
        E = self.E
        self.stats["mults"] += len(Xs)
        masked, alphas = [], []
        for i, c in enumerate(Xs):
            acc = c
            al = []
            for j in self.quorum:
                gam, G, H, pr = self._mask_pair(
                    0, shake256(self.secret, ctx, b"mm", i, j),
                    shake256(ctx, b"mmp", i, j))
                acc = E.add(acc, G)
                al.append(gam)
            masked.append(acc)
            alphas.append(al)
        opened = self._open(masked, 0, ctx, b"mo")
        Z = []
        for i in range(len(Xs)):
            z = E.mul_plain_slots(Ys[i], opened[i])
            for al in alphas[i]:
                z = E.sub(z, E.mul_plain_slots(Ys[i], al))
            Z.append(z)
        return self.refresh(Z, rotk, ctx, tag=b"mz")

    def rotate(self, cts, k, ctx):
        E = self.E
        self.stats["rotations"] += len(cts)
        rot = [E.rot_ct(c, k) for c in cts]
        return self.refresh(rot, k, ctx, tag=b"rot%d" % k)


class Flat:
    def __init__(self, eng, cts, size):
        self.eng = eng
        self.cts = cts
        self.size = size
        self.H = eng.E.n // 2

    @property
    def G(self):
        return len(self.cts)

    def add(self, other):
        E = self.eng.E
        return Flat(self.eng, [E.add(a, b) for a, b in zip(self.cts, other.cts)],
                    self.size)

    def sub(self, other):
        E = self.eng.E
        return Flat(self.eng, [E.sub(a, b) for a, b in zip(self.cts, other.cts)],
                    self.size)

    def mask(self, vec):
        E = self.eng.E
        H, n = self.H, E.n
        out = []
        for g, c in enumerate(self.cts):
            m = np.zeros(n, dtype=np.int64)
            seg = vec[g * H:(g + 1) * H]
            m[:len(seg)] = seg
            out.append(E.mul_plain_slots(c, m))
        return Flat(self.eng, out, self.size)

    def add_const(self, vec):
        E = self.eng.E
        H, n = self.H, E.n
        out = []
        for g, c in enumerate(self.cts):
            m = np.zeros(n, dtype=np.int64)
            seg = vec[g * H:(g + 1) * H]
            m[:len(seg)] = seg
            out.append(E.add_plain_slots(c, m))
        return Flat(self.eng, out, self.size)

    def neg(self):
        E = self.eng.E
        z = E.zero_ct()
        return Flat(self.eng, [E.sub(z, c) for c in self.cts], self.size)

    def scale(self, k):
        E = self.eng.E
        return Flat(self.eng, [E.scale(c, k) for c in self.cts], self.size)


def flat_fresh(fa, ctx):
    return Flat(fa.eng, fa.eng.refresh(fa.cts, 0, ctx), fa.size)


def flat_rot(fa, d, ctx):
    eng = fa.eng
    E = eng.E
    H, G = fa.H, fa.G
    k = d % H
    rot = eng.rotate(fa.cts, k, ctx) if k else eng.refresh(fa.cts, 0, ctx)
    if G == 1:
        return Flat(eng, rot, fa.size)
    lo = np.zeros(E.n, dtype=np.int64)
    lo[:H - k] = 1
    hi = np.zeros(E.n, dtype=np.int64)
    hi[H - k:H] = 1
    out = []
    shift = (d // H)
    for g in range(G):
        g1 = (g + shift) % G
        g2 = (g + shift + 1) % G
        a = E.mul_plain_slots(rot[g1], lo)
        b = E.mul_plain_slots(rot[g2], hi)
        out.append(E.add(a, b))
    return Flat(eng, out, fa.size)


def flat_mult_rot(X, Y, d, ctx):
    eng = X.eng
    E = eng.E
    H = X.H
    k = d % H
    if X.G == 1 and k:
        Yr = [E.rot_ct(c, k) for c in Y.cts]
        Z = eng.masked_mult_raw(X.cts, Yr, k, ctx)
        return Flat(eng, Z, X.size)
    Yr = flat_rot(Y, d, ctx + b"|yr") if d else Y
    Z = eng.masked_mult_raw(X.cts, Yr.cts, 0, ctx)
    return Flat(eng, Z, X.size)


def flat_mult(X, Y, ctx):
    return flat_mult_rot(X, Y, 0, ctx)

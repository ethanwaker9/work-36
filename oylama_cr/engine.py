import math

import numpy as np

from oylama.proofs import (Proof, matvec_hat, prove_preimage, sigma_for,
                           verify_preimage, _fwd_small)
from oylama.sample import ternary
from oylama.util import ser
from oylama.xof import shake256


def _row(R, elems, cols):
    row = np.zeros((cols, R.L, R.n), dtype=np.int64)
    for j, e in elems.items():
        row[j] = e
    return row


class Engine:
    def __init__(self, pbgv, params, dd, tk, quorum, asg, pk):
        self.E = pbgv
        self.pp = params
        self.dd = dd
        self.tk = tk
        self.quorum = quorum
        self.asg = asg
        self.pk = pk
        self.R = pbgv.R
        self.p = pbgv.p
        self.stats = dict(opens=0, mults=0, ct_ops=0, proof_bits=0, dec_bits=0)
        self._eqcache = {}

    def _keys(self, k):
        E = self.E
        pk_k = E.rot_pk(self.pk, k) if k else self.pk
        sh = {j: (E.rot_key(self.tk.party_key(j, self.asg), k) if k
                  else self.tk.party_key(j, self.asg)) for j in self.quorum}
        return pk_k, sh

    def _eq_matrix(self, k):
        if k in self._eqcache:
            return self._eqcache[k]
        R, E = self.R, self.E
        a, b = self.pk
        ak, bk = E.rot_pk(self.pk, k) if k else self.pk
        one = R.zeros()
        one[:, 0] = 1
        pI = R.scalar(one, self.p)
        z = R.zeros()
        cols = 7
        rows = [
            {1: ak, 2: pI},
            {0: one, 1: bk, 3: pI},
            {4: a, 5: pI},
            {0: one, 4: b, 6: pI},
        ]
        M = np.stack([_row(R, r, cols) for r in rows])
        Mh = np.stack([np.stack([R.fwd(M[i, j]) for j in range(cols)])
                       for i in range(M.shape[0])])
        self._eqcache[k] = Mh
        return Mh

    def _mask_pair(self, k, seed, ctx):
        R, E = self.R, self.E
        gam = np.random.default_rng(int.from_bytes(seed[:8], "little")).integers(
            0, self.p, R.n).astype(np.int64)
        gpoly = E.slots.encode(gam)
        pk_k = E.rot_pk(self.pk, k) if k else self.pk
        r1 = np.stack([ternary(shake256(seed, b"g", t), b"m", R.n)
                       for t in range(3)])
        r2 = np.stack([ternary(shake256(seed, b"h", t), b"m", R.n)
                       for t in range(3)])
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
        pr = prove_preimage(R, Mh, wit, tgt, ctx, self.pp.kappa, sig)
        self.stats["proof_bits"] += pr.size_bits if pr else 0
        return gam, G, H, pr

    def refresh(self, cts, k, ctx, tag=b"rf"):
        R, E = self.R, self.E
        pk_k, sh = self._keys(k)
        masked, Hs, gams = [], [], []
        for i, c in enumerate(cts):
            acc = c
            hs = []
            gs = []
            for jj, j in enumerate(self.quorum):
                gam, G, H, pr = self._mask_pair(
                    k, shake256(ctx, tag, i, j), shake256(ctx, tag, b"p", i, j))
                acc = E.add(acc, G)
                hs.append(H)
                gs.append(gam)
            masked.append(acc)
            Hs.append(hs)
            gams.append(gs)
        opened = self._open(masked, sh, ctx, tag)
        out = []
        for i, m in enumerate(opened):
            c = E.enc_slots(self.pk, shake256(ctx, tag, b"re", i), m)
            for H in Hs[i]:
                c = E.sub(c, H)
            out.append(c)
        self.stats["ct_ops"] += len(cts)
        return out

    def _open(self, cts, sh, ctx, tag):
        E, R = self.E, self.R
        shares = []
        for jj, j in enumerate(self.quorum):
            kc, kr = self.dd.commit_key(sh[j], shake256(ctx, tag, b"kc", j))
            s = self.dd.share(sh[j], kc, kr, cts, shake256(ctx, tag, b"ds", j),
                              shake256(ctx, tag, b"dc", j), len(self.quorum))
            shares.append(s)
            self.stats["dec_bits"] += s.size_bits
        self.stats["opens"] += len(cts)
        polys = self.dd.combine(cts, shares)
        return [E.slots.decode(pl) for pl in polys]

    def mult(self, Xs, Ys, ctx):
        E = self.E
        self.stats["mults"] += len(Xs)
        out = self.refresh_mask_mult(Xs, Ys, ctx)
        return out

    def masked_mult_raw(self, Xs, Ys, rotk, ctx):
        R, E = self.R, self.E
        self.stats["mults"] += len(Xs)
        pk_k, sh = self._keys(0)
        masked, alphas = [], []
        for i, c in enumerate(Xs):
            acc = c
            al = []
            for j in self.quorum:
                gam, G, H, pr = self._mask_pair(
                    0, shake256(ctx, b"mm", i, j), shake256(ctx, b"mmp", i, j))
                acc = E.add(acc, G)
                al.append(gam)
            masked.append(acc)
            alphas.append(al)
        opened = self._open(masked, sh, ctx, b"mo")
        Z = []
        for i in range(len(Xs)):
            z = E.mul_plain_slots(Ys[i], opened[i])
            for al in alphas[i]:
                z = E.sub(z, E.mul_plain_slots(Ys[i], al))
            Z.append(z)
        return self.refresh(Z, rotk, ctx, tag=b"mz")

    def refresh_mask_mult(self, Xs, Ys, ctx):
        R, E = self.R, self.E
        pk_k, sh = self._keys(0)
        masked, alphas = [], []
        for i, c in enumerate(Xs):
            acc = c
            al = []
            for j in self.quorum:
                gam, G, H, pr = self._mask_pair(
                    0, shake256(ctx, b"mm", i, j), shake256(ctx, b"mmp", i, j))
                acc = E.add(acc, G)
                al.append(gam)
            masked.append(acc)
            alphas.append(al)
        opened = self._open(masked, sh, ctx, b"mo")
        Z = []
        for i in range(len(Xs)):
            z = E.mul_plain_slots(Ys[i], opened[i])
            for al in alphas[i]:
                z = E.sub(z, E.mul_plain_slots(Ys[i], al))
            Z.append(z)
        return self.refresh(Z, 0, ctx, tag=b"mz")

    def rotate(self, cts, k, ctx):
        E = self.E
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
        E, R = self.eng.E, self.eng.R
        out = [(R.scalar(c[0], k % R.q), R.scalar(c[1], k % R.q))
               for c in self.cts]
        return Flat(self.eng, out, self.size)


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

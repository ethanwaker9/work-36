import numpy as np

from .proofs import (Proof, fwd_small, matvec_hat, prove_lin, prove_preimage_amortized,
                     sigma_for, verify_lin, verify_preimage_amortized)
from .sample import permutation, ternary
from .util import ser
from .xof import DS_SHUFFLE, DS_THETA, shake256


def ring_uniform(R, seed, tag):
    return R.fwd(R.uniform(shake256(seed, tag), DS_THETA))


class Shuffler:
    def __init__(self, params, ring, com, pk):
        self.pp = params
        self.R = ring
        self.com = com
        self.pk = pk
        R = ring
        a, b = pk
        one = R.zeros()
        one[:, 0] = 1
        k = com.k
        cols = k + 3
        AM = np.zeros((3, cols, R.L, R.n), dtype=np.int64)
        AM[0, :k] = com.B1[0]
        AM[1, :k] = com.B2[0]
        AM[2, :k] = com.B2[1]
        AM[1, k] = a
        AM[1, k + 1] = R.scalar(one, params.p)
        AM[2, k] = b
        AM[2, k + 2] = R.scalar(one, params.p)
        self.AMh = np.stack([np.stack([R.fwd(AM[i, j]) for j in range(cols)])
                             for i in range(3)])
        self.cols = cols
        self.kk = k

    def _fold(self, ts, cts, L, ctx):
        R, com, k = self.R, self.com, self.kk
        h = ring_uniform(R, shake256(ctx, b"fold", ser(*ts)), b"h")
        b2p = np.zeros((k, R.L, R.n), dtype=np.int64)
        for j in range(k):
            b2p[j] = (com.B2h[0, j] + h * com.B2h[1, j]) % R.qs
        cm1 = [ts[i][0:1] for i in range(len(cts))]
        cm2 = [((ts[i][1] + R.fwd(cts[i][0])
                 + h * (ts[i][2] + R.fwd(cts[i][1]))) % R.qs) for i in range(len(cts))]
        pub = [((L[i][0] + h * L[i][1]) % R.qs) for i in range(len(cts))]
        return h, b2p, cm1, cm2, pub

    def mix(self, cts, seed, ctx):
        R, pp, com = self.R, self.pp, self.com
        tau = len(cts)
        k, cols = self.kk, self.cols
        a, b = self.pk
        ah, bh = R.fwd(a), R.fwd(b)
        wits, ts, chat = [], [], []
        for i in range(tau):
            w = np.zeros((cols, R.n), dtype=np.int64)
            for j in range(cols):
                w[j] = ternary(shake256(seed, b"w", i, j), b"m", R.n)
            wh = fwd_small(R, w)
            t = matvec_hat(R, self.AMh, wh)
            up = (ah * wh[k] + pp.p * wh[k + 1]) % R.qs
            vp = (bh * wh[k] + pp.p * wh[k + 2]) % R.qs
            wits.append(w)
            ts.append(t)
            chat.append(((R.fwd(cts[i][0]) + up) % R.qs,
                         (R.fwd(cts[i][1]) + vp) % R.qs))
        perm = permutation(shake256(seed, b"perm"), DS_SHUFFLE, tau)
        L = [chat[perm[i]] for i in range(tau)]
        sig_b = sigma_for(pp.kappa, 1, cols * R.n) * np.sqrt(min(tau, pp.batch))
        bnd = []
        for st in range(0, tau, pp.batch):
            grp = list(range(st, min(tau, st + pp.batch)))
            pr = prove_preimage_amortized(
                R, self.AMh, [wits[i] for i in grp], [ts[i] for i in grp],
                shake256(ctx, b"bnd", st), pp.kappa, sig_b,
                rnd=shake256(seed, b"bndr", st))
            bnd.append((grp, pr))
        h, b2p, cm1, cm2, pub = self._fold(ts, cts, L, ctx)
        rvec = [wits[i][:k] for i in range(tau)]
        msgs = [((chat[i][0] + h * chat[i][1]) % R.qs) for i in range(tau)]
        shuf = prove_shuffle(R, com.B1h, b2p, cm1, cm2, rvec, msgs, pub,
                             shake256(ctx, b"shuf"), pp, shake256(seed, b"shufr"))
        out_cts = [(R.inv(L[i][0]), R.inv(L[i][1])) for i in range(tau)]
        size = (tau * 3 + tau * 2) * R.n * R.logq
        size += sum(p.size_bits for _, p in bnd if p is not None)
        size += shuf.size_bits
        return out_cts, Proof("mix", (ts, bnd, L, shuf), size)

    def verify(self, cts, out_cts, proof, ctx):
        R, pp = self.R, self.pp
        ts, bnd, L, shuf = proof.data
        tau = len(cts)
        if len(out_cts) != tau or len(ts) != tau or len(L) != tau:
            return False
        sig_b = sigma_for(pp.kappa, 1, self.cols * R.n) * np.sqrt(min(tau, pp.batch))
        covered = sorted(i for grp, _ in bnd for i in grp)
        if covered != list(range(tau)):
            return False
        for grp, pr in bnd:
            if not verify_preimage_amortized(R, self.AMh, [ts[i] for i in grp],
                                             shake256(ctx, b"bnd", grp[0]),
                                             pp.kappa, sig_b, pr):
                return False
        for i in range(tau):
            if not (np.array_equal(R.inv(L[i][0]), out_cts[i][0])
                    and np.array_equal(R.inv(L[i][1]), out_cts[i][1])):
                return False
        h, b2p, cm1, cm2, pub = self._fold(ts, cts, L, ctx)
        return verify_shuffle(R, self.com.B1h, b2p, cm1, cm2, pub,
                              shake256(ctx, b"shuf"), pp, shuf)


def _lin_terms(i, tau, beta, s, Mhat, R):
    if i == 1:
        return beta, (s[1] * Mhat[0]) % R.qs
    if i == tau:
        return s[tau - 1], ((1 if tau % 2 == 0 else -1) * beta * Mhat[tau - 1]) % R.qs
    return s[i - 1], (s[i] * Mhat[i - 1]) % R.qs


def prove_shuffle(R, B1h, b2h, cm1, cm2, rvec, msgs, pub, ctx, pp, rnd):
    tau = len(cm1)
    k = B1h.shape[1]
    rho = ring_uniform(R, shake256(ctx, b"rho", ser(*pub)), b"rho")
    Mc2 = [(cm2[i] - rho) % R.qs for i in range(tau)]
    Mval = [(msgs[i] - rho) % R.qs for i in range(tau)]
    Mhat = [(pub[i] - rho) % R.qs for i in range(tau)]
    Mhat_inv = [R.inv_hat(Mhat[i]) for i in range(tau)]
    theta = [None] + [ring_uniform(R, shake256(rnd, b"th", i), b"t")
                      for i in range(1, tau)]
    Dr, Dc1, Dc2 = [None] * (tau + 1), [None] * (tau + 1), [None] * (tau + 1)
    for i in range(1, tau + 1):
        if i == 1:
            Di = (theta[1] * Mhat[0]) % R.qs
        elif i == tau:
            Di = (theta[tau - 1] * Mval[tau - 1]) % R.qs
        else:
            Di = (theta[i - 1] * Mval[i - 1] + theta[i] * Mhat[i - 1]) % R.qs
        r = np.zeros((k, R.n), dtype=np.int64)
        for j in range(k):
            r[j] = ternary(shake256(rnd, b"dr", i, j), b"m", R.n)
        rh = fwd_small(R, r)
        c1 = matvec_hat(R, B1h, rh)
        c2 = np.zeros((R.L, R.n), dtype=np.int64)
        for j in range(k):
            c2 = (c2 + b2h[j] * rh[j]) % R.qs
        c2 = (c2 + Di) % R.qs
        Dr[i], Dc1[i], Dc2[i] = r, c1, c2
    beta = ring_uniform(R, shake256(ctx, b"beta", ser(*Dc2[1:])), b"b")
    s = [None] * tau
    acc = np.ones((R.L, R.n), dtype=np.int64)
    for j in range(1, tau):
        acc = (acc * Mval[j - 1]) % R.qs
        acc = (acc * Mhat_inv[j - 1]) % R.qs
        sgn = -1 if (j % 2) else 1
        s[j] = ((sgn * beta * acc) + theta[j]) % R.qs
    sig = sigma_for(pp.kappa, 1, k * R.n)
    lins = []
    for i in range(1, tau + 1):
        alpha, bconst = _lin_terms(i, tau, beta, s, Mhat, R)
        pr = prove_lin(R, B1h, b2h, alpha, bconst, cm1[i - 1],
                       np.stack([Mc2[i - 1]]), Dc1[i], np.stack([Dc2[i]]),
                       rvec[i - 1], Dr[i], shake256(ctx, b"lin", i),
                       pp.kappa, sig, rnd=shake256(rnd, b"lin", i))
        lins.append(pr)
    size = tau * 2 * R.n * R.logq + (tau - 1) * R.n * R.logq
    size += sum(p.size_bits for p in lins if p is not None)
    return Proof("shuffle", (Dc1, Dc2, s, lins), size)


def verify_shuffle(R, B1h, b2h, cm1, cm2, pub, ctx, pp, proof):
    if proof is None:
        return False
    Dc1, Dc2, s, lins = proof.data
    tau = len(cm1)
    k = B1h.shape[1]
    rho = ring_uniform(R, shake256(ctx, b"rho", ser(*pub)), b"rho")
    Mc2 = [(cm2[i] - rho) % R.qs for i in range(tau)]
    Mhat = [(pub[i] - rho) % R.qs for i in range(tau)]
    beta = ring_uniform(R, shake256(ctx, b"beta", ser(*Dc2[1:])), b"b")
    sig = sigma_for(pp.kappa, 1, k * R.n)
    for i in range(1, tau + 1):
        alpha, bconst = _lin_terms(i, tau, beta, s, Mhat, R)
        if not verify_lin(R, B1h, b2h, alpha, bconst, cm1[i - 1],
                          np.stack([Mc2[i - 1]]), Dc1[i], np.stack([Dc2[i]]),
                          shake256(ctx, b"lin", i), pp.kappa, sig, lins[i - 1]):
            return False
    return True

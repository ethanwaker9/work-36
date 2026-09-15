import numpy as np

from .proofs import (Proof, _fwd_small, matvec_batch, matvec_hat,
                     prove_lin_gen,
                     prove_preimage_amortized, sigma_for, verify_lin_gen,
                     verify_preimage_amortized)
from .sample import permutation, ternary
from .util import ser
from .xof import DS_SHUFFLE, DS_THETA, shake256


def _ring_uniform(R, seed, tag):
    return R.fwd(R.uniform(shake256(seed, tag), DS_THETA))


def _powers(R, h, m):
    out = np.zeros((m, R.L, R.n), dtype=np.int64)
    cur = R.zeros()
    cur[:, 0] = 1
    cur = R.fwd(cur)
    for i in range(m):
        out[i] = cur
        cur = (cur * h) % R.qs
    return out


class Shuffler:
    def __init__(self, params, ring, com, pk):
        self.pp = params
        self.R = ring
        self.com = com
        self.pk = pk
        R = ring
        Ah, th = pk
        k = params.rank
        m = params.m_ct
        ck, cn = com.k, com.n
        one = R.zeros()
        one[:, 0] = 1
        ph = R.fwd(R.scalar(one, params.p))
        AE = np.zeros((m, 2 * k + 1, R.L, R.n), dtype=np.int64)
        for i in range(k):
            for j in range(k):
                AE[i, j] = Ah[j, i]
            AE[i, k + i] = ph
        for j in range(k):
            AE[k, j] = th[j]
        AE[k, 2 * k] = ph
        self.AEh = AE
        self.cols = ck + 2 * k + 1
        self.ck = ck
        self.cn = cn
        self.m = m

    def _fold(self, hp, arr):
        R = self.R
        acc = np.zeros(arr.shape[1:], dtype=np.int64)
        for j in range(self.m):
            acc = (acc + hp[j] * arr[j]) % R.qs
        return acc

    def _foldA(self, hp):
        R, ck, cn = self.R, self.ck, self.cn
        AMf = np.zeros((cn + 1, self.cols, R.L, R.n), dtype=np.int64)
        AMf[:cn, :ck] = self.com.B1h
        AMf[cn, :ck] = self.com.B2h[0]
        AMf[cn, ck:] = self._fold(hp, self.AEh)
        return AMf

    def mix(self, cts, seed, ctx):
        R, pp, cn = self.R, self.pp, self.cn
        tau = len(cts)
        cthat = R.fwd_many(np.stack(cts))
        wits = np.stack([ternary(shake256(seed, b"w", i), b"m", R.n,
                                 count=self.cols) for i in range(tau)])
        whs = _fwd_small(R, wits)
        e0 = matvec_batch(R, self.AEh, whs[:, self.ck:])
        chat = (cthat + e0) % R.qs
        perm = permutation(shake256(seed, b"perm"), DS_SHUFFLE, tau)
        Lout = [chat[perm[i]] for i in range(tau)]
        chat = list(chat)
        h = _ring_uniform(R, shake256(ctx, b"fold", ser(*cts), ser(*Lout)),
                          b"h")
        hp = _powers(R, h, self.m)
        AMf = self._foldA(hp)
        b2p = self.com.B2h[0]
        ts = list(matvec_batch(R, AMf, whs))
        cm1 = [ts[i][:cn] for i in range(tau)]
        cm2 = [(ts[i][cn] + self._fold(hp, cthat[i])) % R.qs
               for i in range(tau)]
        pub = [self._fold(hp, Lout[i]) for i in range(tau)]
        msgs = [self._fold(hp, chat[i]) for i in range(tau)]
        rvec = [wits[i][:self.ck] for i in range(tau)]
        sig_b = sigma_for(pp.kappa, 1, self.cols * R.n) \
            * np.sqrt(min(tau, pp.batch))
        bnd = []
        for st in range(0, tau, pp.batch):
            grp = list(range(st, min(tau, st + pp.batch)))
            pr = prove_preimage_amortized(
                R, AMf, [wits[i] for i in grp], [ts[i] for i in grp],
                shake256(ctx, b"bnd", st), pp.kappa, sig_b)
            bnd.append((grp, pr))
        shuf = prove_shuffle(R, self.com.B1h, b2p, cm1, cm2, rvec, msgs, pub,
                             shake256(ctx, b"shuf"), pp)
        out_cts = list(R.inv_many(np.stack(Lout)))
        size = tau * (cn + 1 + self.m) * R.n * R.logq
        size += sum(p.size_bits for _, p in bnd if p is not None)
        size += shuf.size_bits
        return out_cts, Proof("mix", (ts, bnd, shuf), size)

    def verify(self, cts, out_cts, proof, ctx):
        R, pp, cn = self.R, self.pp, self.cn
        ts, bnd, shuf = proof.data
        tau = len(cts)
        cthat = R.fwd_many(np.stack(cts))
        Lout = list(R.fwd_many(np.stack(out_cts)))
        h = _ring_uniform(R, shake256(ctx, b"fold", ser(*cts), ser(*Lout)),
                          b"h")
        hp = _powers(R, h, self.m)
        AMf = self._foldA(hp)
        b2p = self.com.B2h[0]
        sig_b = sigma_for(pp.kappa, 1, self.cols * R.n) \
            * np.sqrt(min(tau, pp.batch))
        for grp, pr in bnd:
            if not verify_preimage_amortized(R, AMf, [ts[i] for i in grp],
                                             shake256(ctx, b"bnd", grp[0]),
                                             pp.kappa, sig_b, pr):
                return False
        cm1 = [ts[i][:cn] for i in range(tau)]
        cm2 = [(ts[i][cn] + self._fold(hp, cthat[i])) % R.qs
               for i in range(tau)]
        pub = [self._fold(hp, Lout[i]) for i in range(tau)]
        return verify_shuffle(R, self.com.B1h, b2p, cm1, cm2, pub,
                              shake256(ctx, b"shuf"), pp, shuf)


def prove_shuffle(R, B1h, b2h, cm1, cm2, rvec, msgs, pub, ctx, pp):
    tau = len(cm1)
    k = B1h.shape[1]
    rho = _ring_uniform(R, shake256(ctx, b"rho", ser(*pub)), b"rho")
    Mc2 = [(cm2[i] - rho) % R.qs for i in range(tau)]
    Mval = [(msgs[i] - rho) % R.qs for i in range(tau)]
    Mhat = [(pub[i] - rho) % R.qs for i in range(tau)]
    Mhat_inv = [R.inv_hat(Mhat[i]) for i in range(tau)]
    theta = [None] + [_ring_uniform(R, shake256(ctx, b"th", i), b"t")
                      for i in range(1, tau)]
    D = [None] * (tau + 1)
    Dr, Dc1, Dc2 = [None] * (tau + 1), [None] * (tau + 1), [None] * (tau + 1)
    Rr = np.stack([ternary(shake256(ctx, b"dr", i), b"m", R.n, count=k)
                   for i in range(1, tau + 1)])
    Rh = _fwd_small(R, Rr)
    C1all = matvec_batch(R, B1h, Rh)
    for i in range(1, tau + 1):
        if i == 1:
            Di = (theta[1] * Mhat[0]) % R.qs
        elif i == tau:
            Di = (theta[tau - 1] * Mval[tau - 1]) % R.qs
        else:
            Di = (theta[i - 1] * Mval[i - 1] + theta[i] * Mhat[i - 1]) % R.qs
        r = Rr[i - 1]
        rh = Rh[i - 1]
        c1 = C1all[i - 1]
        c2 = np.zeros((R.L, R.n), dtype=np.int64)
        for j in range(k):
            c2 = (c2 + b2h[j] * rh[j]) % R.qs
        c2 = (c2 + Di) % R.qs
        D[i], Dr[i], Dc1[i], Dc2[i] = Di, r, c1, c2
    beta = _ring_uniform(R, shake256(ctx, b"beta", ser(*Dc2[1:])), b"b")
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
        if i == 1:
            alpha, bconst = beta, (s[1] * Mhat[0]) % R.qs
        elif i == tau:
            alpha = s[tau - 1]
            bconst = ((1 if tau % 2 == 0 else -1) * beta * Mhat[tau - 1]) % R.qs
        else:
            alpha, bconst = s[i - 1], (s[i] * Mhat[i - 1]) % R.qs
        pr = prove_lin_gen(R, B1h, b2h[None], b2h, alpha[None], bconst,
                           cm1[i - 1], np.stack([Mc2[i - 1]]),
                           Dc1[i], np.stack([Dc2[i]]),
                           rvec[i - 1], Dr[i], shake256(ctx, b"lin", i),
                           pp.kappa, sig)
        lins.append(pr)
    size = tau * (B1h.shape[0] + 1) * R.n * R.logq + (tau - 1) * R.n * R.logq
    size += sum(p.size_bits for p in lins if p is not None)
    return Proof("shuffle", (Dc1, Dc2, s, lins), size)


def verify_shuffle(R, B1h, b2h, cm1, cm2, pub, ctx, pp, proof):
    if proof is None:
        return False
    Dc1, Dc2, s, lins = proof.data
    tau = len(cm1)
    k = B1h.shape[1]
    rho = _ring_uniform(R, shake256(ctx, b"rho", ser(*pub)), b"rho")
    Mc2 = [(cm2[i] - rho) % R.qs for i in range(tau)]
    Mhat = [(pub[i] - rho) % R.qs for i in range(tau)]
    beta = _ring_uniform(R, shake256(ctx, b"beta", ser(*Dc2[1:])), b"b")
    sig = sigma_for(pp.kappa, 1, k * R.n)
    for i in range(1, tau + 1):
        if i == 1:
            alpha, bconst = beta, (s[1] * Mhat[0]) % R.qs
        elif i == tau:
            alpha = s[tau - 1]
            bconst = ((1 if tau % 2 == 0 else -1) * beta * Mhat[tau - 1]) % R.qs
        else:
            alpha, bconst = s[i - 1], (s[i] * Mhat[i - 1]) % R.qs
        if not verify_lin_gen(R, B1h, b2h[None], b2h, alpha[None], bconst,
                              cm1[i - 1], np.stack([Mc2[i - 1]]),
                              Dc1[i], np.stack([Dc2[i]]),
                              shake256(ctx, b"lin", i), pp.kappa, sig,
                              lins[i - 1]):
            return False
    return True

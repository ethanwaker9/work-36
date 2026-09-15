import math
from dataclasses import dataclass, field

import numpy as np

from oylama.commit import BDLOP
from oylama.proofs import (Proof, bits_of, matvec_hat, prove_bnd_amortized,
                           prove_lin, prove_preimage,
                           prove_preimage_amortized, sigma_for, verify_bnd_amortized,
                           verify_lin, verify_preimage,
                           verify_preimage_amortized, _fwd_small)
from oylama.ring import find_ntt_primes
from oylama.rns import RnsRing
from oylama.sample import challenge, permutation, ternary
from oylama.util import chal_to_sparse, ser, sparse_mul
from oylama.xof import DS_A, DS_DDEC, DS_NOISE, DS_SHUFFLE, DS_THETA, shake256


@dataclass
class RingParams:
    N: int = 4096
    nprimes: int = 3
    prime_bits: int = 31
    p: int = 2
    kappa: int = 14
    sec: int = 40
    com_n: int = 1
    com_k: int = 3
    n_mix: int = 2
    n_trustee: int = 4
    threshold: int = 3
    batch: int = 32
    reject_M: float = 3.0
    primes: list = field(default_factory=list)

    def __post_init__(self):
        if not self.primes:
            self.primes = find_ntt_primes(self.N, self.prime_bits,
                                          self.nprimes)





class BGV:
    def __init__(self, params, ring=None):
        self.pp = params
        self.R = ring if ring is not None else RnsRing(params.N, params.primes)
        self.p = params.p

    def keygen(self, seed):
        R = self.R
        a = R.uniform(shake256(seed, b"a"), DS_A)
        s = R.from_small(ternary(shake256(seed, b"s"), DS_NOISE, R.n))
        e = R.from_small(ternary(shake256(seed, b"e"), DS_NOISE, R.n))
        b = R.add(R.mul(a, s), R.scalar(e, self.p))
        return (a, b), s

    def enc_parts(self, pk, seed, v_small):
        R, p = self.R, self.p
        a, b = pk
        r = R.from_small(ternary(seed, b"r", R.n))
        e1 = R.from_small(ternary(seed, b"e1", R.n))
        e0 = ternary(seed, b"e0", R.n)
        f0 = R.from_small(np.asarray(v_small, dtype=np.int64) + p * e0)
        u = R.add(R.mul(a, r), R.scalar(e1, p))
        w = R.add(R.mul(b, r), f0)
        return (u, w), (r, e1, f0)

    def enc(self, pk, seed, v_small):
        return self.enc_parts(pk, seed, v_small)[0]

    def enc_from_parts(self, pk, r, e1, f0):
        R = self.R
        a, b = pk
        u = R.add(R.mul(a, r), R.scalar(e1, self.p))
        w = R.add(R.mul(b, r), f0)
        return (u, w)

    def enc_zero(self, pk, seed):
        R, p = self.R, self.p
        a, b = pk
        r = R.from_small(ternary(seed, b"zr", R.n))
        e1 = R.from_small(ternary(seed, b"ze1", R.n))
        e2 = R.from_small(ternary(seed, b"ze2", R.n))
        u = R.add(R.mul(a, r), R.scalar(e1, p))
        w = R.add(R.mul(b, r), R.scalar(e2, p))
        return (u, w), (r, e1, e2)

    def add_ct(self, c1, c2):
        R = self.R
        return (R.add(c1[0], c2[0]), R.add(c1[1], c2[1]))

    def noise(self, s, c):
        R = self.R
        u, w = c
        return R.center(R.sub(w, R.mul(s, u)))

    def dec(self, s, c):
        z = self.noise(s, c)
        return np.array([int(x) % self.p for x in z], dtype=np.int64)


def replicated_shares(n_parties, threshold):
    from itertools import combinations
    return list(combinations(range(n_parties), threshold - 1))


class ThresholdKey:
    def __init__(self, params, ring, s, seed):
        self.pp = params
        self.R = ring
        self.parties = params.n_trustee
        self.t = params.threshold
        self.unqual = replicated_shares(self.parties, self.t)
        R = ring
        acc = R.zeros()
        self.shares = {}
        for idx, U in enumerate(self.unqual[:-1]):
            sh = R.uniform(shake256(seed, b"sh", idx), DS_NOISE)
            self.shares[U] = sh
            acc = R.add(acc, sh)
        self.shares[self.unqual[-1]] = R.sub(s, acc)
        self.holders = {}
        for j in range(self.parties):
            self.holders[j] = [U for U in self.unqual if j not in U]

    def party_key(self, j, assigned):
        R = self.R
        acc = R.zeros()
        for U in assigned[j]:
            acc = R.add(acc, self.shares[U])
        return acc

    def assign(self, quorum):
        assigned = {j: [] for j in quorum}
        taken = set()
        for U in self.unqual:
            for j in quorum:
                if j not in U and U not in taken:
                    assigned[j].append(U)
                    taken.add(U)
                    break
        if len(taken) != len(self.unqual):
            raise ValueError("quorum too small")
        return assigned





def _ring_uniform(R, seed, tag):
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
        zero = R.zeros()
        k = com.k
        rows = 1 + 2
        cols = k + 3
        AM = np.zeros((rows, cols, R.L, R.n), dtype=np.int64)
        AM[0, :k] = com.B1[0]
        AM[1, :k] = com.B2[0]
        AM[2, :k] = com.B2[1]
        AM[1, k] = a
        AM[1, k + 1] = R.scalar(one, params.p)
        AM[2, k] = b
        AM[2, k + 2] = R.scalar(one, params.p)
        self.AM = AM
        self.AMh = np.stack([np.stack([R.fwd(AM[i, j]) for j in range(cols)])
                             for i in range(rows)])
        self.cols = cols
        self.kk = k

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
            wh = _fwd_small(R, w)
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
                shake256(ctx, b"bnd", st), pp.kappa, sig_b)
            bnd.append((grp, pr))
        h = _ring_uniform(R, shake256(ctx, b"fold", ser(*ts)), b"h")
        b2p = np.zeros((k, R.L, R.n), dtype=np.int64)
        for j in range(k):
            b2p[j] = (com.B2h[0, j] + h * com.B2h[1, j]) % R.qs
        cm1 = [ts[i][0:1] for i in range(tau)]
        cm2 = [((ts[i][1] + R.fwd(cts[i][0])
                 + h * (ts[i][2] + R.fwd(cts[i][1]))) % R.qs) for i in range(tau)]
        pub = [((L[i][0] + h * L[i][1]) % R.qs) for i in range(tau)]
        rvec = [wits[i][:k] for i in range(tau)]
        msgs = [((chat[i][0] + h * chat[i][1]) % R.qs) for i in range(tau)]
        shuf = prove_shuffle(R, com.B1h, b2p, cm1, cm2, rvec, msgs, pub,
                             shake256(ctx, b"shuf"), pp)
        out_cts = [(R.inv(L[i][0]), R.inv(L[i][1])) for i in range(tau)]
        size = (tau * 3 + tau * 2) * R.n * R.logq
        size += sum(p.size_bits for _, p in bnd if p is not None)
        size += shuf.size_bits
        return out_cts, Proof("mix", (ts, bnd, L, shuf), size)

    def verify(self, cts, out_cts, proof, ctx):
        R, pp, com = self.R, self.pp, self.com
        ts, bnd, L, shuf = proof.data
        tau = len(cts)
        k = self.kk
        sig_b = sigma_for(pp.kappa, 1, self.cols * R.n) * np.sqrt(min(tau, pp.batch))
        for grp, pr in bnd:
            if not verify_preimage_amortized(R, self.AMh, [ts[i] for i in grp],
                                             shake256(ctx, b"bnd", grp[0]),
                                             pp.kappa, sig_b, pr):
                return False
        for i in range(tau):
            if not (np.array_equal(R.inv(L[i][0]), out_cts[i][0])
                    and np.array_equal(R.inv(L[i][1]), out_cts[i][1])):
                return False
        h = _ring_uniform(R, shake256(ctx, b"fold", ser(*ts)), b"h")
        b2p = np.zeros((k, R.L, R.n), dtype=np.int64)
        for j in range(k):
            b2p[j] = (com.B2h[0, j] + h * com.B2h[1, j]) % R.qs
        cm1 = [ts[i][0:1] for i in range(tau)]
        cm2 = [((ts[i][1] + R.fwd(cts[i][0])
                 + h * (ts[i][2] + R.fwd(cts[i][1]))) % R.qs) for i in range(tau)]
        pub = [((L[i][0] + h * L[i][1]) % R.qs) for i in range(tau)]
        return verify_shuffle(R, com.B1h, b2p, cm1, cm2, pub,
                              shake256(ctx, b"shuf"), pp, shuf)


def t_msg(t, row):
    return t[row]


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
    D, Dr, Dc1, Dc2 = [None] * (tau + 1), [None] * (tau + 1), [None] * (tau + 1), [None] * (tau + 1)
    for i in range(1, tau + 1):
        if i == 1:
            Di = (theta[1] * Mhat[0]) % R.qs
        elif i == tau:
            Di = (theta[tau - 1] * Mval[tau - 1]) % R.qs
        else:
            Di = (theta[i - 1] * Mval[i - 1] + theta[i] * Mhat[i - 1]) % R.qs
        r = np.zeros((k, R.n), dtype=np.int64)
        for j in range(k):
            r[j] = ternary(shake256(ctx, b"dr", i, j), b"m", R.n)
        rh = _fwd_small(R, r)
        c1 = matvec_hat(R, B1h, rh)
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
            alpha = beta
            bconst = (s[1] * Mhat[0]) % R.qs
        elif i == tau:
            alpha = s[tau - 1]
            bconst = ((1 if tau % 2 == 0 else -1) * beta * Mhat[tau - 1]) % R.qs
        else:
            alpha = s[i - 1]
            bconst = (s[i] * Mhat[i - 1]) % R.qs
        pr = prove_lin(R, B1h, b2h, alpha, bconst, cm1[i - 1],
                       np.stack([Mc2[i - 1]]), Dc1[i], np.stack([Dc2[i]]),
                       rvec[i - 1], Dr[i], shake256(ctx, b"lin", i),
                       pp.kappa, sig)
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
    rho = _ring_uniform(R, shake256(ctx, b"rho", ser(*pub)), b"rho")
    Mc2 = [(cm2[i] - rho) % R.qs for i in range(tau)]
    Mhat = [(pub[i] - rho) % R.qs for i in range(tau)]
    beta = _ring_uniform(R, shake256(ctx, b"beta", ser(*Dc2[1:])), b"b")
    sig = sigma_for(pp.kappa, 1, k * R.n)
    for i in range(1, tau + 1):
        if i == 1:
            alpha = beta
            bconst = (s[1] * Mhat[0]) % R.qs
        elif i == tau:
            alpha = s[tau - 1]
            bconst = ((1 if tau % 2 == 0 else -1) * beta * Mhat[tau - 1]) % R.qs
        else:
            alpha = s[i - 1]
            bconst = (s[i] * Mhat[i - 1]) % R.qs
        if not verify_lin(R, B1h, b2h, alpha, bconst, cm1[i - 1],
                          np.stack([Mc2[i - 1]]), Dc1[i], np.stack([Dc2[i]]),
                          shake256(ctx, b"lin", i), pp.kappa, sig, lins[i - 1]):
            return False
    return True






def bound_drown(sec, B_dec, p, nservers):
    return max(1, (1 << sec) * B_dec // (p * nservers))


class DistDec:
    def __init__(self, params, ring, com, pk, B_dec, batched=True):
        self.pp = params
        self.R = ring
        self.com = com
        self.pk = pk
        self.B_dec = B_dec
        self.batched = batched
        R = ring
        k = com.k
        one = R.zeros()
        one[:, 0] = 1
        AD = np.zeros((2, k + 1, R.L, R.n), dtype=np.int64)
        AD[0, :k] = com.B1[0]
        AD[1, :k] = com.B2[0]
        AD[1, k] = one
        self.AD = AD
        self.ADh = np.stack([np.stack([R.fwd(AD[i, j]) for j in range(k + 1)])
                             for i in range(2)])
        self.pinv_h = R.inv_hat(R.fwd(R.scalar(one, params.p)))

    def commit_key(self, sj, seed):
        R, com = self.R, self.com
        r = np.stack([ternary(shake256(seed, b"kr", j), b"m", R.n)
                      for j in range(com.k)])
        rh = _fwd_small(R, r)
        c1 = matvec_hat(R, com.B1h, rh)
        c2 = matvec_hat(R, com.B2h, rh)
        c2[0] = (c2[0] + R.fwd(sj)) % R.qs
        return (c1, c2), r

    def share(self, sj, keycom, keyrand, cts, seed, ctx, nservers):
        R, pp, com = self.R, self.pp, self.com
        k = com.k
        BE = bound_drown(pp.sec, self.B_dec, pp.p, nservers)
        sig_E = 11.0 * math.sqrt(pp.kappa) * BE * math.sqrt((k + 1) * R.n) \
            * math.sqrt(min(len(cts), pp.batch))
        sig_lin = sigma_for(pp.kappa, 1, k * R.n)
        sjh = R.fwd(sj)
        ts, Ecoms, Erands, lins, wit = [], [], [], [], []
        uh_all = []
        for i, c in enumerate(cts):
            uh = R.fwd(c[0])
            E = R.uniform(shake256(seed, b"E", i), DS_DDEC)
            Ec = R.center(E)
            Eb = np.array([int(v) % (2 * BE + 1) - BE for v in Ec], dtype=object)
            Er = R.from_int_poly(Eb)
            t = (sjh * uh + pp.p * R.fwd(Er)) % R.qs
            r = np.stack([ternary(shake256(seed, b"Er", i, j), b"m", R.n)
                          for j in range(k)])
            rh = _fwd_small(R, r)
            c1 = matvec_hat(R, com.B1h, rh)
            c2 = matvec_hat(R, com.B2h, rh)
            c2[0] = (c2[0] + R.fwd(Er)) % R.qs
            ts.append(t)
            uh_all.append(uh)
            Ecoms.append((c1, c2))
            Erands.append(r)
            wit.append(np.concatenate([np.stack([R.from_small(r[j])
                                                 for j in range(k)]),
                                       Er[None, :, :]], axis=0))
        step = pp.batch if self.batched else 1
        for st in range(0, len(cts), step):
            grp = list(range(st, min(len(cts), st + step)))
            chi = [challenge(shake256(ctx, b"chi", st, i), b"c", R.n, pp.kappa)
                   for i in grp]
            chih = [R.fwd(R.from_small(c)) for c in chi]
            C1 = np.zeros_like(Ecoms[grp[0]][0])
            C2 = np.zeros_like(Ecoms[grp[0]][1])
            U = np.zeros((R.L, R.n), dtype=np.int64)
            T = np.zeros((R.L, R.n), dtype=np.int64)
            racc = np.zeros((k, R.n), dtype=np.int64)
            for pos, i in enumerate(grp):
                C1 = (C1 + chih[pos] * Ecoms[i][0]) % R.qs
                C2 = (C2 + chih[pos] * Ecoms[i][1]) % R.qs
                U = (U + chih[pos] * uh_all[i]) % R.qs
                T = (T + chih[pos] * ts[i]) % R.qs
                idx, sgn = chal_to_sparse(chi[pos])
                for jj in range(k):
                    racc[jj] += sparse_mul(idx, sgn, Erands[i][jj])
            alpha = (-self.pinv_h * U) % R.qs
            beta = (self.pinv_h * T) % R.qs
            bnd_inf = max(1, int(np.max(np.abs(racc))))
            sig_b = sigma_for(pp.kappa, bnd_inf, k * R.n)
            pr = prove_lin(R, com.B1h, com.B2h[0], alpha, beta,
                           keycom[0], keycom[1], C1, C2, keyrand, racc,
                           shake256(ctx, b"dlin", st), pp.kappa, sig_b)
            lins.append((grp, bnd_inf, pr))
        bnds = []
        for st in range(0, len(cts), pp.batch):
            grp = list(range(st, min(len(cts), st + pp.batch)))
            tgt = [np.stack([Ecoms[i][0][0], Ecoms[i][1][0]]) for i in grp]
            pr = prove_bnd_amortized(R, self.ADh, [wit[i] for i in grp], tgt,
                                     shake256(ctx, b"dbnd", st), pp.kappa, sig_E)
            bnds.append((grp, pr))
        size = len(cts) * (1 + 2) * R.n * R.logq
        size += sum(p.size_bits for _, _, p in lins if p is not None)
        size += sum(p.size_bits for _, p in bnds if p is not None)
        return Proof("ddec", (ts, Ecoms, lins, bnds), size)

    def verify_share(self, keycom, cts, share, ctx, nservers):
        R, pp, com = self.R, self.pp, self.com
        k = com.k
        ts, Ecoms, lins, bnds = share.data
        BE = bound_drown(pp.sec, self.B_dec, pp.p, nservers)
        sig_E = 11.0 * math.sqrt(pp.kappa) * BE * math.sqrt((k + 1) * R.n) \
            * math.sqrt(min(len(cts), pp.batch))
        sig_lin = sigma_for(pp.kappa, 1, k * R.n)
        for grp, bnd_inf, pr in lins:
            chi = [challenge(shake256(ctx, b"chi", grp[0], i), b"c", R.n,
                             pp.kappa) for i in grp]
            chih = [R.fwd(R.from_small(c)) for c in chi]
            C1 = np.zeros_like(Ecoms[grp[0]][0])
            C2 = np.zeros_like(Ecoms[grp[0]][1])
            U = np.zeros((R.L, R.n), dtype=np.int64)
            T = np.zeros((R.L, R.n), dtype=np.int64)
            for pos, i in enumerate(grp):
                C1 = (C1 + chih[pos] * Ecoms[i][0]) % R.qs
                C2 = (C2 + chih[pos] * Ecoms[i][1]) % R.qs
                U = (U + chih[pos] * R.fwd(cts[i][0])) % R.qs
                T = (T + chih[pos] * ts[i]) % R.qs
            alpha = (-self.pinv_h * U) % R.qs
            beta = (self.pinv_h * T) % R.qs
            sig_b = sigma_for(pp.kappa, bnd_inf, k * R.n)
            if not verify_lin(R, com.B1h, com.B2h[0], alpha, beta,
                              keycom[0], keycom[1], C1, C2,
                              shake256(ctx, b"dlin", grp[0]), pp.kappa,
                              sig_b, pr):
                return False
        for grp, pr in bnds:
            tgt = [np.stack([Ecoms[i][0][0], Ecoms[i][1][0]]) for i in grp]
            if not verify_bnd_amortized(R, self.ADh, tgt,
                                        shake256(ctx, b"dbnd", grp[0]),
                                        pp.kappa, sig_E, pr):
                return False
        return True

    def combine(self, cts, shares):
        R, pp = self.R, self.pp
        out = []
        for i, c in enumerate(cts):
            acc = R.fwd(c[1])
            for sh in shares:
                acc = (acc - sh.data[0][i]) % R.qs
            z = R.center(R.inv(acc))
            out.append(np.array([int(v) % pp.p for v in z], dtype=np.int64))
        return out

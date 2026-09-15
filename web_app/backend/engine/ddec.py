import math

import numpy as np

from .proofs import (Proof, fwd_small, matvec_hat, prove_bnd_amortized, prove_lin,
                     sigma_for, verify_bnd_amortized, verify_lin)
from .sample import challenge, ternary
from .util import chal_to_sparse, sparse_mul
from .xof import DS_DDEC, shake256


def bound_drown(sec, B_dec, p, nservers):
    return max(1, (1 << sec) * B_dec // (p * nservers))


class DistDec:
    def __init__(self, params, ring, com, pk, B_dec):
        self.pp = params
        self.R = ring
        self.com = com
        self.pk = pk
        self.B_dec = B_dec
        R = ring
        k = com.k
        one = R.zeros()
        one[:, 0] = 1
        AD = np.zeros((2, k + 1, R.L, R.n), dtype=np.int64)
        AD[0, :k] = com.B1[0]
        AD[1, :k] = com.B2[0]
        AD[1, k] = one
        self.ADh = np.stack([np.stack([R.fwd(AD[i, j]) for j in range(k + 1)])
                             for i in range(2)])
        self.pinv_h = R.inv_hat(R.fwd(R.scalar(one, params.p)))

    def commit_key(self, sj, seed):
        R, com = self.R, self.com
        r = np.stack([ternary(shake256(seed, b"kr", j), b"m", R.n)
                      for j in range(com.k)])
        rh = fwd_small(R, r)
        c1 = matvec_hat(R, com.B1h, rh)
        c2 = matvec_hat(R, com.B2h, rh)
        c2[0] = (c2[0] + R.fwd(sj)) % R.qs
        return (c1, c2), r

    def _sig_E(self, nservers, count):
        R, pp, k = self.R, self.pp, self.com.k
        BE = bound_drown(pp.sec, self.B_dec, pp.p, nservers)
        return BE, 11.0 * math.sqrt(pp.kappa) * BE * math.sqrt((k + 1) * R.n) \
            * math.sqrt(min(count, pp.batch))

    def share(self, sj, keycom, keyrand, cts, seed, ctx, nservers):
        R, pp, com = self.R, self.pp, self.com
        k = com.k
        BE, sig_E = self._sig_E(nservers, len(cts))
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
            rh = fwd_small(R, r)
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
        for st in range(0, len(cts), pp.batch):
            grp = list(range(st, min(len(cts), st + pp.batch)))
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
                           shake256(ctx, b"dlin", st), pp.kappa, sig_b,
                           rnd=shake256(seed, b"lr", st))
            lins.append((grp, bnd_inf, pr))
        bnds = []
        for st in range(0, len(cts), pp.batch):
            grp = list(range(st, min(len(cts), st + pp.batch)))
            tgt = [np.stack([Ecoms[i][0][0], Ecoms[i][1][0]]) for i in grp]
            pr = prove_bnd_amortized(R, self.ADh, [wit[i] for i in grp], tgt,
                                     shake256(ctx, b"dbnd", st), pp.kappa, sig_E,
                                     rnd=shake256(seed, b"br", st))
            bnds.append((grp, pr))
        size = len(cts) * (1 + 2) * R.n * R.logq
        size += sum(p.size_bits for _, _, p in lins if p is not None)
        size += sum(p.size_bits for _, p in bnds if p is not None)
        return Proof("ddec", (ts, Ecoms, lins, bnds), size)

    def verify_share(self, keycom, cts, share, ctx, nservers):
        R, pp, com = self.R, self.pp, self.com
        k = com.k
        ts, Ecoms, lins, bnds = share.data
        if len(ts) != len(cts) or len(Ecoms) != len(cts):
            return False
        _, sig_E = self._sig_E(nservers, len(cts))
        for grp, bnd_inf, pr in lins:
            if bnd_inf > pp.kappa * pp.batch:
                return False
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

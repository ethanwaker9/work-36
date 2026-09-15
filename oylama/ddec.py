import math

import numpy as np

from .proofs import (Proof, _fwd_small, matvec_hat, prove_bnd_amortized,
                     prove_lin_gen, sigma_for, verify_bnd_amortized,
                     verify_lin_gen)
from .sample import challenge, ternary
from .util import chal_to_sparse, ser, sparse_mul
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
        self._cache = {}
        R = ring
        one = R.zeros()
        one[:, 0] = 1
        self.pinv_h = R.inv_hat(R.fwd(R.scalar(one, params.p)))

    def _mkE(self, ell):
        from .commit import BDLOP
        if ell in self._cache:
            return self._cache[ell]
        R, pp = self.R, self.pp
        comE = BDLOP(R, n=pp.com_n, ell=ell, k=pp.com_n + ell + 2,
                     seed=b"oylama-drown")
        ck, cn = comE.k, comE.n
        one = R.zeros()
        one[:, 0] = 1
        oneh = R.fwd(one)
        oneh = R.fwd(one)
        AD = np.zeros((cn + ell, ck + ell, R.L, R.n), dtype=np.int64)
        AD[:cn, :ck] = comE.B1h
        AD[cn:, :ck] = comE.B2h
        for j in range(ell):
            AD[cn + j, ck + j] = oneh
        self._cache[ell] = (comE, AD)
        return comE, AD

    def sigma_E(self, comE, nservers):
        R, pp = self.R, self.pp
        BE = bound_drown(pp.sec, self.B_dec, pp.p, nservers)
        dim = (comE.k + comE.ell) * R.n
        return 11.0 * BE * math.sqrt(pp.kappa * dim)

    def commit_key(self, sj, seed):
        R, com = self.R, self.com
        r = ternary(shake256(seed, b"kr"), b"m", R.n, count=com.k)
        rh = _fwd_small(R, r)
        c1 = matvec_hat(R, com.B1h, rh)
        c2 = (matvec_hat(R, com.B2h, rh) + R.fwd_many(sj)) % R.qs
        return (c1, c2), r

    def _sample_E(self, seed, st, count, BE):
        R = self.R
        raw = R.uniform(shake256(seed, b"E", st), DS_DDEC, count=count)
        out = np.zeros((count, R.L, R.n), dtype=np.int64)
        big = np.empty((count, R.n), dtype=object)
        for i in range(count):
            c = R.center(raw[i] if count > 1 else raw)
            big[i] = np.array([int(v) % (2 * BE + 1) - BE for v in c],
                              dtype=object)
            out[i] = R.from_int_poly(big[i])
        return out, big

    def share(self, sj, keycom, keyrand, cts, seed, ctx, nservers):
        R, pp, com = self.R, self.pp, self.com
        ell = min(pp.batch, len(cts))
        comE, ADh = self._mkE(ell)
        k, ck = pp.rank, comE.k
        BE = bound_drown(pp.sec, self.B_dec, pp.p, nservers)
        sig_E = self.sigma_E(comE, nservers)
        sjh = R.fwd_many(sj)
        ts = [None] * len(cts)
        blocks = []
        for st in range(0, len(cts), ell):
            grp = list(range(st, min(len(cts), st + ell)))
            Er, _ = self._sample_E(seed, st, ell, BE)
            Erh = R.fwd_many(Er)
            rE = ternary(shake256(seed, b"rE", st), b"m", R.n, count=ck)
            rEh = _fwd_small(R, rE)
            c1E = matvec_hat(R, comE.B1h, rEh)
            c2E = (matvec_hat(R, comE.B2h, rEh) + Erh) % R.qs
            U = np.zeros((k, R.L, R.n), dtype=np.int64)
            T = np.zeros((R.L, R.n), dtype=np.int64)
            chih = np.zeros((ell, R.L, R.n), dtype=np.int64)
            chs = R.fwd_many(np.stack([cts[i][:k] for i in grp]))
            chall = _fwd_small(R, np.stack([
                challenge(shake256(ctx, b"chi", st, pos), b"c", R.n, pp.kappa)
                for pos in range(len(grp))]))
            for pos, i in enumerate(grp):
                ch = chs[pos]
                acc = np.zeros((R.L, R.n), dtype=np.int64)
                for l in range(k):
                    acc = (acc + sjh[l] * ch[l]) % R.qs
                ts[i] = (acc + pp.p * Erh[pos]) % R.qs
                chih[pos] = chall[pos]
                U = (U + chih[pos] * ch) % R.qs
                T = (T + chih[pos] * ts[i]) % R.qs
            alphas = (-self.pinv_h * U) % R.qs
            beta = (self.pinv_h * T) % R.qs
            sig_b = sigma_for(pp.kappa, 1, max(com.k, ck) * R.n)
            lin = prove_lin_gen(R, com.B1h, com.B2h, comE.B2h, alphas, beta,
                                keycom[0], keycom[1], c1E, c2E,
                                keyrand, rE, shake256(ctx, b"dlin", st),
                                pp.kappa, sig_b, B1b=comE.B1h, gammas=chih)
            wit = np.concatenate([np.stack([R.from_small(rE[j])
                                            for j in range(ck)]), Er], axis=0)
            tgt = np.concatenate([c1E, c2E], axis=0)
            bnd = prove_bnd_amortized(R, ADh, [wit], [tgt],
                                      shake256(ctx, b"dbnd", st), pp.kappa,
                                      sig_E)
            blocks.append((grp, (c1E, c2E), lin, bnd))
        size = len(cts) * R.n * R.logq
        size += len(blocks) * (comE.n + comE.ell) * R.n * R.logq
        size += sum(b[2].size_bits for b in blocks if b[2] is not None)
        size += sum(b[3].size_bits for b in blocks if b[3] is not None)
        return Proof("ddec", (ts, blocks), size)

    def verify_share(self, keycom, cts, share, ctx, nservers):
        R, pp, com = self.R, self.pp, self.com
        ell = min(pp.batch, len(cts))
        comE, ADh = self._mkE(ell)
        k, ck = pp.rank, comE.k
        ts, blocks = share.data
        sig_E = self.sigma_E(comE, nservers)
        for grp, (c1E, c2E), lin, bnd in blocks:
            st = grp[0]
            U = np.zeros((k, R.L, R.n), dtype=np.int64)
            T = np.zeros((R.L, R.n), dtype=np.int64)
            chih = np.zeros((ell, R.L, R.n), dtype=np.int64)
            chs = R.fwd_many(np.stack([cts[i][:k] for i in grp]))
            chall = _fwd_small(R, np.stack([
                challenge(shake256(ctx, b"chi", st, pos), b"c", R.n, pp.kappa)
                for pos in range(len(grp))]))
            for pos, i in enumerate(grp):
                chih[pos] = chall[pos]
                U = (U + chih[pos] * chs[pos]) % R.qs
                T = (T + chih[pos] * ts[i]) % R.qs
            alphas = (-self.pinv_h * U) % R.qs
            beta = (self.pinv_h * T) % R.qs
            sig_b = sigma_for(pp.kappa, 1, max(com.k, ck) * R.n)
            if not verify_lin_gen(R, com.B1h, com.B2h, comE.B2h, alphas, beta,
                                  keycom[0], keycom[1], c1E, c2E,
                                  shake256(ctx, b"dlin", st), pp.kappa,
                                  sig_b, lin, B1b=comE.B1h, gammas=chih):
                return False
            tgt = np.concatenate([c1E, c2E], axis=0)
            if not verify_bnd_amortized(R, ADh, [tgt],
                                        shake256(ctx, b"dbnd", st), pp.kappa,
                                        sig_E, bnd):
                return False
        return True

    def combine(self, cts, shares):
        R, pp = self.R, self.pp
        out = []
        for i, c in enumerate(cts):
            acc = R.fwd(c[pp.rank])
            for sh in shares:
                acc = (acc - sh.data[0][i]) % R.qs
            z = R.center(R.inv(acc))
            out.append(np.array([int(v) % pp.p for v in z], dtype=np.int64))
        return out

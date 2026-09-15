import numpy as np

from .proofs import _fwd_small, matvec_hat
from .rns import RnsRing
from .sample import ternary
from .xof import DS_A, DS_NOISE, shake256


class ModBGV:
    def __init__(self, params, ring=None):
        self.pp = params
        self.R = ring if ring is not None else RnsRing(params.N, params.primes)
        self.p = params.p
        self.k = params.rank
        self.m = params.rank + 1

    def expand_A(self, seed):
        R, k = self.R, self.k
        raw = R.uniform(seed, DS_A, count=k * k)
        return np.asarray(raw).reshape(k, k, R.L, R.n)

    def keygen(self, seed):
        R, k = self.R, self.k
        Ah = self.expand_A(shake256(seed, b"A"))
        sr = ternary(shake256(seed, b"s"), DS_NOISE, R.n, count=k)
        er = ternary(shake256(seed, b"e"), DS_NOISE, R.n, count=k)
        s = np.stack([R.from_small(sr[i]) for i in range(k)])
        e = np.stack([R.from_small(er[i]) for i in range(k)])
        sh = R.fwd_many(s)
        th = matvec_hat(R, Ah, sh)
        th = (th + self.p * R.fwd_many(e)) % R.qs
        return (Ah, th), s

    def ballot_matrix(self, pk):
        R, k, p = self.R, self.k, self.p
        Ah, th = pk
        one = R.zeros()
        one[:, 0] = 1
        oneh = R.fwd(one)
        ph = R.fwd(R.scalar(one, p))
        zero = np.zeros((R.L, R.n), dtype=np.int64)
        M = np.zeros((k + 1, 2 * k + 1, R.L, R.n), dtype=np.int64)
        for i in range(k):
            for j in range(k):
                M[i, j] = Ah[j, i]
            M[i, k + i] = ph
        for j in range(k):
            M[k, j] = th[j]
        M[k, 2 * k] = oneh
        return M

    def _from_wit(self, pk, x):
        R, k, p = self.R, self.k, self.p
        Ah, th = pk
        xh = _fwd_small(R, x)
        AT = np.ascontiguousarray(np.swapaxes(Ah, 0, 1))
        out = np.empty((k + 1, R.L, R.n), dtype=np.int64)
        out[:k] = matvec_hat(R, AT, xh[:k])
        out[k] = matvec_hat(R, th[None], xh[:k])[0]
        out[:k] = (out[:k] + p * xh[k:2 * k]) % R.qs
        out[k] = (out[k] + xh[2 * k]) % R.qs
        return R.inv_many(out)

    def enc_parts(self, pk, seed, v_small):
        R, k, p = self.R, self.k, self.p
        x = np.zeros((2 * k + 1, R.n), dtype=np.int64)
        x[:2 * k] = ternary(shake256(seed, b"re"), DS_NOISE, R.n, count=2 * k)
        e0 = ternary(shake256(seed, b"e0"), DS_NOISE, R.n)
        x[2 * k] = np.asarray(v_small, dtype=np.int64) + p * e0
        return self._from_wit(pk, x), x

    def enc(self, pk, seed, v_small):
        return self.enc_parts(pk, seed, v_small)[0]

    def enc_from_wit(self, pk, x):
        return self._from_wit(pk, x)

    def add_ct(self, c1, c2):
        return (c1 + c2) % self.R.qs

    def phase(self, s, c):
        R, k = self.R, self.k
        sh, ch = R.fwd_many(s), R.fwd_many(c[:k])
        acc = np.zeros((R.L, R.n), dtype=np.int64)
        for i in range(k):
            acc = (acc + sh[i] * ch[i]) % R.qs
        return R.center(R.sub(c[k], R.inv(acc)))

    def phase_hat(self, sh, ch):
        R, k = self.R, self.k
        acc = np.zeros((R.L, R.n), dtype=np.int64)
        for i in range(k):
            acc = (acc + sh[i] * ch[i]) % R.qs
        return (ch[k] - acc) % R.qs

    def dec(self, s, c):
        z = self.phase(s, c)
        return np.array([int(v) % self.p for v in z], dtype=np.int64)


def replicated_shares(n_parties, threshold):
    from itertools import combinations
    return list(combinations(range(n_parties), threshold - 1))


class ThresholdKey:
    def __init__(self, params, ring, s, seed):
        self.pp = params
        self.R = ring
        self.k = params.rank
        self.parties = params.n_trustee
        self.t = params.threshold
        self.unqual = replicated_shares(self.parties, self.t)
        R = ring
        acc = np.zeros_like(s)
        self.shares = {}
        for idx, U in enumerate(self.unqual[:-1]):
            sh = np.stack([R.uniform(shake256(seed, b"sh", idx, j), b"u")
                           for j in range(self.k)])
            self.shares[U] = sh
            acc = (acc + sh) % R.qs
        self.shares[self.unqual[-1]] = (s - acc) % R.qs
        self.holders = {j: [U for U in self.unqual if j not in U]
                        for j in range(self.parties)}

    def party_key(self, j, assigned):
        R = self.R
        acc = np.zeros((self.k, R.L, R.n), dtype=np.int64)
        for U in assigned[j]:
            acc = (acc + self.shares[U]) % R.qs
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

import numpy as np

from .xof import DS_COM, shake256


class BDLOP:
    def __init__(self, ring, n=1, ell=1, k=3, seed=b"oylama-com"):
        self.R = ring
        self.n = n
        self.ell = ell
        self.k = k
        R = ring
        need = n * (k - n) + ell * (k - n - ell)
        rnd = R.uniform(shake256(seed, b"A", n, ell, k), DS_COM, count=max(1, need))
        rnd = np.asarray(rnd).reshape(-1, R.L, R.n)
        one = R.zeros()
        one[:, 0] = 1
        self.one = one
        c = 0
        B1 = np.zeros((n, k, R.L, R.n), dtype=np.int64)
        for i in range(n):
            B1[i, i] = one
            for j in range(n, k):
                B1[i, j] = rnd[c]
                c += 1
        B2 = np.zeros((ell, k, R.L, R.n), dtype=np.int64)
        for i in range(ell):
            B2[i, n + i] = one
            for j in range(n + ell, k):
                B2[i, j] = rnd[c]
                c += 1
        self.B1, self.B2 = B1, B2
        self.B1h = np.stack([np.stack([R.fwd(B1[i, j]) for j in range(k)])
                             for i in range(n)])
        self.B2h = np.stack([np.stack([R.fwd(B2[i, j]) for j in range(k)])
                             for i in range(ell)])

    def _mv(self, Mh, xh):
        R = self.R
        from .proofs import matvec_hat
        return matvec_hat(R, Mh, xh)

    def commit_hat(self, msgs_hat, rh):
        c1 = self._mv(self.B1h, rh)
        c2 = self._mv(self.B2h, rh)
        for i, m in enumerate(msgs_hat):
            c2[i] = (c2[i] + m) % self.R.qs
        return c1, c2

    def commit(self, msgs, r):
        R = self.R
        rh = np.stack([R.fwd(r[j] % R.qs) for j in range(self.k)])
        mh = [R.fwd(m) for m in msgs]
        c1h, c2h = self.commit_hat(mh, rh)
        return c1h, c2h, rh

    def fold_b2(self, rho_h):
        R = self.R
        out = np.zeros((self.k, R.L, R.n), dtype=np.int64)
        for i in range(self.ell):
            out = (out + rho_h[i] * self.B2h[i]) % R.qs
        return out

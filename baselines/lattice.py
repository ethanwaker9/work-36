import numpy as np

from .common import bits, sh

CBITS = 20
REPS = 7


class DenseSigma:
    def __init__(self, q, cbits=CBITS, reps=REPS):
        self.q = q
        self.cbits = cbits
        self.reps = reps

    def sigma(self, m, beta):
        return 11.0 * (1 << self.cbits) * beta * np.sqrt(2.0 * m / 3.0)

    def resp_bits(self, m, beta):
        return int(np.ceil(np.log2(12.0 * self.sigma(m, beta)))) + 1

    def size_bits(self, m, beta=1):
        return self.reps * m * self.resp_bits(m, beta) + 256

    def prove(self, A, x, t, ctx, beta=1):
        q, reps = self.q, self.reps
        m = A.shape[1]
        sg = self.sigma(m, beta)
        rng = np.random.default_rng(int.from_bytes(sh(ctx)[:8], "little"))
        x = np.asarray(x, dtype=np.int64)
        for att in range(12):
            Y = np.rint(rng.standard_normal((reps, m)) * sg).astype(np.int64)
            W = (A @ (Y.T % q)) % q
            dig = sh(ctx, t, W, att, outlen=4 * reps)
            c = np.array([int.from_bytes(dig[4 * i:4 * i + 4], "little")
                          % (1 << self.cbits) for i in range(reps)],
                         dtype=np.int64)
            CX = c[:, None] * x[None, :]
            Z = Y + CX
            num = float(np.sum(CX.astype(np.float64) ** 2))
            dot = float(np.sum(Z.astype(np.float64) * CX.astype(np.float64)))
            ex = np.exp(min(50.0, (-2.0 * dot + num) / (2.0 * sg * sg)))
            if rng.random() < min(1.0, ex / 3.0) \
                    and float(np.max(np.abs(Z))) <= 6.0 * sg:
                break
        return dict(Z=Z, c=c, att=att), self.size_bits(m, beta)

    def prove_lwe(self, A, r, e1, t, ctx, beta=1):
        q, reps = self.q, self.reps
        n, m = A.shape
        d = n + m
        sg = self.sigma(d, beta)
        rng = np.random.default_rng(int.from_bytes(sh(ctx)[:8], "little"))
        x = np.concatenate([np.asarray(r, dtype=np.int64),
                            np.asarray(e1, dtype=np.int64)])
        At = A.T
        for att in range(12):
            Y = np.rint(rng.standard_normal((reps, d)) * sg).astype(np.int64)
            W = (At @ (Y[:, :n].T % q) + (Y[:, n:].T % q)) % q
            dig = sh(ctx, t, W, att, outlen=4 * reps)
            c = np.array([int.from_bytes(dig[4 * i:4 * i + 4], "little")
                          % (1 << self.cbits) for i in range(reps)],
                         dtype=np.int64)
            CX = c[:, None] * x[None, :]
            Z = Y + CX
            num = float(np.sum(CX.astype(np.float64) ** 2))
            dot = float(np.sum(Z.astype(np.float64) * CX.astype(np.float64)))
            ex = np.exp(min(50.0, (-2.0 * dot + num) / (2.0 * sg * sg)))
            if rng.random() < min(1.0, ex / 3.0) \
                    and float(np.max(np.abs(Z))) <= 6.0 * sg:
                break
        return dict(Z=Z, c=c, att=att), self.size_bits(d, beta)

    def verify_lwe(self, A, t, ctx, pr, beta=1):
        q, reps = self.q, self.reps
        n, m = A.shape
        d = n + m
        Z, c = pr["Z"], pr["c"]
        if float(np.max(np.abs(Z))) > 6.0 * self.sigma(d, beta):
            return False
        tt = np.asarray(t, dtype=np.int64)
        W = (A.T @ (Z[:, :n].T % q) + (Z[:, n:].T % q)
             - tt[:, None] * c[None, :]) % q
        dig = sh(ctx, t, W, pr["att"], outlen=4 * reps)
        cc = np.array([int.from_bytes(dig[4 * i:4 * i + 4], "little")
                       % (1 << self.cbits) for i in range(reps)],
                      dtype=np.int64)
        return bool(np.array_equal(c, cc))

    def verify(self, A, t, ctx, pr, beta=1):
        q, reps = self.q, self.reps
        m = A.shape[1]
        Z, c = pr["Z"], pr["c"]
        if float(np.max(np.abs(Z))) > 6.0 * self.sigma(m, beta):
            return False
        tt = np.asarray(t, dtype=np.int64)
        W = (A @ (Z.T % q) - (tt[:, None] * c[None, :])) % q
        dig = sh(ctx, t, W, pr["att"], outlen=4 * reps)
        cc = np.array([int.from_bytes(dig[4 * i:4 * i + 4], "little")
                       % (1 << self.cbits) for i in range(reps)],
                      dtype=np.int64)
        return bool(np.array_equal(c, cc))


class DualRegev:
    def __init__(self, n=512, logq=20):
        self.n = n
        self.q = 1 << logq
        self.logq = logq
        self.m = n * logq
        rng = np.random.default_rng(7)
        self.A = rng.integers(0, self.q, (n, self.m), dtype=np.int64)
        self.sigma = DenseSigma(self.q)

    def id_secret(self, idb):
        rng = np.random.default_rng(int.from_bytes(sh(idb)[:8], "little"))
        return rng.integers(-1, 2, (self.m,), dtype=np.int64)

    def id_key(self, idb):
        return (self.A @ self.id_secret(idb)) % self.q

    def enc(self, u, mu, rng):
        r = rng.integers(-1, 2, (self.n,), dtype=np.int64)
        e1 = rng.integers(-1, 2, (self.m,), dtype=np.int64)
        e2 = int(rng.integers(-1, 2))
        c1 = (self.A.T @ r + e1) % self.q
        c2 = int((u @ r + e2 + (self.q // 2) * mu) % self.q)
        return c1, c2, (r, e1, e2)

    def ct_bits(self):
        return (self.m + 1) * self.logq

    def pk_bits(self):
        return 256 + self.m * self.logq


class LindnerPeikert:
    def __init__(self, n=512, q=12289):
        self.n = n
        self.q = q
        self.logq = bits(q)
        rng = np.random.default_rng(19)
        self.A = rng.integers(0, q, (n, n), dtype=np.int64)
        self.sigma = DenseSigma(q)

    def keygen(self, rng):
        s = rng.integers(-1, 2, (self.n,), dtype=np.int64)
        e = rng.integers(-1, 2, (self.n,), dtype=np.int64)
        return (self.A @ s + e) % self.q, s

    def enc(self, p, mu, rng):
        r = rng.integers(-1, 2, (self.n,), dtype=np.int64)
        e1 = rng.integers(-1, 2, (self.n,), dtype=np.int64)
        e2 = int(rng.integers(-1, 2))
        c1 = (self.A.T @ r + e1) % self.q
        c2 = int((p @ r + e2 + (self.q // 2) * mu) % self.q)
        return c1, c2, np.concatenate([r, e1])

    def ct_bits(self):
        return (self.n + 1) * self.logq

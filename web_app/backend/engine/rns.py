import numpy as np

from .ring import NTT
from .xof import XofReader


def pow_mod_vec(base, e, p):
    r = np.ones_like(base)
    b = base % p
    while e:
        if e & 1:
            r = (r * b) % p
        b = (b * b) % p
        e >>= 1
    return r


class RnsRing:
    def __init__(self, n, primes):
        self.n = n
        self.primes = list(primes)
        self.L = len(primes)
        self.qs = np.array(self.primes, dtype=np.int64).reshape(self.L, 1)
        self.ntts = [NTT(n, p) for p in self.primes]
        self.q = 1
        for p in self.primes:
            self.q *= p
        self.qmax = max(self.primes)
        self.lazy = int((1 << 62) // (self.qmax * self.qmax))
        self.zst = np.stack([self.ntts[i].zetas for i in range(self.L)])
        self.izst = np.stack([self.ntts[i].izetas for i in range(self.L)])
        self.ninv = np.array([self.ntts[i].ninv for i in range(self.L)],
                             dtype=np.int64).reshape(self.L, 1)
        self.logq = self.q.bit_length()
        self.qhat = [self.q // p for p in self.primes]
        self.qhat_inv = [pow(self.qhat[i] % self.primes[i], self.primes[i] - 2,
                             self.primes[i]) for i in range(self.L)]

    def zeros(self):
        return np.zeros((self.L, self.n), dtype=np.int64)

    def add(self, a, b):
        return (a + b) % self.qs

    def sub(self, a, b):
        return (a - b) % self.qs

    def neg(self, a):
        return (-a) % self.qs

    def fwd(self, a):
        return self.fwd_many(np.asarray(a, dtype=np.int64))

    def inv(self, a):
        return self.inv_many(np.asarray(a, dtype=np.int64))

    def fwd_many(self, a, reduced=False):
        n, L = self.n, self.L
        lead = a.shape[:-2]
        q = self.qs.reshape((1,) * len(lead) + (L, 1, 1))
        a = a if reduced else np.mod(a, self.qs)
        ln, m = n >> 1, 1
        while ln >= 1:
            b = a.reshape(*lead, L, m, 2 * ln)
            z = self.zst[:, m:2 * m].reshape((1,) * len(lead) + (L, m, 1))
            t = (b[..., ln:] * z) % q
            u = b[..., :ln].copy()
            hi = u + t
            hi -= q * (hi >= q)
            lo = u - t
            lo += q * (lo < 0)
            b[..., :ln] = hi
            b[..., ln:] = lo
            a = b.reshape(*lead, L, n)
            m <<= 1
            ln >>= 1
        return a

    def inv_many(self, a, reduced=False):
        n, L = self.n, self.L
        lead = a.shape[:-2]
        q = self.qs.reshape((1,) * len(lead) + (L, 1, 1))
        a = a if reduced else np.mod(a, self.qs)
        ln, m = 1, n >> 1
        while m >= 1:
            b = a.reshape(*lead, L, m, 2 * ln)
            z = self.izst[:, m:2 * m].reshape((1,) * len(lead) + (L, m, 1))
            u = b[..., :ln].copy()
            v = b[..., ln:].copy()
            hi = u + v
            hi -= q * (hi >= q)
            b[..., :ln] = hi
            b[..., ln:] = ((u - v) * z) % q
            a = b.reshape(*lead, L, n)
            m >>= 1
            ln <<= 1
        ni = self.ninv.reshape((1,) * len(lead) + (L, 1))
        return (a * ni) % self.qs

    def mul(self, a, b):
        return self.inv((self.fwd(a) * self.fwd(b)) % self.qs)

    def scalar(self, a, k):
        return (a * (k % self.q)) % self.qs

    def from_int_poly(self, v):
        v = np.asarray(v, dtype=object)
        return np.stack([np.array([int(x) % p for x in v], dtype=np.int64)
                         for p in self.primes])

    def from_small(self, v):
        v = np.asarray(v, dtype=np.int64)
        return np.stack([v % p for p in self.primes])

    def to_int(self, a):
        out = np.zeros(self.n, dtype=object)
        for i in range(self.L):
            t = (a[i].astype(object) * self.qhat_inv[i]) % self.primes[i]
            out = out + t * self.qhat[i]
        return out % self.q

    def center(self, a):
        x = self.to_int(a)
        h = self.q >> 1
        return np.array([int(v) - self.q if int(v) > h else int(v) for v in x],
                        dtype=object)

    def inv_hat(self, ah):
        out = np.empty_like(ah)
        for i, p in enumerate(self.primes):
            out[i] = pow_mod_vec(ah[i], p - 2, p)
        return out

    def uniform(self, seed, domain, count=1):
        need = self.n * count
        rd = XofReader(seed, domain)
        raw = rd.read(8 * need * self.L + 8)
        w = np.frombuffer(raw, dtype='<u8', count=need * self.L)
        w = (w >> np.uint64(1)).astype(np.int64).reshape(self.L, need)
        out = np.empty((self.L, need), dtype=np.int64)
        for i, p in enumerate(self.primes):
            out[i] = w[i] % p
        if count == 1:
            return out.reshape(self.L, self.n)
        return out.reshape(self.L, count, self.n).transpose(1, 0, 2)


def approx_center(R, a):
    frac = np.zeros(R.n, dtype=np.float64)
    for i in range(R.L):
        t = (a[i].astype(np.int64) * R.qhat_inv[i]) % R.primes[i]
        frac += t.astype(np.float64) / float(R.primes[i])
    frac = frac - np.floor(frac)
    frac = np.where(frac > 0.5, frac - 1.0, frac)
    return frac * float(R.q)

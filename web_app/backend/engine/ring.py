import numpy as np
from sympy import isprime


def find_ntt_primes(n, nbits, count, start_offset=0):
    step = 2 * n
    p = (1 << nbits) - ((1 << nbits) % step) + 1
    out = []
    skipped = 0
    while len(out) < count:
        if p > (1 << (nbits + 1)):
            raise ValueError("no prime found")
        if isprime(p):
            if skipped < start_offset:
                skipped += 1
            else:
                out.append(p)
        p += step
    return out


def brv(i, bits):
    r = 0
    for _ in range(bits):
        r = (r << 1) | (i & 1)
        i >>= 1
    return r


class NTT:
    def __init__(self, n, q):
        self.n = n
        self.q = q
        self.logn = n.bit_length() - 1
        psi = self._primitive_root_2n()
        self.psi = psi
        self.psi_inv = pow(psi, q - 2, q)
        self.zetas = np.array(
            [pow(psi, brv(i, self.logn), q) for i in range(n)], dtype=np.int64
        )
        self.izetas = np.array(
            [pow(self.psi_inv, brv(i, self.logn), q) for i in range(n)], dtype=np.int64
        )
        self.ninv = pow(n, q - 2, q)

    def _primitive_root_2n(self):
        q, n = self.q, self.n
        for g in range(2, 1 << 20):
            c = pow(g, (q - 1) // (2 * n), q)
            if pow(c, n, q) == q - 1:
                return c
        raise ValueError("no root")

    def fwd(self, a):
        q, n = self.q, self.n
        a = np.array(a % q, dtype=np.int64, copy=True)
        ln = n >> 1
        m = 1
        while ln >= 1:
            b = a.reshape(*a.shape[:-1], m, 2 * ln)
            z = self.zetas[m:2 * m].reshape(m, 1)
            t = (b[..., ln:] * z) % q
            u = b[..., :ln].copy()
            b[..., :ln] = (u + t) % q
            b[..., ln:] = (u - t) % q
            a = b.reshape(*a.shape[:-2], n) if b.ndim > 2 else b.reshape(n)
            m <<= 1
            ln >>= 1
        return a

    def inv(self, a):
        q, n = self.q, self.n
        a = np.array(a % q, dtype=np.int64, copy=True)
        ln = 1
        m = n >> 1
        while m >= 1:
            b = a.reshape(*a.shape[:-1], m, 2 * ln)
            z = self.izetas[m:2 * m].reshape(m, 1)
            u = b[..., :ln].copy()
            v = b[..., ln:].copy()
            b[..., :ln] = (u + v) % q
            b[..., ln:] = ((u - v) * z) % q
            a = b.reshape(*a.shape[:-2], n) if b.ndim > 2 else b.reshape(n)
            m >>= 1
            ln <<= 1
        return (a * self.ninv) % q

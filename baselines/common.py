import hashlib
import time

import numpy as np


def sh(*xs, outlen=32):
    h = hashlib.shake_256()
    for x in xs:
        if isinstance(x, int):
            x = x.to_bytes(8, "little", signed=True)
        elif isinstance(x, str):
            x = x.encode()
        elif not isinstance(x, (bytes, bytearray)):
            a = np.asarray(x)
            x = np.ascontiguousarray(a.astype(np.int64) % (1 << 60)).astype('<i8').tobytes()
        h.update(len(x).to_bytes(4, "little"))
        h.update(x)
    return h.digest(outlen)


class Timer:
    def __enter__(self):
        self.t = time.perf_counter()
        return self

    def __exit__(self, *a):
        self.dt = time.perf_counter() - self.t


def bits(x):
    return int(x).bit_length()


class MatLWE:
    def __init__(self, n, q, ell=1, seed=b"mlwe"):
        self.n = n
        self.q = q
        self.m = 2 * n
        self.ell = ell
        rng = np.random.default_rng(int.from_bytes(sh(seed)[:8], "little"))
        self.A = rng.integers(0, q, (self.n, self.m), dtype=np.int64)

    def keygen(self, rng):
        S = rng.integers(-1, 2, (self.n, self.ell), dtype=np.int64)
        E = rng.integers(-1, 2, (self.m, self.ell), dtype=np.int64)
        P = (self.A.T @ S + E) % self.q
        return (self.A, P), S

    def enc(self, pk, rng, msg_bits):
        A, P = pk
        r = rng.integers(-1, 2, (self.m,), dtype=np.int64)
        e1 = rng.integers(-1, 2, (self.n,), dtype=np.int64)
        e2 = rng.integers(-1, 2, (self.ell,), dtype=np.int64)
        c1 = (A @ r + e1) % self.q
        c2 = (P.T @ r + e2 + (self.q // 2) * np.asarray(msg_bits)) % self.q
        return c1, c2

    def dec(self, sk, ct):
        c1, c2 = ct
        v = (c2 - sk.T @ c1) % self.q
        v = np.where(v > self.q // 2, v - self.q, v)
        return (np.abs(v) > self.q // 4).astype(np.int64)

    def ct_bits(self):
        return (self.n + self.ell) * bits(self.q)

    def pk_bits(self):
        return self.m * self.ell * bits(self.q) + 256


class NegRing:
    def __init__(self, n, q):
        self.n = n
        self.q = q

    def mul(self, a, b):
        n, q = self.n, self.q
        c = np.convolve(np.asarray(a, dtype=np.int64) % q,
                        np.asarray(b, dtype=np.int64))
        out = c[:n].copy()
        out[:len(c) - n] -= c[n:]
        return out % q

    def small(self, rng, w=None):
        if w is None:
            return rng.integers(-1, 2, self.n, dtype=np.int64)
        v = np.zeros(self.n, dtype=np.int64)
        idx = rng.choice(self.n, w, replace=False)
        v[idx] = rng.choice([-1, 1], w)
        return v

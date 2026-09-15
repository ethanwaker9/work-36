import numpy as np

from .ring import NTT, brv
from .rns import RnsRing
from .sample import ternary
from .xof import DS_A, DS_NOISE, shake256


class Slots:
    def __init__(self, n, p, gen=3):
        self.n = n
        self.p = p
        self.ntt = NTT(n, p)
        self.logn = n.bit_length() - 1
        self.half = n // 2
        pos = np.empty(n, dtype=np.int64)
        m = 2 * n
        for eps in (0, 1):
            sgn = 1 if eps == 0 else m - 1
            for k in range(self.half):
                e = (pow(gen, k, m) * sgn) % m
                pos[eps * self.half + k] = brv((e - 1) // 2, self.logn)
        self.pos = pos

    def encode(self, vec):
        v = np.asarray(vec, dtype=np.int64) % self.p
        w = np.zeros(self.n, dtype=np.int64)
        w[self.pos] = v
        return self.ntt.inv(w)

    def decode(self, poly):
        w = self.ntt.fwd(np.asarray(poly, dtype=np.int64) % self.p)
        return w[self.pos]


def autom(poly, g, n):
    idx = (np.arange(n) * g)
    sgn = np.where((idx // n) % 2 == 1, -1, 1)
    tgt = idx % n
    out = np.zeros(poly.shape, dtype=np.int64)
    np.add.at(out.T, tgt, (poly * sgn).T)
    return out


class PackedBGV:
    def __init__(self, n, primes, p, gen=3):
        self.R = RnsRing(n, primes)
        self.p = p
        self.n = n
        self.gen = gen
        self.slots = Slots(n, p, gen)

    def keygen(self, seed):
        R = self.R
        a = R.uniform(shake256(seed, b"a"), DS_A)
        s = R.from_small(ternary(shake256(seed, b"s"), DS_NOISE, R.n))
        e = R.from_small(ternary(shake256(seed, b"e"), DS_NOISE, R.n))
        b = R.add(R.mul(a, s), R.scalar(e, self.p))
        return (a, b), s

    def enc_parts(self, pk, seed, mpoly):
        R, p = self.R, self.p
        a, b = pk
        r = ternary(seed, b"r", R.n)
        e1 = ternary(seed, b"e1", R.n)
        e0 = ternary(seed, b"e0", R.n)
        u = R.add(R.mul(a, R.from_small(r)), R.scalar(R.from_small(e1), p))
        w = R.add(R.mul(b, R.from_small(r)), R.add(R.scalar(R.from_small(e0), p),
                                                   R.from_small(mpoly)))
        f0 = np.asarray(mpoly, dtype=np.int64) + p * e0
        return (u, w), np.stack([r, e1, f0]).astype(np.int64)

    def enc_poly(self, pk, seed, mpoly):
        return self.enc_parts(pk, seed, mpoly)[0]

    def enc_slots(self, pk, seed, vec):
        return self.enc_poly(pk, seed, self.slots.encode(vec))

    def dec_poly(self, s, c):
        R = self.R
        z = R.center(R.sub(c[1], R.mul(s, c[0])))
        return np.array([int(v) % self.p for v in z], dtype=np.int64)

    def dec_slots(self, s, c):
        return self.slots.decode(self.dec_poly(s, c))

    def add(self, c1, c2):
        R = self.R
        return (R.add(c1[0], c2[0]), R.add(c1[1], c2[1]))

    def sub(self, c1, c2):
        R = self.R
        return (R.sub(c1[0], c2[0]), R.sub(c1[1], c2[1]))

    def mul_plain_poly(self, c, mpoly):
        R = self.R
        mh = R.fwd(R.from_small(mpoly))
        return (R.inv((R.fwd(c[0]) * mh) % R.qs),
                R.inv((R.fwd(c[1]) * mh) % R.qs))

    def mul_plain_slots(self, c, vec):
        return self.mul_plain_poly(c, self.slots.encode(vec))

    def add_plain_slots(self, c, vec):
        R = self.R
        return (c[0], R.add(c[1], R.from_small(self.slots.encode(vec))))

    def scale(self, c, k):
        R = self.R
        return (R.scalar(c[0], k % R.q), R.scalar(c[1], k % R.q))

    def zero_ct(self):
        R = self.R
        return (R.zeros(), R.zeros())

    def rot_ct(self, c, k):
        g = pow(self.gen, k, 2 * self.n)
        return (autom(c[0], g, self.n) % self.R.qs,
                autom(c[1], g, self.n) % self.R.qs)

    def rot_key(self, s, k):
        g = pow(self.gen, k, 2 * self.n)
        return autom(s, g, self.n) % self.R.qs

    def rot_pk(self, pk, k):
        g = pow(self.gen, k, 2 * self.n)
        return (autom(pk[0], g, self.n) % self.R.qs,
                autom(pk[1], g, self.n) % self.R.qs)

import numpy as np

from .engine import Flat, flat_fresh, flat_mult, flat_rot


def batcher_stages(M):
    st = []
    p = 1
    while p < M:
        k = p
        while k >= 1:
            lefts = []
            for j in range(k % p, M - k, 2 * k):
                for i in range(min(k, M - j - k)):
                    if (i + j) // (2 * p) == (i + j + k) // (2 * p):
                        lefts.append(i + j)
            if lefts:
                st.append((k, lefts))
            k //= 2
        p *= 2
    return st


class Layout:
    def __init__(self, M, H, nblocks_min):
        self.M = M
        self.H = H
        B = 1
        while B < nblocks_min:
            B *= 2
        self.B = B
        unit = M * B
        self.unit = unit
        if unit <= H:
            self.G = 1
            self.copies = H // unit
            self.size = self.copies * unit
        else:
            self.G = (unit + H - 1) // H
            self.copies = 1
            self.size = self.G * H

    def _rep(self, base):
        v = np.zeros(self.size, dtype=np.int64)
        for c in range(self.copies):
            v[c * self.unit:(c + 1) * self.unit] = base
        return v

    def block_mask(self, blocks):
        base = np.zeros(self.unit, dtype=np.int64)
        for b in blocks:
            base[b * self.M:(b + 1) * self.M] = 1
        return self._rep(base)

    def elem_mask(self, blocks, elems):
        base = np.zeros(self.unit, dtype=np.int64)
        for b in blocks:
            for i in elems:
                base[b * self.M + i] = 1
        return self._rep(base)

    def spread(self, base):
        return self._rep(np.asarray(base, dtype=np.int64))

    def collapse(self, vec):
        return np.asarray(vec, dtype=np.int64)[:self.unit]


def one_minus(lay, X):
    return X.neg().add_const(np.ones(lay.size, dtype=np.int64))


def suffix_and(lay, X, ctx):
    S = flat_fresh(X, ctx + b"|f0")
    t = 1
    step = 0
    while t < lay.B:
        Sr = flat_rot(S, t * lay.M, ctx + b"|sa%d" % step)
        wrap = lay.block_mask([b for b in range(lay.B) if b + t >= lay.B])
        Sr = Sr.mask(1 - wrap).add_const(wrap)
        S = flat_mult(S, Sr, ctx + b"|sm%d" % step)
        t *= 2
        step += 1
    return S


def total_broadcast(lay, X, ctx):
    T = X
    t = 1
    step = 0
    while t < lay.B:
        T = T.add(flat_rot(T, t * lay.M, ctx + b"|tb%d" % step))
        t *= 2
        step += 1
    return T


def compare_swap_stage(lay, K, d, lefts, keybits, ctx):
    M = lay.M
    B = flat_rot(K, d, ctx + b"|B")
    AB = flat_mult(K, B, ctx + b"|AB")
    e = one_minus(lay, K.add(B).sub(AB.scale(2)))
    g = B.sub(AB)
    pad = 1 - lay.block_mask(range(keybits))
    e = e.mask(1 - pad).add_const(pad)
    S = suffix_and(lay, e, ctx + b"|suf")
    P = flat_rot(S, M, ctx + b"|P")
    u = flat_mult(g.mask(lay.block_mask(range(keybits))), P, ctx + b"|u")
    lt = total_broadcast(lay, u, ctx + b"|tb")
    swap = one_minus(lay, lt)
    D = flat_mult(swap, B.sub(K), ctx + b"|D")
    A2 = K.add(D)
    B2 = B.sub(D)
    am = lay.elem_mask(range(lay.B), lefts)
    rm = lay.elem_mask(range(lay.B), [i + d for i in lefts])
    back = flat_rot(B2.mask(am), lay.size - d, ctx + b"|bb")
    keep = 1 - am - rm
    out = A2.mask(am).add(back.mask(rm)).add(K.mask(keep))
    return flat_fresh(out, ctx + b"|fin")


def oblivious_sort(lay, K, keybits, ctx):
    for si, (d, lefts) in enumerate(batcher_stages(lay.M)):
        K = compare_swap_stage(lay, K, d, lefts, keybits,
                               ctx + b"|st%d" % si)
    return K


def plain_sort_reference(vals, M, B, keybits):
    keys = []
    for i in range(M):
        k = 0
        for b in range(keybits):
            k |= int(vals[b * M + i]) << b
        keys.append((k, i))
    order = sorted(range(M), key=lambda i: keys[i][0])
    out = np.zeros_like(vals)
    for pos, src in enumerate(order):
        for b in range(B):
            out[b * M + pos] = vals[b * M + src]
    return out

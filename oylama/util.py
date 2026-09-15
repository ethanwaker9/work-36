import math

import numpy as np


def ser(*arrs):
    out = []
    for a in arrs:
        if isinstance(a, (bytes, bytearray)):
            out.append(bytes(a))
        elif isinstance(a, int):
            out.append(a.to_bytes(8, "little", signed=True))
        else:
            out.append(np.ascontiguousarray(np.asarray(a) % (1 << 62)).astype('<i8').tobytes())
    return b"".join(out)


def bits_ring(n, logq):
    return n * logq


def bits_vec(n, k, bound):
    return n * k * max(1, int(math.ceil(math.log2(2 * bound + 1))))


def sparse_mul(idx, sgn, x):
    n = x.shape[-1]
    acc = np.zeros(x.shape, dtype=np.int64)
    for i, s in zip(idx, sgn):
        sh = np.concatenate([-x[..., n - i:], x[..., :n - i]], axis=-1) if i else x
        acc = acc + s * sh
    return acc


def chal_to_sparse(c):
    idx = np.nonzero(c)[0]
    return [int(i) for i in idx], [int(c[i]) for i in idx]


def rej_accept(z, cx, sigma, M, rng):
    dot = float(np.sum(z.astype(np.float64) * cx.astype(np.float64)))
    nrm = float(np.sum(cx.astype(np.float64) ** 2))
    ex = math.exp(min(50.0, (-2.0 * dot + nrm) / (2.0 * sigma * sigma)))
    return rng.random() < min(1.0, ex / M)


def l2(x):
    return math.sqrt(float(np.sum(x.astype(np.float64) ** 2)))

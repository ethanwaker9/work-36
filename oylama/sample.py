import numpy as np

from .xof import XofReader


def ternary(seed, domain, n, count=1):
    rd = XofReader(seed, domain)
    need = n * count
    out = np.empty(need, dtype=np.int64)
    got = 0
    while got < need:
        raw = rd.read(max(1024, need - got) * 2)
        b = np.frombuffer(raw, dtype=np.uint8).astype(np.int64) & 3
        b = b[b < 3] - 1
        take = min(need - got, b.shape[0])
        out[got:got + take] = b[:take]
        got += take
    return out.reshape(count, n) if count > 1 else out


def bounded(seed, domain, n, bound, count=1):
    rd = XofReader(seed, domain)
    m = 2 * bound + 1
    nb = max(1, (m.bit_length() + 7) // 8)
    lim = (1 << (8 * nb)) - ((1 << (8 * nb)) % m)
    need = n * count
    out = np.empty(need, dtype=object)
    got = 0
    while got < need:
        raw = rd.read(max(1024, need - got) * nb * 2)
        k = len(raw) // nb
        vals = [int.from_bytes(raw[i * nb:(i + 1) * nb], "little") for i in range(k)]
        vals = [v % m - bound for v in vals if v < lim]
        take = min(need - got, len(vals))
        out[got:got + take] = vals[:take]
        got += take
    return out.reshape(count, n) if count > 1 else out


def gaussian_int(seed, domain, n, sigma, count=1):
    rd = XofReader(seed, domain)
    need = n * count
    raw = rd.read(need * 8 + 64)
    u = np.frombuffer(raw[:need * 8], dtype='<u4').astype(np.float64)
    u = u.reshape(-1, 2)
    u1 = (u[:, 0] + 1.0) / (2.0 ** 32 + 2.0)
    u2 = u[:, 1] / (2.0 ** 32)
    z = np.sqrt(-2.0 * np.log(u1)) * np.cos(2.0 * np.pi * u2)
    z = np.rint(z * sigma).astype(np.int64)
    z = z[:need]
    if z.shape[0] < need:
        z = np.concatenate([z, np.zeros(need - z.shape[0], dtype=np.int64)])
    return z.reshape(count, n) if count > 1 else z


def challenge(seed, domain, n, weight):
    rd = XofReader(seed, domain)
    c = np.zeros(n, dtype=np.int64)
    placed = 0
    while placed < weight:
        raw = rd.read(8)
        idx = int.from_bytes(raw[:4], "little") % n
        sgn = 1 if raw[4] & 1 else -1
        if c[idx] == 0:
            c[idx] = sgn
            placed += 1
    return c


def permutation(seed, domain, m):
    rd = XofReader(seed, domain)
    perm = list(range(m))
    for i in range(m - 1, 0, -1):
        j = int.from_bytes(rd.read(8), "little") % (i + 1)
        perm[i], perm[j] = perm[j], perm[i]
    return perm

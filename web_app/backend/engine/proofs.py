import math
import os

import numpy as np

from .rns import approx_center
from .sample import challenge, gaussian_int
from .util import chal_to_sparse, rej_accept, ser, sparse_mul
from .xof import XofReader, shake256

_RNG = np.random.default_rng()

REJ_ALPHA = 11.0
REJ_M = 3.0


def sigma_for(kappa, beta_inf, dim):
    return REJ_ALPHA * beta_inf * math.sqrt(kappa * dim)


def bits_of(sigma):
    return max(2, int(math.ceil(math.log2(6.0 * sigma))) + 1)


class Proof:
    def __init__(self, kind, data, size_bits):
        self.kind = kind
        self.data = data
        self.size_bits = size_bits


def _rnd(rnd):
    return rnd if rnd is not None else os.urandom(32)


def fwd_small(R, x):
    x = np.asarray(x, dtype=np.int64)
    xr = np.stack([x % p for p in R.primes], axis=-2)
    return R.fwd_many(xr, reduced=True)


def matvec_hat(R, Ah, xh):
    cols = Ah.shape[1]
    out = np.zeros((Ah.shape[0], R.L, R.n), dtype=np.int64)
    step = max(1, min(cols, R.lazy))
    for st in range(0, cols, step):
        acc = Ah[:, st] * xh[st]
        for j in range(st + 1, min(cols, st + step)):
            acc += Ah[:, j] * xh[j]
        out = (out + acc) % R.qs
    return out


def _dot_hat(R, bh, xh):
    cols = bh.shape[0]
    out = np.zeros((R.L, R.n), dtype=np.int64)
    step = max(1, min(cols, R.lazy))
    for st in range(0, cols, step):
        acc = bh[st] * xh[st]
        for j in range(st + 1, min(cols, st + step)):
            acc += bh[j] * xh[j]
        out = (out + acc) % R.qs
    return out


def prove_preimage(R, Ah, x, t, ctx, kappa, sigma, rnd=None, M=REJ_M, max_tries=60):
    k = x.shape[0]
    rnd = _rnd(rnd)
    for attempt in range(max_tries):
        y = gaussian_int(shake256(rnd, ctx, b"y", attempt), b"m", R.n, sigma, count=k)
        W = matvec_hat(R, Ah, fwd_small(R, y))
        dseed = shake256(ctx, ser(t), ser(W), b"chal")
        d = challenge(dseed, b"c", R.n, kappa)
        idx, sgn = chal_to_sparse(d)
        dx = sparse_mul(idx, sgn, x)
        z = y + dx
        if not rej_accept(z.reshape(-1), dx.reshape(-1), sigma, M, _RNG):
            continue
        if float(np.max(np.abs(z))) > 6.0 * sigma:
            continue
        size = k * R.n * bits_of(sigma) + 256
        return Proof("preimage", (dseed, z), size)
    return None


def verify_preimage(R, Ah, t, ctx, kappa, sigma, proof):
    if proof is None:
        return False
    dseed, z = proof.data
    if z.shape != (Ah.shape[1], R.n):
        return False
    if float(np.max(np.abs(z))) > 6.0 * sigma:
        return False
    d = challenge(dseed, b"c", R.n, kappa)
    dh = R.fwd(R.from_small(d))
    Az = matvec_hat(R, Ah, fwd_small(R, z))
    W = np.stack([(Az[i] - dh * t[i]) % R.qs for i in range(Az.shape[0])])
    return shake256(ctx, ser(t), ser(W), b"chal") == dseed


def prove_preimage_amortized(R, Ah, xs, ts, ctx, kappa, sigma, rnd=None, M=REJ_M,
                             max_tries=60):
    tau = len(xs)
    k = xs[0].shape[0]
    rnd = _rnd(rnd)
    for attempt in range(max_tries):
        y = gaussian_int(shake256(rnd, ctx, b"ay", attempt), b"m", R.n, sigma, count=k)
        W = matvec_hat(R, Ah, fwd_small(R, y))
        dseed = shake256(ctx, ser(*ts), ser(W), b"achal")
        acc = np.zeros((k, R.n), dtype=np.int64)
        for i in range(tau):
            d = challenge(shake256(dseed, b"i", i), b"c", R.n, kappa)
            idx, sgn = chal_to_sparse(d)
            acc += sparse_mul(idx, sgn, xs[i])
        z = y + acc
        if not rej_accept(z.reshape(-1), acc.reshape(-1), sigma, M, _RNG):
            continue
        if float(np.max(np.abs(z))) > 6.0 * sigma:
            continue
        size = k * R.n * bits_of(sigma) + 256
        return Proof("amortized", (dseed, z), size)
    return None


def verify_preimage_amortized(R, Ah, ts, ctx, kappa, sigma, proof):
    if proof is None:
        return False
    dseed, z = proof.data
    if float(np.max(np.abs(z))) > 6.0 * sigma:
        return False
    Az = matvec_hat(R, Ah, fwd_small(R, z))
    acc = np.zeros_like(Az)
    for i in range(len(ts)):
        d = challenge(shake256(dseed, b"i", i), b"c", R.n, kappa)
        dh = R.fwd(R.from_small(d))
        for row in range(Az.shape[0]):
            acc[row] = (acc[row] + dh * ts[i][row]) % R.qs
    W = np.stack([(Az[i] - acc[i]) % R.qs for i in range(Az.shape[0])])
    return shake256(ctx, ser(*ts), ser(W), b"achal") == dseed


def prove_lin(R, B1h, b2h, alpha_h, beta_h, c1, c2, c1p, c2p, r, rp, ctx,
              kappa, sigma, rnd=None, M=REJ_M, max_tries=60):
    k = B1h.shape[1]
    rnd = _rnd(rnd)
    for att in range(max_tries):
        yy = gaussian_int(shake256(rnd, ctx, b"ly", att), b"m", R.n, sigma, count=2 * k)
        y, yp = yy[:k], yy[k:]
        yyh = fwd_small(R, yy)
        yh, yph = yyh[:k], yyh[k:]
        t = matvec_hat(R, B1h, yh)
        tp = matvec_hat(R, B1h, yph)
        u = (alpha_h * _dot_hat(R, b2h, yh) - _dot_hat(R, b2h, yph)) % R.qs
        dseed = shake256(ctx, ser(c1, c2, c1p, c2p),
                         ser(np.stack([t[0], tp[0], u])), b"lin")
        d = challenge(dseed, b"c", R.n, kappa)
        idx, sgn = chal_to_sparse(d)
        dr = sparse_mul(idx, sgn, r)
        drp = sparse_mul(idx, sgn, rp)
        z, zp = y + dr, yp + drp
        if not rej_accept(np.concatenate([z.reshape(-1), zp.reshape(-1)]),
                          np.concatenate([dr.reshape(-1), drp.reshape(-1)]),
                          sigma, M, _RNG):
            continue
        if float(max(np.max(np.abs(z)), np.max(np.abs(zp)))) > 6.0 * sigma:
            continue
        return Proof("lin", (dseed, z, zp), 2 * k * R.n * bits_of(sigma) + 256)
    return None


def verify_lin(R, B1h, b2h, alpha_h, beta_h, c1, c2, c1p, c2p, ctx, kappa,
               sigma, proof):
    if proof is None:
        return False
    dseed, z, zp = proof.data
    if float(max(np.max(np.abs(z)), np.max(np.abs(zp)))) > 6.0 * sigma:
        return False
    d = challenge(dseed, b"c", R.n, kappa)
    dh = R.fwd(R.from_small(d))
    zzh = fwd_small(R, np.concatenate([z, zp]))
    zh, zph = zzh[:z.shape[0]], zzh[z.shape[0]:]
    t = (matvec_hat(R, B1h, zh) - dh * c1) % R.qs
    tp = (matvec_hat(R, B1h, zph) - dh * c1p) % R.qs
    rhs = (alpha_h * c2[0] + beta_h - c2p[0]) % R.qs
    u = (alpha_h * _dot_hat(R, b2h, zh) - _dot_hat(R, b2h, zph)
         - rhs * dh) % R.qs
    exp = shake256(ctx, ser(c1, c2, c1p, c2p),
                   ser(np.stack([t[0], tp[0], u])), b"lin")
    return exp == dseed


def gaussian_big_rns(R, seed, k, sigma):
    need = k * R.n
    rd = XofReader(seed, b"gbr")
    raw = np.frombuffer(rd.read(need * 16 + 8), dtype=np.uint64)[:2 * need]
    u1 = (raw[0::2] >> np.uint64(11)).astype(np.float64) / float(1 << 53)
    u2 = (raw[1::2] >> np.uint64(11)).astype(np.float64) / float(1 << 53)
    g = np.sqrt(-2.0 * np.log(np.clip(u1, 1e-300, None))) * np.cos(2.0 * np.pi * u2)
    t = max(0, int(math.floor(math.log2(max(sigma, 1.0)))) - 36)
    hi = np.rint(g * (sigma / float(1 << t))).astype(np.int64).reshape(k, R.n)
    lo = np.zeros((k, R.n), dtype=np.int64)
    if t:
        rawl = np.frombuffer(rd.read(need * 8 + 8), dtype=np.uint64)[:need]
        lo = (rawl % np.uint64(1 << t)).astype(np.int64).reshape(k, R.n)
    two_t = np.array([pow(2, t, p) for p in R.primes], dtype=np.int64)
    yr = np.zeros((k, R.L, R.n), dtype=np.int64)
    for i in range(R.L):
        yr[:, i, :] = (hi % R.primes[i] * two_t[i] + lo) % R.primes[i]
    return yr


def prove_bnd_amortized(R, Ah, xs_rns, ts, ctx, kappa, sigma, rnd=None, M=REJ_M,
                        max_tries=40):
    tau = len(xs_rns)
    k = xs_rns[0].shape[0]
    rnd = _rnd(rnd)
    for att in range(max_tries):
        yr = gaussian_big_rns(R, shake256(rnd, ctx, b"by", att), k, sigma)
        yh = R.fwd_many(yr)
        W = matvec_hat(R, Ah, yh)
        dseed = shake256(ctx, ser(*ts), ser(W), b"bchal")
        acc = np.zeros((k, R.L, R.n), dtype=np.int64)
        for i in range(tau):
            d = challenge(shake256(dseed, b"i", i), b"c", R.n, kappa)
            dh = R.fwd(R.from_small(d))
            acc = (acc + dh * R.fwd_many(xs_rns[i])) % R.qs
        accc = R.inv_many(acc)
        z = (yr + accc) % R.qs
        zf = np.concatenate([approx_center(R, z[j]) for j in range(k)])
        df = np.concatenate([approx_center(R, accc[j]) for j in range(k)])
        ex = math.exp(min(50.0, (-2.0 * float(np.dot(zf, df))
                                 + float(np.dot(df, df))) / (2.0 * sigma ** 2)))
        if _RNG.random() >= min(1.0, ex / M):
            continue
        if float(np.max(np.abs(zf))) > 6.0 * sigma:
            continue
        return Proof("bnd", (dseed, z), k * R.n * bits_of(sigma) + 256)
    return None


def verify_bnd_amortized(R, Ah, ts, ctx, kappa, sigma, proof):
    if proof is None:
        return False
    dseed, z = proof.data
    zf = np.concatenate([approx_center(R, z[j]) for j in range(z.shape[0])])
    if float(np.max(np.abs(zf))) > 6.0 * sigma:
        return False
    zh = R.fwd_many(z)
    Az = matvec_hat(R, Ah, zh)
    acc = np.zeros_like(Az)
    for i in range(len(ts)):
        d = challenge(shake256(dseed, b"i", i), b"c", R.n, kappa)
        dh = R.fwd(R.from_small(d))
        for row in range(Az.shape[0]):
            acc[row] = (acc[row] + dh * ts[i][row]) % R.qs
    W = np.stack([(Az[i] - acc[i]) % R.qs for i in range(Az.shape[0])])
    return shake256(ctx, ser(*ts), ser(W), b"bchal") == dseed

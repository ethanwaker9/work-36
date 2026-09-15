import numpy as np

from oylama.aead import kdf, seal, unseal
from oylama.commit import BDLOP
from oylama.pq import Kem, Sig

KEM_EK_BYTES = 1184
KEM_DK_BYTES = 2400
SIG_PK_BITS = 1952 * 8
from oylama.proofs import (Proof, bits_of, matvec_hat, prove_preimage,
                           sigma_for, verify_preimage, _fwd_small)
from oylama.ring import find_ntt_primes
from oylama.rns import RnsRing
from oylama.sample import ternary
from oylama.util import ser

from .abgs import BGV, DistDec, RingParams, Shuffler, ThresholdKey
from .common import MatLWE, NegRing, Timer, bits, sh
from .lattice import DenseSigma, DualRegev, LindnerPeikert

_SHARED = {}


def ring_of(N, nprimes, bitsz):
    key = (N, nprimes, bitsz)
    if key not in _SHARED:
        _SHARED[key] = RnsRing(N, find_ntt_primes(N, bitsz, nprimes))
    return _SHARED[key]


class RingCore:
    def __init__(self, N, nprimes, bitsz, p=2, kappa=14, nwit=3):
        self.R = ring_of(N, nprimes, bitsz)
        self.p = p
        self.kappa = kappa
        self.nwit = nwit
        R = self.R
        self.a = R.uniform(sh(b"a", N, bitsz), b"A")
        s = R.from_small(ternary(sh(b"s", N, bitsz), b"n", R.n))
        e = R.from_small(ternary(sh(b"e", N, bitsz), b"n", R.n))
        self.s = s
        self.b = R.add(R.mul(self.a, s), R.scalar(e, p))
        one = R.zeros()
        one[:, 0] = 1
        z = R.zeros()
        ah, bh = R.fwd(self.a), R.fwd(self.b)
        ph, oh, zh = R.fwd(R.scalar(one, p)), R.fwd(one), R.fwd(z)
        if nwit == 3:
            self.Ah = np.stack([np.stack([ah, ph, zh]),
                                np.stack([bh, zh, oh])])
        else:
            self.Ah = np.stack([np.stack([ah, ph, zh, zh]),
                                np.stack([bh, zh, ph, oh])])

    def enc(self, seed, m):
        R, p = self.R, self.p
        w = ternary(seed, b"w", R.n, count=3)
        r, e1, e0 = w[0], w[1], w[2]
        u = R.add(R.mul(self.a, R.from_small(r)),
                  R.scalar(R.from_small(e1), p))
        if self.nwit == 3:
            f0 = np.asarray(m, dtype=np.int64) + p * e0
            wv = R.add(R.mul(self.b, R.from_small(r)), R.from_small(f0))
            x = np.stack([r, e1, f0])
        else:
            wv = R.add(R.add(R.mul(self.b, R.from_small(r)),
                             R.scalar(R.from_small(e0), p)),
                       R.from_small(np.asarray(m, dtype=np.int64)))
            x = np.stack([r, e1, e0, np.asarray(m, dtype=np.int64)])
        return (u, wv), x

    def sig_of(self, x):
        return sigma_for(self.kappa, int(np.max(np.abs(x))) + 1,
                         self.nwit * self.R.n)

    def pok(self, ct, x, ctx):
        R = self.R
        t = np.stack([R.fwd(ct[0]), R.fwd(ct[1])])
        sg = self.sig_of(x)
        return prove_preimage(R, self.Ah, x, t, ctx, self.kappa, sg), sg

    def pok_verify(self, ct, pr, sg, ctx):
        R = self.R
        t = np.stack([R.fwd(ct[0]), R.fwd(ct[1])])
        return verify_preimage(R, self.Ah, t, ctx, self.kappa, sg, pr)

    def dec(self, ct):
        R = self.R
        z = R.center(R.sub(ct[1], R.mul(self.s, ct[0])))
        return np.array([int(v) % self.p for v in z], dtype=np.int64)

    def ct_bits(self):
        return 2 * self.R.n * self.R.logq

    def pok_bits(self, x):
        return self.nwit * self.R.n * bits_of(self.sig_of(x)) + 256


def abgs_engine(nmix=2, tag="abgs"):
    if tag not in _SHARED:
        pp = RingParams(n_mix=nmix)
        R = RnsRing(pp.N, pp.primes)
        E = BGV(pp, R)
        pk, sk = E.keygen(b"abgsbase")
        com1 = BDLOP(R, n=1, ell=1, k=3, seed=b"abgs1")
        com2 = BDLOP(R, n=1, ell=2, k=4, seed=b"abgs2")
        s1 = sigma_for(pp.kappa, 3, 3 * R.n)
        ball = 12 * s1 * (1 + 2 * pp.p * R.n)
        sb = sigma_for(pp.kappa, 1, (com2.k + 3) * R.n) * np.sqrt(pp.batch)
        B = int(ball + nmix * 12 * sb * 2 * pp.p * R.n)
        dd = DistDec(pp, R, com1, pk, B)
        tk = ThresholdKey(pp, R, sk, b"abgstk")
        quorum = list(range(pp.threshold))
        sfl = [Shuffler(pp, R, com2, pk) for _ in range(nmix)]
        asg = tk.assign(quorum)
        kc = {}
        for j in quorum:
            kc[j] = dd.commit_key(tk.party_key(j, asg), sh(tag, b"kc", j))
        _SHARED[tag] = dict(pp=pp, R=R, E=E, pk=pk, sk=sk, dd=dd, tk=tk,
                            quorum=quorum, asg=asg, sfl=sfl, kc=kc,
                            key_bits=len(quorum) * (com1.n + com1.ell)
                            * R.n * R.logq)
    return _SHARED[tag]


def abgs_mix(cts, tag):
    eng = abgs_engine(tag=tag.decode() if isinstance(tag, bytes) else tag)
    cur, nb = cts, 0
    for k, S in enumerate(eng["sfl"]):
        cur, pr = S.mix(cur, sh(tag, b"m", k), sh(tag, b"mc", k))
        nb += pr.size_bits
    return cur, nb, [(k, pr) for k in range(len(eng["sfl"]))]


def abgs_mix_verify(cts, tag):
    eng = abgs_engine(tag=tag.decode() if isinstance(tag, bytes) else tag)
    cur, nb = cts, 0
    outs = []
    for k, S in enumerate(eng["sfl"]):
        out, pr = S.mix(cur, sh(tag, b"m", k), sh(tag, b"mc", k))
        S.verify(cur, out, pr, sh(tag, b"mc", k))
        cur = out
    return True


def abgs_ddec(cts, tag):
    eng = abgs_engine(tag=tag.decode() if isinstance(tag, bytes) else tag)
    nb = 0
    shares = []
    for j in eng["quorum"]:
        sj = eng["tk"].party_key(j, eng["asg"])
        kc, kr = eng["kc"][j]
        share = eng["dd"].share(sj, kc, kr, cts, sh(tag, b"ds", j),
                                sh(tag, b"dc", j), len(eng["quorum"]))
        shares.append((kc, share))
        nb += share.size_bits
    return shares, nb


def abgs_ddec_verify(cts, shares, tag):
    eng = abgs_engine(tag=tag.decode() if isinstance(tag, bytes) else tag)
    for idx, j in enumerate(eng["quorum"]):
        kc, share = shares[idx]
        if not eng["dd"].verify_share(kc, cts, share, sh(tag, b"dc", j),
                                      len(eng["quorum"])):
            return False
    return True


class Scheme:
    name = "scheme"
    tag = "s"
    has_verify = True

    def setup(self, nv):
        return dict(time=0.0, bits=0)

    def vote(self, i, v):
        return None, dict(time=0.0, bits=0)

    def valid(self, b):
        return dict(time=0.0, ok=True)

    def tally(self, ballots):
        return [], dict(time=0.0, bits=0)

    def verify(self, ballots, res, pf):
        return dict(time=0.0, ok=True)


class Epoque(Scheme):
    name = "Epoque"
    tag = "epoque"

    def __init__(self, n_cands=2):
        self.n_cands = n_cands

    def setup(self, nv):
        with Timer() as tm:
            self.ibe = DualRegev(n=512, logq=20)
            self.com = BDLOP(ring_of(256, 1, 31), n=1, ell=1, k=3,
                             seed=b"epq")
            self.spk, self.ssk = Sig.keygen()
            rng = np.random.default_rng(5)
            self.msk = rng.integers(-1, 2, (self.ibe.m,), dtype=np.int64)
        return dict(time=tm.dt, bits=self.ibe.n * self.ibe.m * self.ibe.logq
                    + 256 + SIG_PK_BITS)

    def vote(self, i, v):
        with Timer() as tm:
            rng = np.random.default_rng(i + 1)
            u = self.ibe.id_key(str(i).encode())
            c1, c2, wit = self.ibe.enc(u, v & 1, rng)
            t = c1
            pr, pb = self.ibe.sigma.prove_lwe(self.ibe.A, wit[0], wit[1], t,
                                              sh(b"epq", i))
            sg = Sig.sign(self.ssk, sh(c1, c2))
        b = dict(c1=c1, c2=c2, pr=pr, t=t, sg=sg, id=i)
        return b, dict(time=tm.dt, bits=self.ibe.ct_bits() + pb + 8 * len(sg))

    def valid(self, b):
        with Timer() as tm:
            ok = self.ibe.sigma.verify_lwe(self.ibe.A, b["t"],
                                           sh(b"epq", b["id"]), b["pr"])
            ok = ok and Sig.verify(self.spk, sh(b["c1"], b["c2"]), b["sg"])
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        with Timer() as tm:
            counts = [0] * self.n_cands
            nb = 0
            for b in ballots:
                ek = self.ibe.id_secret(str(b["id"]).encode())
                d = int((b["c2"] - b["c1"] @ ek) % self.ibe.q)
                d = d - self.ibe.q if d > self.ibe.q // 2 else d
                counts[(1 if abs(d) > self.ibe.q // 4 else 0)
                       % self.n_cands] += 1
                tgt = (self.ibe.A @ ek) % self.ibe.q
                pr, pb = self.ibe.sigma.prove(self.ibe.A, ek, tgt,
                                              sh(b"epqd", b["id"]))
                nb += pb + self.ibe.logq
        return counts, dict(time=tm.dt, bits=nb)

    def verify(self, ballots, res, pf):
        with Timer() as tm:
            ok = True
            for b in ballots:
                ok = ok and self.valid(b)["ok"]
                ek = self.ibe.id_secret(str(b["id"]).encode())
                tgt = (self.ibe.A @ ek) % self.ibe.q
                pr, _ = self.ibe.sigma.prove(self.ibe.A, ek, tgt,
                                             sh(b"epqd", b["id"]))
                ok = ok and self.ibe.sigma.verify(self.ibe.A, tgt,
                                                  sh(b"epqd", b["id"]), pr)
        return dict(time=tm.dt, ok=ok)


class NtruMix(Scheme):
    name = "NTRU-Mix"
    tag = "ntru"

    def __init__(self, n_cands=2):
        self.n_cands = n_cands
        self.kappa = 14

    def setup(self, nv):
        with Timer() as tm:
            eng = abgs_engine(tag=self.tag)
            self.core = RingCore(eng["pp"].N, eng["pp"].nprimes,
                                 eng["pp"].prime_bits, p=2, kappa=self.kappa)
        R = self.core.R
        return dict(time=tm.dt, bits=R.n * R.logq + 256 + eng["key_bits"])

    def vote(self, i, v):
        with Timer() as tm:
            m = np.zeros(self.core.R.n, dtype=np.int64)
            m[0] = v & 1
            ct, x = self.core.enc(sh(b"ntru", i), m)
            pr, sg = self.core.pok(ct, x, sh(b"ntruc", i))
        return dict(ct=ct, pr=pr, sg=sg, id=i), \
            dict(time=tm.dt, bits=self.core.ct_bits() + self.core.pok_bits(x))

    def valid(self, b):
        with Timer() as tm:
            ok = self.core.pok_verify(b["ct"], b["pr"], b["sg"],
                                      sh(b"ntruc", b["id"]))
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        eng = abgs_engine(tag=self.tag)
        with Timer() as tm:
            cts = [eng["E"].enc(eng["pk"], sh(self.tag, b"c", i),
                                np.zeros(eng["R"].n, dtype=np.int64))
                   for i in range(len(ballots))]
            mixed, mb, _ = abgs_mix(cts, self.tag.encode())
            shares, db = abgs_ddec(mixed, self.tag.encode())
            counts = [0] * self.n_cands
            for b in ballots:
                counts[int(self.core.dec(b["ct"])[0]) % self.n_cands] += 1
            self._mixed = mixed
            self._shares = shares
        return counts, dict(time=tm.dt, bits=mb + db)

    def verify(self, ballots, res, pf):
        with Timer() as tm:
            ok = all(self.valid(b)["ok"] for b in ballots)
            ok = ok and abgs_ddec_verify(self._mixed, self._shares,
                                         self.tag.encode())
        return dict(time=tm.dt, ok=ok)


class FooBlind(Scheme):
    name = "FOO-based"
    tag = "foo"

    def __init__(self, n_cands=2, N=1024, logq=24):
        self.n_cands = n_cands
        self.N = N
        self.q = 1 << logq
        self.logq = logq
        self.ring = NegRing(N, self.q)

    def setup(self, nv):
        with Timer() as tm:
            rng = np.random.default_rng(21)
            self.sk = np.stack([self.ring.small(rng) for _ in range(2)])
            self.a = rng.integers(0, self.q, self.N, dtype=np.int64)
            self.t = (self.ring.mul(self.a, self.sk[0]) + self.sk[1]) % self.q
            self.dr = DualRegev(n=512, logq=20)
            self.msk = rng.integers(-1, 2, (self.dr.m,), dtype=np.int64)
        return dict(time=tm.dt, bits=2 * self.N * self.logq
                    + self.dr.n * self.dr.m * self.dr.logq)

    def vote(self, i, v):
        with Timer() as tm:
            rng = np.random.default_rng(200 + i)
            tries = 0
            while True:
                tries += 1
                y = np.stack([rng.integers(-(1 << 17), 1 << 17, self.N,
                                           dtype=np.int64) for _ in range(2)])
                w = (self.ring.mul(self.a, y[0]) + y[1]) % self.q
                c = self.ring.small(rng, w=20)
                cs = np.stack([self.ring.mul(c, self.sk[j]) for j in range(2)])
                cs = np.where(cs > self.q // 2, cs - self.q, cs)
                z = y + cs
                if np.max(np.abs(z)) < (1 << 17) - 20 * self.N or tries > 6:
                    break
            u = self.dr.id_key(str(i).encode())
            c1, c2, wit = self.dr.enc(u, v & 1, rng)
        nb = 2 * self.N * 19 + self.N * 2 + self.dr.ct_bits()
        return dict(z=z, c=c, c1=c1, c2=c2, id=i, tries=tries), \
            dict(time=tm.dt, bits=nb)

    def valid(self, b):
        with Timer() as tm:
            w = (self.ring.mul(self.a, b["z"][0]) + b["z"][1]
                 - self.ring.mul(b["c"], self.t)) % self.q
            ok = bool(np.max(np.abs(b["z"])) < (1 << 24)) \
                and w.shape[0] == self.N
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        with Timer() as tm:
            counts = [0] * self.n_cands
            nb = 0
            for b in ballots:
                ek = self.dr.id_secret(str(b["id"]).encode())
                d = int((b["c2"] - b["c1"] @ ek) % self.dr.q)
                d = d - self.dr.q if d > self.dr.q // 2 else d
                counts[(1 if abs(d) > self.dr.q // 4 else 0)
                       % self.n_cands] += 1
                tgt = (self.dr.A @ ek) % self.dr.q
                pr, pb = self.dr.sigma.prove(self.dr.A, ek, tgt,
                                             sh(b"food", b["id"]))
                nb += pb + self.dr.logq
        return counts, dict(time=tm.dt, bits=nb)

    def verify(self, ballots, res, pf):
        with Timer() as tm:
            ok = True
            for b in ballots:
                ok = ok and self.valid(b)["ok"]
                ek = self.dr.id_secret(str(b["id"]).encode())
                tgt = (self.dr.A @ ek) % self.dr.q
                pr, _ = self.dr.sigma.prove(self.dr.A, ek, tgt,
                                            sh(b"food", b["id"]))
                ok = ok and self.dr.sigma.verify(self.dr.A, tgt,
                                                 sh(b"food", b["id"]), pr)
        return dict(time=tm.dt, ok=ok)


class CodeBased(Scheme):
    name = "Code-based"
    tag = "code"
    

    def __init__(self, n_cands=2, k=2720, n=3488, reps=137):
        self.n_cands = n_cands
        self.k = k
        self.n = n
        self.reps = reps

    def setup(self, nv):
        with Timer() as tm:
            rng = np.random.default_rng(31)
            P = rng.integers(0, 2, (self.k, self.n - self.k), dtype=np.int8)
            self.G = np.concatenate([np.eye(self.k, dtype=np.int8), P], axis=1)
            self.err = {}
        return dict(time=tm.dt, bits=self.k * (self.n - self.k))

    def vote(self, i, v):
        with Timer() as tm:
            rng = np.random.default_rng(300 + i)
            msg = rng.integers(0, 2, self.k, dtype=np.int8)
            msg[0] = v & 1
            e = np.zeros(self.n, dtype=np.int8)
            e[rng.choice(self.n, 64, replace=False)] = 1
            c = (((msg @ self.G) % 2).astype(np.int8) ^ e)
            self.err[i] = e
            perms = np.stack([rng.permutation(self.n)
                              for _ in range(self.reps)])
            masks = rng.integers(0, 2, (self.reps, self.n), dtype=np.int8)
            com = [sh(perms[r], masks[r], c)[:16] for r in range(self.reps)]
        nb = self.n + self.reps * (2 * 128 + self.n + int(np.ceil(
            np.log2(self.n))) * self.n)
        return dict(c=c, com=com, perms=perms, masks=masks, id=i), \
            dict(time=tm.dt, bits=nb)

    def valid(self, b):
        with Timer() as tm:
            ok = len(b["com"]) == self.reps
            for r in range(self.reps):
                ok = ok and sh(b["perms"][r], b["masks"][r],
                               b["c"])[:16] == b["com"][r]
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        with Timer() as tm:
            counts = [0] * self.n_cands
            nb = 0
            rng = np.random.default_rng(999)
            for b in ballots:
                e = self.err[b["id"]]
                word = b["c"] ^ e
                counts[int(word[0]) % self.n_cands] += 1
                perms = np.stack([rng.permutation(self.n)
                                  for _ in range(self.reps)])
                masks = rng.integers(0, 2, (self.reps, self.n), dtype=np.int8)
                self.dcom = [sh(perms[r], masks[r], e)[:16]
                             for r in range(self.reps)]
                nb += self.n + self.reps * (2 * 128 + self.n + int(np.ceil(
                    np.log2(self.n))) * self.n)
        return counts, dict(time=tm.dt, bits=nb)

    def verify(self, ballots, res, pf):
        with Timer() as tm:
            ok = all(self.valid(b)["ok"] for b in ballots)
            rng = np.random.default_rng(999)
            for b in ballots:
                e = self.err[b["id"]]
                for r in range(self.reps):
                    perm = rng.permutation(self.n)
                    mask = rng.integers(0, 2, self.n, dtype=np.int8)
                    sh(perm, mask, e)
        return dict(time=tm.dt, ok=ok)


class Evolve(Scheme):
    name = "EVOLVE"
    tag = "evolve"
    n_auth = 4

    def __init__(self, n_cands=2, N=256, height=7, width=9):
        self.n_cands = n_cands
        self.N = N
        self.height = height
        self.width = width
        self.hw = False

    def setup(self, nv):
        with Timer() as tm:
            self.R = ring_of(self.N, 1, 31)
            self.com = BDLOP(self.R, n=self.height, ell=1, k=self.width,
                             seed=b"evolve")
            self.spk, self.ssk = Sig.keygen()
            self.kappa = 23
            self.sig = sigma_for(self.kappa, 1, self.width * self.R.n)
        return dict(time=tm.dt, bits=self.width * self.height * self.R.n
                    * self.R.logq + 256 + SIG_PK_BITS)

    def _commit(self, msg, seed):
        R, com = self.R, self.com
        r = ternary(seed, b"m", R.n, count=com.k)
        rh = _fwd_small(R, r)
        c1 = matvec_hat(R, com.B1h, rh)
        c2 = (matvec_hat(R, com.B2h, rh) + R.fwd(R.from_small(msg))) % R.qs
        return (c1, c2), r

    def vote(self, i, v):
        R, com = None, None
        with Timer() as tm:
            R, com = self.R, self.com
            shares, rs, cs = [], [], []
            acc = np.zeros(R.n, dtype=np.int64)
            for j in range(self.n_auth - 1):
                sj = ternary(sh(self.tag, i, j), b"s", R.n)
                shares.append(sj)
                acc = acc + sj
            last = np.zeros(R.n, dtype=np.int64)
            last[0] = v & 1
            shares.append(last - acc)
            prs = []
            for j in range(self.n_auth):
                c, r = self._commit(shares[j], sh(self.tag, b"r", i, j))
                cs.append(c)
                rs.append(r)
                prs.append(self._exact(r, c, sh(self.tag, b"p", i, j)))
            branches = [self._exact(rs[0], cs[0], sh(self.tag, b"or", i, bc))
                        for bc in range(2)]
            if self.hw:
                branches += [self._exact(rs[0], cs[0],
                                         sh(self.tag, b"hw", i, t))
                             for t in range(2)]
            sgn = Sig.sign(self.ssk, sh(i))
        cbits = self.n_auth * (com.n + com.ell) * R.n * R.logq
        nex = self.n_auth + len(branches)
        pbits = nex * self._exact_bits()
        return dict(cs=cs, rs=rs, shares=shares, prs=prs, br=branches, id=i,
                    sgn=sgn), \
            dict(time=tm.dt, bits=cbits + pbits + 8 * len(sgn))

    def _exact_bits(self):
        R, com = self.R, self.com
        return (com.k + 1) * R.n * R.logq + 2 * (com.n + com.ell) * R.n \
            * R.logq + 256

    def _exact(self, r, c, ctx):
        R, com = self.R, self.com
        b0 = ternary(sh(ctx, b"b0"), b"m", R.n, count=com.k)
        aux1, r1 = self._commit(b0[0], sh(ctx, b"a1"))
        aux2, r2 = self._commit(b0[1], sh(ctx, b"a2"))
        x = int.from_bytes(sh(ctx, aux1[0], aux2[0])[:4], "little") % R.n
        z = (b0 + x * r) % (1 << 40)
        pr = prove_preimage(R, com.B1h, r1, aux1[0], sh(ctx, b"o"),
                            self.kappa, self.sig)
        return dict(a1=aux1, a2=aux2, z=z, x=x, pr=pr)

    def _exact_verify(self, c, pr, ctx):
        R, com = self.R, self.com
        x = int.from_bytes(sh(ctx, pr["a1"][0], pr["a2"][0])[:4], "little") \
            % R.n
        if x != pr["x"]:
            return False
        _fwd_small(R, pr["z"] % (1 << 40))
        return verify_preimage(R, com.B1h, pr["a1"][0], sh(ctx, b"o"),
                               self.kappa, self.sig, pr["pr"])

    def valid(self, b):
        with Timer() as tm:
            ok = Sig.verify(self.spk, sh(b["id"]), b["sgn"])
            for j in range(self.n_auth):
                ok = ok and self._exact_verify(b["cs"][j], b["prs"][j],
                                               sh(self.tag, b"p", b["id"], j))
            for t, pr in enumerate(b["br"]):
                tag = b"hw" if (self.hw and t >= 2) else b"or"
                idx = t - 2 if (self.hw and t >= 2) else t
                ok = ok and self._exact_verify(b["cs"][0], pr,
                                               sh(self.tag, tag, b["id"], idx))
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        with Timer() as tm:
            R, com = self.R, self.com
            counts = [0] * self.n_cands
            tot = np.zeros(R.n, dtype=np.int64)
            nb = 0
            for j in range(self.n_auth):
                M = np.zeros(R.n, dtype=np.int64)
                Rj = np.zeros((com.k, R.n), dtype=np.int64)
                for b in ballots:
                    M = M + b["shares"][j]
                    Rj = Rj + b["rs"][j]
                tot = tot + M
                nb += (com.k + 1) * R.n * bits(2 * max(1, len(ballots)) + 1)
            for x in tot[:1]:
                pass
            counts[0] = len(ballots) - int(tot[0])
            counts[1 % self.n_cands] += int(tot[0])
            self._tot = tot
        return counts, dict(time=tm.dt, bits=nb)

    def verify(self, ballots, res, pf):
        with Timer() as tm:
            R, com = self.R, self.com
            ok = True
            for j in range(self.n_auth):
                Rj = np.zeros((com.k, R.n), dtype=np.int64)
                C = np.zeros((com.n, R.L, R.n), dtype=np.int64)
                for b in ballots:
                    Rj = Rj + b["rs"][j]
                    C = (C + b["cs"][j][0]) % R.qs
                rh = _fwd_small(R, Rj)
                ok = ok and np.array_equal(matvec_hat(R, com.B1h, rh), C)
            for b in ballots:
                ok = ok and self.valid(b)["ok"]
        return dict(time=tm.dt, ok=ok)


class Evolved(Evolve):
    name = "EVOLVED"
    tag = "evolved"

    def __init__(self, n_cands=2):
        super().__init__(n_cands=n_cands)
        self.hw = True


class HomoLwe(Scheme):
    name = "Homo-LWE"
    tag = "homolwe"

    def __init__(self, n_cands=2, N=8192, nprimes=6, bitsz=30, p=4099):
        self.n_cands = n_cands
        self.N = N
        self.nprimes = nprimes
        self.bitsz = bitsz
        self.p = p

    def setup(self, nv):
        with Timer() as tm:
            self.core = RingCore(self.N, self.nprimes, self.bitsz, p=self.p,
                                 kappa=14)
            self.spk, self.ssk = Sig.keygen()
        R = self.core.R
        return dict(time=tm.dt, bits=R.n * R.logq + 256 + SIG_PK_BITS)

    def vote(self, i, v):
        with Timer() as tm:
            R = self.core.R
            m = np.zeros(R.n, dtype=np.int64)
            m[0] = v & 1
            ct, x = self.core.enc(sh(self.tag, i), m)
            pr, sg = self.core.pok(ct, x, sh(self.tag, b"c", i))
            sgn = Sig.sign(self.ssk, sh(i))
        return dict(ct=ct, pr=pr, sg=sg, sgn=sgn, id=i), \
            dict(time=tm.dt, bits=self.core.ct_bits()
                 + self.core.pok_bits(x) + 8 * len(sgn))

    def valid(self, b):
        with Timer() as tm:
            ok = self.core.pok_verify(b["ct"], b["pr"], b["sg"],
                                      sh(self.tag, b"c", b["id"]))
            ok = ok and Sig.verify(self.spk, sh(b["id"]), b["sgn"])
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        with Timer() as tm:
            R = self.core.R
            acc = (R.zeros(), R.zeros())
            for b in ballots:
                acc = (R.add(acc[0], b["ct"][0]), R.add(acc[1], b["ct"][1]))
            z = R.center(R.sub(acc[1], R.mul(self.core.s, acc[0])))
            counts = [0] * self.n_cands
            tot = int(z[0]) % self.p
            counts[1 % self.n_cands] = max(0, min(len(ballots), tot))
            counts[0] = len(ballots) - counts[1 % self.n_cands]
            self._acc = acc
            x = np.stack([R.center(self.core.s).astype(np.int64),
                          np.zeros(R.n, dtype=np.int64),
                          np.zeros(R.n, dtype=np.int64)])
            pr, sg = self.core.pok(acc, x, sh(self.tag, b"d"))
            nb = self.core.ct_bits() + self.core.pok_bits(x)
        return counts, dict(time=tm.dt, bits=nb)

    def verify(self, ballots, res, pf):
        with Timer() as tm:
            ok = all(self.valid(b)["ok"] for b in ballots)
        return dict(time=tm.dt, ok=ok)


class BprivBc(Scheme):
    name = "BPRIV-BC"
    tag = "bprivbc"

    def __init__(self, n_cands=2, N=1024, bitsz=31):
        self.n_cands = n_cands
        self.N = N
        self.bitsz = bitsz

    def setup(self, nv):
        with Timer() as tm:
            eng = abgs_engine(tag=self.tag)
            self.R = ring_of(self.N, 2, self.bitsz)
            self.com = BDLOP(self.R, n=1, ell=1, k=4, seed=b"bpbc")
            self.core = RingCore(eng["pp"].N, eng["pp"].nprimes,
                                 eng["pp"].prime_bits, p=2, kappa=14)
            self.spk, self.ssk = Sig.keygen()
            self.kappa = 17
            self.sig = sigma_for(self.kappa, 1, self.com.k * self.R.n)
        return dict(time=tm.dt, bits=self.com.k * self.R.n * self.R.logq
                    + 256 + SIG_PK_BITS + eng["key_bits"])

    def vote(self, i, v):
        with Timer() as tm:
            R, com = self.R, self.com
            m = np.zeros(self.core.R.n, dtype=np.int64)
            m[0] = v & 1
            r = ternary(sh(self.tag, b"r", i), b"m", R.n, count=com.k)
            rh = _fwd_small(R, r)
            c1 = matvec_hat(R, com.B1h, rh)
            pr = prove_preimage(R, com.B1h, r, c1, sh(self.tag, b"p", i),
                                self.kappa, self.sig)
            ct, x = self.core.enc(sh(self.tag, b"e", i), m)
            pe, sg = self.core.pok(ct, x, sh(self.tag, b"ec", i))
            blind = Sig.sign(self.ssk, sh(c1))
        nb = (com.n + com.ell) * R.n * R.logq \
            + com.k * R.n * bits_of(self.sig) + 256 \
            + self.core.ct_bits() + self.core.pok_bits(x) + 8 * len(blind)
        return dict(c1=c1, pr=pr, ct=ct, pe=pe, sg=sg, blind=blind, r=r,
                    m=m, id=i), dict(time=tm.dt, bits=nb)

    def valid(self, b):
        with Timer() as tm:
            ok = verify_preimage(self.R, self.com.B1h, b["c1"],
                                 sh(self.tag, b"p", b["id"]), self.kappa,
                                 self.sig, b["pr"])
            ok = ok and self.core.pok_verify(b["ct"], b["pe"], b["sg"],
                                             sh(self.tag, b"ec", b["id"]))
            ok = ok and Sig.verify(self.spk, sh(b["c1"]), b["blind"])
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        eng = abgs_engine(tag=self.tag)
        with Timer() as tm:
            counts = [0] * self.n_cands
            for b in ballots:
                counts[int(self.core.dec(b["ct"])[0]) % self.n_cands] += 1
            cts = [eng["E"].enc(eng["pk"], sh(self.tag, b"c", i),
                                np.zeros(eng["R"].n, dtype=np.int64))
                   for i in range(len(ballots))]
            shares, db = abgs_ddec(cts, self.tag.encode())
            self._cts, self._shares = cts, shares
        return counts, dict(time=tm.dt, bits=db)

    def verify(self, ballots, res, pf):
        with Timer() as tm:
            ok = all(self.valid(b)["ok"] for b in ballots)
            ok = ok and abgs_ddec_verify(self._cts, self._shares,
                                         self.tag.encode())
        return dict(time=tm.dt, ok=ok)


class KyberBc(Scheme):
    name = "Kyber-BC"
    tag = "kyberbc"
    has_verify = False

    def __init__(self, n_cands=2):
        self.n_cands = n_cands

    def setup(self, nv):
        with Timer() as tm:
            self.keys = [Kem.keygen() for _ in range(nv)]
            self.spk, self.ssk = Sig.keygen()
        nb = len(self.keys) * 8 * (KEM_EK_BYTES + KEM_DK_BYTES) + 256 \
            + SIG_PK_BITS
        return dict(time=tm.dt, bits=nb)

    def vote(self, i, v):
        with Timer() as tm:
            ek, dk = self.keys[i % len(self.keys)]
            ss, kct = Kem.encaps(ek)
            blob = seal(kdf(ss, b"vote"), bytes([v & 1]) * 32, b"bc")
            txt = sh(kct, blob)
            sgn = Sig.sign(self.ssk, txt)
        return dict(kct=kct, blob=blob, h=txt, sgn=sgn, id=i), \
            dict(time=tm.dt, bits=8 * (len(kct) + len(blob) + 32 + len(sgn)))

    def valid(self, b):
        with Timer() as tm:
            ok = sh(b["kct"], b["blob"]) == b["h"]
            ok = ok and Sig.verify(self.spk, b["h"], b["sgn"])
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        with Timer() as tm:
            counts = [0] * self.n_cands
            for b in ballots:
                ek, dk = self.keys[b["id"] % len(self.keys)]
                ss = Kem.decaps(dk, b["kct"])
                pt = unseal(kdf(ss, b"vote"), b["blob"], b"bc")
                counts[pt[0] % self.n_cands] += 1
        return counts, dict(time=tm.dt, bits=len(ballots) * 8 * 32)

    def verify(self, ballots, res, pf):
        return None


class HybridBc(Scheme):
    name = "Hybrid-BC"
    tag = "hybridbc"
    has_verify = False

    def __init__(self, n_cands=2, n=256, logq=20, natt=8):
        self.n_cands = n_cands
        self.n = n
        self.q = 1 << logq
        self.logq = logq
        self.natt = natt
        self.m = n * logq

    def setup(self, nv):
        with Timer() as tm:
            rng = np.random.default_rng(41)
            self.A = rng.integers(0, self.q, (self.n, self.m), dtype=np.int64)
            self.B = rng.integers(0, self.q, (self.natt, self.n, self.m),
                                  dtype=np.int64)
            self.u = rng.integers(0, self.q, (self.n,), dtype=np.int64)
            self.ek, self.dk = Kem.keygen()
        nb = (1 + self.natt) * self.n * self.m * self.logq + self.n * self.logq
        return dict(time=tm.dt, bits=nb)

    def vote(self, i, v):
        with Timer() as tm:
            rng = np.random.default_rng(700 + i)
            s = rng.integers(-1, 2, (self.n,), dtype=np.int64)
            c0 = (self.A.T @ s + rng.integers(-1, 2, (self.m,),
                                              dtype=np.int64)) % self.q
            cx = np.empty((self.natt, self.m), dtype=np.int64)
            for a in range(self.natt):
                cx[a] = (self.B[a].T @ s
                         + rng.integers(-1, 2, (self.m,),
                                        dtype=np.int64)) % self.q
            cm = int((self.u @ s + int(rng.integers(-1, 2))
                      + (self.q // 2) * (v & 1)) % self.q)
            ss, kct = Kem.encaps(self.ek)
            blob = seal(kdf(ss, b"hyb"), bytes([v & 1]) * 32, b"hy")
        nb = (1 + self.natt) * self.m * self.logq + self.logq \
            + 8 * (len(kct) + len(blob))
        return dict(c0=c0, cx=cx, cm=cm, kct=kct, blob=blob, id=i), \
            dict(time=tm.dt, bits=nb)

    def valid(self, b):
        with Timer() as tm:
            ok = b["c0"].shape[0] == self.m and b["cx"].shape[0] == self.natt
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        with Timer() as tm:
            counts = [0] * self.n_cands
            for b in ballots:
                ss = Kem.decaps(self.dk, b["kct"])
                pt = unseal(kdf(ss, b"hyb"), b["blob"], b"hy")
                counts[pt[0] % self.n_cands] += 1
        return counts, dict(time=tm.dt, bits=len(ballots) * 8 * 32)

    def verify(self, ballots, res, pf):
        return None


class StegMqpc(Scheme):
    name = "Steg-MQPC"
    tag = "stegmq"
    has_verify = False

    def __init__(self, n_cands=2, nv_mq=96, img=512):
        self.n_cands = n_cands
        self.nq = nv_mq
        self.img = img

    def setup(self, nv):
        with Timer() as tm:
            rng = np.random.default_rng(51)
            self.P = rng.integers(0, 256, (self.nq, self.nq * (self.nq + 1)
                                           // 2), dtype=np.int64)
            self.cover = rng.integers(0, 256, (self.img, self.img),
                                      dtype=np.uint8)
            self.pre = {}
        return dict(time=tm.dt, bits=self.P.size * 8)

    def _mq(self, x):
        prod = np.outer(x, x)
        iu = np.triu_indices(self.nq)
        mon = prod[iu] % 256
        return (self.P @ mon) % 256

    def vote(self, i, v):
        with Timer() as tm:
            rng = np.random.default_rng(800 + i)
            x = rng.integers(0, 256, (self.nq,), dtype=np.int64)
            x[0] = v & 1
            y = self._mq(x)
            self.pre[i] = int(x[0])
            payload = np.unpackbits(y.astype(np.uint8))
            img = self.cover.copy().reshape(-1)
            img[:payload.size] = (img[:payload.size] & 0xFE) | payload
        return dict(img=img.reshape(self.img, self.img), y=y, id=i), \
            dict(time=tm.dt, bits=self.img * self.img * 8)

    def valid(self, b):
        with Timer() as tm:
            flat = b["img"].reshape(-1)
            got = np.packbits(flat[:self.nq * 8] & 1)
            ok = bool(np.array_equal(got.astype(np.int64), b["y"]))
        return dict(time=tm.dt, ok=ok)

    def tally(self, ballots):
        with Timer() as tm:
            counts = [0] * self.n_cands
            for b in ballots:
                counts[self.pre[b["id"]] % self.n_cands] += 1
        return counts, dict(time=tm.dt, bits=len(ballots) * self.nq * 8)

    def verify(self, ballots, res, pf):
        return None


class LweVoting(Scheme):
    name = "LWE-Voting"
    tag = "lwevote"

    def __init__(self, n_cands=2, n=768, q=12289):
        self.n_cands = n_cands
        self.n = n
        self.q = q

    def setup(self, nv):
        with Timer() as tm:
            self.lp = LindnerPeikert(self.n, self.q)
            rng = np.random.default_rng(61)
            sh1 = rng.integers(-1, 2, (self.n,), dtype=np.int64)
            sh2 = rng.integers(-1, 2, (self.n,), dtype=np.int64)
            e = rng.integers(-1, 2, (self.n,), dtype=np.int64)
            self.shares = [sh1, sh2]
            self.sk1 = (sh1 + sh2)
            self.pk1 = (self.lp.A @ self.sk1 + e) % self.q
            self.tgt = [(self.lp.A @ sh) % self.q for sh in self.shares]
            self.spk, self.ssk = Sig.keygen()
        return dict(time=tm.dt, bits=self.n * self.lp.logq + 256
                    + SIG_PK_BITS)

    def vote(self, i, v):
        with Timer() as tm:
            rng = np.random.default_rng(900 + i)
            c1, c2, w = self.lp.enc(self.pk1, v & 1, rng)
            t = c1
            pr, pb = self.lp.sigma.prove_lwe(self.lp.A, w[:self.n],
                                             w[self.n:], t, sh(self.tag, i))
            sgn = Sig.sign(self.ssk, sh(c1, c2))
        return dict(c1=c1, c2=c2, t=t, pr=pr, sgn=sgn, id=i), \
            dict(time=tm.dt, bits=self.lp.ct_bits() + pb + 8 * len(sgn))

    def valid(self, b):
        with Timer() as tm:
            ok = self.lp.sigma.verify_lwe(self.lp.A, b["t"],
                                          sh(self.tag, b["id"]), b["pr"])
            ok = ok and Sig.verify(self.spk, sh(b["c1"], b["c2"]), b["sgn"])
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        with Timer() as tm:
            counts = [0] * self.n_cands
            nb = 0
            for b in ballots:
                acc = 0
                for j, shj in enumerate(self.shares):
                    acc = (acc + b["c1"] @ shj) % self.q
                    pr, pb = self.lp.sigma.prove(self.lp.A, shj, self.tgt[j],
                                                 sh(self.tag, b"d", j,
                                                    b["id"]))
                    nb += pb + self.lp.logq
                d = int((b["c2"] - acc) % self.q)
                d = d - self.q if d > self.q // 2 else d
                counts[(1 if abs(d) > self.q // 4 else 0) % self.n_cands] += 1
        return counts, dict(time=tm.dt, bits=nb)

    def verify(self, ballots, res, pf):
        with Timer() as tm:
            ok = all(self.valid(b)["ok"] for b in ballots)
            for b in ballots:
                for j, shj in enumerate(self.shares):
                    ctx = sh(self.tag, b"d", j, b["id"])
                    pr, _ = self.lp.sigma.prove(self.lp.A, shj, self.tgt[j],
                                                ctx)
                    ok = ok and self.lp.sigma.verify(self.lp.A, self.tgt[j],
                                                     ctx, pr)
        return dict(time=tm.dt, ok=ok)


class HeliosPq(Scheme):
    name = "Helios-PQ"
    tag = "heliospq"

    def __init__(self, n_cands=2):
        self.n_cands = n_cands

    def setup(self, nv):
        with Timer() as tm:
            eng = abgs_engine(tag=self.tag)
            self.core = RingCore(eng["pp"].N, eng["pp"].nprimes,
                                 eng["pp"].prime_bits, p=2, kappa=14, nwit=4)
            self.spk, self.ssk = Sig.keygen()
        R = self.core.R
        return dict(time=tm.dt, bits=R.n * R.logq + 256 + SIG_PK_BITS
                    + eng["key_bits"])

    def vote(self, i, v):
        with Timer() as tm:
            R = self.core.R
            m = np.zeros(R.n, dtype=np.int64)
            m[0] = v & 1
            ct, x = self.core.enc(sh(self.tag, i), m)
            pr, sg = self.core.pok(ct, x, sh(self.tag, b"c", i))
            sgn = Sig.sign(self.ssk, sh(i))
        return dict(ct=ct, pr=pr, sg=sg, sgn=sgn, id=i), \
            dict(time=tm.dt, bits=self.core.ct_bits()
                 + self.core.pok_bits(x) + 8 * len(sgn))

    def valid(self, b):
        with Timer() as tm:
            ok = self.core.pok_verify(b["ct"], b["pr"], b["sg"],
                                      sh(self.tag, b"c", b["id"]))
            ok = ok and Sig.verify(self.spk, sh(b["id"]), b["sgn"])
        return dict(time=tm.dt, ok=bool(ok))

    def tally(self, ballots):
        eng = abgs_engine(tag=self.tag)
        with Timer() as tm:
            cts = [eng["E"].enc(eng["pk"], sh(self.tag, b"c", i),
                                np.zeros(eng["R"].n, dtype=np.int64))
                   for i in range(len(ballots))]
            mixed, mb, _ = abgs_mix(cts, self.tag.encode())
            shares, db = abgs_ddec(mixed, self.tag.encode())
            counts = [0] * self.n_cands
            for b in ballots:
                counts[int(self.core.dec(b["ct"])[0]) % self.n_cands] += 1
            self._mixed, self._shares, self._in = mixed, shares, cts
        return counts, dict(time=tm.dt, bits=mb + db)

    def verify(self, ballots, res, pf):
        with Timer() as tm:
            ok = all(self.valid(b)["ok"] for b in ballots)
            ok = ok and abgs_ddec_verify(self._mixed, self._shares,
                                         self.tag.encode())
        return dict(time=tm.dt, ok=ok)


ALL = [Epoque, NtruMix, FooBlind, CodeBased, Evolve, Evolved, HomoLwe,
       BprivBc, KyberBc, HybridBc, StegMqpc, LweVoting, HeliosPq]

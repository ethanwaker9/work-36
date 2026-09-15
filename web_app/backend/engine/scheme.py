import copy
import os
import time
from dataclasses import dataclass, field

import numpy as np

from .circuit import (Layout, batcher_stages, compare_swap_stage, one_minus,
                      suffix_and, total_broadcast)
from .commit import BDLOP
from .ddec import DistDec
from .evaluator import Evaluator, Flat, flat_fresh, flat_mult, flat_rot
from .packed import PackedBGV
from .proofs import prove_preimage, sigma_for, verify_preimage
from .ring import find_ntt_primes
from .shuffle import Shuffler
from .threshold import ThresholdKey
from .util import ser
from .xof import DS_BALLOT, DS_TRACKER, shake256


@dataclass
class Params:
    N: int = 8192
    nprimes: int = 5
    prime_bits: int = 24
    p: int = 65537
    kappa: int = 23
    sec: int = 40
    n_mix: int = 2
    n_trustee: int = 3
    threshold: int = 2
    batch: int = 8
    lam: int = 128
    primes: list = field(default_factory=list)

    def __post_init__(self):
        if not self.primes:
            self.primes = find_ntt_primes(self.N, self.prime_bits, self.nprimes)


class _DryE:
    def __init__(self, n):
        self.n = n

    def add(self, a, b):
        return None

    def sub(self, a, b):
        return None

    def mul_plain_slots(self, c, v):
        return None

    def add_plain_slots(self, c, v):
        return None

    def zero_ct(self):
        return None

    def scale(self, c, k):
        return None

    def rot_ct(self, c, k):
        return None


class _DryEval:
    def __init__(self, E):
        self.E = E
        self.phase = "load"
        self.counts = {}

    def _tick(self, n=1):
        self.counts[self.phase] = self.counts.get(self.phase, 0) + n

    def refresh(self, cts, k, ctx, tag=b"rf"):
        self._tick()
        return [None] * len(cts)

    def rotate(self, cts, k, ctx):
        self._tick()
        return [None] * len(cts)

    def masked_mult_raw(self, Xs, Ys, rotk, ctx):
        self._tick(2)
        return [None] * len(Xs)


class Oylama:
    def __init__(self, uid, mmax=16, n_cands=2, pp=None):
        self.uid = uid
        self.pp = pp or Params()
        self.lam = self.pp.lam
        self.Mmax = mmax
        self.n_cands = n_cands
        self.mbits = max(2, mmax.bit_length())
        self.vbits = max(1, n_cands.bit_length())
        self.E = PackedBGV(self.pp.N, self.pp.primes, self.pp.p)
        self.R = self.E.R
        self.H = self.pp.N // 2
        self.keybits = self.mbits + self.lam
        self.lay = Layout(mmax, self.H, self.keybits + self.vbits + 1)
        if self.lay.G != 1:
            raise ValueError("board capacity too large for one packed ciphertext")
        self.com = BDLOP(self.R, n=1, ell=1, k=3, seed=b"oylama-dec")
        self.com_mix = BDLOP(self.R, n=1, ell=2, k=4, seed=b"oylama-mix")
        self.pk = None
        self.eng = None

    def keygen(self, seed):
        return self.E.keygen(seed)

    def attach(self, pk):
        R, pp = self.R, self.pp
        self.pk = pk
        self.dd = DistDec(pp, R, self.com, pk, 1 << 52)
        one = R.zeros()
        one[:, 0] = 1
        a, b = pk
        self.Ah = np.stack([
            np.stack([R.fwd(a), R.fwd(R.scalar(one, pp.p)), R.fwd(R.zeros())]),
            np.stack([R.fwd(b), R.fwd(R.zeros()), R.fwd(one)]),
        ])
        return self

    def deal(self, s, seed):
        return ThresholdKey.deal(self.R, self.pp.n_trustee, self.pp.threshold, s, seed)

    def spread(self, bits, base_block):
        v = np.zeros(self.pp.N, dtype=np.int64)
        for b, x in enumerate(bits):
            v[(base_block + b) * self.Mmax] = int(x)
        return v

    def new_credential(self):
        h = shake256(os.urandom(32), b"credential", outlen=(self.lam + 7) // 8)
        return [(h[i // 8] >> (i % 8)) & 1 for i in range(self.lam)]

    def register(self, cred, seed):
        return self.E.enc_slots(self.pk, shake256(seed, b"roster"),
                                self.spread(cred, self.mbits))

    def ballot_context(self, tag, ct):
        return shake256(DS_BALLOT, self.uid, tag, ser(ct[0], ct[1]))

    def sigma_bound(self):
        return sigma_for(self.pp.kappa, 2 * self.pp.p, 3 * self.R.n)

    def vote(self, cred, nu, seed):
        E, R, pp = self.E, self.R, self.pp
        vb = [(nu >> t) & 1 for t in range(self.vbits)]
        polys = ((b"v", E.slots.encode(self.spread(vb, self.keybits))),
                 (b"c", E.slots.encode(self.spread(cred, self.mbits))))
        cts, pis = [], []
        for tag, poly in polys:
            ct, x = E.enc_parts(self.pk, shake256(seed, tag), poly)
            t = np.stack([R.fwd(ct[0]), R.fwd(ct[1])])
            sig = sigma_for(pp.kappa, int(np.max(np.abs(x))) + 1, 3 * R.n)
            pr = prove_preimage(R, self.Ah, x, t, self.ballot_context(tag, ct),
                                pp.kappa, sig, rnd=shake256(seed, tag, b"rnd"))
            if pr is None:
                return self.vote(cred, nu, os.urandom(32))
            cts.append(ct)
            pis.append((pr, sig))
        size = 4 * R.n * R.logq + sum(p.size_bits for p, _ in pis)
        return dict(C1=cts[0], C2=cts[1], pi=pis, size_bits=size)

    def check_ballot(self, ballot):
        R, pp = self.R, self.pp
        bound = self.sigma_bound() * (1 + 1e-9)
        for tag, ct, (pi, sig) in ((b"v", ballot["C1"], ballot["pi"][0]),
                                   (b"c", ballot["C2"], ballot["pi"][1])):
            if not (0 < sig <= bound):
                return False
            for comp in ct:
                if comp.shape != (R.L, R.n) or np.any(comp < 0) or np.any(comp >= R.qs):
                    return False
            t = np.stack([R.fwd(ct[0]), R.fwd(ct[1])])
            if not verify_preimage(R, self.Ah, t, self.ballot_context(tag, ct),
                                   pp.kappa, sig, pi):
                return False
        return True

    def ballot_key(self, ballot):
        return shake256(ser(ballot["C1"][0], ballot["C2"][0]))

    def tracker(self, ballot):
        return shake256(DS_TRACKER, self.uid,
                        ser(ballot["C1"][0], ballot["C1"][1],
                            ballot["C2"][0], ballot["C2"][1]),
                        ballot["pi"][0][0].data[0], ballot["pi"][1][0].data[0],
                        outlen=16)

    def evaluator(self, holdings, quorum, secret, public, monitor=None, verify=True):
        pp = self.pp
        tk = ThresholdKey.from_holdings(self.R, pp.n_trustee, pp.threshold, holdings)
        asg = tk.assign(quorum)
        keys = {j: tk.party_key(j, asg) for j in quorum}
        self.eng = Evaluator(self.E, pp, self.dd, keys, quorum, self.pk, secret,
                             public, monitor, verify)
        return self.eng

    def load(self, ballots, roster, ctx):
        E, lay = self.E, self.lay
        nb = len(ballots)
        items = []
        for k, b in enumerate(ballots):
            items.append((E.add(b["C1"], b["C2"]), k))
        for j, rc in enumerate(roster):
            items.append((rc, nb + j))
        acc = None
        for (ct, pos) in items:
            self.eng.phase = "load:%d" % pos
            rot = self.eng.rotate([ct], (self.H - pos) % self.H,
                                  shake256(ctx, b"ld", pos))[0]
            m = np.zeros(E.n, dtype=np.int64)
            for b in range(lay.B):
                if b * lay.M + pos < lay.size:
                    m[b * lay.M + pos] = 1
            cm = E.mul_plain_slots(rot, m)
            acc = cm if acc is None else E.add(acc, cm)
        F = Flat(self.eng, [acc], lay.size)
        step = 1
        self.eng.phase = "replicate"
        while step < lay.copies:
            F = F.add(flat_rot(F, lay.size - step * lay.unit,
                               shake256(ctx, b"rep", step)))
            step *= 2
        base = np.zeros(lay.unit, dtype=np.int64)
        for pos in range(len(items)):
            val = pos if pos < nb else nb
            for b in range(self.mbits):
                base[b * lay.M + pos] = (val >> b) & 1
        return flat_fresh(F.add_const(lay.spread(base)), shake256(ctx, b"lf"))

    def cleanse(self, F, nb, ctx):
        lay, eng = self.lay, self.eng
        K = F
        stages = batcher_stages(lay.M)
        for si, (d, lefts) in enumerate(stages):
            eng.phase = "sort:%d:%d" % (si + 1, len(stages))
            K = compare_swap_stage(lay, K, d, lefts, self.keybits,
                                   ctx + b"|s%d" % si)
        eng.phase = "dedup"
        nxt = flat_rot(K, 1, ctx + b"|nx")
        cm = lay.block_mask(range(self.mbits, self.mbits + self.lam))
        diff = K.sub(nxt)
        sq = flat_mult(diff, diff, ctx + b"|sq")
        eqc = one_minus(lay, sq).mask(cm).add_const(1 - cm)
        Dk = suffix_and(lay, eqc, ctx + b"|Dk")
        eng.phase = "roster"
        nbb = np.zeros(lay.unit, dtype=np.int64)
        for b in range(self.mbits):
            if (nb >> b) & 1:
                nbb[b * lay.M:(b + 1) * lay.M] = 1
        nbbits = lay.spread(nbb)
        im = lay.block_mask(range(self.mbits))
        eqi = nxt.mask(2 * nbbits - 1).add_const(1 - nbbits)
        eqi = eqi.mask(im).add_const(1 - im)
        Fk = suffix_and(lay, eqi, ctx + b"|Fk")
        eng.phase = "valid"
        b0 = lay.block_mask([0])
        valid = flat_mult(Dk.mask(b0), Fk, ctx + b"|vb")
        vb = total_broadcast(lay, flat_fresh(valid.mask(b0), ctx + b"|fv"),
                             ctx + b"|bc")
        eng.phase = "gate"
        vmask = lay.block_mask(range(self.keybits, self.keybits + self.vbits))
        return flat_mult(K.mask(vmask), vb, ctx + b"|cg")

    def extract(self, F, count, ctx):
        E, lay = self.E, self.lay
        outs = []
        for i in range(count):
            self.eng.phase = "extract:%d:%d" % (i + 1, count)
            rot = self.eng.rotate(F.cts, i % self.H, shake256(ctx, b"ex", i))[0]
            m = np.zeros(E.n, dtype=np.int64)
            for b in range(self.vbits):
                m[(self.keybits + b) * lay.M] = 1
            outs.append(E.mul_plain_slots(rot, m))
        return outs

    def plan(self, nb, nv):
        dry = copy.copy(self)
        dry.E = _DryE(self.pp.N)
        dry.eng = _DryEval(dry.E)
        ballots = [dict(C1=None, C2=None) for _ in range(nb)]
        F = dry.load(ballots, [None] * nv, b"dry")
        out = dry.cleanse(F, nb, b"dry")
        dry.extract(out, self.Mmax, b"dry")
        total = sum(dry.eng.counts.values())
        groups = {}
        for ph, c in dry.eng.counts.items():
            key = ph.split(":")[0]
            groups[key] = groups.get(key, 0) + c
        return dict(total=total, groups=groups,
                    stages=len(batcher_stages(self.lay.M)))

    def mix(self, cts, secret, ctx, verify=True):
        R, pp = self.R, self.pp
        cur = cts
        report = []
        for k in range(pp.n_mix):
            S = Shuffler(pp, R, self.com_mix, self.pk)
            ok, out, pr, t0 = False, None, None, time.perf_counter()
            for attempt in range(3):
                mctx = shake256(ctx, b"mc", k, attempt)
                out, pr = S.mix(cur, shake256(secret, b"mix", k, attempt), mctx)
                ok = S.verify(cur, out, pr, mctx) if verify else True
                if ok:
                    break
            report.append(dict(server=k + 1, ok=bool(ok), bits=int(pr.size_bits),
                               seconds=round(time.perf_counter() - t0, 2)))
            cur = out
        return cur, report

    def read_slot_vote(self, vec):
        return sum(int(vec[(self.keybits + b) * self.Mmax]) << b
                   for b in range(self.vbits))

    def decrypt(self, cts, ctx, verify=True):
        eng = self.eng
        shares, report = [], []
        for j in eng.quorum:
            t0 = time.perf_counter()
            kc, kr = eng.keycom(j, 0)
            dctx = shake256(ctx, b"final", j)
            sh = self.dd.share(eng.base_keys[j], kc, kr, cts,
                               shake256(eng.secret, ctx, b"final", j), dctx,
                               len(eng.quorum))
            ok = self.dd.verify_share(kc, cts, sh, dctx, len(eng.quorum)) if verify else True
            shares.append(sh)
            report.append(dict(trustee=j + 1, ok=bool(ok), bits=int(sh.size_bits),
                               seconds=round(time.perf_counter() - t0, 2)))
        polys = self.dd.combine(cts, shares)
        votes = [self.read_slot_vote(self.E.slots.decode(pl)) for pl in polys]
        return votes, report

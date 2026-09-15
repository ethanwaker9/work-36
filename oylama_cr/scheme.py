import numpy as np

from oylama.commit import BDLOP
from oylama.params import Params
from oylama.proofs import prove_preimage, sigma_for, verify_preimage
from oylama.pq import Sig
from oylama.ring import find_ntt_primes
from oylama.ringkit import DistDec, ThresholdKey
from oylama.util import ser
from oylama.xof import shake256

from .circuit import (Layout, batcher_stages, compare_swap_stage, one_minus,
                      suffix_and, total_broadcast)
from .engine import Engine, Flat, flat_fresh, flat_mult, flat_rot
from .pack import PackedBGV


def cr_params(N=8192, nprimes=5, bits=24, p=65537, n_trustee=3, threshold=2):
    primes = find_ntt_primes(N, bits, nprimes)
    return Params(N=N, primes=primes, p=p, n_trustee=n_trustee,
                  threshold=threshold, batch=8, n_mix=2)


class OylamaCR:
    def __init__(self, pp=None, lam=128, Mmax=16, n_cands=2, idx_bits=None):
        self.pp = pp or cr_params()
        self.lam = lam
        self.Mmax = Mmax
        self.n_cands = n_cands
        self.mbits = idx_bits or max(2, (Mmax).bit_length())
        self.vbits = max(1, (n_cands).bit_length())
        self.E = PackedBGV(self.pp.N, self.pp.primes, self.pp.p)
        self.H = self.pp.N // 2
        self.keybits = self.mbits + self.lam
        self.lay = Layout(Mmax, self.H, self.keybits + self.vbits + 1)

    def setup(self, seed=b"cr-setup"):
        E, pp = self.E, self.pp
        pk, s = E.keygen(seed)
        self.pk, self.s = pk, s
        self.com = BDLOP(E.R, n=1, ell=1, k=3)
        self.dd = DistDec(pp, E.R, self.com, pk, 1 << 52)
        self.tk = ThresholdKey(pp, E.R, s, shake256(seed, b"tk"))
        self.quorum = list(range(pp.threshold))
        self.asg = self.tk.assign(self.quorum)
        self.eng = Engine(E, pp, self.dd, self.tk, self.quorum, self.asg, pk)
        self.reg_pk, self.reg_sk = Sig.keygen()
        R = E.R
        one = R.zeros()
        one[:, 0] = 1
        a, b = pk
        self.Ah = np.stack([
            np.stack([R.fwd(a), R.fwd(R.scalar(one, pp.p)), R.fwd(R.zeros())]),
            np.stack([R.fwd(b), R.fwd(R.zeros()), R.fwd(one)]),
        ])
        return dict(pk=pk, reg_pk=self.reg_pk), dict(s=s)

    def _spread(self, bits, base_block=0):
        v = np.zeros(self.pp.N, dtype=np.int64)
        for b, x in enumerate(bits):
            v[(base_block + b) * self.Mmax] = int(x)
        return v

    def fakecred(self, seed=None):
        import os
        raw = seed or os.urandom(32)
        h = shake256(raw, b"fake", outlen=(self.lam + 7) // 8)
        return [(h[i // 8] >> (i % 8)) & 1 for i in range(self.lam)]

    def register(self, i, seed=b"reg"):
        cred = self.fakecred(shake256(seed, b"c", i))
        ct = self.E.enc_slots(self.pk, shake256(seed, b"r", i),
                              self._spread(cred, self.mbits))
        return cred, ct

    def vote(self, cred, nu, seed):
        E, R, pp = self.E, self.E.R, self.pp
        vbits = [(nu >> t) & 1 for t in range(self.vbits)]
        mv = self._spread(vbits, self.keybits)
        mc = self._spread(cred, self.mbits)
        pv = E.slots.encode(mv)
        pc = E.slots.encode(mc)
        C1 = E.enc_poly(self.pk, shake256(seed, b"v"), pv)
        C2 = E.enc_poly(self.pk, shake256(seed, b"c"), pc)
        pis = []
        for tag, ct, poly, sd in ((b"v", C1, pv, shake256(seed, b"v")),
                                  (b"c", C2, pc, shake256(seed, b"c"))):
            from oylama.sample import ternary
            r = ternary(sd, b"r", R.n)
            e1 = ternary(sd, b"e1", R.n)
            e0 = ternary(sd, b"e0", R.n)
            f0 = poly + pp.p * e0
            x = np.stack([r, e1, f0]).astype(np.int64)
            t = np.stack([R.fwd(ct[0]), R.fwd(ct[1])])
            sig = sigma_for(pp.kappa, int(np.max(np.abs(x))) + 1, 3 * R.n)
            ctx = shake256(b"cr-ballot", tag, ser(ct[0], ct[1]))
            pis.append((prove_preimage(R, self.Ah, x, t, ctx, pp.kappa, sig),
                        sig))
        size = 4 * R.n * R.logq + sum(p.size_bits for p, _ in pis if p)
        return dict(C1=C1, C2=C2, pi=pis, size_bits=size)

    def valid(self, BB, ballot):
        R, pp = self.E.R, self.pp
        for tag, ct, (pi, sig) in ((b"v", ballot["C1"], ballot["pi"][0]),
                                   (b"c", ballot["C2"], ballot["pi"][1])):
            ctx = shake256(b"cr-ballot", tag, ser(ct[0], ct[1]))
            t = np.stack([R.fwd(ct[0]), R.fwd(ct[1])])
            if not verify_preimage(R, self.Ah, t, ctx, pp.kappa, sig, pi):
                return False
        h = shake256(ser(ballot["C1"][0], ballot["C2"][0]))
        return all(shake256(ser(b["C1"][0], b["C2"][0])) != h for b in BB)

    def _load(self, ballots, roster, ctx):
        E, lay = self.E, self.lay
        nb = len(ballots)
        items = []
        for k, b in enumerate(ballots):
            items.append((E.add(b["C1"], b["C2"]), k))
        for j, rc in enumerate(roster):
            items.append((rc, nb + j))
        acc = None
        for (ct, pos) in items:
            rot = self.eng.rotate([ct], (self.H - pos) % self.H,
                                  shake256(ctx, b"ld", pos))[0]
            m = np.zeros(E.n, dtype=np.int64)
            for b in range(lay.B):
                if b * lay.M + pos < lay.size:
                    m[b * lay.M + pos] = 1
            cm = E.mul_plain_slots(rot, m)
            acc = cm if acc is None else E.add(acc, cm)
        F = Flat(self.eng, [acc], lay.size)
        c = lay.copies
        step = 1
        while step < c:
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
        for si, (d, lefts) in enumerate(batcher_stages(lay.M)):
            K = compare_swap_stage(lay, K, d, lefts, self.keybits,
                                   ctx + b"|s%d" % si)
        nxt = flat_rot(K, 1, ctx + b"|nx")
        credblocks = list(range(self.mbits, self.mbits + self.lam))
        cm = lay.block_mask(credblocks)
        diff = K.sub(nxt)
        sq = flat_mult(diff, diff, ctx + b"|sq")
        eqc = one_minus(lay, sq).mask(cm).add_const(1 - cm)
        Dk = suffix_and(lay, eqc, ctx + b"|Dk")
        nbb = np.zeros(lay.unit, dtype=np.int64)
        for b in range(self.mbits):
            if (nb >> b) & 1:
                nbb[b * lay.M:(b + 1) * lay.M] = 1
        nbbits = lay.spread(nbb)
        im = lay.block_mask(range(self.mbits))
        eqi = nxt.mask(2 * nbbits - 1).add_const(1 - nbbits)
        eqi = eqi.mask(im).add_const(1 - im)
        Fk = suffix_and(lay, eqi, ctx + b"|Fk")
        b0 = lay.block_mask([0])
        valid = flat_mult(Dk.mask(b0), Fk, ctx + b"|vb")
        vb = total_broadcast(lay, flat_fresh(valid.mask(b0), ctx + b"|fv"),
                             ctx + b"|bc")
        vmask = lay.block_mask(range(self.keybits,
                                     self.keybits + self.vbits))
        out = flat_mult(K.mask(vmask), vb, ctx + b"|cg")
        return out

    def read_vote(self, ct):
        v = self.E.dec_slots(self.s, ct)
        return sum(int(v[(self.keybits + b) * self.Mmax]) << b
                   for b in range(self.vbits))

    def extract(self, F, count, ctx):
        E, lay = self.E, self.lay
        outs = []
        for i in range(count):
            rot = self.eng.rotate(F.cts, i % self.H,
                                  shake256(ctx, b"ex", i))[0]
            m = np.zeros(E.n, dtype=np.int64)
            for b in range(self.vbits):
                m[(self.keybits + b) * lay.M] = 1
            outs.append(E.mul_plain_slots(rot, m))
        return outs

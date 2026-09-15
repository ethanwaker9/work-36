import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from baselines.common import Timer
from baselines.schemes import ALL
from oylama.params import Params
from oylama.pq import Kem, Sig, backend
from oylama.scheme import Oylama

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "results")


class OylamaWrap:
    name = "Oylama"
    tag = "oylama"

    def __init__(self, n_cands=2, n_mix=2, n_trustee=4, threshold=3):
        self.pp = Params(n_mix=n_mix, n_trustee=n_trustee, threshold=threshold)
        self.V = Oylama(self.pp, n_cands=n_cands)
        self.creds = []

    def setup(self, nv):
        with Timer() as tm:
            pd, sd = self.V.setup()
        bits_ = self.V.setup_bits()
        self.BB = []
        return dict(time=tm.dt, bits=bits_)

    def register(self, nv):
        self.creds = [self.V.register("v%d" % i) for i in range(nv)]

    def vote(self, i, v):
        cp, cs = self.creds[i]
        with Timer() as tm:
            b, stp, stq = self.V.vote(cp, cs, v, b"s%d" % i)
        return b, dict(time=tm.dt, bits=b["size_bits"])

    def valid(self, b):
        with Timer() as tm:
            ok = self.V.valid(self.BB, b)
        return dict(time=tm.dt, ok=ok)

    def box(self, b):
        self.V.box(self.BB, b)

    def tally(self, ballots):
        with Timer() as tm:
            res, pf = self.V.tally(self.BB)
        self.pf = pf
        self.res = res
        return res, dict(time=tm.dt, bits=pf["size_bits"])

    def verify(self, ballots, res, pf):
        with Timer() as tm:
            ok = self.V.verify(self.V.publish(self.BB), self.res, self.pf)
        return dict(time=tm.dt, ok=ok)


def warm():
    pk, sk = Sig.keygen()
    s = Sig.sign(sk, b"x")
    Sig.verify(pk, b"x", s)
    ek, dk = Kem.keygen()
    Kem.encaps(ek)


def run(nv=8, n_cands=2, reps=1):
    warm()
    rows = []
    for C in ALL + [OylamaWrap]:
        s = C(n_cands=n_cands)
        st = s.setup(nv)
        if hasattr(s, "register"):
            s.register(nv)
        s.vote(0, 1)
        vt, vb = [], 0
        ballots = []
        for i in range(nv):
            b, info = s.vote(i, i % n_cands)
            vt.append(info["time"])
            vb = info["bits"]
            ballots.append(b)
        for rep in range(max(0, 16 - nv)):
            _, info = s.vote(rep % nv, rep % n_cands)
            vt.append(info["time"])
        vl = []
        for rep in range(max(16, nv)):
            vl.append(s.valid(ballots[rep % nv])["time"])
        for b in ballots:
            if hasattr(s, "box"):
                s.box(b)
        want = [0] * n_cands
        for i in range(nv):
            want[i % n_cands] += 1
        res, tl = s.tally(ballots)
        vr = s.verify(ballots, res, None)
        vrm = None if vr is None else vr["time"] * 1e3 / nv
        rows.append(dict(name=s.name, nv=nv, ok=bool(res == want),
                         setup_ms=st["time"] * 1e3, setup_kb=st["bits"] / 8192,
                         vote_ms=float(np.median(vt)) * 1e3, vote_kb=vb / 8192,
                         valid_ms=float(np.median(vl)) * 1e3,
                         tally_ms=tl["time"] * 1e3 / nv,
                         tally_kb=tl["bits"] / 8192 / nv,
                         verify_ms=vrm))
        print("%-12s setup %8.2f ms %9.1f KB | vote %8.3f ms %8.1f KB | "
              "valid %7.3f ms | tally %9.2f ms %9.1f KB | verify %s | %s"
              % (s.name, rows[-1]["setup_ms"], rows[-1]["setup_kb"],
                 rows[-1]["vote_ms"], rows[-1]["vote_kb"],
                 rows[-1]["valid_ms"], rows[-1]["tally_ms"],
                 rows[-1]["tally_kb"],
                 "  --  " if vrm is None else "%8.3f ms" % vrm,
                 "res-ok" if rows[-1]["ok"] else "res=%s want=%s" % (res, want)))
        sys.stdout.flush()
    return rows


if __name__ == "__main__":
    nv = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    os.makedirs(OUT, exist_ok=True)
    rows = run(nv=nv)
    with open(os.path.join(OUT, "schemes_n%d.json" % nv), "w") as f:
        json.dump(dict(backend=backend(), nv=nv, rows=rows), f, indent=1)
    print("saved")

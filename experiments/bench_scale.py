import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from oylama.params import Params
from oylama.pq import Kem, Sig
from oylama.scheme import Oylama

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "results")


def run(ns, n_mix=2, n_cands=3):
    pk, sk = Sig.keygen()
    Sig.verify(pk, b"x", Sig.sign(sk, b"x"))
    rows = []
    for n in ns:
        pp = Params(n_mix=n_mix, n_trustee=4, threshold=3)
        V = Oylama(pp, n_cands=n_cands)
        t0 = time.perf_counter()
        V.setup()
        ts = time.perf_counter() - t0
        creds = [V.register("v%d" % i) for i in range(n)]
        BB = []
        tv, tvl = [], []
        for i, (cp, cs) in enumerate(creds):
            t0 = time.perf_counter()
            b, _, _ = V.vote(cp, cs, i % n_cands, b"s%d" % i)
            tv.append(time.perf_counter() - t0)
            t0 = time.perf_counter()
            ok = V.valid(BB, b)
            tvl.append(time.perf_counter() - t0)
            V.box(BB, b)
        t0 = time.perf_counter()
        res, pf = V.tally(BB)
        tt = time.perf_counter() - t0
        t0 = time.perf_counter()
        ok = V.verify(V.publish(BB), res, pf)
        tvf = time.perf_counter() - t0
        rows.append(dict(n=n, setup_s=ts, vote_ms=float(np.median(tv)) * 1e3,
                         valid_ms=float(np.median(tvl)) * 1e3,
                         ballot_kb=b["size_bits"] / 8192,
                         tally_s=tt, verify_s=tvf,
                         proof_kb=pf["size_bits"] / 8192, ok=bool(ok),
                         n_mix=n_mix))
        print(rows[-1])
        sys.stdout.flush()
    return rows


if __name__ == "__main__":
    ns = [int(x) for x in sys.argv[1:]] or [4, 8, 16]
    os.makedirs(OUT, exist_ok=True)
    rows = run(ns)
    with open(os.path.join(OUT, "scale.json"), "w") as f:
        json.dump(dict(rows=rows), f, indent=1)
    print("saved")

import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from oylama_cr.circuit import batcher_stages
from oylama_cr.scheme import OylamaCR, cr_params

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "results")


def cost_model(M, lam, mbits, t_mult, t_rot, nT):
    B = 1
    while B < lam + mbits + 2:
        B *= 2
    lg = int(np.log2(B))
    stages = len(batcher_stages(M))
    mults = stages * (3 + lg)
    rots = stages * (4 + 2 * lg)
    mults += 2 * lg + 2
    rots += 2 * lg + 3
    G = max(1, (M * B) // 4096)
    return dict(stages=stages, mults=mults, rots=rots, G=G,
                time=G * (mults * t_mult + rots * t_rot))


def run(Ms, lam=128, n_cands=2):
    rows = []
    t_mult = t_rot = None
    for M in Ms:
        V = OylamaCR(pp=cr_params(), Mmax=M, lam=lam, n_cands=n_cands)
        t0 = time.perf_counter()
        V.setup()
        t_setup = time.perf_counter() - t0
        nv = M // 2
        creds, roster = [], []
        for i in range(nv):
            c, ct = V.register(i)
            creds.append(c)
            roster.append(ct)
        t0 = time.perf_counter()
        BB = [V.vote(creds[i], i % n_cands, b"b%d" % i) for i in range(nv)]
        t_vote = (time.perf_counter() - t0) / nv
        t0 = time.perf_counter()
        okv = all(V.valid(BB[:i], BB[i]) for i in range(len(BB)))
        t_valid = (time.perf_counter() - t0) / nv
        t0 = time.perf_counter()
        F = V._load(BB, roster, b"ld")
        t_load = time.perf_counter() - t0
        V.eng.stats = dict(opens=0, mults=0, ct_ops=0, proof_bits=0,
                           dec_bits=0)
        t0 = time.perf_counter()
        out = V.cleanse(F, len(BB), b"cl")
        t_cl = time.perf_counter() - t0
        st = dict(V.eng.stats)
        t0 = time.perf_counter()
        vs = V.extract(out, M, b"ex")
        t_ex = time.perf_counter() - t0
        got = [V.read_vote(c) for c in vs]
        oke = sum(got) == sum(i % n_cands for i in range(nv))
        rows.append(dict(M=M, lam=lam, nv=nv, setup_s=t_setup,
                         vote_ms=t_vote * 1e3, valid_ms=t_valid * 1e3,
                         ballot_kb=BB[0]["size_bits"] / 8192,
                         load_s=t_load, cleanse_s=t_cl, extract_s=t_ex,
                         mults=st["mults"], opens=st["opens"],
                         transcript_mb=(st["proof_bits"] + st["dec_bits"])
                         / 8 / 2 ** 20, valid_ok=bool(okv),
                         extract_ok=bool(oke)))
        print(rows[-1])
        sys.stdout.flush()
    return rows


if __name__ == "__main__":
    Ms = [int(x) for x in sys.argv[1:]] or [4, 8]
    os.makedirs(OUT, exist_ok=True)
    rows = run(Ms)
    with open(os.path.join(OUT, "cr.json"), "w") as f:
        json.dump(dict(rows=rows), f, indent=1)
    print("saved")

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from baselines.schemes import ALL
from oylama.params import Params
from oylama.proofs import prove_preimage, sigma_for, verify_preimage
from oylama.ring import find_ntt_primes
from oylama.rns import RnsRing
from oylama.scheme import Oylama, decode_vote, encode_vote

FAIL = []


def check(name, cond):
    print("%-42s %s" % (name, "ok" if cond else "FAIL"))
    if not cond:
        FAIL.append(name)


def naive_negacyclic(a, b, q, n):
    c = np.convolve(a.astype(object), b.astype(object))
    out = np.zeros(n, dtype=object)
    for i, v in enumerate(c):
        if i < n:
            out[i] += int(v)
        else:
            out[i - n] -= int(v)
    return np.array([int(v) % q for v in out], dtype=object)


def test_ring():
    R = RnsRing(256, find_ntt_primes(256, 31, 3))
    a = np.random.randint(0, 1 << 30, (R.L, R.n)).astype(np.int64)
    b = np.random.randint(0, 1 << 30, (R.L, R.n)).astype(np.int64)
    check("ntt round trip", np.array_equal(R.inv(R.fwd(a)) % R.qs, a % R.qs))
    prod = R.mul(a, b)
    ok = all(np.array_equal(prod[i],
                            naive_negacyclic(a[i], b[i], R.primes[i], R.n))
             for i in range(R.L))
    check("ntt product against convolution", ok)


def test_encoding():
    N = 256
    ok = all(decode_vote(encode_vote(v, N), 4) == v for v in range(4))
    bad = encode_vote(1, N).copy()
    bad[40] ^= 1
    check("vote encoding round trip", ok)
    check("vote encoding rejects tampering", decode_vote(bad, 4) is None)


def test_proof():
    pp = Params()
    R = RnsRing(pp.N, pp.primes)
    from oylama.proofs import _fwd_small, matvec_hat
    A = np.random.randint(0, 1 << 30, (3, 5, R.L, R.n)).astype(np.int64)
    x = np.random.randint(-1, 2, (5, R.n)).astype(np.int64)
    t = matvec_hat(R, A, _fwd_small(R, x))
    sg = sigma_for(pp.kappa, 1, 5 * R.n)
    pr = prove_preimage(R, A, x, t, b"ctx", pp.kappa, sg)
    check("proof of knowledge verifies",
          verify_preimage(R, A, t, b"ctx", pp.kappa, sg, pr))
    t2 = t.copy()
    t2[0, 0, 0] = (t2[0, 0, 0] + 1) % R.primes[0]
    check("proof of knowledge rejects a wrong target",
          not verify_preimage(R, A, t2, b"ctx", pp.kappa, sg, pr))


def test_oylama(n=4):
    V = Oylama(Params(), n_cands=2)
    V.setup(b"test")
    BB = []
    want = [0, 0]
    for i in range(n):
        cp, cs = V.register("v%d" % i)
        v = i % 2
        want[v] += 1
        b, st, _ = V.vote(cp, cs, v, b"seed%d" % i)
        check("ballot %d accepted" % i, V.valid(BB, b))
        V.box(BB, b)
        check("voter %d finds its ballot" % i, V.verify_vote(b["id"], st, BB))
    res, pf = V.tally(BB)
    check("tally matches the cast votes", res == want)
    check("tally transcript verifies",
          V.verify(V.publish(BB), res, pf))
    forged = [dict(e) for e in V.publish(BB)]
    forged[0]["ct"] = (forged[0]["ct"] + 1) % V.R.qs
    check("verification rejects an altered board",
          not V.verify(forged, res, pf))
    check("verification rejects an altered result",
          not V.verify(V.publish(BB), [want[0] + 1, want[1]], pf))
    bad = dict(pf)
    mix = list(pf["mix"])
    inp, out, pr = mix[-1]
    out2 = [c.copy() for c in out]
    out2[0] = (out2[0] + 1) % V.R.qs
    mix[-1] = (inp, out2, pr)
    bad["mix"] = mix
    check("verification rejects a tampered shuffle output",
          not V.verify(V.publish(BB), res, bad))
    bad2 = dict(pf)
    sh = list(pf["shares"])
    ts = list(sh[0].data[0])
    ts[0] = (ts[0] + 1) % V.R.qs
    from oylama.proofs import Proof
    sh[0] = Proof(sh[0].kind, (ts,) + tuple(sh[0].data[1:]), sh[0].size_bits)
    bad2["shares"] = sh
    check("verification rejects a tampered decryption share",
          not V.verify(V.publish(BB), res, bad2))


def test_baselines(n=4):
    for C in ALL:
        s = C(n_cands=2)
        s.setup(n)
        if hasattr(s, "register"):
            s.register(n)
        ballots = []
        want = [0, 0]
        for i in range(n):
            b, _ = s.vote(i, i % 2)
            want[i % 2] += 1
            ballots.append(b)
        okv = all(s.valid(b)["ok"] for b in ballots)
        res, _ = s.tally(ballots)
        vr = s.verify(ballots, res, None)
        check("%s validity and tally" % s.name,
              okv and res == want and (vr is None or vr["ok"]))


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 4
    test_ring()
    test_encoding()
    test_proof()
    test_oylama(n)
    test_baselines(n)
    print()
    if FAIL:
        print("%d checks failed: %s" % (len(FAIL), ", ".join(FAIL)))
        sys.exit(1)
    print("all checks passed")

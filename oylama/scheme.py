import numpy as np

from .aead import kdf, seal
from .commit import BDLOP
from .ddec import DistDec
from .module import ModBGV, ThresholdKey
from .params import Params
from .pq import Kem, Sig, SIG_PK_BYTES
from .proofs import prove_preimage, sigma_for, verify_preimage
from .rns import RnsRing
from .shuffle import Shuffler
from .util import ser
from .xof import DS_BALLOT, shake256

TAG_BITS = 224
IDX_BITS = 16


def encode_vote(j, N):
    m = np.zeros(N, dtype=np.int64)
    for b in range(IDX_BITS):
        m[b] = (j >> b) & 1
    tag = shake256(b"vote-tag", int(j).to_bytes(4, "little"),
                   outlen=TAG_BITS // 8)
    for b in range(TAG_BITS):
        m[IDX_BITS + b] = (tag[b // 8] >> (b % 8)) & 1
    return m


def decode_vote(m, n_cands):
    j = 0
    for b in range(IDX_BITS):
        j |= int(m[b] & 1) << b
    if j < 0 or j >= n_cands:
        return None
    exp = encode_vote(j, len(m))
    if not np.array_equal(exp[:IDX_BITS + TAG_BITS], m[:IDX_BITS + TAG_BITS]):
        return None
    if int(np.sum(m[IDX_BITS + TAG_BITS:])) != 0:
        return None
    return j


class Oylama:
    def __init__(self, params=None, n_cands=2):
        self.pp = params or Params()
        self.n_cands = n_cands
        self.R = RnsRing(self.pp.N, self.pp.primes)
        self.E = ModBGV(self.pp, self.R)
        self.com_mix = BDLOP(self.R, n=self.pp.com_n, ell=1,
                             k=self.pp.com_k_mix, seed=b"oylama-mix")
        self.com_dec = BDLOP(self.R, n=self.pp.com_n, ell=self.pp.rank,
                             k=self.pp.com_k_dec, seed=b"oylama-dec")

    def setup(self, seed=b"oylama-setup"):
        R, pp = self.R, self.pp
        pk, s = self.E.keygen(seed)
        tk = ThresholdKey(pp, R, s, shake256(seed, b"tk"))
        quorum = list(range(pp.threshold))
        asg = tk.assign(quorum)
        reg_pk, reg_sk = Sig.keygen()
        self.Ah = self.E.ballot_matrix(pk)
        self.pd = dict(pk=pk, reg_pk=reg_pk, n_cands=self.n_cands)
        self.sd = dict(s=s, tk=tk, quorum=quorum, asg=asg, reg_sk=reg_sk)
        self.shufflers = [Shuffler(pp, R, self.com_mix, pk)
                          for _ in range(pp.n_mix)]
        self.dd = DistDec(pp, R, self.com_dec, pk, self.noise_bound())
        kcoms, krands = {}, {}
        for j in quorum:
            sj = tk.party_key(j, asg)
            kcoms[j], krands[j] = self.dd.commit_key(sj, shake256(seed,
                                                                  b"kc", j))
        self.pd["kcoms"] = kcoms
        self.sd["krands"] = krands
        return self.pd, self.sd

    def setup_bits(self):
        pp, R = self.pp, self.R
        keys = pp.threshold * (pp.com_n + pp.rank)
        return (pp.rank + keys) * R.n * R.logq + 256 + 8 * SIG_PK_BYTES

    def noise_bound(self):
        pp, R = self.pp, self.R
        k = pp.rank
        s1 = sigma_for(pp.kappa, 3, (2 * k + 1) * R.n)
        ball = 12 * s1 * (1 + 2 * pp.p * k * R.n)
        cols = pp.com_k_mix + 2 * k + 1
        sb = sigma_for(pp.kappa, 1, cols * R.n) * np.sqrt(pp.batch)
        mix = 12 * sb * (2 * pp.p * k * R.n)
        return int(ball + pp.n_mix * mix)

    def register(self, vid, seed=b"reg"):
        upk, usk = Sig.keygen()
        dek, ddk = Kem.keygen()
        ss, kct = Kem.encaps(dek)
        blob = seal(kdf(ss, b"cred"), upk, b"cred")
        cert = Sig.sign(self.sd["reg_sk"], ser(vid.encode(), upk))
        return (dict(id=vid, upk=upk, cert=cert),
                dict(usk=usk, dek=dek, kct=kct, blob=blob))

    def _ballot_sigma(self):
        return sigma_for(self.pp.kappa, 3, (2 * self.pp.rank + 1) * self.R.n)

    def vote(self, cred_pub, cred_sec, v, seed):
        R, pp = self.R, self.pp
        m = encode_vote(v, R.n)
        c, x = self.E.enc_parts(self.pd["pk"], seed, m)
        t = R.fwd_many(c)
        ctx = shake256(DS_BALLOT, cred_pub["id"].encode(), cred_pub["upk"],
                       ser(c))
        pi = prove_preimage(R, self.Ah, x, t, ctx, pp.kappa,
                            self._ballot_sigma())
        payload = ser(c) + pi.data[0] + ser(pi.data[1])
        sigma = Sig.sign(cred_sec["usk"], payload)
        ballot = dict(id=cred_pub["id"], upk=cred_pub["upk"], ct=c, pi=pi,
                      sig=sigma)
        ballot["size_bits"] = (pp.m_ct * R.n * R.logq + pi.size_bits
                               + 8 * len(sigma))
        return ballot, dict(v=v, x=x), shake256(b"bh", payload, sigma)

    def valid(self, BB, ballot):
        R, pp = self.R, self.pp
        c = ballot["ct"]
        if c.shape != (pp.m_ct, R.L, R.n):
            return False
        if np.any(c < 0) or np.any(c >= R.qs):
            return False
        payload = ser(c) + ballot["pi"].data[0] + ser(ballot["pi"].data[1])
        if not Sig.verify(ballot["upk"], payload, ballot["sig"]):
            return False
        ctx = shake256(DS_BALLOT, ballot["id"].encode(), ballot["upk"], ser(c))
        if not verify_preimage(R, self.Ah, R.fwd_many(c), ctx, pp.kappa,
                               self._ballot_sigma(), ballot["pi"]):
            return False
        h = shake256(b"bh", payload, ballot["sig"])
        return all(x["hash"] != h for x in BB)

    def box(self, BB, ballot):
        payload = ser(ballot["ct"]) + ballot["pi"].data[0] \
            + ser(ballot["pi"].data[1])
        BB.append(dict(id=ballot["id"], upk=ballot["upk"], ct=ballot["ct"],
                       pi=ballot["pi"], sig=ballot["sig"],
                       hash=shake256(b"bh", payload, ballot["sig"])))
        return BB

    def verify_vote(self, vid, st_pre, BB):
        c = self.E.enc_from_wit(self.pd["pk"], st_pre["x"])
        last = None
        for e in BB:
            if e["id"] == vid:
                last = e
        if last is None:
            return False
        return np.array_equal(last["ct"], c)

    def publish(self, BB):
        return [dict(e) for e in BB]

    def _weed(self, BB):
        last = {}
        for e in BB:
            last[e["id"]] = e
        return [last[k] for k in sorted(last.keys())]

    def tally(self, BB, ctx=b"tally"):
        kept = self._weed(BB)
        cur = [e["ct"] for e in kept]
        mixproofs = []
        for k, S in enumerate(self.shufflers):
            out, pr = S.mix(cur, shake256(ctx, b"mix", k),
                            shake256(ctx, b"mc", k))
            mixproofs.append((cur, out, pr))
            cur = out
        tk, asg = self.sd["tk"], self.sd["asg"]
        shares = []
        for j in self.sd["quorum"]:
            sj = tk.party_key(j, asg)
            kc, kr = self.pd["kcoms"][j], self.sd["krands"][j]
            sh = self.dd.share(sj, kc, kr, cur, shake256(ctx, b"ds", j),
                               shake256(ctx, b"dc", j), len(self.sd["quorum"]))
            shares.append(sh)
        plains = self.dd.combine(cur, shares)
        counts = [0] * self.n_cands
        for m in plains:
            j = decode_vote(m, self.n_cands)
            if j is not None:
                counts[j] += 1
        proof = dict(kept=[e["hash"] for e in kept], mix=mixproofs,
                     shares=shares, plains=plains)
        size = sum(p.size_bits for _, _, p in mixproofs)
        size += sum(sh.size_bits for sh in shares)
        proof["size_bits"] = size
        return counts, proof

    def verify(self, PBB, result, proof, ctx=b"tally"):
        kept = self._weed(PBB)
        if [e["hash"] for e in kept] != proof["kept"]:
            return False
        for e in kept:
            if not self.valid([x for x in PBB if x["hash"] != e["hash"]], e):
                return False
        cur = [e["ct"] for e in kept]
        for k, (inp, out, pr) in enumerate(proof["mix"]):
            if not all(np.array_equal(inp[i], cur[i]) for i in range(len(cur))):
                return False
            if not self.shufflers[k].verify(inp, out, pr,
                                            shake256(ctx, b"mc", k)):
                return False
            cur = out
        for idx, j in enumerate(self.sd["quorum"]):
            if not self.dd.verify_share(self.pd["kcoms"][j], cur,
                                        proof["shares"][idx],
                                        shake256(ctx, b"dc", j),
                                        len(self.sd["quorum"])):
                return False
        plains = self.dd.combine(cur, proof["shares"])
        counts = [0] * self.n_cands
        for m in plains:
            jj = decode_vote(m, self.n_cands)
            if jj is not None:
                counts[jj] += 1
        return counts == list(result)

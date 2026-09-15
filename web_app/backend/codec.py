import base64
import re

import numpy as np

from .engine.proofs import Proof, bits_of


def b64(raw):
    return base64.b64encode(bytes(raw)).decode("ascii")


def unb64(text):
    return base64.b64decode(text.encode("ascii") if isinstance(text, str) else text)


def ring_to_b64(a):
    return b64(np.ascontiguousarray(np.asarray(a, dtype=np.int64).astype("<u4")).tobytes())


def ring_from_b64(text, L, n):
    raw = unb64(text)
    if len(raw) != 4 * L * n:
        raise ValueError("ring element has wrong length")
    return np.frombuffer(raw, dtype="<u4").astype(np.int64).reshape(L, n)


def i64_to_b64(a):
    return b64(np.ascontiguousarray(np.asarray(a, dtype=np.int64).astype("<i8")).tobytes())


def i64_from_b64(text, rows, n):
    raw = unb64(text)
    if len(raw) != 8 * rows * n:
        raise ValueError("response vector has wrong length")
    return np.frombuffer(raw, dtype="<i8").astype(np.int64).reshape(rows, n)


def ballot_to_json(ballot):
    return dict(
        c1=dict(u=ring_to_b64(ballot["C1"][0]), w=ring_to_b64(ballot["C1"][1])),
        c2=dict(u=ring_to_b64(ballot["C2"][0]), w=ring_to_b64(ballot["C2"][1])),
        proofs=[dict(d=b64(pr.data[0]), z=i64_to_b64(pr.data[1]), sigma=float(sig))
                for pr, sig in ballot["pi"]])


def ballot_from_json(V, obj):
    L, n = V.R.L, V.R.n
    C1 = (ring_from_b64(obj["c1"]["u"], L, n), ring_from_b64(obj["c1"]["w"], L, n))
    C2 = (ring_from_b64(obj["c2"]["u"], L, n), ring_from_b64(obj["c2"]["w"], L, n))
    proofs = obj["proofs"]
    if len(proofs) != 2:
        raise ValueError("a ballot carries exactly two proofs")
    pis = []
    for p in proofs:
        d = unb64(p["d"])
        if len(d) != 32:
            raise ValueError("challenge seed must be 32 bytes")
        sig = float(p["sigma"])
        if not (sig > 0):
            raise ValueError("invalid sigma")
        z = i64_from_b64(p["z"], 3, n)
        pis.append((Proof("preimage", (d, z), 3 * n * bits_of(sig) + 256), sig))
    size = 4 * n * V.R.logq + sum(pr.size_bits for pr, _ in pis)
    return dict(C1=C1, C2=C2, pi=pis, size_bits=size)


def ballot_to_npz(ballot):
    (pr0, s0), (pr1, s1) = ballot["pi"]
    return dict(c1u=ballot["C1"][0].astype(np.uint32), c1w=ballot["C1"][1].astype(np.uint32),
                c2u=ballot["C2"][0].astype(np.uint32), c2w=ballot["C2"][1].astype(np.uint32),
                d0=np.frombuffer(pr0.data[0], dtype=np.uint8), z0=pr0.data[1],
                d1=np.frombuffer(pr1.data[0], dtype=np.uint8), z1=pr1.data[1],
                sig=np.array([s0, s1], dtype=np.float64),
                size=np.array([ballot["size_bits"]], dtype=np.int64))


def ballot_from_npz(data):
    n = data["z0"].shape[1]
    sig = data["sig"]
    pis = []
    for d, z, s in ((data["d0"], data["z0"], sig[0]), (data["d1"], data["z1"], sig[1])):
        pis.append((Proof("preimage", (d.tobytes(), z.astype(np.int64)),
                          3 * n * bits_of(float(s)) + 256), float(s)))
    return dict(C1=(data["c1u"].astype(np.int64), data["c1w"].astype(np.int64)),
                C2=(data["c2u"].astype(np.int64), data["c2w"].astype(np.int64)),
                pi=pis, size_bits=int(data["size"][0]))


def cred_to_bytes(bits):
    out = bytearray((len(bits) + 7) // 8)
    for i, b in enumerate(bits):
        if b:
            out[i // 8] |= 1 << (i % 8)
    return bytes(out)


def cred_from_bytes(raw, lam):
    return [(raw[i // 8] >> (i % 8)) & 1 for i in range(lam)]


def cred_to_text(bits):
    h = cred_to_bytes(bits).hex().upper()
    return "-".join(h[i:i + 4] for i in range(0, len(h), 4))


def cred_from_text(text, lam):
    h = re.sub(r"[^0-9A-Fa-f]", "", text or "")
    if len(h) != (lam + 7) // 8 * 2:
        raise ValueError("a credential has %d hexadecimal digits" % ((lam + 7) // 8 * 2))
    return cred_from_bytes(bytes.fromhex(h), lam)

import hashlib

DS_A = b"OYLAMA/pk-a"
DS_CHAL = b"OYLAMA/challenge"
DS_NOISE = b"OYLAMA/noise"
DS_COM = b"OYLAMA/commit-a"
DS_KDF = b"OYLAMA/kdf"
DS_BALLOT = b"OYLAMA/ballot"
DS_TRACKER = b"OYLAMA/tracker"
DS_SHUFFLE = b"OYLAMA/shuffle"
DS_DDEC = b"OYLAMA/ddec"
DS_THETA = b"OYLAMA/theta"
DS_ROSTER = b"OYLAMA/roster"


def shake256(*chunks, outlen=32):
    h = hashlib.shake_256()
    for c in chunks:
        if isinstance(c, int):
            c = c.to_bytes(8, "little")
        h.update(len(c).to_bytes(4, "little"))
        h.update(c)
    return h.digest(outlen)


class XofReader:
    def __init__(self, seed, domain):
        self._pre = bytes(domain) + bytes(seed)
        self._buf = b""
        self._pos = 0
        self._ctr = 0
        self._blk = 1 << 14

    def _more(self, need):
        want = max(need, self._blk)
        parts = []
        got = 0
        while got < want:
            h = hashlib.shake_256()
            h.update(self._pre)
            h.update(self._ctr.to_bytes(8, "little"))
            parts.append(h.digest(self._blk))
            got += self._blk
            self._ctr += 1
        self._buf = self._buf[self._pos:] + b"".join(parts)
        self._pos = 0

    def read(self, nbytes):
        if self._pos + nbytes > len(self._buf):
            self._more(nbytes)
        out = self._buf[self._pos:self._pos + nbytes]
        self._pos += nbytes
        return out

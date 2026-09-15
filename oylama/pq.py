import os

_BACKEND = None
_OQS = None

try:
    import oqs as _oqs_mod
    _OQS = _oqs_mod
    _BACKEND = "liboqs"
except Exception:
    _BACKEND = None

if _BACKEND is None:
    from dilithium_py.ml_dsa import ML_DSA_65 as _MLDSA
    from kyber_py.ml_kem import ML_KEM_768 as _MLKEM
    _BACKEND = "pure-python"


def backend():
    return _BACKEND


class Sig:
    alg = "ML-DSA-65"

    @staticmethod
    def keygen():
        if _BACKEND == "liboqs":
            s = _OQS.Signature(Sig.alg)
            pk = s.generate_keypair()
            return pk, s
        return _MLDSA.keygen()

    @staticmethod
    def sign(sk, msg):
        if _BACKEND == "liboqs":
            return sk.sign(msg)
        return _MLDSA.sign(sk, msg)

    _ver = None

    @staticmethod
    def verify(pk, msg, sig):
        if _BACKEND == "liboqs":
            if Sig._ver is None:
                Sig._ver = _OQS.Signature(Sig.alg)
            return Sig._ver.verify(msg, sig, pk)
        return _MLDSA.verify(pk, msg, sig)


class Kem:
    alg = "ML-KEM-768"

    @staticmethod
    def keygen():
        if _BACKEND == "liboqs":
            k = _OQS.KeyEncapsulation(Kem.alg)
            ek = k.generate_keypair()
            return ek, k
        return _MLKEM.keygen()

    _enc = None

    @staticmethod
    def encaps(ek):
        if _BACKEND == "liboqs":
            if Kem._enc is None:
                Kem._enc = _OQS.KeyEncapsulation(Kem.alg)
            ct, ss = Kem._enc.encap_secret(ek)
            return ss, ct
        return _MLKEM.encaps(ek)

    @staticmethod
    def decaps(dk, ct):
        if _BACKEND == "liboqs":
            return dk.decap_secret(ct)
        return _MLKEM.decaps(dk, ct)


SIG_PK_BYTES = 1952
SIG_BYTES = 3309
KEM_PK_BYTES = 1184
KEM_CT_BYTES = 1088

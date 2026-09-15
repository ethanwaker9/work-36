import contextlib
import io
import os

_OQS = None
BACKEND = None

if os.environ.get("OYLAMA_PQ", "") != "python":
    try:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            import oqs as _oqs_mod
            _oqs_mod.Signature("ML-DSA-65")
        _OQS = _oqs_mod
        BACKEND = "liboqs"
    except BaseException:
        _OQS = None

if _OQS is None:
    from dilithium_py.ml_dsa import ML_DSA_65 as _MLDSA
    from kyber_py.ml_kem import ML_KEM_768 as _MLKEM
    BACKEND = "pure-python"


class Sig:
    alg = "ML-DSA-65"

    @staticmethod
    def keygen():
        if _OQS is not None:
            s = _OQS.Signature(Sig.alg)
            pk = s.generate_keypair()
            return bytes(pk), bytes(s.export_secret_key())
        pk, sk = _MLDSA.keygen()
        return bytes(pk), bytes(sk)

    @staticmethod
    def sign(sk, msg):
        if _OQS is not None:
            return bytes(_OQS.Signature(Sig.alg, secret_key=sk).sign(msg))
        return bytes(_MLDSA.sign(sk, msg))

    @staticmethod
    def verify(pk, msg, sig):
        try:
            if _OQS is not None:
                return bool(_OQS.Signature(Sig.alg).verify(msg, sig, pk))
            return bool(_MLDSA.verify(pk, msg, sig))
        except Exception:
            return False


class Kem:
    alg = "ML-KEM-768"

    @staticmethod
    def keygen():
        if _OQS is not None:
            k = _OQS.KeyEncapsulation(Kem.alg)
            ek = k.generate_keypair()
            return bytes(ek), bytes(k.export_secret_key())
        ek, dk = _MLKEM.keygen()
        return bytes(ek), bytes(dk)

    @staticmethod
    def encaps(ek):
        if _OQS is not None:
            ct, ss = _OQS.KeyEncapsulation(Kem.alg).encap_secret(ek)
            return bytes(ss), bytes(ct)
        ss, ct = _MLKEM.encaps(ek)
        return bytes(ss), bytes(ct)

    @staticmethod
    def decaps(dk, ct):
        if _OQS is not None:
            return bytes(_OQS.KeyEncapsulation(Kem.alg, secret_key=dk).decap_secret(ct))
        return bytes(_MLKEM.decaps(dk, ct))

import os

from Crypto.Cipher import AES

from .xof import DS_KDF, shake256


def kdf(shared, label, nbytes=32):
    return shake256(shared, label, DS_KDF, outlen=nbytes)


def seal(key, plaintext, aad=b""):
    nonce = os.urandom(12)
    c = AES.new(key, AES.MODE_GCM, nonce=nonce)
    c.update(aad)
    ct, tag = c.encrypt_and_digest(plaintext)
    return nonce + tag + ct


def unseal(key, blob, aad=b""):
    nonce, tag, ct = blob[:12], blob[12:28], blob[28:]
    c = AES.new(key, AES.MODE_GCM, nonce=nonce)
    c.update(aad)
    return c.decrypt_and_verify(ct, tag)

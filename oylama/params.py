from dataclasses import dataclass, field

from .ring import find_ntt_primes


@dataclass
class Params:
    name: str = "Oylama-128"
    N: int = 256
    rank: int = 16
    nprimes: int = 3
    prime_bits: int = 31
    p: int = 2
    kappa: int = 23
    sec: int = 40
    com_n: int = 4
    n_mix: int = 2
    n_trustee: int = 4
    threshold: int = 3
    batch: int = 32
    reject_M: float = 3.0
    primes: list = field(default_factory=list)

    def __post_init__(self):
        if not self.primes:
            self.primes = find_ntt_primes(self.N, self.prime_bits, self.nprimes)

    @property
    def m_ct(self):
        return self.rank + 1

    @property
    def com_k_mix(self):
        return self.com_n + 3

    @property
    def com_k_dec(self):
        return self.com_n + self.rank + 2

    @property
    def q(self):
        v = 1
        for x in self.primes:
            v *= x
        return v

    @property
    def logq(self):
        return self.q.bit_length()


SMALL = Params(name="Oylama-toy", N=256, rank=8, nprimes=2, prime_bits=24)
STD = Params()

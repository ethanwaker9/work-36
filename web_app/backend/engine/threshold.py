from itertools import combinations

from .xof import DS_NOISE, shake256


def unqualified_sets(n_parties, threshold):
    return list(combinations(range(n_parties), threshold - 1))


class ThresholdKey:
    def __init__(self, ring, n_parties, threshold, shares):
        self.R = ring
        self.parties = n_parties
        self.t = threshold
        self.unqual = unqualified_sets(n_parties, threshold)
        self.shares = dict(shares)

    @classmethod
    def deal(cls, ring, n_parties, threshold, s, seed):
        R = ring
        unqual = unqualified_sets(n_parties, threshold)
        acc = R.zeros()
        shares = {}
        for idx, U in enumerate(unqual[:-1]):
            sh = R.uniform(shake256(seed, b"sh", idx), DS_NOISE)
            shares[U] = sh
            acc = R.add(acc, sh)
        shares[unqual[-1]] = R.sub(s, acc)
        return cls(ring, n_parties, threshold, shares)

    def holdings(self, j):
        return {U: self.shares[U] for U in self.unqual if j not in U}

    @classmethod
    def from_holdings(cls, ring, n_parties, threshold, holdings):
        merged = {}
        for h in holdings.values():
            merged.update(h)
        return cls(ring, n_parties, threshold, merged)

    def assign(self, quorum):
        assigned = {j: [] for j in quorum}
        taken = set()
        for U in self.unqual:
            for j in quorum:
                if j not in U and U not in taken and U in self.shares:
                    assigned[j].append(U)
                    taken.add(U)
                    break
        if len(taken) != len(self.unqual):
            raise ValueError("quorum too small")
        return assigned

    def party_key(self, j, assigned):
        R = self.R
        acc = R.zeros()
        for U in assigned[j]:
            acc = R.add(acc, self.shares[U])
        return acc

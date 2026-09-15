package org.oylama.crypto;

public final class PackedBgv implements CtOps {
    public final Ring R;
    public final Slots slots;
    public final int p;
    public final int n;

    public static final class Enc {
        public final int[][] ct;
        public final long[][] x;

        Enc(int[][] ct, long[][] x) {
            this.ct = ct;
            this.x = x;
        }
    }

    public static final class KeyPair {
        public final int[][] pk;
        public final int[] s;

        KeyPair(int[][] pk, int[] s) {
            this.pk = pk;
            this.s = s;
        }
    }

    public PackedBgv(int n, int[] primes, int p) {
        this.R = new Ring(n, primes);
        this.slots = new Slots(n, p);
        this.p = p;
        this.n = n;
    }

    @Override
    public int n() {
        return n;
    }

    public KeyPair keygen(byte[] seed) {
        int[] a = R.uniform(Hash.h(seed, "a"), "OYLAMA/pk-a");
        int[] s = R.fromSmall(Sample.ternary(Hash.h(seed, "s"), "OYLAMA/noise", n));
        int[] e = R.fromSmall(Sample.ternary(Hash.h(seed, "e"), "OYLAMA/noise", n));
        int[] b = R.add(R.mul(a, s), R.scalar(e, p));
        return new KeyPair(new int[][]{a, b}, s);
    }

    public Enc encParts(int[][] pkHat, byte[] seed, long[] mpoly) {
        long[] r = Sample.ternary(seed, "r", n);
        long[] e1 = Sample.ternary(seed, "e1", n);
        long[] e0 = Sample.ternary(seed, "e0", n);
        int[] rh = R.fwd(R.fromSmall(r));
        int[] u = R.add(R.inv(R.mulHat(pkHat[0], rh)), R.scalar(R.fromSmall(e1), p));
        long[] f0 = new long[n];
        for (int i = 0; i < n; i++) {
            f0[i] = mpoly[i] + (long) p * e0[i];
        }
        int[] w = R.add(R.inv(R.mulHat(pkHat[1], rh)), R.fromSmall(f0));
        return new Enc(new int[][]{u, w}, new long[][]{r, e1, f0});
    }

    public int[][] encSlots(int[][] pkHat, byte[] seed, long[] vec) {
        return encParts(pkHat, seed, slots.encode(vec)).ct;
    }

    public long[] decSlots(int[] s, int[][] c) {
        return slots.decode(R.centerModP(R.sub(c[1], R.mul(s, c[0])), p));
    }

    public int[][] hat(int[][] pk) {
        return new int[][]{R.fwd(pk[0]), R.fwd(pk[1])};
    }

    @Override
    public int[][] add(int[][] a, int[][] b) {
        return new int[][]{R.add(a[0], b[0]), R.add(a[1], b[1])};
    }

    @Override
    public int[][] sub(int[][] a, int[][] b) {
        return new int[][]{R.sub(a[0], b[0]), R.sub(a[1], b[1])};
    }

    public int[][] mulPlainPoly(int[][] c, long[] mpoly) {
        int[] mh = R.fwd(R.fromSmall(mpoly));
        return new int[][]{R.inv(R.mulHat(R.fwd(c[0]), mh)), R.inv(R.mulHat(R.fwd(c[1]), mh))};
    }

    @Override
    public int[][] mulPlainSlots(int[][] c, long[] vec) {
        return mulPlainPoly(c, slots.encode(vec));
    }

    @Override
    public int[][] addPlainSlots(int[][] c, long[] vec) {
        return new int[][]{c[0], R.add(c[1], R.fromSmall(slots.encode(vec)))};
    }

    @Override
    public int[][] scale(int[][] c, long k) {
        return new int[][]{R.scalar(c[0], k), R.scalar(c[1], k)};
    }

    @Override
    public int[][] zero() {
        return new int[][]{R.zeros(), R.zeros()};
    }

    public int[] autom(int[] poly, int k) {
        long g = Ntt.powmod(3, k, 2L * n);
        int[] out = new int[poly.length];
        for (int i = 0; i < R.L; i++) {
            int qi = R.q[i];
            int o = i * n;
            for (int j = 0; j < n; j++) {
                long idx = j * g;
                int tgt = (int) (idx % n);
                boolean neg = ((idx / n) & 1) == 1;
                int v = poly[o + j];
                out[o + tgt] = neg ? (v == 0 ? 0 : qi - v) : v;
            }
        }
        return out;
    }

    @Override
    public int[][] rotCt(int[][] c, int k) {
        return new int[][]{autom(c[0], k), autom(c[1], k)};
    }

    public int[] rotKey(int[] s, int k) {
        return autom(s, k);
    }

    public int[][] rotPk(int[][] pk, int k) {
        return new int[][]{autom(pk[0], k), autom(pk[1], k)};
    }
}

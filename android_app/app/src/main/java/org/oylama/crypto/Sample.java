package org.oylama.crypto;

public final class Sample {
    private Sample() {
    }

    public static final class Sparse {
        public final int[] idx;
        public final int[] sgn;

        Sparse(int[] idx, int[] sgn) {
            this.idx = idx;
            this.sgn = sgn;
        }
    }

    public static long[] ternary(byte[] seed, String domain, int n) {
        return ternary(seed, domain, n, 1)[0];
    }

    public static long[][] ternary(byte[] seed, String domain, int n, int count) {
        Xof rd = new Xof(seed, domain);
        int need = n * count;
        long[] flat = new long[need];
        int got = 0;
        while (got < need) {
            byte[] raw = rd.read(Math.max(1024, need - got) * 2);
            for (int i = 0; i < raw.length && got < need; i++) {
                int v = raw[i] & 3;
                if (v < 3) {
                    flat[got++] = v - 1;
                }
            }
        }
        long[][] out = new long[count][n];
        for (int c = 0; c < count; c++) {
            System.arraycopy(flat, c * n, out[c], 0, n);
        }
        return out;
    }

    public static long[][] gaussian(byte[] seed, String domain, int n, double sigma, int count) {
        Xof rd = new Xof(seed, domain);
        int need = n * count;
        byte[] raw = rd.read(need * 8 + 64);
        long[][] out = new long[count][n];
        for (int i = 0; i < need; i++) {
            int o = 8 * i;
            long a = (raw[o] & 0xFFL) | ((raw[o + 1] & 0xFFL) << 8) | ((raw[o + 2] & 0xFFL) << 16) | ((raw[o + 3] & 0xFFL) << 24);
            long b = (raw[o + 4] & 0xFFL) | ((raw[o + 5] & 0xFFL) << 8) | ((raw[o + 6] & 0xFFL) << 16) | ((raw[o + 7] & 0xFFL) << 24);
            double u1 = (a + 1.0) / (4294967296.0 + 2.0);
            double u2 = b / 4294967296.0;
            double z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
            out[i / n][i % n] = (long) Math.rint(z * sigma);
        }
        return out;
    }

    public static Sparse challenge(byte[] seed, String domain, int n, int weight) {
        Xof rd = new Xof(seed, domain);
        byte[] c = new byte[n];
        int placed = 0;
        byte[] raw = new byte[8];
        while (placed < weight) {
            rd.read(raw, 0, 8);
            long v = (raw[0] & 0xFFL) | ((raw[1] & 0xFFL) << 8) | ((raw[2] & 0xFFL) << 16) | ((raw[3] & 0xFFL) << 24);
            int idx = (int) (v % n);
            byte sgn = (byte) (((raw[4] & 1) != 0) ? 1 : -1);
            if (c[idx] == 0) {
                c[idx] = sgn;
                placed++;
            }
        }
        int[] idx = new int[weight];
        int[] sg = new int[weight];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (c[i] != 0) {
                idx[k] = i;
                sg[k] = c[i];
                k++;
            }
        }
        return new Sparse(idx, sg);
    }

    public static long[] dense(Sparse d, int n) {
        long[] r = new long[n];
        for (int t = 0; t < d.idx.length; t++) {
            r[d.idx[t]] = d.sgn[t];
        }
        return r;
    }

    public static int[] permutation(byte[] seed, String domain, int m) {
        Xof rd = new Xof(seed, domain);
        int[] perm = new int[m];
        for (int i = 0; i < m; i++) {
            perm[i] = i;
        }
        byte[] raw = new byte[8];
        for (int i = m - 1; i > 0; i--) {
            rd.read(raw, 0, 8);
            long v = 0;
            for (int k = 7; k >= 0; k--) {
                v = (v << 8) | (raw[k] & 0xFFL);
            }
            int j = (int) Long.remainderUnsigned(v, i + 1);
            int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        return perm;
    }

    public static long[] sparseMul(Sparse d, long[] x) {
        int n = x.length;
        long[] acc = new long[n];
        for (int t = 0; t < d.idx.length; t++) {
            int i = d.idx[t];
            long s = d.sgn[t];
            for (int k = 0; k < i; k++) {
                acc[k] -= s * x[n - i + k];
            }
            for (int k = i; k < n; k++) {
                acc[k] += s * x[k - i];
            }
        }
        return acc;
    }

    public static long[][] sparseMul(Sparse d, long[][] x) {
        long[][] r = new long[x.length][];
        for (int i = 0; i < x.length; i++) {
            r[i] = sparseMul(d, x[i]);
        }
        return r;
    }
}

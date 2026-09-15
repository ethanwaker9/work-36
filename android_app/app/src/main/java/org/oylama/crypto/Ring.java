package org.oylama.crypto;

import java.math.BigInteger;

public final class Ring {
    public final int n;
    public final int L;
    public final int[] q;
    final double[] qinv;
    final Ntt[] ntt;
    public final BigInteger Q;
    final BigInteger[] qhat;
    final long[] qhatInv;
    public final int logq;
    final double Qd;

    public Ring(int n, int[] primes) {
        this.n = n;
        this.L = primes.length;
        this.q = primes.clone();
        this.qinv = new double[L];
        this.ntt = new Ntt[L];
        BigInteger prod = BigInteger.ONE;
        for (int i = 0; i < L; i++) {
            qinv[i] = 1.0 / primes[i];
            ntt[i] = new Ntt(n, primes[i]);
            prod = prod.multiply(BigInteger.valueOf(primes[i]));
        }
        Q = prod;
        Qd = prod.doubleValue();
        logq = prod.bitLength();
        qhat = new BigInteger[L];
        qhatInv = new long[L];
        for (int i = 0; i < L; i++) {
            qhat[i] = prod.divide(BigInteger.valueOf(primes[i]));
            long r = qhat[i].mod(BigInteger.valueOf(primes[i])).longValue();
            qhatInv[i] = Ntt.powmod(r, primes[i] - 2L, primes[i]);
        }
    }

    static int mm(long x, int q, double qi) {
        long t = x - (long) (x * qi) * q;
        if (t < 0) {
            t += q;
        } else if (t >= q) {
            t -= q;
        }
        return (int) t;
    }

    public int[] zeros() {
        return new int[L * n];
    }

    public int[] one() {
        int[] r = zeros();
        for (int i = 0; i < L; i++) {
            r[i * n] = 1;
        }
        return r;
    }

    public int[] add(int[] a, int[] b) {
        int[] r = new int[a.length];
        for (int i = 0; i < L; i++) {
            int qi = q[i];
            for (int k = i * n, e = k + n; k < e; k++) {
                int v = a[k] + b[k];
                r[k] = v >= qi ? v - qi : v;
            }
        }
        return r;
    }

    public void addInPlace(int[] a, int[] b) {
        for (int i = 0; i < L; i++) {
            int qi = q[i];
            for (int k = i * n, e = k + n; k < e; k++) {
                int v = a[k] + b[k];
                a[k] = v >= qi ? v - qi : v;
            }
        }
    }

    public int[] sub(int[] a, int[] b) {
        int[] r = new int[a.length];
        for (int i = 0; i < L; i++) {
            int qi = q[i];
            for (int k = i * n, e = k + n; k < e; k++) {
                int v = a[k] - b[k];
                r[k] = v < 0 ? v + qi : v;
            }
        }
        return r;
    }

    public int[] neg(int[] a) {
        int[] r = new int[a.length];
        for (int i = 0; i < L; i++) {
            int qi = q[i];
            for (int k = i * n, e = k + n; k < e; k++) {
                r[k] = a[k] == 0 ? 0 : qi - a[k];
            }
        }
        return r;
    }

    public int[] fwd(int[] a) {
        int[] r = a.clone();
        for (int i = 0; i < L; i++) {
            ntt[i].fwd(r, i * n);
        }
        return r;
    }

    public int[] inv(int[] a) {
        int[] r = a.clone();
        for (int i = 0; i < L; i++) {
            ntt[i].inv(r, i * n);
        }
        return r;
    }

    public int[] mulHat(int[] a, int[] b) {
        int[] r = new int[a.length];
        for (int i = 0; i < L; i++) {
            int qi = q[i];
            double qq = qinv[i];
            for (int k = i * n, e = k + n; k < e; k++) {
                r[k] = mm((long) a[k] * b[k], qi, qq);
            }
        }
        return r;
    }

    public int[] addMulHat(int[] acc, int[] a, int[] b) {
        for (int i = 0; i < L; i++) {
            int qi = q[i];
            double qq = qinv[i];
            for (int k = i * n, e = k + n; k < e; k++) {
                int v = acc[k] + mm((long) a[k] * b[k], qi, qq);
                acc[k] = v >= qi ? v - qi : v;
            }
        }
        return acc;
    }

    public int[] mul(int[] a, int[] b) {
        return inv(mulHat(fwd(a), fwd(b)));
    }

    public int[] scalar(int[] a, long k) {
        int[] r = new int[a.length];
        for (int i = 0; i < L; i++) {
            int qi = q[i];
            long kk = Math.floorMod(k, (long) qi);
            double qq = qinv[i];
            for (int j = i * n, e = j + n; j < e; j++) {
                r[j] = mm(a[j] * kk, qi, qq);
            }
        }
        return r;
    }

    public int[] constant(long c) {
        int[] r = zeros();
        for (int i = 0; i < L; i++) {
            r[i * n] = (int) Math.floorMod(c, (long) q[i]);
        }
        return r;
    }

    public int[] fromSmall(long[] v) {
        int[] r = new int[L * n];
        for (int i = 0; i < L; i++) {
            long qi = q[i];
            int o = i * n;
            for (int k = 0; k < n; k++) {
                r[o + k] = (int) Math.floorMod(v[k], qi);
            }
        }
        return r;
    }

    public int[] fromBig(BigInteger[] v) {
        int[] r = new int[L * n];
        BigInteger[] qs = new BigInteger[L];
        for (int i = 0; i < L; i++) {
            qs[i] = BigInteger.valueOf(q[i]);
        }
        for (int k = 0; k < n; k++) {
            BigInteger x = v[k];
            if (x.bitLength() < 62) {
                long xl = x.longValue();
                for (int i = 0; i < L; i++) {
                    r[i * n + k] = (int) Math.floorMod(xl, (long) q[i]);
                }
            } else {
                for (int i = 0; i < L; i++) {
                    r[i * n + k] = x.mod(qs[i]).intValue();
                }
            }
        }
        return r;
    }

    public BigInteger[] center(int[] a) {
        BigInteger[] out = new BigInteger[n];
        BigInteger half = Q.shiftRight(1);
        for (int k = 0; k < n; k++) {
            BigInteger acc = BigInteger.ZERO;
            for (int i = 0; i < L; i++) {
                long t = (a[i * n + k] * qhatInv[i]) % q[i];
                acc = acc.add(qhat[i].multiply(BigInteger.valueOf(t)));
            }
            acc = acc.mod(Q);
            if (acc.compareTo(half) > 0) {
                acc = acc.subtract(Q);
            }
            out[k] = acc;
        }
        return out;
    }

    public long[] centerModP(int[] a, int p) {
        BigInteger[] c = center(a);
        BigInteger bp = BigInteger.valueOf(p);
        long[] r = new long[n];
        for (int k = 0; k < n; k++) {
            r[k] = c[k].mod(bp).longValue();
        }
        return r;
    }

    public double[] approxCenter(int[] a) {
        double[] frac = new double[n];
        for (int i = 0; i < L; i++) {
            int qi = q[i];
            long hi = qhatInv[i];
            int o = i * n;
            for (int k = 0; k < n; k++) {
                long t = (a[o + k] * hi) % qi;
                frac[k] += (double) t / (double) qi;
            }
        }
        for (int k = 0; k < n; k++) {
            double f = frac[k] - Math.floor(frac[k]);
            if (f > 0.5) {
                f -= 1.0;
            }
            frac[k] = f * Qd;
        }
        return frac;
    }

    public int[] invHat(int[] ah) {
        int[] r = new int[ah.length];
        for (int i = 0; i < L; i++) {
            int qi = q[i];
            double qq = qinv[i];
            long e = qi - 2L;
            for (int k = i * n, end = k + n; k < end; k++) {
                long base = ah[k];
                long res = 1;
                long ee = e;
                while (ee > 0) {
                    if ((ee & 1) == 1) {
                        res = mm(res * base, qi, qq);
                    }
                    base = mm(base * base, qi, qq);
                    ee >>= 1;
                }
                r[k] = (int) res;
            }
        }
        return r;
    }

    public int[][] uniformMany(byte[] seed, String domain, int count) {
        int need = n * count;
        Xof rd = new Xof(seed, domain);
        byte[] raw = rd.read(8 * need * L + 8);
        int[][] out = new int[count][L * n];
        for (int i = 0; i < L; i++) {
            long qi = q[i];
            for (int j = 0; j < need; j++) {
                int o = 8 * (i * need + j);
                long w = 0;
                for (int b = 7; b >= 0; b--) {
                    w = (w << 8) | (raw[o + b] & 0xFFL);
                }
                w >>>= 1;
                out[j / n][i * n + (j % n)] = (int) (w % qi);
            }
        }
        return out;
    }

    public int[] uniform(byte[] seed, String domain) {
        return uniformMany(seed, domain, 1)[0];
    }

    public int[][] fwdSmall(long[][] x) {
        int[][] r = new int[x.length][];
        for (int j = 0; j < x.length; j++) {
            r[j] = fwd(fromSmall(x[j]));
        }
        return r;
    }

    public int[][] fwdAll(int[][] x) {
        int[][] r = new int[x.length][];
        for (int j = 0; j < x.length; j++) {
            r[j] = fwd(x[j]);
        }
        return r;
    }

    public int[][] invAll(int[][] x) {
        int[][] r = new int[x.length][];
        for (int j = 0; j < x.length; j++) {
            r[j] = inv(x[j]);
        }
        return r;
    }

    public int[][] matvecHat(int[][][] A, int[][] xh) {
        int rows = A.length;
        int cols = A[0].length;
        int[][] out = new int[rows][];
        for (int r = 0; r < rows; r++) {
            int[] o = new int[L * n];
            for (int i = 0; i < L; i++) {
                int qi = q[i];
                int base = i * n;
                for (int k = 0; k < n; k++) {
                    long acc = 0;
                    int idx = base + k;
                    for (int c = 0; c < cols; c++) {
                        int[] a = A[r][c];
                        if (a == null) {
                            continue;
                        }
                        acc += (long) a[idx] * xh[c][idx];
                    }
                    o[idx] = (int) (acc % qi);
                }
            }
            out[r] = o;
        }
        return out;
    }

    public int[] dotHat(int[][] b, int[][] xh) {
        int cols = b.length;
        int[] o = new int[L * n];
        for (int i = 0; i < L; i++) {
            int qi = q[i];
            int base = i * n;
            for (int k = 0; k < n; k++) {
                long acc = 0;
                int idx = base + k;
                for (int c = 0; c < cols; c++) {
                    acc += (long) b[c][idx] * xh[c][idx];
                }
                o[idx] = (int) (acc % qi);
            }
        }
        return o;
    }
}

package org.oylama.crypto;

import java.math.BigInteger;

public final class Ntt {
    final int n;
    final int q;
    final int logn;
    final int[] zetas;
    final int[] izetas;
    final long[] zShoup;
    final long[] izShoup;
    final int ninv;
    final long ninvShoup;

    public Ntt(int n, int q) {
        this.n = n;
        this.q = q;
        this.logn = Integer.numberOfTrailingZeros(n);
        long psi = 0;
        for (long g = 2; g < (1 << 20); g++) {
            long c = powmod(g, (q - 1L) / (2L * n), q);
            if (powmod(c, n, q) == q - 1L) {
                psi = c;
                break;
            }
        }
        long psiInv = powmod(psi, q - 2L, q);
        long[] pw = new long[n];
        long[] ipw = new long[n];
        pw[0] = 1;
        ipw[0] = 1;
        for (int i = 1; i < n; i++) {
            pw[i] = pw[i - 1] * psi % q;
            ipw[i] = ipw[i - 1] * psiInv % q;
        }
        zetas = new int[n];
        izetas = new int[n];
        zShoup = new long[n];
        izShoup = new long[n];
        for (int i = 0; i < n; i++) {
            int j = brv(i, logn);
            zetas[i] = (int) pw[j];
            izetas[i] = (int) ipw[j];
            zShoup[i] = (pw[j] << 32) / q;
            izShoup[i] = (ipw[j] << 32) / q;
        }
        ninv = (int) powmod(n, q - 2L, q);
        ninvShoup = ((long) ninv << 32) / q;
    }

    static int brv(int i, int bits) {
        int r = 0;
        for (int k = 0; k < bits; k++) {
            r = (r << 1) | (i & 1);
            i >>= 1;
        }
        return r;
    }

    static long powmod(long b, long e, long m) {
        long r = 1;
        long x = b % m;
        while (e > 0) {
            if ((e & 1) == 1) {
                r = r * x % m;
            }
            x = x * x % m;
            e >>= 1;
        }
        return r;
    }

    public void fwd(int[] a, int off) {
        final int q = this.q;
        final int twoQ = 2 * q;
        int ln = n >> 1;
        int m = 1;
        while (ln >= 1) {
            for (int j = 0; j < m; j++) {
                final long z = zetas[m + j];
                final long zs = zShoup[m + j];
                int base = off + j * 2 * ln;
                int end = base + ln;
                for (int k = base; k < end; k++) {
                    int x = a[k];
                    if (x >= twoQ) {
                        x -= twoQ;
                    }
                    final long y = a[k + ln];
                    final int t = (int) (y * z - ((y * zs) >>> 32) * q);
                    a[k] = x + t;
                    a[k + ln] = x - t + twoQ;
                }
            }
            m <<= 1;
            ln >>= 1;
        }
        for (int k = off, e = off + n; k < e; k++) {
            int v = a[k];
            if (v >= twoQ) {
                v -= twoQ;
            }
            if (v >= q) {
                v -= q;
            }
            a[k] = v;
        }
    }

    public void inv(int[] a, int off) {
        final int q = this.q;
        final int twoQ = 2 * q;
        int ln = 1;
        int m = n >> 1;
        while (m >= 1) {
            for (int j = 0; j < m; j++) {
                final long z = izetas[m + j];
                final long zs = izShoup[m + j];
                int base = off + j * 2 * ln;
                int end = base + ln;
                for (int k = base; k < end; k++) {
                    final int u = a[k];
                    final int v = a[k + ln];
                    int s = u + v;
                    if (s >= twoQ) {
                        s -= twoQ;
                    }
                    final long d = u - v + twoQ;
                    a[k] = s;
                    a[k + ln] = (int) (d * z - ((d * zs) >>> 32) * q);
                }
            }
            m >>= 1;
            ln <<= 1;
        }
        final long w = ninv;
        final long ws = ninvShoup;
        for (int k = off, e = off + n; k < e; k++) {
            final long x = a[k];
            int t = (int) (x * w - ((x * ws) >>> 32) * q);
            if (t >= q) {
                t -= q;
            }
            a[k] = t;
        }
    }

    public static int[] findPrimes(int n, int bits, int count) {
        long step = 2L * n;
        long p = (1L << bits) - ((1L << bits) % step) + 1;
        int[] out = new int[count];
        int got = 0;
        while (got < count) {
            if (p > (1L << (bits + 1))) {
                throw new IllegalStateException("no prime");
            }
            if (BigInteger.valueOf(p).isProbablePrime(64)) {
                out[got++] = (int) p;
            }
            p += step;
        }
        return out;
    }
}

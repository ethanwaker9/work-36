package org.oylama.crypto;

public final class Slots {
    final int n;
    final int p;
    final Ntt ntt;
    final int[] pos;

    public Slots(int n, int p) {
        this.n = n;
        this.p = p;
        this.ntt = new Ntt(n, p);
        int logn = Integer.numberOfTrailingZeros(n);
        int half = n / 2;
        int m = 2 * n;
        pos = new int[n];
        for (int eps = 0; eps < 2; eps++) {
            long sgn = eps == 0 ? 1 : m - 1;
            long g = 1;
            for (int k = 0; k < half; k++) {
                long e = (g * sgn) % m;
                pos[eps * half + k] = Ntt.brv((int) ((e - 1) / 2), logn);
                g = (g * 3) % m;
            }
        }
    }

    public long[] encode(long[] vec) {
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            w[pos[i]] = (int) Math.floorMod(vec[i], (long) p);
        }
        ntt.inv(w, 0);
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = w[i];
        }
        return r;
    }

    public long[] decode(long[] poly) {
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = (int) Math.floorMod(poly[i], (long) p);
        }
        ntt.fwd(w, 0);
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = w[pos[i]];
        }
        return r;
    }
}

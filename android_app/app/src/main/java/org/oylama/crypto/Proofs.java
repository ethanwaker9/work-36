package org.oylama.crypto;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

public final class Proofs {
    public static final double REJ_ALPHA = 11.0;
    public static final double REJ_M = 3.0;

    private Proofs() {
    }

    public static double sigmaFor(int kappa, double betaInf, long dim) {
        return REJ_ALPHA * betaInf * Math.sqrt((double) kappa * dim);
    }

    public static int bitsOf(double sigma) {
        return Math.max(2, (int) Math.ceil(Math.log(6.0 * sigma) / Math.log(2.0)) + 1);
    }

    static long[][] add(long[][] a, long[][] b) {
        long[][] r = new long[a.length][];
        for (int i = 0; i < a.length; i++) {
            r[i] = new long[a[i].length];
            for (int k = 0; k < a[i].length; k++) {
                r[i][k] = a[i][k] + b[i][k];
            }
        }
        return r;
    }

    static double maxAbs(long[][] z) {
        long m = 0;
        for (long[] row : z) {
            for (long v : row) {
                long a = Math.abs(v);
                if (a > m) {
                    m = a;
                }
            }
        }
        return m;
    }

    static boolean rejAccept(long[][] z, long[][] cx, double sigma, double M) {
        double dot = 0;
        double nrm = 0;
        for (int i = 0; i < z.length; i++) {
            for (int k = 0; k < z[i].length; k++) {
                double c = cx[i][k];
                dot += (double) z[i][k] * c;
                nrm += c * c;
            }
        }
        double ex = Math.exp(Math.min(50.0, (-2.0 * dot + nrm) / (2.0 * sigma * sigma)));
        return ThreadLocalRandom.current().nextDouble() < Math.min(1.0, ex / M);
    }

    static int[] subMul(Ring R, int[] a, int[] dh, int[] t) {
        return R.sub(a, R.mulHat(dh, t));
    }

    public static Proof provePreimage(Ring R, int[][][] Ah, long[][] x, int[][] t, byte[] ctx, int kappa, double sigma, byte[] rnd) {
        int k = x.length;
        Hash.Stream pre = Hash.stream(ctx, Hash.ser(t));
        for (int att = 0; att < 60; att++) {
            long[][] y = Sample.gaussian(Hash.h(rnd, ctx, "y", att), "m", R.n, sigma, k);
            int[][] W = R.matvecHat(Ah, R.fwdSmall(y));
            byte[] dseed = pre.copy().add(Hash.ser(W)).add("chal").digest(32);
            Sample.Sparse d = Sample.challenge(dseed, "c", R.n, kappa);
            long[][] dx = Sample.sparseMul(d, x);
            long[][] z = add(y, dx);
            if (!rejAccept(z, dx, sigma, REJ_M)) {
                continue;
            }
            if (maxAbs(z) > 6.0 * sigma) {
                continue;
            }
            return Proof.preimage(dseed, z, (long) k * R.n * bitsOf(sigma) + 256);
        }
        return null;
    }

    public static boolean verifyPreimage(Ring R, int[][][] Ah, int[][] t, byte[] ctx, int kappa, double sigma, Proof p) {
        if (p == null || p.z == null || p.z.length != Ah[0].length) {
            return false;
        }
        for (long[] row : p.z) {
            if (row.length != R.n) {
                return false;
            }
        }
        if (maxAbs(p.z) > 6.0 * sigma) {
            return false;
        }
        Sample.Sparse d = Sample.challenge(p.seed, "c", R.n, kappa);
        int[] dh = R.fwd(R.fromSmall(Sample.dense(d, R.n)));
        int[][] Az = R.matvecHat(Ah, R.fwdSmall(p.z));
        int[][] W = new int[Az.length][];
        for (int r = 0; r < Az.length; r++) {
            W[r] = subMul(R, Az[r], dh, t[r]);
        }
        return Arrays.equals(Hash.h(ctx, Hash.ser(t), Hash.ser(W), "chal"), p.seed);
    }

    static Hash.Ser serAll(int[][][] ts) {
        int total = 0;
        for (int[][] t : ts) {
            total += t.length;
        }
        int[][] all = new int[total][];
        int o = 0;
        for (int[][] t : ts) {
            for (int[] row : t) {
                all[o++] = row;
            }
        }
        return Hash.ser(all);
    }

    public static Proof provePreimageAmortized(Ring R, int[][][] Ah, long[][][] xs, int[][][] ts, byte[] ctx, int kappa, double sigma, byte[] rnd) {
        int k = xs[0].length;
        Hash.Stream pre = Hash.stream(ctx, serAll(ts));
        for (int att = 0; att < 60; att++) {
            long[][] y = Sample.gaussian(Hash.h(rnd, ctx, "ay", att), "m", R.n, sigma, k);
            int[][] W = R.matvecHat(Ah, R.fwdSmall(y));
            byte[] dseed = pre.copy().add(Hash.ser(W)).add("achal").digest(32);
            long[][] acc = new long[k][R.n];
            for (int i = 0; i < xs.length; i++) {
                Sample.Sparse d = Sample.challenge(Hash.h(dseed, "i", i), "c", R.n, kappa);
                long[][] m = Sample.sparseMul(d, xs[i]);
                for (int j = 0; j < k; j++) {
                    for (int q = 0; q < R.n; q++) {
                        acc[j][q] += m[j][q];
                    }
                }
            }
            long[][] z = add(y, acc);
            if (!rejAccept(z, acc, sigma, REJ_M)) {
                continue;
            }
            if (maxAbs(z) > 6.0 * sigma) {
                continue;
            }
            return new Proof("amortized", dseed, z, null, null, (long) k * R.n * bitsOf(sigma) + 256);
        }
        return null;
    }

    public static boolean verifyPreimageAmortized(Ring R, int[][][] Ah, int[][][] ts, byte[] ctx, int kappa, double sigma, Proof p) {
        if (p == null) {
            return false;
        }
        if (maxAbs(p.z) > 6.0 * sigma) {
            return false;
        }
        int[][] Az = R.matvecHat(Ah, R.fwdSmall(p.z));
        int rows = Az.length;
        int[][] acc = new int[rows][R.L * R.n];
        for (int i = 0; i < ts.length; i++) {
            Sample.Sparse d = Sample.challenge(Hash.h(p.seed, "i", i), "c", R.n, kappa);
            int[] dh = R.fwd(R.fromSmall(Sample.dense(d, R.n)));
            for (int r = 0; r < rows; r++) {
                R.addMulHat(acc[r], dh, ts[i][r]);
            }
        }
        int[][] W = new int[rows][];
        for (int r = 0; r < rows; r++) {
            W[r] = R.sub(Az[r], acc[r]);
        }
        return Arrays.equals(Hash.h(ctx, serAll(ts), Hash.ser(W), "achal"), p.seed);
    }

    static Hash.Ser serLin(int[][] c1, int[][] c2, int[][] c1p, int[][] c2p) {
        int[][] all = new int[c1.length + c2.length + c1p.length + c2p.length][];
        int o = 0;
        for (int[][] g : new int[][][]{c1, c2, c1p, c2p}) {
            for (int[] row : g) {
                all[o++] = row;
            }
        }
        return Hash.ser(all);
    }

    static long[][] concat(long[][] a, long[][] b) {
        long[][] r = new long[a.length + b.length][];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public static Proof proveLin(Ring R, int[][][] B1h, int[][] b2h, int[] alpha, int[] beta, int[][] c1, int[][] c2,
                                 int[][] c1p, int[][] c2p, long[][] r, long[][] rp, byte[] ctx, int kappa, double sigma, byte[] rnd) {
        int k = B1h[0].length;
        Hash.Stream pre = Hash.stream(ctx, serLin(c1, c2, c1p, c2p));
        for (int att = 0; att < 60; att++) {
            long[][] yy = Sample.gaussian(Hash.h(rnd, ctx, "ly", att), "m", R.n, sigma, 2 * k);
            long[][] y = Arrays.copyOfRange(yy, 0, k);
            long[][] yp = Arrays.copyOfRange(yy, k, 2 * k);
            int[][] yh = R.fwdSmall(y);
            int[][] yph = R.fwdSmall(yp);
            int[][] t = R.matvecHat(B1h, yh);
            int[][] tp = R.matvecHat(B1h, yph);
            int[] u = R.sub(R.mulHat(alpha, R.dotHat(b2h, yh)), R.dotHat(b2h, yph));
            byte[] dseed = pre.copy().add(Hash.ser(t[0], tp[0], u)).add("lin").digest(32);
            Sample.Sparse d = Sample.challenge(dseed, "c", R.n, kappa);
            long[][] dr = Sample.sparseMul(d, r);
            long[][] drp = Sample.sparseMul(d, rp);
            long[][] z = add(y, dr);
            long[][] zp = add(yp, drp);
            if (!rejAccept(concat(z, zp), concat(dr, drp), sigma, REJ_M)) {
                continue;
            }
            if (Math.max(maxAbs(z), maxAbs(zp)) > 6.0 * sigma) {
                continue;
            }
            return new Proof("lin", dseed, z, zp, null, 2L * k * R.n * bitsOf(sigma) + 256);
        }
        return null;
    }

    public static boolean verifyLin(Ring R, int[][][] B1h, int[][] b2h, int[] alpha, int[] beta, int[][] c1, int[][] c2,
                                    int[][] c1p, int[][] c2p, byte[] ctx, int kappa, double sigma, Proof p) {
        if (p == null) {
            return false;
        }
        if (Math.max(maxAbs(p.z), maxAbs(p.zp)) > 6.0 * sigma) {
            return false;
        }
        Sample.Sparse d = Sample.challenge(p.seed, "c", R.n, kappa);
        int[] dh = R.fwd(R.fromSmall(Sample.dense(d, R.n)));
        int[][] zh = R.fwdSmall(p.z);
        int[][] zph = R.fwdSmall(p.zp);
        int[] t = R.sub(R.matvecHat(B1h, zh)[0], R.mulHat(dh, c1[0]));
        int[] tp = R.sub(R.matvecHat(B1h, zph)[0], R.mulHat(dh, c1p[0]));
        int[] rhs = R.sub(R.add(R.mulHat(alpha, c2[0]), beta), c2p[0]);
        int[] u = R.sub(R.sub(R.mulHat(alpha, R.dotHat(b2h, zh)), R.dotHat(b2h, zph)), R.mulHat(rhs, dh));
        return Arrays.equals(Hash.h(ctx, serLin(c1, c2, c1p, c2p), Hash.ser(t, tp, u), "lin"), p.seed);
    }

    public static int[][] gaussianBigRns(Ring R, byte[] seed, int k, double sigma) {
        int n = R.n;
        int need = k * n;
        Xof rd = new Xof(seed, "gbr");
        byte[] raw = rd.read(need * 16 + 8);
        int t = Math.max(0, (int) Math.floor(Math.log(Math.max(sigma, 1.0)) / Math.log(2.0)) - 36);
        double scale = sigma / (double) (1L << t);
        long[] hi = new long[need];
        for (int i = 0; i < need; i++) {
            long a = le64(raw, 16 * i);
            long b = le64(raw, 16 * i + 8);
            double u1 = (a >>> 11) / 9007199254740992.0;
            double u2 = (b >>> 11) / 9007199254740992.0;
            double g = Math.sqrt(-2.0 * Math.log(Math.max(u1, 1e-300))) * Math.cos(2.0 * Math.PI * u2);
            hi[i] = (long) Math.rint(g * scale);
        }
        long[] lo = new long[need];
        if (t > 0) {
            byte[] rawl = rd.read(need * 8 + 8);
            long mask = (1L << t) - 1;
            for (int i = 0; i < need; i++) {
                lo[i] = le64(rawl, 8 * i) & mask;
            }
        }
        int[][] yr = new int[k][R.L * n];
        for (int i = 0; i < R.L; i++) {
            long p = R.q[i];
            long twoT = Ntt.powmod(2, t, p);
            for (int j = 0; j < need; j++) {
                long v = (Math.floorMod(hi[j], p) * twoT + lo[j]) % p;
                yr[j / n][i * n + (j % n)] = (int) v;
            }
        }
        return yr;
    }

    static long le64(byte[] a, int o) {
        return (a[o] & 0xFFL) | ((a[o + 1] & 0xFFL) << 8) | ((a[o + 2] & 0xFFL) << 16) | ((a[o + 3] & 0xFFL) << 24)
            | ((a[o + 4] & 0xFFL) << 32) | ((a[o + 5] & 0xFFL) << 40) | ((a[o + 6] & 0xFFL) << 48) | ((a[o + 7] & 0xFFL) << 56);
    }

    static double[] approxAll(Ring R, int[][] z) {
        double[] out = new double[z.length * R.n];
        for (int j = 0; j < z.length; j++) {
            System.arraycopy(R.approxCenter(z[j]), 0, out, j * R.n, R.n);
        }
        return out;
    }

    public static Proof proveBndAmortized(Ring R, int[][][] Ah, int[][][] xs, int[][][] ts, byte[] ctx, int kappa, double sigma, byte[] rnd) {
        int k = xs[0].length;
        Hash.Stream pre = Hash.stream(ctx, serAll(ts));
        for (int att = 0; att < 40; att++) {
            int[][] yr = gaussianBigRns(R, Hash.h(rnd, ctx, "by", att), k, sigma);
            int[][] W = R.matvecHat(Ah, R.fwdAll(yr));
            byte[] dseed = pre.copy().add(Hash.ser(W)).add("bchal").digest(32);
            int[][] acc = new int[k][R.L * R.n];
            for (int i = 0; i < xs.length; i++) {
                Sample.Sparse d = Sample.challenge(Hash.h(dseed, "i", i), "c", R.n, kappa);
                int[] dh = R.fwd(R.fromSmall(Sample.dense(d, R.n)));
                for (int j = 0; j < k; j++) {
                    R.addMulHat(acc[j], dh, R.fwd(xs[i][j]));
                }
            }
            int[][] accc = R.invAll(acc);
            int[][] z = new int[k][];
            for (int j = 0; j < k; j++) {
                z[j] = R.add(yr[j], accc[j]);
            }
            double[] zf = approxAll(R, z);
            double[] df = approxAll(R, accc);
            double dot = 0, nrm = 0, zmax = 0;
            for (int i = 0; i < zf.length; i++) {
                dot += zf[i] * df[i];
                nrm += df[i] * df[i];
                zmax = Math.max(zmax, Math.abs(zf[i]));
            }
            double ex = Math.exp(Math.min(50.0, (-2.0 * dot + nrm) / (2.0 * sigma * sigma)));
            if (ThreadLocalRandom.current().nextDouble() >= Math.min(1.0, ex / REJ_M)) {
                continue;
            }
            if (zmax > 6.0 * sigma) {
                continue;
            }
            return new Proof("bnd", dseed, null, null, z, (long) k * R.n * bitsOf(sigma) + 256);
        }
        return null;
    }

    public static boolean verifyBndAmortized(Ring R, int[][][] Ah, int[][][] ts, byte[] ctx, int kappa, double sigma, Proof p) {
        if (p == null || p.zr == null) {
            return false;
        }
        double[] zf = approxAll(R, p.zr);
        for (double v : zf) {
            if (Math.abs(v) > 6.0 * sigma) {
                return false;
            }
        }
        int[][] Az = R.matvecHat(Ah, R.fwdAll(p.zr));
        int rows = Az.length;
        int[][] acc = new int[rows][R.L * R.n];
        for (int i = 0; i < ts.length; i++) {
            Sample.Sparse d = Sample.challenge(Hash.h(p.seed, "i", i), "c", R.n, kappa);
            int[] dh = R.fwd(R.fromSmall(Sample.dense(d, R.n)));
            for (int r = 0; r < rows; r++) {
                R.addMulHat(acc[r], dh, ts[i][r]);
            }
        }
        int[][] W = new int[rows][];
        for (int r = 0; r < rows; r++) {
            W[r] = R.sub(Az[r], acc[r]);
        }
        return Arrays.equals(Hash.h(ctx, serAll(ts), Hash.ser(W), "bchal"), p.seed);
    }
}

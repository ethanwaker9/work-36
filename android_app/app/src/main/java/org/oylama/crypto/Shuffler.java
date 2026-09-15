package org.oylama.crypto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Shuffler {
    public static final class MixProof {
        public int[][][] ts;
        public List<int[]> bndGroups = new ArrayList<>();
        public List<Proof> bnds = new ArrayList<>();
        public int[][][] L;
        public ShuffleProof shuffle;
        public long sizeBits;
    }

    public static final class ShuffleProof {
        public int[][][] dc1;
        public int[][] dc2;
        public int[][] s;
        public Proof[] lins;
        public long sizeBits;
    }

    final Params pp;
    final Ring R;
    final Bdlop com;
    final int[][] pk;
    final int[][][] AMh;
    final int cols;
    final int kk;

    public Shuffler(Params pp, Ring R, Bdlop com, int[][] pk) {
        this.pp = pp;
        this.R = R;
        this.com = com;
        this.pk = pk;
        int k = com.k;
        cols = k + 3;
        kk = k;
        int[] pOne = R.scalar(R.one(), pp.p);
        int[][][] AM = new int[3][cols][];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < cols; j++) {
                AM[i][j] = R.zeros();
            }
        }
        for (int j = 0; j < k; j++) {
            AM[0][j] = com.B1[0][j];
            AM[1][j] = com.B2[0][j];
            AM[2][j] = com.B2[1][j];
        }
        AM[1][k] = pk[0];
        AM[1][k + 1] = pOne;
        AM[2][k] = pk[1];
        AM[2][k + 2] = pOne;
        AMh = new int[3][cols][];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < cols; j++) {
                AMh[i][j] = R.fwd(AM[i][j]);
            }
        }
    }

    static int[] ringUniform(Ring R, byte[] seed, String tag) {
        return R.fwd(R.uniform(Hash.h(seed, tag), "OYLAMA/theta"));
    }

    private int[] fold(int[][][] ts, int[][][] cts, int[][][] L, byte[] ctx, int[][] b2pOut, int[][][] cm1Out, int[][] cm2Out, int[][] pubOut) {
        int tau = cts.length;
        int[] h = ringUniform(R, Hash.h(ctx, "fold", Hash.ser(flatTs(ts))), "h");
        for (int j = 0; j < kk; j++) {
            b2pOut[j] = R.add(com.B2h[0][j], R.mulHat(h, com.B2h[1][j]));
        }
        for (int i = 0; i < tau; i++) {
            cm1Out[i] = new int[][]{ts[i][0]};
            int[] a = R.add(ts[i][1], R.fwd(cts[i][0]));
            int[] b = R.add(ts[i][2], R.fwd(cts[i][1]));
            cm2Out[i] = R.add(a, R.mulHat(h, b));
            pubOut[i] = R.add(L[i][0], R.mulHat(h, L[i][1]));
        }
        return h;
    }

    public int[][][] mix(int[][][] cts, byte[] seed, byte[] ctx, MixProof[] proofOut) {
        int tau = cts.length;
        int k = kk;
        int[] ah = R.fwd(pk[0]);
        int[] bh = R.fwd(pk[1]);
        long[][][] wits = new long[tau][][];
        int[][][] ts = new int[tau][][];
        int[][][] chat = new int[tau][][];
        for (int i = 0; i < tau; i++) {
            long[][] w = new long[cols][];
            for (int j = 0; j < cols; j++) {
                w[j] = Sample.ternary(Hash.h(seed, "w", i, j), "m", R.n);
            }
            int[][] wh = R.fwdSmall(w);
            ts[i] = R.matvecHat(AMh, wh);
            int[] up = R.add(R.mulHat(ah, wh[k]), R.scalar(wh[k + 1], pp.p));
            int[] vp = R.add(R.mulHat(bh, wh[k]), R.scalar(wh[k + 2], pp.p));
            wits[i] = w;
            chat[i] = new int[][]{R.add(R.fwd(cts[i][0]), up), R.add(R.fwd(cts[i][1]), vp)};
        }
        int[] perm = Sample.permutation(Hash.h(seed, "perm"), "OYLAMA/shuffle", tau);
        int[][][] L = new int[tau][][];
        for (int i = 0; i < tau; i++) {
            L[i] = chat[perm[i]];
        }
        double sigB = Proofs.sigmaFor(pp.kappa, 1, (long) cols * R.n) * Math.sqrt(Math.min(tau, pp.batch));
        MixProof mp = new MixProof();
        for (int st = 0; st < tau; st += pp.batch) {
            int end = Math.min(tau, st + pp.batch);
            long[][][] xs = Arrays.copyOfRange(wits, st, end);
            int[][][] tg = Arrays.copyOfRange(ts, st, end);
            Proof pr = Proofs.provePreimageAmortized(R, AMh, xs, tg, Hash.h(ctx, "bnd", st), pp.kappa, sigB, Hash.h(seed, "bndr", st));
            mp.bndGroups.add(new int[]{st, end});
            mp.bnds.add(pr);
        }
        int[][] b2p = new int[k][];
        int[][][] cm1 = new int[tau][][];
        int[][] cm2 = new int[tau][];
        int[][] pub = new int[tau][];
        int[] h = fold(ts, cts, L, ctx, b2p, cm1, cm2, pub);
        long[][][] rvec = new long[tau][][];
        int[][] msgs = new int[tau][];
        for (int i = 0; i < tau; i++) {
            rvec[i] = Arrays.copyOfRange(wits[i], 0, k);
            msgs[i] = R.add(chat[i][0], R.mulHat(h, chat[i][1]));
        }
        mp.shuffle = proveShuffle(b2p, cm1, cm2, rvec, msgs, pub, Hash.h(ctx, "shuf"), Hash.h(seed, "shufr"));
        mp.ts = ts;
        mp.L = L;
        int[][][] out = new int[tau][][];
        for (int i = 0; i < tau; i++) {
            out[i] = new int[][]{R.inv(L[i][0]), R.inv(L[i][1])};
        }
        long size = (long) (tau * 3 + tau * 2) * R.n * R.logq;
        for (Proof p : mp.bnds) {
            size += p == null ? 0 : p.sizeBits;
        }
        size += mp.shuffle.sizeBits;
        mp.sizeBits = size;
        proofOut[0] = mp;
        return out;
    }

    static int[][] flatTs(int[][][] ts) {
        int[][] all = new int[ts.length * 3][];
        for (int i = 0; i < ts.length; i++) {
            for (int r = 0; r < 3; r++) {
                all[i * 3 + r] = ts[i][r];
            }
        }
        return all;
    }

    public boolean verify(int[][][] cts, int[][][] outCts, MixProof mp, byte[] ctx) {
        int tau = cts.length;
        if (outCts.length != tau || mp.ts.length != tau || mp.L.length != tau) {
            return false;
        }
        double sigB = Proofs.sigmaFor(pp.kappa, 1, (long) cols * R.n) * Math.sqrt(Math.min(tau, pp.batch));
        int covered = 0;
        for (int g = 0; g < mp.bnds.size(); g++) {
            int st = mp.bndGroups.get(g)[0];
            int end = mp.bndGroups.get(g)[1];
            if (st != covered) {
                return false;
            }
            covered = end;
            int[][][] tg = Arrays.copyOfRange(mp.ts, st, end);
            if (!Proofs.verifyPreimageAmortized(R, AMh, tg, Hash.h(ctx, "bnd", st), pp.kappa, sigB, mp.bnds.get(g))) {
                return false;
            }
        }
        if (covered != tau) {
            return false;
        }
        for (int i = 0; i < tau; i++) {
            if (!Arrays.equals(R.inv(mp.L[i][0]), outCts[i][0]) || !Arrays.equals(R.inv(mp.L[i][1]), outCts[i][1])) {
                return false;
            }
        }
        int[][] b2p = new int[kk][];
        int[][][] cm1 = new int[tau][][];
        int[][] cm2 = new int[tau][];
        int[][] pub = new int[tau][];
        fold(mp.ts, cts, mp.L, ctx, b2p, cm1, cm2, pub);
        return verifyShuffle(b2p, cm1, cm2, pub, Hash.h(ctx, "shuf"), mp.shuffle);
    }

    private int[][] linTerms(int i, int tau, int[] beta, int[][] s, int[][] Mhat) {
        if (i == 1) {
            return new int[][]{beta, R.mulHat(s[1], Mhat[0])};
        }
        if (i == tau) {
            int[] b = R.mulHat(beta, Mhat[tau - 1]);
            return new int[][]{s[tau - 1], tau % 2 == 0 ? b : R.neg(b)};
        }
        return new int[][]{s[i - 1], R.mulHat(s[i], Mhat[i - 1])};
    }

    ShuffleProof proveShuffle(int[][] b2h, int[][][] cm1, int[][] cm2, long[][][] rvec, int[][] msgs, int[][] pub, byte[] ctx, byte[] rnd) {
        int tau = cm1.length;
        int k = com.B1h[0].length;
        int[] rho = ringUniform(R, Hash.h(ctx, "rho", Hash.ser(pub)), "rho");
        int[][] Mc2 = new int[tau][];
        int[][] Mval = new int[tau][];
        int[][] Mhat = new int[tau][];
        int[][] MhatInv = new int[tau][];
        for (int i = 0; i < tau; i++) {
            Mc2[i] = R.sub(cm2[i], rho);
            Mval[i] = R.sub(msgs[i], rho);
            Mhat[i] = R.sub(pub[i], rho);
            MhatInv[i] = R.invHat(Mhat[i]);
        }
        int[][] theta = new int[tau][];
        for (int i = 1; i < tau; i++) {
            theta[i] = ringUniform(R, Hash.h(rnd, "th", i), "t");
        }
        long[][][] Dr = new long[tau + 1][][];
        int[][][] Dc1 = new int[tau + 1][][];
        int[][] Dc2 = new int[tau + 1][];
        for (int i = 1; i <= tau; i++) {
            int[] Di;
            if (i == 1) {
                Di = R.mulHat(theta[1], Mhat[0]);
            } else if (i == tau) {
                Di = R.mulHat(theta[tau - 1], Mval[tau - 1]);
            } else {
                Di = R.add(R.mulHat(theta[i - 1], Mval[i - 1]), R.mulHat(theta[i], Mhat[i - 1]));
            }
            long[][] r = new long[k][];
            for (int j = 0; j < k; j++) {
                r[j] = Sample.ternary(Hash.h(rnd, "dr", i, j), "m", R.n);
            }
            int[][] rh = R.fwdSmall(r);
            Dc1[i] = R.matvecHat(com.B1h, rh);
            int[] c2 = R.zeros();
            for (int j = 0; j < k; j++) {
                R.addMulHat(c2, b2h[j], rh[j]);
            }
            Dc2[i] = R.add(c2, Di);
            Dr[i] = r;
        }
        int[] beta = ringUniform(R, Hash.h(ctx, "beta", Hash.ser(Arrays.copyOfRange(Dc2, 1, tau + 1))), "b");
        int[][] s = new int[tau][];
        int[] acc = new int[R.L * R.n];
        Arrays.fill(acc, 1);
        for (int j = 1; j < tau; j++) {
            acc = R.mulHat(acc, Mval[j - 1]);
            acc = R.mulHat(acc, MhatInv[j - 1]);
            int[] term = R.mulHat(beta, acc);
            if (j % 2 == 1) {
                term = R.neg(term);
            }
            s[j] = R.add(term, theta[j]);
        }
        double sig = Proofs.sigmaFor(pp.kappa, 1, (long) k * R.n);
        Proof[] lins = new Proof[tau];
        for (int i = 1; i <= tau; i++) {
            int[][] ab = linTerms(i, tau, beta, s, Mhat);
            lins[i - 1] = Proofs.proveLin(R, com.B1h, b2h, ab[0], ab[1], cm1[i - 1], new int[][]{Mc2[i - 1]}, Dc1[i],
                new int[][]{Dc2[i]}, rvec[i - 1], Dr[i], Hash.h(ctx, "lin", i), pp.kappa, sig, Hash.h(rnd, "lin", i));
        }
        ShuffleProof sp = new ShuffleProof();
        sp.dc1 = Dc1;
        sp.dc2 = Dc2;
        sp.s = s;
        sp.lins = lins;
        long size = (long) tau * 2 * R.n * R.logq + (long) (tau - 1) * R.n * R.logq;
        for (Proof p : lins) {
            size += p == null ? 0 : p.sizeBits;
        }
        sp.sizeBits = size;
        return sp;
    }

    boolean verifyShuffle(int[][] b2h, int[][][] cm1, int[][] cm2, int[][] pub, byte[] ctx, ShuffleProof sp) {
        if (sp == null) {
            return false;
        }
        int tau = cm1.length;
        int k = com.B1h[0].length;
        int[] rho = ringUniform(R, Hash.h(ctx, "rho", Hash.ser(pub)), "rho");
        int[][] Mc2 = new int[tau][];
        int[][] Mhat = new int[tau][];
        for (int i = 0; i < tau; i++) {
            Mc2[i] = R.sub(cm2[i], rho);
            Mhat[i] = R.sub(pub[i], rho);
        }
        int[] beta = ringUniform(R, Hash.h(ctx, "beta", Hash.ser(Arrays.copyOfRange(sp.dc2, 1, tau + 1))), "b");
        double sig = Proofs.sigmaFor(pp.kappa, 1, (long) k * R.n);
        for (int i = 1; i <= tau; i++) {
            int[][] ab = linTerms(i, tau, beta, sp.s, Mhat);
            if (!Proofs.verifyLin(R, com.B1h, b2h, ab[0], ab[1], cm1[i - 1], new int[][]{Mc2[i - 1]}, sp.dc1[i],
                new int[][]{sp.dc2[i]}, Hash.h(ctx, "lin", i), pp.kappa, sig, sp.lins[i - 1])) {
                return false;
            }
        }
        return true;
    }
}

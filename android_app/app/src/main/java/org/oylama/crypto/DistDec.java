package org.oylama.crypto;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public final class DistDec {
    public static final class KeyCom {
        public final int[][] c1;
        public final int[][] c2;
        public final long[][] r;

        KeyCom(int[][] c1, int[][] c2, long[][] r) {
            this.c1 = c1;
            this.c2 = c2;
            this.r = r;
        }
    }

    public static final class Share {
        public final int[][] ts;
        public final int[][][] ec1;
        public final int[][][] ec2;
        public final List<int[]> linGroups = new ArrayList<>();
        public final List<Long> linBounds = new ArrayList<>();
        public final List<Proof> lins = new ArrayList<>();
        public final List<int[]> bndGroups = new ArrayList<>();
        public final List<Proof> bnds = new ArrayList<>();
        public long sizeBits;

        Share(int count) {
            ts = new int[count][];
            ec1 = new int[count][][];
            ec2 = new int[count][][];
        }
    }

    final Params pp;
    final Ring R;
    final Bdlop com;
    final BigInteger bDec;
    final int[][][] ADh;
    final int[] pinvH;

    public DistDec(Params pp, Ring R, Bdlop com, BigInteger bDec) {
        this.pp = pp;
        this.R = R;
        this.com = com;
        this.bDec = bDec;
        int k = com.k;
        int[] one = R.one();
        ADh = new int[2][k + 1][];
        for (int j = 0; j < k; j++) {
            ADh[0][j] = R.fwd(com.B1[0][j]);
            ADh[1][j] = R.fwd(com.B2[0][j]);
        }
        ADh[0][k] = R.fwd(R.zeros());
        ADh[1][k] = R.fwd(one);
        pinvH = R.invHat(R.fwd(R.scalar(one, pp.p)));
    }

    BigInteger boundDrown(int nservers) {
        BigInteger v = BigInteger.ONE.shiftLeft(pp.sec).multiply(bDec).divide(BigInteger.valueOf((long) pp.p * nservers));
        return v.max(BigInteger.ONE);
    }

    double sigmaE(int nservers, int count) {
        double be = boundDrown(nservers).doubleValue();
        return 11.0 * Math.sqrt(pp.kappa) * be * Math.sqrt((com.k + 1.0) * R.n) * Math.sqrt(Math.min(count, pp.batch));
    }

    public KeyCom commitKey(int[] sj, byte[] seed) {
        int k = com.k;
        long[][] r = new long[k][];
        for (int j = 0; j < k; j++) {
            r[j] = Sample.ternary(Hash.h(seed, "kr", j), "m", R.n);
        }
        int[][] rh = R.fwdSmall(r);
        int[][] c1 = R.matvecHat(com.B1h, rh);
        int[][] c2 = R.matvecHat(com.B2h, rh);
        c2[0] = R.add(c2[0], R.fwd(sj));
        return new KeyCom(c1, c2, r);
    }

    public Share share(int[] sj, KeyCom kc, int[][][] cts, byte[] seed, byte[] ctx, int nservers) {
        int k = com.k;
        int count = cts.length;
        BigInteger be = boundDrown(nservers);
        BigInteger mod = be.shiftLeft(1).add(BigInteger.ONE);
        double sigE = sigmaE(nservers, count);
        int[] sjh = R.fwd(sj);
        Share out = new Share(count);
        int[][] uh = new int[count][];
        long[][][] erands = new long[count][][];
        int[][][] wit = new int[count][][];
        for (int i = 0; i < count; i++) {
            uh[i] = R.fwd(cts[i][0]);
            int[] E = R.uniform(Hash.h(seed, "E", i), "OYLAMA/ddec");
            BigInteger[] ec = R.center(E);
            BigInteger[] eb = new BigInteger[R.n];
            for (int c = 0; c < R.n; c++) {
                eb[c] = ec[c].mod(mod).subtract(be);
            }
            int[] er = R.fromBig(eb);
            out.ts[i] = R.add(R.mulHat(sjh, uh[i]), R.scalar(R.fwd(er), pp.p));
            long[][] r = new long[k][];
            for (int j = 0; j < k; j++) {
                r[j] = Sample.ternary(Hash.h(seed, "Er", i, j), "m", R.n);
            }
            int[][] rh = R.fwdSmall(r);
            int[][] c1 = R.matvecHat(com.B1h, rh);
            int[][] c2 = R.matvecHat(com.B2h, rh);
            c2[0] = R.add(c2[0], R.fwd(er));
            out.ec1[i] = c1;
            out.ec2[i] = c2;
            erands[i] = r;
            int[][] w = new int[k + 1][];
            for (int j = 0; j < k; j++) {
                w[j] = R.fromSmall(r[j]);
            }
            w[k] = er;
            wit[i] = w;
        }
        for (int st = 0; st < count; st += pp.batch) {
            int end = Math.min(count, st + pp.batch);
            int[][] C1 = new int[out.ec1[st].length][R.L * R.n];
            int[][] C2 = new int[out.ec2[st].length][R.L * R.n];
            int[] U = R.zeros();
            int[] T = R.zeros();
            long[][] racc = new long[k][R.n];
            for (int i = st; i < end; i++) {
                Sample.Sparse chi = Sample.challenge(Hash.h(ctx, "chi", st, i), "c", R.n, pp.kappa);
                int[] chih = R.fwd(R.fromSmall(Sample.dense(chi, R.n)));
                for (int a = 0; a < C1.length; a++) {
                    R.addMulHat(C1[a], chih, out.ec1[i][a]);
                }
                for (int a = 0; a < C2.length; a++) {
                    R.addMulHat(C2[a], chih, out.ec2[i][a]);
                }
                R.addMulHat(U, chih, uh[i]);
                R.addMulHat(T, chih, out.ts[i]);
                for (int jj = 0; jj < k; jj++) {
                    long[] m = Sample.sparseMul(chi, erands[i][jj]);
                    for (int c = 0; c < R.n; c++) {
                        racc[jj][c] += m[c];
                    }
                }
            }
            int[] alpha = R.neg(R.mulHat(pinvH, U));
            int[] beta = R.mulHat(pinvH, T);
            long bnd = 1;
            for (long[] row : racc) {
                for (long v : row) {
                    bnd = Math.max(bnd, Math.abs(v));
                }
            }
            double sigB = Proofs.sigmaFor(pp.kappa, bnd, (long) k * R.n);
            Proof pr = Proofs.proveLin(R, com.B1h, com.B2h[0], alpha, beta, kc.c1, kc.c2, C1, C2, kc.r, racc,
                Hash.h(ctx, "dlin", st), pp.kappa, sigB, Hash.h(seed, "lr", st));
            out.linGroups.add(new int[]{st, end});
            out.linBounds.add(bnd);
            out.lins.add(pr);
        }
        for (int st = 0; st < count; st += pp.batch) {
            int end = Math.min(count, st + pp.batch);
            int[][][] ws = new int[end - st][][];
            int[][][] tg = new int[end - st][][];
            for (int i = st; i < end; i++) {
                ws[i - st] = wit[i];
                tg[i - st] = new int[][]{out.ec1[i][0], out.ec2[i][0]};
            }
            Proof pr = Proofs.proveBndAmortized(R, ADh, ws, tg, Hash.h(ctx, "dbnd", st), pp.kappa, sigE, Hash.h(seed, "br", st));
            out.bndGroups.add(new int[]{st, end});
            out.bnds.add(pr);
        }
        long size = (long) count * 3 * R.n * R.logq;
        for (Proof p : out.lins) {
            size += p == null ? 0 : p.sizeBits;
        }
        for (Proof p : out.bnds) {
            size += p == null ? 0 : p.sizeBits;
        }
        out.sizeBits = size;
        return out;
    }

    public boolean verifyShare(KeyCom kc, int[][][] cts, Share sh, byte[] ctx, int nservers) {
        int k = com.k;
        int count = cts.length;
        if (sh.ts.length != count) {
            return false;
        }
        double sigE = sigmaE(nservers, count);
        for (int g = 0; g < sh.lins.size(); g++) {
            int st = sh.linGroups.get(g)[0];
            int end = sh.linGroups.get(g)[1];
            long bnd = sh.linBounds.get(g);
            if (bnd > (long) pp.kappa * pp.batch) {
                return false;
            }
            int[][] C1 = new int[sh.ec1[st].length][R.L * R.n];
            int[][] C2 = new int[sh.ec2[st].length][R.L * R.n];
            int[] U = R.zeros();
            int[] T = R.zeros();
            for (int i = st; i < end; i++) {
                Sample.Sparse chi = Sample.challenge(Hash.h(ctx, "chi", st, i), "c", R.n, pp.kappa);
                int[] chih = R.fwd(R.fromSmall(Sample.dense(chi, R.n)));
                for (int a = 0; a < C1.length; a++) {
                    R.addMulHat(C1[a], chih, sh.ec1[i][a]);
                }
                for (int a = 0; a < C2.length; a++) {
                    R.addMulHat(C2[a], chih, sh.ec2[i][a]);
                }
                R.addMulHat(U, chih, R.fwd(cts[i][0]));
                R.addMulHat(T, chih, sh.ts[i]);
            }
            int[] alpha = R.neg(R.mulHat(pinvH, U));
            int[] beta = R.mulHat(pinvH, T);
            double sigB = Proofs.sigmaFor(pp.kappa, bnd, (long) k * R.n);
            if (!Proofs.verifyLin(R, com.B1h, com.B2h[0], alpha, beta, kc.c1, kc.c2, C1, C2,
                Hash.h(ctx, "dlin", st), pp.kappa, sigB, sh.lins.get(g))) {
                return false;
            }
        }
        for (int g = 0; g < sh.bnds.size(); g++) {
            int st = sh.bndGroups.get(g)[0];
            int end = sh.bndGroups.get(g)[1];
            int[][][] tg = new int[end - st][][];
            for (int i = st; i < end; i++) {
                tg[i - st] = new int[][]{sh.ec1[i][0], sh.ec2[i][0]};
            }
            if (!Proofs.verifyBndAmortized(R, ADh, tg, Hash.h(ctx, "dbnd", st), pp.kappa, sigE, sh.bnds.get(g))) {
                return false;
            }
        }
        return true;
    }

    public long[][] combine(int[][][] cts, List<Share> shares) {
        long[][] out = new long[cts.length][];
        for (int i = 0; i < cts.length; i++) {
            int[] acc = R.fwd(cts[i][1]);
            for (Share sh : shares) {
                acc = R.sub(acc, sh.ts[i]);
            }
            out[i] = R.centerModP(R.inv(acc), pp.p);
        }
        return out;
    }
}

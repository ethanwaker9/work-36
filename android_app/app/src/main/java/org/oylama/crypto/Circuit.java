package org.oylama.crypto;

import java.util.ArrayList;
import java.util.List;

public final class Circuit {
    private Circuit() {
    }

    public static final class Stage {
        public final int d;
        public final int[] lefts;

        Stage(int d, int[] lefts) {
            this.d = d;
            this.lefts = lefts;
        }
    }

    public static List<Stage> batcherStages(int M) {
        List<Stage> st = new ArrayList<>();
        int p = 1;
        while (p < M) {
            int k = p;
            while (k >= 1) {
                List<Integer> lefts = new ArrayList<>();
                for (int j = k % p; j < M - k; j += 2 * k) {
                    for (int i = 0; i < Math.min(k, M - j - k); i++) {
                        if ((i + j) / (2 * p) == (i + j + k) / (2 * p)) {
                            lefts.add(i + j);
                        }
                    }
                }
                if (!lefts.isEmpty()) {
                    int[] a = new int[lefts.size()];
                    for (int i = 0; i < a.length; i++) {
                        a[i] = lefts.get(i);
                    }
                    st.add(new Stage(k, a));
                }
                k /= 2;
            }
            p *= 2;
        }
        return st;
    }

    public static final class Layout {
        public final int M;
        public final int H;
        public final int B;
        public final int unit;
        public final int G;
        public final int copies;
        public final int size;

        public Layout(int M, int H, int nblocksMin) {
            this.M = M;
            this.H = H;
            int b = 1;
            while (b < nblocksMin) {
                b *= 2;
            }
            B = b;
            unit = M * B;
            if (unit <= H) {
                G = 1;
                copies = H / unit;
                size = copies * unit;
            } else {
                G = (unit + H - 1) / H;
                copies = 1;
                size = G * H;
            }
        }

        long[] rep(long[] base) {
            long[] v = new long[size];
            for (int c = 0; c < copies; c++) {
                System.arraycopy(base, 0, v, c * unit, unit);
            }
            return v;
        }

        public long[] blockMask(int from, int to) {
            long[] base = new long[unit];
            for (int b = from; b < to; b++) {
                for (int i = b * M; i < (b + 1) * M; i++) {
                    base[i] = 1;
                }
            }
            return rep(base);
        }

        public long[] blockMask(int[] blocks) {
            long[] base = new long[unit];
            for (int b : blocks) {
                for (int i = b * M; i < (b + 1) * M; i++) {
                    base[i] = 1;
                }
            }
            return rep(base);
        }

        public long[] elemMask(int[] elems) {
            long[] base = new long[unit];
            for (int b = 0; b < B; b++) {
                for (int i : elems) {
                    base[b * M + i] = 1;
                }
            }
            return rep(base);
        }

        public long[] spread(long[] base) {
            return rep(base);
        }

        public long[] ones() {
            long[] v = new long[size];
            java.util.Arrays.fill(v, 1);
            return v;
        }
    }

    static long[] oneMinus(long[] a) {
        long[] r = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = 1 - a[i];
        }
        return r;
    }

    static long[] subVec(long[] a, long[] b) {
        long[] r = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] - b[i];
        }
        return r;
    }

    static long[] linVec(long[] a, long mul, long add) {
        long[] r = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] * mul + add;
        }
        return r;
    }

    static Flat oneMinus(Layout lay, Flat X) {
        return X.neg().addConst(lay.ones());
    }

    static byte[] c(byte[] ctx, String s) {
        return Hash.concat(ctx, s);
    }

    static Flat suffixAnd(Layout lay, Flat X, byte[] ctx) {
        Flat S = Flat.fresh(X, c(ctx, "|f0"));
        int t = 1;
        int step = 0;
        while (t < lay.B) {
            Flat Sr = Flat.rot(S, t * lay.M, c(ctx, "|sa" + step));
            List<Integer> wb = new ArrayList<>();
            for (int b = 0; b < lay.B; b++) {
                if (b + t >= lay.B) {
                    wb.add(b);
                }
            }
            int[] wa = new int[wb.size()];
            for (int i = 0; i < wa.length; i++) {
                wa[i] = wb.get(i);
            }
            long[] wrap = lay.blockMask(wa);
            Sr = Sr.mask(oneMinus(wrap)).addConst(wrap);
            S = Flat.mult(S, Sr, c(ctx, "|sm" + step));
            t *= 2;
            step++;
        }
        return S;
    }

    static Flat totalBroadcast(Layout lay, Flat X, byte[] ctx) {
        Flat T = X;
        int t = 1;
        int step = 0;
        while (t < lay.B) {
            T = T.add(Flat.rot(T, t * lay.M, c(ctx, "|tb" + step)));
            t *= 2;
            step++;
        }
        return T;
    }

    static Flat compareSwapStage(Layout lay, Flat K, int d, int[] lefts, int keybits, byte[] ctx) {
        int M = lay.M;
        Flat B = Flat.rot(K, d, c(ctx, "|B"));
        Flat AB = Flat.mult(K, B, c(ctx, "|AB"));
        Flat e = oneMinus(lay, K.add(B).sub(AB.scale(2)));
        Flat g = B.sub(AB);
        long[] keyMask = lay.blockMask(0, keybits);
        long[] pad = oneMinus(keyMask);
        e = e.mask(oneMinus(pad)).addConst(pad);
        Flat S = suffixAnd(lay, e, c(ctx, "|suf"));
        Flat P = Flat.rot(S, M, c(ctx, "|P"));
        Flat u = Flat.mult(g.mask(keyMask), P, c(ctx, "|u"));
        Flat lt = totalBroadcast(lay, u, c(ctx, "|tb"));
        Flat swap = oneMinus(lay, lt);
        Flat D = Flat.mult(swap, B.sub(K), c(ctx, "|D"));
        Flat A2 = K.add(D);
        Flat B2 = B.sub(D);
        long[] am = lay.elemMask(lefts);
        int[] rights = new int[lefts.length];
        for (int i = 0; i < lefts.length; i++) {
            rights[i] = lefts[i] + d;
        }
        long[] rm = lay.elemMask(rights);
        Flat back = Flat.rot(B2.mask(am), lay.size - d, c(ctx, "|bb"));
        long[] keep = subVec(subVec(lay.ones(), am), rm);
        Flat out = A2.mask(am).add(back.mask(rm)).add(K.mask(keep));
        return Flat.fresh(out, c(ctx, "|fin"));
    }
}

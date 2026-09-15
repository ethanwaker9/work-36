package org.oylama.crypto;

public final class Flat {
    final Engine eng;
    final int[][][] cts;
    final int size;
    final int H;

    Flat(Engine eng, int[][][] cts, int size) {
        this.eng = eng;
        this.cts = cts;
        this.size = size;
        this.H = eng.ops().n() / 2;
    }

    int G() {
        return cts.length;
    }

    Flat add(Flat o) {
        CtOps E = eng.ops();
        int[][][] r = new int[cts.length][][];
        for (int g = 0; g < cts.length; g++) {
            r[g] = E.add(cts[g], o.cts[g]);
        }
        return new Flat(eng, r, size);
    }

    Flat sub(Flat o) {
        CtOps E = eng.ops();
        int[][][] r = new int[cts.length][][];
        for (int g = 0; g < cts.length; g++) {
            r[g] = E.sub(cts[g], o.cts[g]);
        }
        return new Flat(eng, r, size);
    }

    private long[] segment(long[] vec, int g) {
        long[] m = new long[eng.ops().n()];
        int from = g * H;
        int to = Math.min(vec.length, (g + 1) * H);
        if (to > from) {
            System.arraycopy(vec, from, m, 0, to - from);
        }
        return m;
    }

    Flat mask(long[] vec) {
        CtOps E = eng.ops();
        int[][][] r = new int[cts.length][][];
        for (int g = 0; g < cts.length; g++) {
            r[g] = E.mulPlainSlots(cts[g], segment(vec, g));
        }
        return new Flat(eng, r, size);
    }

    Flat addConst(long[] vec) {
        CtOps E = eng.ops();
        int[][][] r = new int[cts.length][][];
        for (int g = 0; g < cts.length; g++) {
            r[g] = E.addPlainSlots(cts[g], segment(vec, g));
        }
        return new Flat(eng, r, size);
    }

    Flat neg() {
        CtOps E = eng.ops();
        int[][][] r = new int[cts.length][][];
        for (int g = 0; g < cts.length; g++) {
            r[g] = E.sub(E.zero(), cts[g]);
        }
        return new Flat(eng, r, size);
    }

    Flat scale(long k) {
        CtOps E = eng.ops();
        int[][][] r = new int[cts.length][][];
        for (int g = 0; g < cts.length; g++) {
            r[g] = E.scale(cts[g], k);
        }
        return new Flat(eng, r, size);
    }

    static Flat fresh(Flat fa, byte[] ctx) {
        return new Flat(fa.eng, fa.eng.refresh(fa.cts, 0, ctx, "rf"), fa.size);
    }

    static Flat rot(Flat fa, int d, byte[] ctx) {
        Engine eng = fa.eng;
        CtOps E = eng.ops();
        int H = fa.H;
        int k = Math.floorMod(d, H);
        int[][][] r = k != 0 ? eng.rotate(fa.cts, k, ctx) : eng.refresh(fa.cts, 0, ctx, "rf");
        if (fa.G() == 1) {
            return new Flat(eng, r, fa.size);
        }
        long[] lo = new long[E.n()];
        long[] hi = new long[E.n()];
        for (int i = 0; i < H - k; i++) {
            lo[i] = 1;
        }
        for (int i = H - k; i < H; i++) {
            hi[i] = 1;
        }
        int G = fa.G();
        int shift = d / H;
        int[][][] out = new int[G][][];
        for (int g = 0; g < G; g++) {
            int g1 = (g + shift) % G;
            int g2 = (g + shift + 1) % G;
            out[g] = E.add(E.mulPlainSlots(r[g1], lo), E.mulPlainSlots(r[g2], hi));
        }
        return new Flat(eng, out, fa.size);
    }

    static Flat multRot(Flat X, Flat Y, int d, byte[] ctx) {
        Engine eng = X.eng;
        CtOps E = eng.ops();
        int k = Math.floorMod(d, X.H);
        if (X.G() == 1 && k != 0) {
            int[][][] yr = new int[Y.cts.length][][];
            for (int i = 0; i < yr.length; i++) {
                yr[i] = E.rotCt(Y.cts[i], k);
            }
            return new Flat(eng, eng.maskedMultRaw(X.cts, yr, k, ctx), X.size);
        }
        Flat Yr = d != 0 ? rot(Y, d, Hash.concat(ctx, "|yr")) : Y;
        return new Flat(eng, eng.maskedMultRaw(X.cts, Yr.cts, 0, ctx), X.size);
    }

    static Flat mult(Flat X, Flat Y, byte[] ctx) {
        return multRot(X, Y, 0, ctx);
    }
}

package org.oylama.crypto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Evaluator implements Engine {
    public interface Monitor {
        void onOpen(Evaluator eng, int count, int rotation, boolean ok);
    }

    public static final class Stats {
        public int opens;
        public int batches;
        public int mults;
        public int rotations;
        public int refreshes;
        public long proofBits;
        public long decBits;
        public int shares;
        public int sharesOk;
        public int masks;
        public int masksOk;
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(
        Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), r -> {
            Thread t = new Thread(r, "oylama-trustee");
            t.setDaemon(true);
            return t;
        });

    static <T> List<T> all(List<Callable<T>> tasks) {
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (Callable<T> c : tasks) {
                fs.add(POOL.submit(c));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) {
                out.add(f.get());
            }
            return out;
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            throw new IllegalStateException(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    final PackedBgv E;
    final Ring R;
    final Params pp;
    final DistDec dd;
    public final Map<Integer, int[]> baseKeys;
    public final List<Integer> quorum;
    final int[][] pk;
    final int[][] pkHat;
    public final byte[] secret;
    public final byte[] pub;
    final Monitor monitor;
    final boolean verify;
    public volatile String phase = "setup";
    public final Stats stats = new Stats();
    public byte[] digest;
    private final Map<Integer, int[][][]> eqCache = new ConcurrentHashMap<>();
    private final Map<Integer, int[][]> pkHatCache = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, int[]>> keyCache = new ConcurrentHashMap<>();
    private final Map<String, DistDec.KeyCom> kcCache = new ConcurrentHashMap<>();

    public Evaluator(PackedBgv E, Params pp, DistDec dd, Map<Integer, int[]> keys, List<Integer> quorum, int[][] pk,
                     byte[] secret, byte[] pub, Monitor monitor, boolean verify) {
        this.E = E;
        this.R = E.R;
        this.pp = pp;
        this.dd = dd;
        this.baseKeys = keys;
        this.quorum = new ArrayList<>(quorum);
        this.pk = pk;
        this.pkHat = E.hat(pk);
        this.secret = secret;
        this.pub = pub;
        this.monitor = monitor;
        this.verify = verify;
        this.digest = Hash.h("OYLAMA/transcript", pub);
    }

    @Override
    public CtOps ops() {
        return E;
    }

    @Override
    public void phase(String p) {
        phase = p;
    }

    int[][] pkHatK(int k) {
        int[][] v = pkHatCache.get(k);
        if (v == null) {
            v = k == 0 ? pkHat : E.hat(E.rotPk(pk, k));
            pkHatCache.put(k, v);
        }
        return v;
    }

    Map<Integer, int[]> keysK(int k) {
        Map<Integer, int[]> m = keyCache.get(k);
        if (m == null) {
            m = new HashMap<>();
            for (int j : quorum) {
                m.put(j, k == 0 ? baseKeys.get(j) : E.rotKey(baseKeys.get(j), k));
            }
            keyCache.put(k, m);
        }
        return m;
    }

    public DistDec.KeyCom keycom(int j, int k) {
        String key = j + ":" + k;
        DistDec.KeyCom kc = kcCache.get(key);
        if (kc == null) {
            kc = dd.commitKey(keysK(k).get(j), Hash.h(secret, "kc", j, k));
            kcCache.put(key, kc);
        }
        return kc;
    }

    int[][][] eqMatrix(int k) {
        int[][][] cached = eqCache.get(k);
        if (cached != null) {
            return cached;
        }
        int[][] pkk = k == 0 ? pk : E.rotPk(pk, k);
        int[] one = R.one();
        int[] pI = R.scalar(one, pp.p);
        int[][][] M = new int[4][7][];
        M[0][1] = pkk[0];
        M[0][2] = pI;
        M[1][0] = one;
        M[1][1] = pkk[1];
        M[1][3] = pI;
        M[2][4] = pk[0];
        M[2][5] = pI;
        M[3][0] = one;
        M[3][4] = pk[1];
        M[3][6] = pI;
        int[][][] Mh = new int[4][7][];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 7; j++) {
                Mh[i][j] = M[i][j] == null ? null : R.fwd(M[i][j]);
            }
        }
        eqCache.put(k, Mh);
        return Mh;
    }

    void warm(int k) {
        pkHatK(k);
        eqMatrix(k);
        keysK(k);
        for (int j : quorum) {
            keycom(j, k);
        }
    }

    static final class MaskPair {
        long[] gam;
        int[][] G;
        int[][] H;
        byte[] proofSeed;
        long bits;
        boolean ok;
    }

    MaskPair maskPair(int k, byte[] seed, byte[] ctx) {
        int n = R.n;
        Xof rd = new Xof(seed, "gamma");
        byte[] raw = rd.read(8 * n);
        long[] gam = new long[n];
        for (int i = 0; i < n; i++) {
            long v = 0;
            for (int b = 7; b >= 0; b--) {
                v = (v << 8) | (raw[8 * i + b] & 0xFFL);
            }
            gam[i] = Long.remainderUnsigned(v, pp.p);
        }
        long[] gpoly = E.slots.encode(gam);
        int[][] pkh = pkHatK(k);
        long[][] r1 = new long[3][];
        long[][] r2 = new long[3][];
        for (int t = 0; t < 3; t++) {
            r1[t] = Sample.ternary(Hash.h(seed, "g", t), "m", n);
            r2[t] = Sample.ternary(Hash.h(seed, "h", t), "m", n);
        }
        int[] r10 = R.fwd(R.fromSmall(r1[0]));
        int[] r20 = R.fwd(R.fromSmall(r2[0]));
        int[] gp = R.fromSmall(gpoly);
        int[] g0 = R.add(R.inv(R.mulHat(pkh[0], r10)), R.scalar(R.fromSmall(r1[1]), pp.p));
        int[] g1 = R.add(R.add(R.inv(R.mulHat(pkh[1], r10)), R.scalar(R.fromSmall(r1[2]), pp.p)), gp);
        int[] h0 = R.add(R.inv(R.mulHat(pkHat[0], r20)), R.scalar(R.fromSmall(r2[1]), pp.p));
        int[] h1 = R.add(R.add(R.inv(R.mulHat(pkHat[1], r20)), R.scalar(R.fromSmall(r2[2]), pp.p)), gp);
        long[][] wit = {gpoly, r1[0], r1[1], r1[2], r2[0], r2[1], r2[2]};
        int[][] tgt = {R.fwd(g0), R.fwd(g1), R.fwd(h0), R.fwd(h1)};
        int[][][] Mh = eqMatrix(k);
        double sig = Proofs.sigmaFor(pp.kappa, pp.p / 2, 7L * n);
        Proof pr = Proofs.provePreimage(R, Mh, wit, tgt, ctx, pp.kappa, sig, Hash.h(seed, "pr"));
        MaskPair mp = new MaskPair();
        mp.gam = gam;
        mp.G = new int[][]{g0, g1};
        mp.H = new int[][]{h0, h1};
        mp.proofSeed = pr == null ? new byte[0] : pr.seed;
        mp.bits = pr == null ? 0 : pr.sizeBits;
        mp.ok = !verify || Proofs.verifyPreimage(R, Mh, tgt, ctx, pp.kappa, sig, pr);
        return mp;
    }

    List<MaskPair> maskPairs(int k, byte[] ctx, String seedTag, String ctxTag, int i) {
        warm(k);
        List<Callable<MaskPair>> tasks = new ArrayList<>();
        for (int j : quorum) {
            final byte[] seed = seedTag == null ? Hash.h(secret, ctx, ctxTag, i, j) : Hash.h(secret, ctx, seedTag, i, j);
            final byte[] pctx = seedTag == null ? Hash.h(ctx, ctxTag, "p", i, j) : Hash.h(ctx, ctxTag, i, j);
            tasks.add(() -> maskPair(k, seed, pctx));
        }
        List<MaskPair> pairs = all(tasks);
        for (int q = 0; q < pairs.size(); q++) {
            MaskPair mp = pairs.get(q);
            stats.masks++;
            stats.proofBits += mp.bits;
            if (verify && mp.ok) {
                stats.masksOk++;
            }
            digest = Hash.h(digest, "mask", seedTag == null ? Hash.h(ctx, ctxTag, "p", i, quorum.get(q)) : Hash.h(ctx, ctxTag, i, quorum.get(q)), mp.proofSeed);
        }
        return pairs;
    }

    static final class ShareResult {
        DistDec.Share share;
        boolean ok;
        byte[] dctx;
    }

    long[][] open(int[][][] cts, int k, byte[] ctx, String tag) {
        warm(k);
        Map<Integer, int[]> sh = keysK(k);
        List<Callable<ShareResult>> tasks = new ArrayList<>();
        for (int j : quorum) {
            tasks.add(() -> {
                ShareResult r = new ShareResult();
                DistDec.KeyCom kc = keycom(j, k);
                r.dctx = Hash.h(ctx, tag, "dc", j);
                r.share = dd.share(sh.get(j), kc, cts, Hash.h(secret, ctx, tag, "ds", j), r.dctx, quorum.size());
                r.ok = !verify || dd.verifyShare(kc, cts, r.share, r.dctx, quorum.size());
                return r;
            });
        }
        List<ShareResult> results = all(tasks);
        List<DistDec.Share> shares = new ArrayList<>();
        boolean allOk = true;
        for (int q = 0; q < results.size(); q++) {
            ShareResult r = results.get(q);
            shares.add(r.share);
            stats.decBits += r.share.sizeBits;
            stats.shares++;
            if (verify && r.ok) {
                stats.sharesOk++;
            }
            allOk &= r.ok;
            digest = Hash.h(digest, "share", quorum.get(q), r.dctx, Hash.ser(r.share.ts));
        }
        stats.opens += cts.length;
        stats.batches++;
        long[][] polys = dd.combine(cts, shares);
        long[][] out = new long[polys.length][];
        for (int i = 0; i < polys.length; i++) {
            out[i] = E.slots.decode(polys[i]);
        }
        if (monitor != null) {
            monitor.onOpen(this, cts.length, k, allOk);
        }
        return out;
    }

    @Override
    public int[][][] refresh(int[][][] cts, int k, byte[] ctx, String tag) {
        int[][][] masked = new int[cts.length][][];
        List<List<MaskPair>> pairs = new ArrayList<>();
        for (int i = 0; i < cts.length; i++) {
            List<MaskPair> ps = maskPairs(k, ctx, null, tag, i);
            int[][] acc = cts[i];
            for (MaskPair mp : ps) {
                acc = E.add(acc, mp.G);
            }
            masked[i] = acc;
            pairs.add(ps);
        }
        long[][] opened = open(masked, k, ctx, tag);
        int[][][] out = new int[cts.length][][];
        for (int i = 0; i < cts.length; i++) {
            int[][] c = E.encSlots(pkHat, Hash.h(secret, ctx, tag, "re", i), opened[i]);
            for (MaskPair mp : pairs.get(i)) {
                c = E.sub(c, mp.H);
            }
            out[i] = c;
        }
        stats.refreshes += cts.length;
        return out;
    }

    @Override
    public int[][][] maskedMultRaw(int[][][] xs, int[][][] ys, int rotk, byte[] ctx) {
        stats.mults += xs.length;
        int[][][] masked = new int[xs.length][][];
        List<List<MaskPair>> pairs = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            List<MaskPair> ps = maskPairs(0, ctx, "mm", "mmp", i);
            int[][] acc = xs[i];
            for (MaskPair mp : ps) {
                acc = E.add(acc, mp.G);
            }
            masked[i] = acc;
            pairs.add(ps);
        }
        long[][] opened = open(masked, 0, ctx, "mo");
        int[][][] Z = new int[xs.length][][];
        for (int i = 0; i < xs.length; i++) {
            int[][] z = E.mulPlainSlots(ys[i], opened[i]);
            for (MaskPair mp : pairs.get(i)) {
                z = E.sub(z, E.mulPlainSlots(ys[i], mp.gam));
            }
            Z[i] = z;
        }
        return refresh(Z, rotk, ctx, "mz");
    }

    @Override
    public int[][][] rotate(int[][][] cts, int k, byte[] ctx) {
        stats.rotations += cts.length;
        int[][][] rot = new int[cts.length][][];
        for (int i = 0; i < cts.length; i++) {
            rot[i] = E.rotCt(cts[i], k);
        }
        return refresh(rot, k, ctx, "rot" + k);
    }
}

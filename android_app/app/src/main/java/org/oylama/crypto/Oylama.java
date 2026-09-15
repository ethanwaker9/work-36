package org.oylama.crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Oylama {
    public interface Progress {
        void step(String step, String detail);
    }

    public static final class Plan {
        public int total;
        public int stages;
        public final Map<String, Integer> groups = new LinkedHashMap<>();
    }

    public static final class MixReport {
        public int server;
        public boolean ok;
        public long bits;
        public double seconds;
    }

    public static final class DecReport {
        public int trustee;
        public boolean ok;
        public long bits;
        public double seconds;
    }

    static final SecureRandom RNG = new SecureRandom();

    public final byte[] uid;
    public final Params pp;
    public final int lam;
    public final int mmax;
    public final int nCands;
    public final int mbits;
    public final int vbits;
    public final int keybits;
    public final int H;
    public final PackedBgv E;
    public final Ring R;
    public final Circuit.Layout lay;
    public final Bdlop com;
    public final Bdlop comMix;
    public int[][] pk;
    public int[][] pkHat;
    public DistDec dd;
    public int[][][] Ah;

    public Oylama(byte[] uid, int mmax, int nCands, Params pp) {
        this.uid = uid;
        this.pp = pp;
        this.lam = pp.lam;
        this.mmax = mmax;
        this.nCands = nCands;
        this.mbits = Math.max(2, 32 - Integer.numberOfLeadingZeros(mmax));
        this.vbits = Math.max(1, 32 - Integer.numberOfLeadingZeros(nCands));
        this.keybits = mbits + lam;
        this.H = pp.N / 2;
        this.E = new PackedBgv(pp.N, pp.primes, pp.p);
        this.R = E.R;
        this.lay = new Circuit.Layout(mmax, H, keybits + vbits + 1);
        if (lay.G != 1) {
            throw new IllegalArgumentException("board capacity too large");
        }
        this.com = new Bdlop(R, 1, 1, 3, "oylama-dec");
        this.comMix = new Bdlop(R, 1, 2, 4, "oylama-mix");
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public PackedBgv.KeyPair keygen(byte[] seed) {
        return E.keygen(seed);
    }

    public Oylama attach(int[][] pk) {
        this.pk = pk;
        this.pkHat = E.hat(pk);
        this.dd = new DistDec(pp, R, com, java.math.BigInteger.ONE.shiftLeft(52));
        int[] one = R.one();
        Ah = new int[][][]{
            {pkHat[0], R.fwd(R.scalar(one, pp.p)), R.fwd(R.zeros())},
            {pkHat[1], R.fwd(R.zeros()), R.fwd(one)}
        };
        return this;
    }

    public ThresholdKey deal(int[] s, byte[] seed) {
        return ThresholdKey.deal(R, pp.nTrustee, pp.threshold, s, seed);
    }

    public long[] spread(int[] bits, int baseBlock) {
        long[] v = new long[pp.N];
        for (int b = 0; b < bits.length; b++) {
            v[(baseBlock + b) * mmax] = bits[b];
        }
        return v;
    }

    public int[] newCredential() {
        byte[] h = Hash.shake((lam + 7) / 8, random(32), "credential");
        return Codec.bitsFromBytes(h, lam);
    }

    public int[][] register(int[] cred, byte[] seed) {
        return E.encSlots(pkHat, Hash.h(seed, "roster"), spread(cred, mbits));
    }

    public byte[] ballotContext(String tag, int[][] ct) {
        return Hash.h("OYLAMA/ballot", uid, tag, Hash.ser(ct[0], ct[1]));
    }

    public double sigmaBound() {
        return Proofs.sigmaFor(pp.kappa, 2L * pp.p, 3L * R.n);
    }

    public Ballot vote(int[] cred, int nu, byte[] seed, Progress progress) {
        int[] vb = new int[vbits];
        for (int t = 0; t < vbits; t++) {
            vb[t] = (nu >> t) & 1;
        }
        String[] tags = {"v", "c"};
        long[][] polys = {E.slots.encode(spread(vb, keybits)), E.slots.encode(spread(cred, mbits))};
        int[][][] cts = new int[2][][];
        Proof[] prs = new Proof[2];
        double[] sigs = new double[2];
        for (int i = 0; i < 2; i++) {
            if (progress != null) {
                progress.step(i == 0 ? "encrypt-vote" : "encrypt-credential", null);
            }
            PackedBgv.Enc enc = E.encParts(pkHat, Hash.h(seed, tags[i]), polys[i]);
            int[][] t = {R.fwd(enc.ct[0]), R.fwd(enc.ct[1])};
            long mx = 0;
            for (long[] row : enc.x) {
                for (long v : row) {
                    mx = Math.max(mx, Math.abs(v));
                }
            }
            double sig = Proofs.sigmaFor(pp.kappa, mx + 1, 3L * R.n);
            if (progress != null) {
                progress.step(i == 0 ? "prove-vote" : "prove-credential", null);
            }
            Proof pr = Proofs.provePreimage(R, Ah, enc.x, t, ballotContext(tags[i], enc.ct), pp.kappa, sig, Hash.h(seed, tags[i], "rnd"));
            if (pr == null) {
                return vote(cred, nu, random(32), progress);
            }
            cts[i] = enc.ct;
            prs[i] = pr;
            sigs[i] = sig;
        }
        long size = 4L * R.n * R.logq + prs[0].sizeBits + prs[1].sizeBits;
        return new Ballot(cts[0], cts[1], prs[0], sigs[0], prs[1], sigs[1], size);
    }

    public boolean checkBallot(Ballot b) {
        double bound = sigmaBound() * (1 + 1e-9);
        int[][][] cts = {b.c1, b.c2};
        Proof[] prs = {b.p1, b.p2};
        double[] sigs = {b.s1, b.s2};
        String[] tags = {"v", "c"};
        for (int i = 0; i < 2; i++) {
            if (!(sigs[i] > 0 && sigs[i] <= bound)) {
                return false;
            }
            for (int[] comp : cts[i]) {
                if (comp.length != R.L * R.n) {
                    return false;
                }
                for (int l = 0; l < R.L; l++) {
                    int q = R.q[l];
                    for (int k = l * R.n; k < (l + 1) * R.n; k++) {
                        if (comp[k] < 0 || comp[k] >= q) {
                            return false;
                        }
                    }
                }
            }
            int[][] t = {R.fwd(cts[i][0]), R.fwd(cts[i][1])};
            if (!Proofs.verifyPreimage(R, Ah, t, ballotContext(tags[i], cts[i]), pp.kappa, sigs[i], prs[i])) {
                return false;
            }
        }
        return true;
    }

    public byte[] ballotKey(Ballot b) {
        return Hash.h(Hash.ser(b.c1[0], b.c2[0]));
    }

    public byte[] tracker(Ballot b) {
        return Hash.shake(16, "OYLAMA/tracker", uid, Hash.ser(b.c1[0], b.c1[1], b.c2[0], b.c2[1]), b.p1.seed, b.p2.seed);
    }

    public Evaluator evaluator(Map<Integer, Map<String, int[]>> holdings, List<Integer> quorum, byte[] secret, byte[] pub,
                               Evaluator.Monitor monitor, boolean verify) {
        Map<String, int[]> merged = new LinkedHashMap<>();
        for (Map<String, int[]> h : holdings.values()) {
            merged.putAll(h);
        }
        ThresholdKey tk = new ThresholdKey(R, pp.nTrustee, pp.threshold, merged);
        Map<Integer, List<String>> asg = tk.assign(quorum);
        Map<Integer, int[]> keys = new LinkedHashMap<>();
        for (int j : quorum) {
            keys.put(j, tk.partyKey(j, asg));
        }
        return new Evaluator(E, pp, dd, keys, quorum, pk, secret, pub, monitor, verify);
    }

    static byte[] c(byte[] ctx, String s) {
        return Hash.concat(ctx, s);
    }

    public Flat load(Engine eng, List<int[][]> ballotSums, List<int[][]> roster, byte[] ctx) {
        CtOps ops = eng.ops();
        int nb = ballotSums.size();
        List<int[][]> items = new ArrayList<>(ballotSums);
        items.addAll(roster);
        int[][] acc = null;
        for (int pos = 0; pos < items.size(); pos++) {
            eng.phase("load:" + pos);
            int[][] rot = eng.rotate(new int[][][]{items.get(pos)}, Math.floorMod(H - pos, H), Hash.h(ctx, "ld", pos))[0];
            long[] m = new long[pp.N];
            for (int b = 0; b < lay.B; b++) {
                if (b * lay.M + pos < lay.size) {
                    m[b * lay.M + pos] = 1;
                }
            }
            int[][] cm = ops.mulPlainSlots(rot, m);
            acc = acc == null ? cm : ops.add(acc, cm);
        }
        Flat F = new Flat(eng, new int[][][]{acc}, lay.size);
        int step = 1;
        eng.phase("replicate");
        while (step < lay.copies) {
            F = F.add(Flat.rot(F, lay.size - step * lay.unit, Hash.h(ctx, "rep", step)));
            step *= 2;
        }
        long[] base = new long[lay.unit];
        for (int pos = 0; pos < items.size(); pos++) {
            int val = pos < nb ? pos : nb;
            for (int b = 0; b < mbits; b++) {
                base[b * lay.M + pos] = (val >> b) & 1;
            }
        }
        return Flat.fresh(F.addConst(lay.spread(base)), Hash.h(ctx, "lf"));
    }

    public Flat cleanse(Engine eng, Flat F, int nb, byte[] ctx) {
        Flat K = F;
        List<Circuit.Stage> stages = Circuit.batcherStages(lay.M);
        for (int si = 0; si < stages.size(); si++) {
            eng.phase("sort:" + (si + 1) + ":" + stages.size());
            Circuit.Stage s = stages.get(si);
            K = Circuit.compareSwapStage(lay, K, s.d, s.lefts, keybits, c(ctx, "|s" + si));
        }
        eng.phase("dedup");
        Flat nxt = Flat.rot(K, 1, c(ctx, "|nx"));
        long[] cm = lay.blockMask(mbits, mbits + lam);
        Flat diff = K.sub(nxt);
        Flat sq = Flat.mult(diff, diff, c(ctx, "|sq"));
        Flat eqc = Circuit.oneMinus(lay, sq).mask(cm).addConst(Circuit.oneMinus(cm));
        Flat Dk = Circuit.suffixAnd(lay, eqc, c(ctx, "|Dk"));
        eng.phase("roster");
        long[] nbb = new long[lay.unit];
        for (int b = 0; b < mbits; b++) {
            if (((nb >> b) & 1) == 1) {
                for (int i = b * lay.M; i < (b + 1) * lay.M; i++) {
                    nbb[i] = 1;
                }
            }
        }
        long[] nbbits = lay.spread(nbb);
        long[] im = lay.blockMask(0, mbits);
        Flat eqi = nxt.mask(Circuit.linVec(nbbits, 2, -1)).addConst(Circuit.oneMinus(nbbits));
        eqi = eqi.mask(im).addConst(Circuit.oneMinus(im));
        Flat Fk = Circuit.suffixAnd(lay, eqi, c(ctx, "|Fk"));
        eng.phase("valid");
        long[] b0 = lay.blockMask(0, 1);
        Flat valid = Flat.mult(Dk.mask(b0), Fk, c(ctx, "|vb"));
        Flat vb = Circuit.totalBroadcast(lay, Flat.fresh(valid.mask(b0), c(ctx, "|fv")), c(ctx, "|bc"));
        eng.phase("gate");
        long[] vmask = lay.blockMask(keybits, keybits + vbits);
        return Flat.mult(K.mask(vmask), vb, c(ctx, "|cg"));
    }

    public int[][][] extract(Engine eng, Flat F, int count, byte[] ctx) {
        CtOps ops = eng.ops();
        int[][][] outs = new int[count][][];
        for (int i = 0; i < count; i++) {
            eng.phase("extract:" + (i + 1) + ":" + count);
            int[][] rot = eng.rotate(F.cts, i % H, Hash.h(ctx, "ex", i))[0];
            long[] m = new long[pp.N];
            for (int b = 0; b < vbits; b++) {
                m[(keybits + b) * lay.M] = 1;
            }
            outs[i] = ops.mulPlainSlots(rot, m);
        }
        return outs;
    }

    static final class DryOps implements CtOps {
        final int n;

        DryOps(int n) {
            this.n = n;
        }

        public int n() {
            return n;
        }

        public int[][] add(int[][] a, int[][] b) {
            return null;
        }

        public int[][] sub(int[][] a, int[][] b) {
            return null;
        }

        public int[][] mulPlainSlots(int[][] c, long[] vec) {
            return null;
        }

        public int[][] addPlainSlots(int[][] c, long[] vec) {
            return null;
        }

        public int[][] scale(int[][] c, long k) {
            return null;
        }

        public int[][] zero() {
            return null;
        }

        public int[][] rotCt(int[][] c, int k) {
            return null;
        }
    }

    static final class DryEngine implements Engine {
        final DryOps ops;
        String phase = "load";
        final Map<String, Integer> counts = new LinkedHashMap<>();

        DryEngine(int n) {
            ops = new DryOps(n);
        }

        void tick(int n) {
            counts.merge(phase, n, Integer::sum);
        }

        public CtOps ops() {
            return ops;
        }

        public void phase(String p) {
            phase = p;
        }

        public int[][][] rotate(int[][][] cts, int k, byte[] ctx) {
            tick(1);
            return new int[cts.length][][];
        }

        public int[][][] refresh(int[][][] cts, int k, byte[] ctx, String tag) {
            tick(1);
            return new int[cts.length][][];
        }

        public int[][][] maskedMultRaw(int[][][] xs, int[][][] ys, int rotk, byte[] ctx) {
            tick(2);
            return new int[xs.length][][];
        }
    }

    public Plan plan(int nb, int nv) {
        DryEngine dry = new DryEngine(pp.N);
        List<int[][]> b = new ArrayList<>();
        for (int i = 0; i < nb; i++) {
            b.add(null);
        }
        List<int[][]> r = new ArrayList<>();
        for (int i = 0; i < nv; i++) {
            r.add(null);
        }
        Flat F = load(dry, b, r, new byte[1]);
        Flat out = cleanse(dry, F, nb, new byte[1]);
        extract(dry, out, mmax, new byte[1]);
        Plan p = new Plan();
        for (Map.Entry<String, Integer> e : dry.counts.entrySet()) {
            String key = e.getKey().split(":")[0];
            if (key.equals("replicate")) {
                key = "load";
            }
            p.groups.merge(key, e.getValue(), Integer::sum);
            p.total += e.getValue();
        }
        p.stages = Circuit.batcherStages(lay.M).size();
        return p;
    }

    public int[][][] mix(int[][][] cts, byte[] secret, byte[] ctx, List<MixReport> reports) {
        int[][][] cur = cts;
        for (int k = 0; k < pp.nMix; k++) {
            Shuffler S = new Shuffler(pp, R, comMix, pk);
            long t0 = System.nanoTime();
            boolean ok = false;
            int[][][] out = null;
            Shuffler.MixProof[] holder = new Shuffler.MixProof[1];
            for (int attempt = 0; attempt < 3; attempt++) {
                byte[] mctx = Hash.h(ctx, "mc", k, attempt);
                out = S.mix(cur, Hash.h(secret, "mix", k, attempt), mctx, holder);
                ok = S.verify(cur, out, holder[0], mctx);
                if (ok) {
                    break;
                }
            }
            MixReport rep = new MixReport();
            rep.server = k + 1;
            rep.ok = ok;
            rep.bits = holder[0].sizeBits;
            rep.seconds = (System.nanoTime() - t0) / 1e9;
            reports.add(rep);
            cur = out;
        }
        return cur;
    }

    public int readSlotVote(long[] vec) {
        int v = 0;
        for (int b = 0; b < vbits; b++) {
            v |= ((int) vec[(keybits + b) * mmax]) << b;
        }
        return v;
    }

    public int[] decrypt(Evaluator eng, int[][][] cts, byte[] ctx, List<DecReport> reports) {
        List<DistDec.Share> shares = new ArrayList<>();
        for (int j : eng.quorum) {
            long t0 = System.nanoTime();
            DistDec.KeyCom kc = eng.keycom(j, 0);
            byte[] dctx = Hash.h(ctx, "final", j);
            DistDec.Share sh = dd.share(eng.baseKeys.get(j), kc, cts, Hash.h(eng.secret, ctx, "final", j), dctx, eng.quorum.size());
            boolean ok = dd.verifyShare(kc, cts, sh, dctx, eng.quorum.size());
            shares.add(sh);
            DecReport rep = new DecReport();
            rep.trustee = j + 1;
            rep.ok = ok;
            rep.bits = sh.sizeBits;
            rep.seconds = (System.nanoTime() - t0) / 1e9;
            reports.add(rep);
        }
        long[][] polys = dd.combine(cts, shares);
        int[] votes = new int[polys.length];
        for (int i = 0; i < polys.length; i++) {
            votes[i] = readSlotVote(E.slots.decode(polys[i]));
        }
        return votes;
    }

    public int[][] ballotSum(Ballot b) {
        return E.add(b.c1, b.c2);
    }

    public static boolean same(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}

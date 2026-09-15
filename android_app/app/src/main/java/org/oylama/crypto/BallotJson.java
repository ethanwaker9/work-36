package org.oylama.crypto;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class BallotJson {
    private BallotJson() {
    }

    public static JSONObject toJson(Ballot b) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("c1", new JSONObject().put("u", Codec.ringToB64(b.c1[0])).put("w", Codec.ringToB64(b.c1[1])));
        o.put("c2", new JSONObject().put("u", Codec.ringToB64(b.c2[0])).put("w", Codec.ringToB64(b.c2[1])));
        JSONArray pr = new JSONArray();
        pr.put(new JSONObject().put("d", Codec.b64(b.p1.seed)).put("z", Codec.longsToB64(b.p1.z)).put("sigma", b.s1));
        pr.put(new JSONObject().put("d", Codec.b64(b.p2.seed)).put("z", Codec.longsToB64(b.p2.z)).put("sigma", b.s2));
        o.put("proofs", pr);
        return o;
    }

    public static Ballot fromJson(Oylama V, JSONObject o) throws JSONException {
        int L = V.R.L;
        int n = V.R.n;
        JSONObject c1 = o.getJSONObject("c1");
        JSONObject c2 = o.getJSONObject("c2");
        int[][] C1 = {Codec.ringFromB64(c1.getString("u"), L, n), Codec.ringFromB64(c1.getString("w"), L, n)};
        int[][] C2 = {Codec.ringFromB64(c2.getString("u"), L, n), Codec.ringFromB64(c2.getString("w"), L, n)};
        JSONArray pr = o.getJSONArray("proofs");
        if (pr.length() != 2) {
            throw new IllegalArgumentException("a ballot carries exactly two proofs");
        }
        Proof[] ps = new Proof[2];
        double[] sg = new double[2];
        long size = 4L * n * V.R.logq;
        for (int i = 0; i < 2; i++) {
            JSONObject p = pr.getJSONObject(i);
            byte[] d = Codec.unb64(p.getString("d"));
            if (d.length != 32) {
                throw new IllegalArgumentException("challenge seed must be 32 bytes");
            }
            sg[i] = p.getDouble("sigma");
            if (!(sg[i] > 0)) {
                throw new IllegalArgumentException("invalid sigma");
            }
            long[][] z = Codec.longsFromB64(p.getString("z"), 3, n);
            ps[i] = Proof.preimage(d, z, 3L * n * Proofs.bitsOf(sg[i]) + 256);
            size += ps[i].sizeBits;
        }
        return new Ballot(C1, C2, ps[0], sg[0], ps[1], sg[1], size);
    }

    public static Oylama schemeFromPublicKey(JSONObject pk) throws JSONException {
        JSONArray pr = pk.getJSONArray("primes");
        int[] primes = new int[pr.length()];
        for (int i = 0; i < primes.length; i++) {
            primes[i] = pr.getInt(i);
        }
        Params pp = new Params(pk.getInt("N"), primes, pk.getInt("p"));
        Oylama V = new Oylama(Hash.unhex(pk.getString("uid")), pk.getInt("mmax"), pk.getInt("n_cands"), pp);
        int[][] key = {Codec.ringFromB64(pk.getString("a"), V.R.L, V.R.n), Codec.ringFromB64(pk.getString("b"), V.R.L, V.R.n)};
        return V.attach(key);
    }
}

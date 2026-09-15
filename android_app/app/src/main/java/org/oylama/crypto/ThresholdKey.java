package org.oylama.crypto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ThresholdKey {
    public final int parties;
    public final int t;
    public final List<int[]> unqual;
    public final Map<String, int[]> shares;
    private final Ring R;

    public ThresholdKey(Ring R, int parties, int t, Map<String, int[]> shares) {
        this.R = R;
        this.parties = parties;
        this.t = t;
        this.unqual = combinations(parties, t - 1);
        this.shares = new LinkedHashMap<>(shares);
    }

    public static List<int[]> combinations(int n, int k) {
        List<int[]> out = new ArrayList<>();
        int[] cur = new int[k];
        comb(out, cur, 0, 0, n, k);
        return out;
    }

    private static void comb(List<int[]> out, int[] cur, int start, int depth, int n, int k) {
        if (depth == k) {
            out.add(cur.clone());
            return;
        }
        for (int i = start; i < n; i++) {
            cur[depth] = i;
            comb(out, cur, i + 1, depth + 1, n, k);
        }
    }

    public static String key(int[] U) {
        StringBuilder sb = new StringBuilder("u");
        for (int x : U) {
            sb.append('_').append(x);
        }
        return sb.toString();
    }

    static boolean contains(int[] U, int j) {
        for (int x : U) {
            if (x == j) {
                return true;
            }
        }
        return false;
    }

    public static ThresholdKey deal(Ring R, int parties, int t, int[] s, byte[] seed) {
        List<int[]> unq = combinations(parties, t - 1);
        Map<String, int[]> sh = new LinkedHashMap<>();
        int[] acc = R.zeros();
        for (int idx = 0; idx < unq.size() - 1; idx++) {
            int[] v = R.uniform(Hash.h(seed, "sh", idx), "OYLAMA/noise");
            sh.put(key(unq.get(idx)), v);
            acc = R.add(acc, v);
        }
        sh.put(key(unq.get(unq.size() - 1)), R.sub(s, acc));
        return new ThresholdKey(R, parties, t, sh);
    }

    public Map<String, int[]> holdings(int j) {
        Map<String, int[]> h = new LinkedHashMap<>();
        for (int[] U : unqual) {
            if (!contains(U, j)) {
                h.put(key(U), shares.get(key(U)));
            }
        }
        return h;
    }

    public Map<Integer, List<String>> assign(List<Integer> quorum) {
        Map<Integer, List<String>> asg = new LinkedHashMap<>();
        for (int j : quorum) {
            asg.put(j, new ArrayList<>());
        }
        Set<String> taken = new HashSet<>();
        for (int[] U : unqual) {
            String k = key(U);
            for (int j : quorum) {
                if (!contains(U, j) && !taken.contains(k) && shares.containsKey(k)) {
                    asg.get(j).add(k);
                    taken.add(k);
                    break;
                }
            }
        }
        if (taken.size() != unqual.size()) {
            throw new IllegalStateException("quorum too small");
        }
        return asg;
    }

    public int[] partyKey(int j, Map<Integer, List<String>> asg) {
        int[] acc = R.zeros();
        for (String k : asg.get(j)) {
            acc = R.add(acc, shares.get(k));
        }
        return acc;
    }
}

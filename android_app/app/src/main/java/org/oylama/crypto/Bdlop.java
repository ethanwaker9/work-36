package org.oylama.crypto;

public final class Bdlop {
    public final int rows;
    public final int ell;
    public final int k;
    public final int[][][] B1;
    public final int[][][] B2;
    public final int[][][] B1h;
    public final int[][][] B2h;

    public Bdlop(Ring R, int rows, int ell, int k, String seed) {
        this.rows = rows;
        this.ell = ell;
        this.k = k;
        int need = rows * (k - rows) + ell * (k - rows - ell);
        int[][] rnd = R.uniformMany(Hash.h(Hash.bytes(seed), "A", rows, ell, k), "OYLAMA/commit-a", Math.max(1, need));
        int[] one = R.one();
        int c = 0;
        B1 = new int[rows][k][];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < k; j++) {
                B1[i][j] = R.zeros();
            }
            B1[i][i] = one.clone();
            for (int j = rows; j < k; j++) {
                B1[i][j] = rnd[c++];
            }
        }
        B2 = new int[ell][k][];
        for (int i = 0; i < ell; i++) {
            for (int j = 0; j < k; j++) {
                B2[i][j] = R.zeros();
            }
            B2[i][rows + i] = one.clone();
            for (int j = rows + ell; j < k; j++) {
                B2[i][j] = rnd[c++];
            }
        }
        B1h = new int[rows][k][];
        B2h = new int[ell][k][];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < k; j++) {
                B1h[i][j] = R.fwd(B1[i][j]);
            }
        }
        for (int i = 0; i < ell; i++) {
            for (int j = 0; j < k; j++) {
                B2h[i][j] = R.fwd(B2[i][j]);
            }
        }
    }
}

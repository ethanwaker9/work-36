package org.oylama.crypto;

public interface CtOps {
    int n();

    int[][] add(int[][] a, int[][] b);

    int[][] sub(int[][] a, int[][] b);

    int[][] mulPlainSlots(int[][] c, long[] vec);

    int[][] addPlainSlots(int[][] c, long[] vec);

    int[][] scale(int[][] c, long k);

    int[][] zero();

    int[][] rotCt(int[][] c, int k);
}

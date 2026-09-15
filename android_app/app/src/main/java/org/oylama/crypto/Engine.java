package org.oylama.crypto;

public interface Engine {
    CtOps ops();

    void phase(String phase);

    int[][][] rotate(int[][][] cts, int k, byte[] ctx);

    int[][][] refresh(int[][][] cts, int k, byte[] ctx, String tag);

    int[][][] maskedMultRaw(int[][][] xs, int[][][] ys, int rotk, byte[] ctx);
}

package org.oylama.crypto;

public final class Ballot {
    public final int[][] c1;
    public final int[][] c2;
    public final Proof p1;
    public final Proof p2;
    public final double s1;
    public final double s2;
    public final long sizeBits;

    public Ballot(int[][] c1, int[][] c2, Proof p1, double s1, Proof p2, double s2, long sizeBits) {
        this.c1 = c1;
        this.c2 = c2;
        this.p1 = p1;
        this.p2 = p2;
        this.s1 = s1;
        this.s2 = s2;
        this.sizeBits = sizeBits;
    }
}

package org.oylama.crypto;

public final class Proof {
    public final String kind;
    public final byte[] seed;
    public final long[][] z;
    public final long[][] zp;
    public final int[][] zr;
    public final long sizeBits;

    Proof(String kind, byte[] seed, long[][] z, long[][] zp, int[][] zr, long sizeBits) {
        this.kind = kind;
        this.seed = seed;
        this.z = z;
        this.zp = zp;
        this.zr = zr;
        this.sizeBits = sizeBits;
    }

    public static Proof preimage(byte[] seed, long[][] z, long sizeBits) {
        return new Proof("preimage", seed, z, null, null, sizeBits);
    }
}

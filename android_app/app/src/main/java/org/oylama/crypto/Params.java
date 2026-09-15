package org.oylama.crypto;

public final class Params {
    public final int N;
    public final int[] primes;
    public final int p;
    public final int kappa;
    public final int sec;
    public final int nMix;
    public final int nTrustee;
    public final int threshold;
    public final int batch;
    public final int lam;

    public Params(int N, int[] primes, int p) {
        this.N = N;
        this.primes = primes;
        this.p = p;
        this.kappa = 23;
        this.sec = 40;
        this.nMix = 2;
        this.nTrustee = 3;
        this.threshold = 2;
        this.batch = 8;
        this.lam = 128;
    }

    private static Params standard;

    public static synchronized Params standard() {
        if (standard == null) {
            standard = new Params(8192, Ntt.findPrimes(8192, 24, 5), 65537);
        }
        return standard;
    }
}

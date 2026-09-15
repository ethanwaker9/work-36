package org.oylama.crypto;

public final class Shake256 {
    private static final long[] RC = {
        0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL, 0x8000000080008000L,
        0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
        0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
        0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L, 0x8000000000008003L,
        0x8000000000008002L, 0x8000000000000080L, 0x000000000000800AL, 0x800000008000000AL,
        0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };
    private static final int RATE = 136;

    private final long[] s = new long[25];
    private final byte[] out = new byte[RATE];
    private int pos;
    private int outPos = RATE;
    private boolean squeezing;

    public Shake256 copy() {
        Shake256 c = new Shake256();
        System.arraycopy(s, 0, c.s, 0, 25);
        c.pos = pos;
        return c;
    }

    private void permute() {
        final long[] st = s;
        long a0 = st[0], a1 = st[1], a2 = st[2], a3 = st[3], a4 = st[4];
        long a5 = st[5], a6 = st[6], a7 = st[7], a8 = st[8], a9 = st[9];
        long a10 = st[10], a11 = st[11], a12 = st[12], a13 = st[13], a14 = st[14];
        long a15 = st[15], a16 = st[16], a17 = st[17], a18 = st[18], a19 = st[19];
        long a20 = st[20], a21 = st[21], a22 = st[22], a23 = st[23], a24 = st[24];
        for (int r = 0; r < 24; r++) {
            final long c0 = a0 ^ a5 ^ a10 ^ a15 ^ a20;
            final long c1 = a1 ^ a6 ^ a11 ^ a16 ^ a21;
            final long c2 = a2 ^ a7 ^ a12 ^ a17 ^ a22;
            final long c3 = a3 ^ a8 ^ a13 ^ a18 ^ a23;
            final long c4 = a4 ^ a9 ^ a14 ^ a19 ^ a24;
            final long d0 = c4 ^ ((c1 << 1) | (c1 >>> 63));
            final long d1 = c0 ^ ((c2 << 1) | (c2 >>> 63));
            final long d2 = c1 ^ ((c3 << 1) | (c3 >>> 63));
            final long d3 = c2 ^ ((c4 << 1) | (c4 >>> 63));
            final long d4 = c3 ^ ((c0 << 1) | (c0 >>> 63));
            a0 ^= d0;
            a1 ^= d1;
            a2 ^= d2;
            a3 ^= d3;
            a4 ^= d4;
            a5 ^= d0;
            a6 ^= d1;
            a7 ^= d2;
            a8 ^= d3;
            a9 ^= d4;
            a10 ^= d0;
            a11 ^= d1;
            a12 ^= d2;
            a13 ^= d3;
            a14 ^= d4;
            a15 ^= d0;
            a16 ^= d1;
            a17 ^= d2;
            a18 ^= d3;
            a19 ^= d4;
            a20 ^= d0;
            a21 ^= d1;
            a22 ^= d2;
            a23 ^= d3;
            a24 ^= d4;
            final long b0 = a0;
            final long b10 = (a1 << 1) | (a1 >>> 63);
            final long b20 = (a2 << 62) | (a2 >>> 2);
            final long b5 = (a3 << 28) | (a3 >>> 36);
            final long b15 = (a4 << 27) | (a4 >>> 37);
            final long b16 = (a5 << 36) | (a5 >>> 28);
            final long b1 = (a6 << 44) | (a6 >>> 20);
            final long b11 = (a7 << 6) | (a7 >>> 58);
            final long b21 = (a8 << 55) | (a8 >>> 9);
            final long b6 = (a9 << 20) | (a9 >>> 44);
            final long b7 = (a10 << 3) | (a10 >>> 61);
            final long b17 = (a11 << 10) | (a11 >>> 54);
            final long b2 = (a12 << 43) | (a12 >>> 21);
            final long b12 = (a13 << 25) | (a13 >>> 39);
            final long b22 = (a14 << 39) | (a14 >>> 25);
            final long b23 = (a15 << 41) | (a15 >>> 23);
            final long b8 = (a16 << 45) | (a16 >>> 19);
            final long b18 = (a17 << 15) | (a17 >>> 49);
            final long b3 = (a18 << 21) | (a18 >>> 43);
            final long b13 = (a19 << 8) | (a19 >>> 56);
            final long b14 = (a20 << 18) | (a20 >>> 46);
            final long b24 = (a21 << 2) | (a21 >>> 62);
            final long b9 = (a22 << 61) | (a22 >>> 3);
            final long b19 = (a23 << 56) | (a23 >>> 8);
            final long b4 = (a24 << 14) | (a24 >>> 50);
            a0 = b0 ^ (~b1 & b2);
            a1 = b1 ^ (~b2 & b3);
            a2 = b2 ^ (~b3 & b4);
            a3 = b3 ^ (~b4 & b0);
            a4 = b4 ^ (~b0 & b1);
            a5 = b5 ^ (~b6 & b7);
            a6 = b6 ^ (~b7 & b8);
            a7 = b7 ^ (~b8 & b9);
            a8 = b8 ^ (~b9 & b5);
            a9 = b9 ^ (~b5 & b6);
            a10 = b10 ^ (~b11 & b12);
            a11 = b11 ^ (~b12 & b13);
            a12 = b12 ^ (~b13 & b14);
            a13 = b13 ^ (~b14 & b10);
            a14 = b14 ^ (~b10 & b11);
            a15 = b15 ^ (~b16 & b17);
            a16 = b16 ^ (~b17 & b18);
            a17 = b17 ^ (~b18 & b19);
            a18 = b18 ^ (~b19 & b15);
            a19 = b19 ^ (~b15 & b16);
            a20 = b20 ^ (~b21 & b22);
            a21 = b21 ^ (~b22 & b23);
            a22 = b22 ^ (~b23 & b24);
            a23 = b23 ^ (~b24 & b20);
            a24 = b24 ^ (~b20 & b21);
            a0 ^= RC[r];
        }
        st[0] = a0; st[1] = a1; st[2] = a2; st[3] = a3; st[4] = a4;
        st[5] = a5; st[6] = a6; st[7] = a7; st[8] = a8; st[9] = a9;
        st[10] = a10; st[11] = a11; st[12] = a12; st[13] = a13; st[14] = a14;
        st[15] = a15; st[16] = a16; st[17] = a17; st[18] = a18; st[19] = a19;
        st[20] = a20; st[21] = a21; st[22] = a22; st[23] = a23; st[24] = a24;
    }

    private static long le64(byte[] a, int o) {
        return (a[o] & 0xFFL) | ((a[o + 1] & 0xFFL) << 8) | ((a[o + 2] & 0xFFL) << 16) | ((a[o + 3] & 0xFFL) << 24)
            | ((a[o + 4] & 0xFFL) << 32) | ((a[o + 5] & 0xFFL) << 40) | ((a[o + 6] & 0xFFL) << 48) | ((a[o + 7] & 0xFFL) << 56);
    }

    private void absorbByte(int b) {
        s[pos >>> 3] ^= (b & 0xFFL) << ((pos & 7) << 3);
        if (++pos == RATE) {
            permute();
            pos = 0;
        }
    }

    public Shake256 update(byte[] data, int off, int len) {
        while (len > 0 && (pos & 7) != 0) {
            absorbByte(data[off++]);
            len--;
        }
        final long[] st = s;
        int p = pos;
        while (len >= 8) {
            st[p >>> 3] ^= le64(data, off);
            off += 8;
            len -= 8;
            p += 8;
            if (p == RATE) {
                permute();
                p = 0;
            }
        }
        pos = p;
        while (len > 0) {
            absorbByte(data[off++]);
            len--;
        }
        return this;
    }

    public Shake256 update(byte[] data) {
        return update(data, 0, data.length);
    }

    public Shake256 updateLong(long v) {
        byte[] t = new byte[8];
        for (int i = 0; i < 8; i++) {
            t[i] = (byte) (v >>> (8 * i));
        }
        return update(t, 0, 8);
    }

    public Shake256 updateInt32(int v) {
        byte[] t = {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
        return update(t, 0, 4);
    }

    public Shake256 updateInt64(int[] a) {
        final long[] st = s;
        int n = a.length;
        int p = pos;
        if ((p & 7) == 0) {
            for (int i = 0; i < n; i++) {
                st[p >>> 3] ^= a[i] & 0xFFFFFFFFL;
                p += 8;
                if (p == RATE) {
                    permute();
                    p = 0;
                }
            }
            pos = p;
        } else if ((p & 7) == 4) {
            for (int i = 0; i < n; i++) {
                st[p >>> 3] ^= (a[i] & 0xFFFFFFFFL) << 32;
                p += 8;
                if (p > RATE) {
                    permute();
                    p -= RATE;
                }
            }
            pos = p;
        } else {
            byte[] t = new byte[8];
            for (int v : a) {
                t[0] = (byte) v;
                t[1] = (byte) (v >>> 8);
                t[2] = (byte) (v >>> 16);
                t[3] = (byte) (v >>> 24);
                update(t, 0, 8);
            }
        }
        return this;
    }

    private void extract() {
        for (int i = 0; i < 17; i++) {
            long v = s[i];
            int o = 8 * i;
            for (int k = 0; k < 8; k++) {
                out[o + k] = (byte) (v >>> (8 * k));
            }
        }
        outPos = 0;
    }

    public void squeeze(byte[] dst, int off, int len) {
        if (!squeezing) {
            s[pos >>> 3] ^= 0x1FL << ((pos & 7) << 3);
            s[16] ^= 0x80L << 56;
            permute();
            squeezing = true;
            extract();
        }
        while (len > 0) {
            if (outPos == RATE) {
                permute();
                extract();
            }
            int take = Math.min(RATE - outPos, len);
            System.arraycopy(out, outPos, dst, off, take);
            outPos += take;
            off += take;
            len -= take;
        }
    }

    public byte[] digest(int n) {
        byte[] d = new byte[n];
        squeeze(d, 0, n);
        return d;
    }
}

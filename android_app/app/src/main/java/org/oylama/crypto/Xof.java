package org.oylama.crypto;

public final class Xof {
    private static final int BLOCK = 1 << 14;
    private final byte[] pre;
    private byte[] buf = new byte[0];
    private int pos;
    private long ctr;

    public Xof(byte[] seed, String domain) {
        pre = Hash.concat(Hash.bytes(domain), seed);
    }

    private void more(int need) {
        int want = Math.max(need, BLOCK);
        int blocks = (want + BLOCK - 1) / BLOCK;
        int rest = buf.length - pos;
        byte[] nb = new byte[rest + blocks * BLOCK];
        System.arraycopy(buf, pos, nb, 0, rest);
        int off = rest;
        for (int i = 0; i < blocks; i++) {
            Shake256 h = new Shake256();
            h.update(pre);
            h.updateLong(ctr++);
            h.squeeze(nb, off, BLOCK);
            off += BLOCK;
        }
        buf = nb;
        pos = 0;
    }

    public byte[] read(int n) {
        byte[] r = new byte[n];
        read(r, 0, n);
        return r;
    }

    public void read(byte[] dst, int off, int n) {
        if (pos + n > buf.length) {
            more(n);
        }
        System.arraycopy(buf, pos, dst, off, n);
        pos += n;
    }
}

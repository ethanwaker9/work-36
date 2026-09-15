package org.oylama.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

public final class Codec {
    private Codec() {
    }

    public static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    public static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }

    public static String ringToB64(int[] a) {
        ByteBuffer bb = ByteBuffer.allocate(4 * a.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : a) {
            bb.putInt(v);
        }
        return b64(bb.array());
    }

    public static int[] ringFromB64(String s, int L, int n) {
        byte[] raw = unb64(s);
        if (raw.length != 4 * L * n) {
            throw new IllegalArgumentException("ring element has wrong length");
        }
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[L * n];
        for (int i = 0; i < out.length; i++) {
            long v = bb.getInt() & 0xFFFFFFFFL;
            if (v > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("coefficient out of range");
            }
            out[i] = (int) v;
        }
        return out;
    }

    public static String longsToB64(long[][] rows) {
        int n = rows[0].length;
        ByteBuffer bb = ByteBuffer.allocate(8 * rows.length * n).order(ByteOrder.LITTLE_ENDIAN);
        for (long[] row : rows) {
            for (long v : row) {
                bb.putLong(v);
            }
        }
        return b64(bb.array());
    }

    public static long[][] longsFromB64(String s, int rows, int n) {
        byte[] raw = unb64(s);
        if (raw.length != 8 * rows * n) {
            throw new IllegalArgumentException("response vector has wrong length");
        }
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        long[][] out = new long[rows][n];
        for (int r = 0; r < rows; r++) {
            for (int k = 0; k < n; k++) {
                out[r][k] = bb.getLong();
            }
        }
        return out;
    }

    public static int[] bitsFromBytes(byte[] raw, int lam) {
        int[] bits = new int[lam];
        for (int i = 0; i < lam; i++) {
            bits[i] = (raw[i / 8] >> (i % 8)) & 1;
        }
        return bits;
    }

    public static byte[] bytesFromBits(int[] bits) {
        byte[] out = new byte[(bits.length + 7) / 8];
        for (int i = 0; i < bits.length; i++) {
            if (bits[i] != 0) {
                out[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        return out;
    }

    public static String credentialText(int[] bits) {
        String h = Hash.hex(bytesFromBits(bits)).toUpperCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length(); i += 4) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(h, i, Math.min(h.length(), i + 4));
        }
        return sb.toString();
    }

    public static int[] credentialBits(String text, int lam) {
        String h = text == null ? "" : text.replaceAll("[^0-9a-fA-F]", "");
        if (h.length() != (lam + 7) / 8 * 2) {
            throw new IllegalArgumentException("A credential has " + ((lam + 7) / 8 * 2) + " hexadecimal digits");
        }
        return bitsFromBytes(Hash.unhex(h), lam);
    }

    public static String randomCredentialText() {
        return credentialText(bitsFromBytes(Oylama.random(16), 128));
    }
}

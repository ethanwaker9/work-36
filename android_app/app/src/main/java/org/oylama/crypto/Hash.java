package org.oylama.crypto;

import java.nio.charset.StandardCharsets;

public final class Hash {
    private Hash() {
    }

    public static final class Ser {
        final int[][] arrays;

        Ser(int[][] arrays) {
            this.arrays = arrays;
        }

        long length() {
            long n = 0;
            for (int[] a : arrays) {
                n += 8L * a.length;
            }
            return n;
        }
    }

    public static Ser ser(int[]... arrays) {
        return new Ser(arrays);
    }

    public static Ser serRows(int[][] rows, int[]... more) {
        int[][] all = new int[rows.length + more.length][];
        System.arraycopy(rows, 0, all, 0, rows.length);
        System.arraycopy(more, 0, all, rows.length, more.length);
        return new Ser(all);
    }

    public static byte[] bytes(Object c) {
        if (c instanceof byte[]) {
            return (byte[]) c;
        }
        if (c instanceof String) {
            return ((String) c).getBytes(StandardCharsets.US_ASCII);
        }
        throw new IllegalArgumentException("chunk");
    }

    public static final class Stream {
        private final Shake256 h;

        Stream(Shake256 h) {
            this.h = h;
        }

        public Stream add(Object c) {
            if (c instanceof Integer || c instanceof Long) {
                h.updateInt32(8);
                h.updateLong(((Number) c).longValue());
            } else if (c instanceof Ser) {
                Ser s = (Ser) c;
                h.updateInt32((int) s.length());
                for (int[] a : s.arrays) {
                    h.updateInt64(a);
                }
            } else {
                byte[] b = bytes(c);
                h.updateInt32(b.length);
                h.update(b);
            }
            return this;
        }

        public Stream copy() {
            return new Stream(h.copy());
        }

        public byte[] digest(int n) {
            return h.digest(n);
        }
    }

    public static Stream stream(Object... chunks) {
        Stream s = new Stream(new Shake256());
        for (Object c : chunks) {
            s.add(c);
        }
        return s;
    }

    public static byte[] shake(int outlen, Object... chunks) {
        return stream(chunks).digest(outlen);
    }

    public static byte[] h(Object... chunks) {
        return shake(32, chunks);
    }

    public static byte[] concat(byte[] a, String b) {
        byte[] bb = b.getBytes(StandardCharsets.US_ASCII);
        byte[] r = new byte[a.length + bb.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(bb, 0, r, a.length, bb.length);
        return r;
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        String t = s.replaceAll("[^0-9a-fA-F]", "");
        byte[] r = new byte[t.length() / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
        }
        return r;
    }
}

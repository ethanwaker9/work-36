package org.oylama.node;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class Bin {
    private Bin() {
    }

    static void writeArrays(File f, int[]... arrays) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            out.writeInt(arrays.length);
            for (int[] a : arrays) {
                out.writeInt(a.length);
                for (int v : a) {
                    out.writeInt(v);
                }
            }
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("rename failed");
        }
    }

    static int[][] readArrays(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            int n = in.readInt();
            int[][] out = new int[n][];
            for (int i = 0; i < n; i++) {
                int len = in.readInt();
                int[] a = new int[len];
                for (int k = 0; k < len; k++) {
                    a[k] = in.readInt();
                }
                out[i] = a;
            }
            return out;
        }
    }

    static void writeMap(File f, Map<String, int[]> map) throws IOException {
        f.getParentFile().mkdirs();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16))) {
            out.writeInt(map.size());
            for (Map.Entry<String, int[]> e : map.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue().length);
                for (int v : e.getValue()) {
                    out.writeInt(v);
                }
            }
        }
    }

    static Map<String, int[]> readMap(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            int n = in.readInt();
            Map<String, int[]> out = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                String k = in.readUTF();
                int len = in.readInt();
                int[] a = new int[len];
                for (int j = 0; j < len; j++) {
                    a[j] = in.readInt();
                }
                out.put(k, a);
            }
            return out;
        }
    }

    static void writeBytes(File f, byte[] data) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("rename failed");
        }
    }

    static byte[] readBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                bo.write(buf, 0, r);
            }
            return bo.toByteArray();
        }
    }

    static void writeText(File f, String s) throws IOException {
        writeBytes(f, s.getBytes(StandardCharsets.UTF_8));
    }

    static String readText(File f) throws IOException {
        return new String(readBytes(f), StandardCharsets.UTF_8);
    }

    static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteTree(k);
                }
            }
        }
        f.delete();
    }
}

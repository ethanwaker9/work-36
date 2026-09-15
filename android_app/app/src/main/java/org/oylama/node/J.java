package org.oylama.node;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class J {
    private J() {
    }

    public static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                Object v = kv[i + 1];
                o.put((String) kv[i], v == null ? JSONObject.NULL : v);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v == null ? JSONObject.NULL : v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isNull(JSONObject o, String k) {
        return o == null || !o.has(k) || o.isNull(k);
    }

    public static String str(JSONObject o, String k) {
        if (isNull(o, k)) {
            return "";
        }
        return String.valueOf(o.opt(k));
    }

    public static String strOrNull(JSONObject o, String k) {
        return isNull(o, k) ? null : String.valueOf(o.opt(k));
    }

    public static int num(JSONObject o, String k, int def) {
        if (isNull(o, k)) {
            return def;
        }
        Object v = o.opt(k);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static double dbl(JSONObject o, String k, double def) {
        if (isNull(o, k)) {
            return def;
        }
        Object v = o.opt(k);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean bool(JSONObject o, String k, boolean def) {
        if (isNull(o, k)) {
            return def;
        }
        Object v = o.opt(k);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    public static JSONObject o(JSONObject o, String k) {
        if (isNull(o, k)) {
            return null;
        }
        return o.optJSONObject(k);
    }

    public static JSONArray a(JSONObject o, String k) {
        if (isNull(o, k)) {
            return new JSONArray();
        }
        JSONArray r = o.optJSONArray(k);
        return r == null ? new JSONArray() : r;
    }

    public static List<JSONObject> list(JSONArray arr) {
        List<JSONObject> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x != null) {
                out.add(x);
            }
        }
        return out;
    }

    public static JSONArray arr(Iterable<?> items) {
        JSONArray a = new JSONArray();
        for (Object x : items) {
            a.put(x == null ? JSONObject.NULL : x);
        }
        return a;
    }

    public static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}

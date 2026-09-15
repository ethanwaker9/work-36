package org.oylama.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.oylama.node.ApiException;
import org.oylama.node.LocalNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public abstract class Backend {
    public abstract Object call(String method, String path, JSONObject body, String token) throws ApiException;

    public abstract String key();

    public abstract String label();

    public abstract boolean isLocal();

    public static final class Local extends Backend {
        final LocalNode node;

        Local(LocalNode node) {
            this.node = node;
        }

        @Override
        public Object call(String method, String path, JSONObject body, String token) throws ApiException {
            return node.call(method, path, body, token);
        }

        @Override
        public String key() {
            return "device";
        }

        @Override
        public String label() {
            return "This device";
        }

        @Override
        public boolean isLocal() {
            return true;
        }
    }

    public static final class Remote extends Backend {
        final String base;

        Remote(String url) {
            base = normalize(url);
        }

        static String normalize(String url) {
            String u = url == null ? "" : url.trim();
            if (u.isEmpty()) {
                u = "http://10.0.2.2:8000";
            }
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "http://" + u;
            }
            while (u.endsWith("/")) {
                u = u.substring(0, u.length() - 1);
            }
            return u;
        }

        @Override
        public Object call(String method, String path, JSONObject body, String token) throws ApiException {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + path).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(8000);
                c.setReadTimeout(120000);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept", "application/json");
                if (token != null) {
                    c.setRequestProperty("Authorization", "Bearer " + token);
                }
                if (body != null && !method.equals("GET")) {
                    byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(data.length);
                    try (OutputStream o = c.getOutputStream()) {
                        o.write(data);
                    }
                }
                int code = c.getResponseCode();
                InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                String text = "";
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[1 << 15];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        bo.write(buf, 0, r);
                    }
                    in.close();
                    text = bo.toString("UTF-8");
                }
                Object parsed = text.trim().isEmpty() ? new JSONObject() : new JSONTokener(text).nextValue();
                if (code >= 400) {
                    String msg = parsed instanceof JSONObject ? ((JSONObject) parsed).optString("error", "Request failed") : "Request failed";
                    throw new ApiException(code, msg);
                }
                if (parsed instanceof JSONObject || parsed instanceof JSONArray) {
                    return parsed;
                }
                return new JSONObject();
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException(0, "Cannot reach the Oylama server at " + base + ". " + e.getClass().getSimpleName());
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }

        @Override
        public String key() {
            return "server:" + base;
        }

        @Override
        public String label() {
            return base.replaceFirst("^https?://", "");
        }

        @Override
        public boolean isLocal() {
            return false;
        }
    }
}

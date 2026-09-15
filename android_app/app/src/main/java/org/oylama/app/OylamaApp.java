package org.oylama.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.oylama.crypto.BallotJson;
import org.oylama.crypto.Oylama;
import org.oylama.node.ApiException;
import org.oylama.node.J;
import org.oylama.node.LocalNode;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class OylamaApp extends Application {
    public static final String CHANNEL_TALLY = "tally";
    public static final String CHANNEL_RESULTS = "results";

    public final ExecutorService io = Executors.newCachedThreadPool();
    public final Handler main = new Handler(Looper.getMainLooper());
    private LocalNode node;
    private Backend backend;
    private SharedPreferences prefs;
    private final Map<String, Oylama> schemeCache = new HashMap<>();
    public volatile boolean seeding;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("oylama", MODE_PRIVATE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel tally = new NotificationChannel(CHANNEL_TALLY, "Encrypted tallies", NotificationManager.IMPORTANCE_LOW);
        tally.setDescription("Progress of tallies running on this device");
        nm.createNotificationChannel(tally);
        NotificationChannel results = new NotificationChannel(CHANNEL_RESULTS, "Published results", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(results);
        rebuildBackend();
    }

    public synchronized LocalNode node() {
        if (node == null) {
            node = new LocalNode(new File(getFilesDir(), "node"));
            node.setListener(new LocalNode.TallyListener() {
                @Override
                public void started(String eid, String title) {
                    main.post(() -> TallyService.start(OylamaApp.this, title));
                }

                @Override
                public void progress(String eid, int done, int total, String label) {
                    TallyService.update(OylamaApp.this, done, total, label);
                }

                @Override
                public void finished(String eid, boolean ok) {
                    main.post(() -> TallyService.finish(OylamaApp.this, ok));
                }
            });
        }
        return node;
    }

    public String mode() {
        return prefs.getString("mode", "device");
    }

    public String serverUrl() {
        return prefs.getString("server", "http://10.0.2.2:8000");
    }

    public synchronized void setMode(String mode, String url) {
        prefs.edit().putString("mode", mode).putString("server", Backend.Remote.normalize(url)).apply();
        rebuildBackend();
    }

    private synchronized void rebuildBackend() {
        if (mode().equals("server")) {
            backend = new Backend.Remote(serverUrl());
        } else {
            backend = new Backend.Local(node());
        }
    }

    public synchronized Backend backend() {
        return backend;
    }

    public JSONObject session() {
        String s = prefs.getString("session:" + backend().key(), null);
        if (s == null) {
            return null;
        }
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    public void saveSession(JSONObject s) {
        prefs.edit().putString("session:" + backend().key(), s.toString()).apply();
    }

    public void clearSession() {
        prefs.edit().remove("session:" + backend().key()).apply();
    }

    public JSONObject user() {
        JSONObject s = session();
        return s == null ? null : J.o(s, "user");
    }

    public String role() {
        JSONObject u = user();
        return u == null ? "voter" : J.str(u, "role");
    }

    public String email() {
        JSONObject u = user();
        return u == null ? "" : J.str(u, "email");
    }

    public String token() {
        JSONObject s = session();
        return s == null ? null : J.strOrNull(s, "token");
    }

    public Object call(String method, String path, JSONObject body) throws ApiException {
        return backend().call(method, path, body, token());
    }

    public JSONObject obj(String method, String path, JSONObject body) throws ApiException {
        Object r = call(method, path, body);
        return r instanceof JSONObject ? (JSONObject) r : new JSONObject();
    }

    public JSONArray arr(String method, String path, JSONObject body) throws ApiException {
        Object r = call(method, path, body);
        return r instanceof JSONArray ? (JSONArray) r : new JSONArray();
    }

    public <T> void run(Callable<T> work, Consumer<T> ok, Consumer<Exception> err) {
        io.submit(() -> {
            try {
                T v = work.call();
                main.post(() -> {
                    if (ok != null) {
                        ok.accept(v);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (err != null) {
                        err.accept(e);
                    }
                });
            }
        });
    }

    String vaultKey(String kind, String email, String eid) {
        return kind + ":" + backend().key() + ":" + email + (eid == null ? "" : ":" + eid);
    }

    public String credential(String eid) {
        return prefs.getString(vaultKey("cred", email(), eid), null);
    }

    public void saveCredential(String eid, String text) {
        prefs.edit().putString(vaultKey("cred", email(), eid), text).apply();
    }

    public void forgetCredential(String eid) {
        prefs.edit().remove(vaultKey("cred", email(), eid)).apply();
    }

    public JSONArray receipts() {
        try {
            return new JSONArray(prefs.getString(vaultKey("receipts", email(), null), "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public boolean voted(String eid) {
        JSONArray r = receipts();
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.optJSONObject(i);
            if (o != null && eid.equals(o.optString("election"))) {
                return true;
            }
        }
        return false;
    }

    public void addReceipt(JSONObject r) {
        JSONArray old = receipts();
        JSONArray n = new JSONArray();
        n.put(r);
        for (int i = 0; i < old.length() && i < 99; i++) {
            n.put(old.opt(i));
        }
        prefs.edit().putString(vaultKey("receipts", email(), null), n.toString()).apply();
    }

    public void clearReceipts() {
        prefs.edit().remove(vaultKey("receipts", email(), null)).apply();
    }

    public synchronized Oylama schemeFor(JSONObject pk) throws JSONException {
        String k = backend().key() + ":" + pk.getString("uid") + ":" + pk.optString("fingerprint");
        Oylama V = schemeCache.get(k);
        if (V == null) {
            V = BallotJson.schemeFromPublicKey(pk);
            schemeCache.put(k, V);
        }
        return V;
    }

    public void ensureSeeded(Runnable done) {
        if (!backend().isLocal()) {
            if (done != null) {
                done.run();
            }
            return;
        }
        run(() -> {
            LocalNode n = node();
            if (n.isEmpty()) {
                seeding = true;
                try {
                    n.seed();
                } finally {
                    seeding = false;
                }
            }
            return true;
        }, v -> {
            if (done != null) {
                done.run();
            }
        }, e -> {
            seeding = false;
            if (done != null) {
                done.run();
            }
        });
    }
}

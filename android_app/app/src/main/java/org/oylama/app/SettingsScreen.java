package org.oylama.app;

import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.oylama.node.J;

public class SettingsScreen extends Screen {
    String mode;
    String url;
    LinearLayout testResult;

    @Override
    public String title() {
        return "Election server";
    }

    @Override
    public boolean chrome() {
        return app.session() != null;
    }

    @Override
    protected void load() {
        if (mode == null) {
            mode = app.mode();
            url = app.serverUrl();
        }
        LinearLayout body = page();
        if (app.session() == null) {
            LinearLayout back = Ui.row(act);
            back.addView(Ui.smallButton(act, "Back to sign in", Ui.GHOST, R.drawable.ic_left, v -> act.showLogin()));
            body.addView(back);
            Ui.space(body, 8);
        }
        body.addView(Ui.h1(act, "Where does the election run?"));
        Ui.space(body, 6);
        body.addView(Ui.muted(act, "Oylama can run a complete election node on this phone, or connect to the Oylama web server so that phones and browsers take part in the same elections."));
        Ui.space(body, 16);
        body.addView(option("device", R.drawable.ic_zap, "This device", "A full Oylama node runs inside the app: key ceremony, registrar, bulletin board, encrypted cleansing, mixing and threshold decryption. Works offline."));
        Ui.space(body, 10);
        body.addView(option("server", R.drawable.ic_server, "Oylama server", "Connect to the web app started with python3 web_app/run.py. Ballots are still encrypted and proven on this phone before they are posted."));
        if (mode.equals("server")) {
            Ui.space(body, 12);
            LinearLayout c = Ui.card(act);
            EditText in = Ui.input(act, "http://10.0.2.2:8000", true);
            in.setText(url);
            in.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int d) {
                }

                public void onTextChanged(CharSequence s, int a, int b, int d) {
                    url = s.toString();
                }

                public void afterTextChanged(android.text.Editable s) {
                }
            });
            c.addView(Ui.field(act, "Server address", in));
            Ui.space(c, 6);
            c.addView(Ui.small(act, "Android emulator: http://10.0.2.2:8000 · phone on the same Wi-Fi: the Network address printed by run.py", Ui.MUTED));
            Ui.space(c, 10);
            TextView test = Ui.button(act, "Test connection", Ui.OUTLINE, R.drawable.ic_refresh, null);
            testResult = Ui.col(act);
            test.setOnClickListener(v -> {
                Busy.on(test);
                Backend.Remote r = new Backend.Remote(url);
                app.run(() -> r.call("GET", "/api/system", null, null), res -> {
                    Busy.off(test);
                    testResult.removeAllViews();
                    Ui.space(testResult, 10);
                    JSONObject s = (JSONObject) res;
                    testResult.addView(Ui.notice(act, R.drawable.ic_check, "Connected to " + J.str(s, "name") + " at " + r.label() + " · N = " + J.num(s, "ring_degree", 0)
                        + " · " + J.str(s, "backend"), Ui.SUCCESS, Ui.SUCCESS50));
                }, e -> {
                    Busy.off(test);
                    testResult.removeAllViews();
                    Ui.space(testResult, 10);
                    testResult.addView(Ui.notice(act, R.drawable.ic_alert, message(e), Ui.DANGER, Ui.DANGER50));
                });
            });
            c.addView(test, Ui.match());
            c.addView(testResult);
            body.addView(c);
        }
        Ui.space(body, 16);
        boolean changed = !mode.equals(app.mode()) || (mode.equals("server") && !Backend.Remote.normalize(url).equals(app.serverUrl()));
        TextView save = Ui.button(act, changed ? "Save and sign in again" : "Saved", Ui.PRIMARY_BTN, R.drawable.ic_check, v -> {
            app.setMode(mode, url);
            Ui.toast(act, mode.equals("server") ? "Using the Oylama server at " + app.backend().label() : "Using the on-device node");
            act.boot();
        });
        Ui.enable(save, changed);
        body.addView(save, Ui.match());
        Ui.space(body, 20);
        LinearLayout node = Ui.card(act);
        node.addView(Ui.cardTitle(act, R.drawable.ic_layers, "On-device node", null));
        Ui.space(node, 8);
        node.addView(Ui.kv(act, "Encryption", "Packed BGV, N = 8192, 5 × 24-bit NTT primes"));
        node.addView(Ui.kv(act, "Plaintext", "p = 65537, 8192 slots"));
        node.addView(Ui.kv(act, "Credentials", "128 bits"));
        node.addView(Ui.kv(act, "Registrar", "ML-DSA-65, ML-KEM-768, AES-256-GCM"));
        node.addView(Ui.kv(act, "Trustees", "2 of 3 replicated sharing"));
        node.addView(Ui.kv(act, "Cores used", String.valueOf(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())))));
        Ui.space(node, 10);
        TextView reset = Ui.button(act, "Reset on-device data", Ui.DANGER_BTN, R.drawable.ic_trash, v -> new AlertDialog.Builder(act)
            .setTitle("Reset the on-device node?")
            .setMessage("All elections, keys, ballots and results stored on this phone are deleted and fresh demo elections are prepared.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset", (d, w) -> {
                if (app.node().tallyRunning()) {
                    Ui.toast(act, "Wait until the running tally has finished");
                    return;
                }
                app.run(() -> {
                    app.node().reset();
                    return true;
                }, r -> {
                    app.setMode("device", app.serverUrl());
                    app.clearSession();
                    act.boot();
                }, e -> Ui.toast(act, message(e)));
            }).show());
        node.addView(reset, Ui.match());
        body.addView(node);
        show(body);
    }

    LinearLayout option(String key, int icon, String title, String text) {
        boolean on = mode.equals(key);
        LinearLayout row = Ui.row(act);
        row.setGravity(android.view.Gravity.TOP);
        row.setBackground(Ui.ripple(act, on ? Ui.PRIMARY50 : Ui.SURFACE, 16, on ? Ui.PRIMARY : Ui.BORDER, on ? 2 : 1));
        row.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14));
        row.addView(Ui.iconTile(act, icon, on ? Ui.PRIMARY : Ui.MUTED, on ? Ui.SURFACE : Ui.SURFACE2, 42));
        row.addView(Ui.gap(act, 12));
        LinearLayout col = Ui.col(act);
        col.addView(Ui.text(act, title, 15.5f, Ui.TEXT, true));
        Ui.space(col, 2);
        col.addView(Ui.small(act, text, Ui.MUTED));
        row.addView(col, Ui.weight(1));
        row.setOnClickListener(v -> {
            mode = key;
            load();
        });
        return row;
    }
}

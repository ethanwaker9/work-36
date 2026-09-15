package org.oylama.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.oylama.crypto.Ballot;
import org.oylama.crypto.BallotJson;
import org.oylama.crypto.Codec;
import org.oylama.crypto.Hash;
import org.oylama.crypto.Oylama;
import org.oylama.node.J;

import java.util.LinkedHashMap;
import java.util.Map;

public class BoothScreen extends Screen {
    final String eid;
    JSONObject e;
    int step;
    int choice;
    boolean useStored;
    String other = "";
    JSONObject receipt;
    final Map<String, TextView[]> progressRows = new LinkedHashMap<>();
    final Map<String, Long> stamps = new LinkedHashMap<>();
    String current;
    long t0;

    static final String[][] STEPS = {
        {"key", "Download the election public key"},
        {"encode", "Encode your choice and credential into plaintext slots"},
        {"encrypt-vote", "Encrypt the vote with packed lattice encryption"},
        {"encrypt-credential", "Encrypt the credential"},
        {"prove-vote", "Prove knowledge of the vote encryption"},
        {"prove-credential", "Prove knowledge of the credential encryption"},
        {"post", "Post anonymously to the bulletin board"},
        {"verify", "Bulletin board verifies both proofs"}
    };

    public BoothScreen(String eid, String presetCredential) {
        this.eid = eid;
        if (presetCredential != null) {
            other = presetCredential;
        }
    }

    @Override
    public String title() {
        return "Voting booth";
    }

    @Override
    public String subtitle() {
        return e == null ? null : J.str(e, "title");
    }

    @Override
    protected void load() {
        if (e != null) {
            draw();
            return;
        }
        fetch(() -> app.obj("GET", "/api/elections/" + eid, null), res -> {
            e = (JSONObject) res;
            useStored = other.isEmpty() && app.credential(eid) != null;
            act.chrome(this);
            draw();
        });
    }

    void draw() {
        LinearLayout body = page();
        if (!J.str(e, "status").equals("voting")) {
            String st = J.str(e, "status");
            body.addView(Widgets.empty(act, R.drawable.ic_clock, "The booth is closed", st.equals("registration") ? "Voting has not opened yet. Get your credential now so you are ready."
                : st.equals("ceremony") ? "The trustees are still generating the election key." : "Voting has ended for this election."));
            if (st.equals("registration")) {
                Ui.space(body, 10);
                body.addView(Ui.button(act, "Get my credential", Ui.PRIMARY_BTN, R.drawable.ic_key, v -> act.replace(new CredentialScreen(eid))), Ui.match());
            }
            show(body);
            return;
        }
        body.addView(stepper());
        Ui.space(body, 14);
        switch (step) {
            case 0:
                chooseStep(body);
                break;
            case 1:
                credentialStep(body);
                break;
            case 2:
                reviewStep(body);
                break;
            default:
                castStep(body);
                break;
        }
        show(body);
    }

    View stepper() {
        String[] labels = {"Choose", "Credential", "Review", "Cast"};
        LinearLayout r = Ui.row(act);
        for (int i = 0; i < labels.length; i++) {
            boolean on = i == step, done = i < step;
            LinearLayout cell = Ui.col(act);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setBackground(Ui.round(act, on ? Ui.PRIMARY50 : Ui.SURFACE, 12, on ? Ui.PRIMARY : Ui.BORDER, 1));
            cell.setPadding(Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 7));
            TextView n = Ui.text(act, done ? "✓" : String.valueOf(i + 1), 11, done || on ? Color.WHITE : Ui.MUTED, true);
            n.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(done ? Ui.SUCCESS : on ? Ui.PRIMARY : Ui.SURFACE3);
            n.setBackground(g);
            cell.addView(n, new LinearLayout.LayoutParams(Ui.dp(act, 22), Ui.dp(act, 22)));
            Ui.space(cell, 4);
            TextView lb = Ui.text(act, labels[i], 12, on ? Ui.PRIMARY600 : done ? Ui.TEXT2 : Ui.MUTED, true);
            lb.setSingleLine(true);
            lb.setEllipsize(android.text.TextUtils.TruncateAt.END);
            lb.setGravity(Gravity.CENTER);
            cell.addView(lb, Ui.match());
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i > 0) {
                lp.leftMargin = Ui.dp(act, 6);
            }
            r.addView(cell, lp);
        }
        return r;
    }

    void chooseStep(LinearLayout body) {
        body.addView(Widgets.section(act, "Choose one option", J.a(e, "candidates").length() + " options on the ballot"));
        Ui.space(body, 10);
        for (JSONObject c : J.list(J.a(e, "candidates"))) {
            int idx = J.num(c, "idx", 0);
            boolean on = idx == choice;
            int color = Ui.parseColor(J.str(c, "color"), Ui.PRIMARY);
            LinearLayout row = Ui.row(act);
            row.setBackground(Ui.ripple(act, on ? Ui.PRIMARY50 : Ui.SURFACE, 14, on ? Ui.PRIMARY : Ui.BORDER, on ? 2 : 1));
            row.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14));
            View bar = new View(act);
            bar.setBackground(Ui.round(act, color, 3, 0, 0));
            row.addView(bar, new LinearLayout.LayoutParams(Ui.dp(act, 4), Ui.dp(act, 44)));
            row.addView(Ui.gap(act, 10));
            row.addView(Ui.avatar(act, J.str(c, "name"), color, 44));
            row.addView(Ui.gap(act, 12));
            LinearLayout col = Ui.col(act);
            col.addView(Ui.text(act, J.str(c, "name"), 16, Ui.TEXT, true));
            col.addView(Ui.small(act, J.str(c, "party").isEmpty() ? "Option " + idx : J.str(c, "party"), Ui.MUTED));
            if (!J.str(c, "bio").isEmpty()) {
                TextView b = Ui.small(act, J.str(c, "bio"), Ui.MUTED);
                b.setPadding(0, Ui.dp(act, 3), 0, 0);
                col.addView(b);
            }
            row.addView(col, Ui.weight(1));
            TextView radio = Ui.text(act, on ? "✓" : "", 13, Color.WHITE, true);
            radio.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(on ? Ui.PRIMARY : Ui.SURFACE);
            g.setStroke(Ui.dp(act, 2), on ? Ui.PRIMARY : Ui.BORDER_STRONG);
            radio.setBackground(g);
            row.addView(radio, new LinearLayout.LayoutParams(Ui.dp(act, 26), Ui.dp(act, 26)));
            row.setOnClickListener(v -> {
                choice = idx;
                draw();
            });
            body.addView(row);
            Ui.space(body, 10);
        }
        Ui.space(body, 6);
        TextView next = Ui.button(act, "Continue", Ui.PRIMARY_BTN, R.drawable.ic_right, v -> {
            step = 1;
            top();
            draw();
        });
        Ui.enable(next, choice > 0);
        body.addView(next, Ui.match());
        Ui.space(body, 14);
        body.addView(explainer());
    }

    void credentialStep(LinearLayout body) {
        String stored = app.credential(eid);
        body.addView(Widgets.section(act, "Which credential signs this ballot?", null));
        Ui.space(body, 10);
        if (stored != null) {
            body.addView(credOption(true, R.drawable.ic_finger, "My credential on this device",
                stored.substring(0, 4) + "-••••-••••-••••-••••-••••-••••-" + stored.substring(stored.length() - 4)));
            Ui.space(body, 10);
        }
        body.addView(credOption(false, R.drawable.ic_mask, "Another credential", "Paste a credential, for example one you were handed or a fake one you generated"));
        if (!useStored || stored == null) {
            Ui.space(body, 10);
            EditText in = Ui.input(act, "XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX", true);
            in.setText(other);
            in.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                public void onTextChanged(CharSequence s, int a, int b, int c) {
                    other = s.toString();
                }

                public void afterTextChanged(android.text.Editable s) {
                }
            });
            body.addView(in);
            Ui.space(body, 4);
            body.addView(Ui.small(act, "32 hexadecimal digits. The board cannot tell whether a credential is real.", Ui.MUTED));
        }
        if (stored == null) {
            Ui.space(body, 10);
            body.addView(Ui.notice(act, R.drawable.ic_info, "No credential is stored on this device for this election. Get or open your credential first, or paste one here.", Ui.WARNING, Ui.WARNING50));
        }
        Ui.space(body, 10);
        body.addView(Ui.notice(act, R.drawable.ic_shield, "Your credential never leaves this phone in the clear. It is encrypted together with your vote, and only the encrypted cleansing learns whether it matches a roster entry.", Ui.PRIMARY, Ui.PRIMARY50));
        Ui.space(body, 14);
        LinearLayout nav = Ui.row(act);
        nav.addView(Ui.button(act, "Back", Ui.OUTLINE, R.drawable.ic_left, v -> {
            step = 0;
            top();
            draw();
        }), Ui.weight(1));
        nav.addView(Ui.gap(act, 10));
        nav.addView(Ui.button(act, "Continue", Ui.PRIMARY_BTN, R.drawable.ic_right, v -> {
            try {
                Codec.credentialBits(credText(), 128);
            } catch (IllegalArgumentException ex) {
                Ui.toast(act, useStored ? "Get your credential first" : ex.getMessage());
                return;
            }
            step = 2;
            top();
            draw();
        }), Ui.weight(1));
        body.addView(nav, Ui.match());
    }

    View credOption(boolean stored, int icon, String title, String sub) {
        boolean on = useStored == stored;
        LinearLayout row = Ui.row(act);
        row.setBackground(Ui.ripple(act, on ? Ui.PRIMARY50 : Ui.SURFACE, 14, on ? Ui.PRIMARY : Ui.BORDER, on ? 2 : 1));
        row.setPadding(Ui.dp(act, 14), Ui.dp(act, 12), Ui.dp(act, 14), Ui.dp(act, 12));
        row.addView(Ui.iconTile(act, icon, stored ? Ui.PRIMARY : Ui.VIOLET, stored ? Ui.SURFACE : Ui.VIOLET50, 40));
        row.addView(Ui.gap(act, 12));
        LinearLayout col = Ui.col(act);
        col.addView(Ui.text(act, title, 15, Ui.TEXT, true));
        TextView s = stored ? Ui.mono(act, sub, 12, Ui.MUTED) : Ui.small(act, sub, Ui.MUTED);
        col.addView(s);
        row.addView(col, Ui.weight(1));
        row.setOnClickListener(v -> {
            useStored = stored;
            draw();
        });
        return row;
    }

    String credText() {
        String stored = app.credential(eid);
        return useStored && stored != null ? stored : other.trim();
    }

    JSONObject chosen() {
        for (JSONObject c : J.list(J.a(e, "candidates"))) {
            if (J.num(c, "idx", 0) == choice) {
                return c;
            }
        }
        return new JSONObject();
    }

    void reviewStep(LinearLayout body) {
        body.addView(Widgets.section(act, "Review your ballot", null));
        Ui.space(body, 10);
        body.addView(Widgets.candidate(act, chosen(), null));
        Ui.space(body, 12);
        LinearLayout kv = Ui.card(act);
        kv.addView(Ui.kv(act, "Election", J.str(e, "title")));
        kv.addView(Ui.kv(act, "Credential", useStored && app.credential(eid) != null ? "My credential on this device" : credText()));
        kv.addView(Ui.kv(act, "Encryption", "Packed BGV, N = 8192, on this phone"));
        kv.addView(Ui.kv(act, "Channel", "Anonymous: the board does not record who posts"));
        body.addView(kv);
        Ui.space(body, 12);
        body.addView(Ui.notice(act, R.drawable.ic_refresh, "You can vote again at any time while voting is open. Only the last ballot with your credential is counted, and nobody can see that a ballot was replaced.", Ui.PRIMARY, Ui.PRIMARY50));
        Ui.space(body, 14);
        LinearLayout nav = Ui.row(act);
        nav.addView(Ui.button(act, "Back", Ui.OUTLINE, R.drawable.ic_left, v -> {
            step = 1;
            top();
            draw();
        }), Ui.weight(1));
        nav.addView(Ui.gap(act, 10));
        nav.addView(Ui.button(act, "Encrypt and cast", Ui.PRIMARY_BTN, R.drawable.ic_lock, v -> {
            step = 3;
            top();
            receipt = null;
            draw();
            cast();
        }), Ui.weight(1));
        body.addView(nav, Ui.match());
    }

    TextView elapsed;
    LinearLayout receiptBox;

    void castStep(LinearLayout body) {
        LinearLayout head = Ui.row(act);
        head.addView(Ui.h2(act, receipt == null ? "Encrypting and casting" : "Ballot cast"), Ui.weight(1));
        elapsed = Ui.small(act, "", Ui.MUTED);
        head.addView(elapsed);
        body.addView(head);
        Ui.space(body, 10);
        progressRows.clear();
        for (String[] s : STEPS) {
            LinearLayout row = Ui.row(act);
            row.setBackground(Ui.round(act, Ui.SURFACE, 12, Ui.BORDER, 1));
            row.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
            TextView ic = Ui.text(act, "", 13, Ui.FAINT, true);
            ic.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Ui.SURFACE3);
            ic.setBackground(g);
            row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(act, 26), Ui.dp(act, 26)));
            row.addView(Ui.gap(act, 10));
            LinearLayout col = Ui.col(act);
            TextView title = Ui.text(act, s[1], 13.5f, Ui.FAINT, true);
            col.addView(title);
            TextView detail = Ui.small(act, "", Ui.MUTED);
            detail.setVisibility(View.GONE);
            col.addView(detail);
            row.addView(col, Ui.weight(1));
            TextView time = Ui.small(act, "", Ui.FAINT);
            row.addView(time);
            body.addView(row);
            Ui.space(body, 8);
            progressRows.put(s[0], new TextView[]{ic, title, detail, time});
            row.setTag(s[0]);
        }
        receiptBox = Ui.col(act);
        body.addView(receiptBox);
    }

    void mark(String key, String state, String detail, String time) {
        TextView[] r = progressRows.get(key);
        if (r == null) {
            return;
        }
        View row = (View) r[0].getParent();
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        switch (state) {
            case "done":
                g.setColor(Ui.SUCCESS50);
                r[0].setText("✓");
                r[0].setTextColor(Ui.SUCCESS);
                r[1].setTextColor(Ui.TEXT2);
                row.setBackground(Ui.round(act, Ui.SURFACE, 12, Ui.BORDER, 1));
                break;
            case "active":
                g.setColor(Ui.PRIMARY);
                r[0].setText("•");
                r[0].setTextColor(Color.WHITE);
                r[1].setTextColor(Ui.TEXT);
                row.setBackground(Ui.round(act, Ui.PRIMARY50, 12, Ui.PRIMARY100, 1));
                break;
            case "failed":
                g.setColor(Ui.DANGER50);
                r[0].setText("✕");
                r[0].setTextColor(Ui.DANGER);
                row.setBackground(Ui.round(act, Ui.DANGER50, 12, 0xFFF6CACA, 1));
                break;
            default:
                g.setColor(Ui.SURFACE3);
                break;
        }
        r[0].setBackground(g);
        if (detail != null) {
            r[2].setText(detail);
            r[2].setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (time != null) {
            r[3].setText(time);
        }
    }

    void begin(String key, String detail) {
        begin(key, detail, System.nanoTime());
    }

    void begin(String key, String detail, long now) {
        if (current != null && !current.equals(key) && stamps.containsKey(current)) {
            mark(current, "done", null, Math.round((now - stamps.get(current)) / 1e6) + " ms");
        }
        current = key;
        stamps.put(key, now);
        mark(key, "active", detail, null);
    }

    void post(Runnable r) {
        app.main.post(() -> {
            if (isShown() && step == 3) {
                r.run();
            }
        });
    }

    void cast() {
        t0 = System.nanoTime();
        current = null;
        stamps.clear();
        String cred = credText();
        int nu = choice;
        begin("key", "N = 8192, five 24-bit primes");
        Runnable clock = new Runnable() {
            @Override
            public void run() {
                if (elapsed != null && receipt == null && step == 3) {
                    elapsed.setText(String.format(java.util.Locale.US, "%.1f s", (System.nanoTime() - t0) / 1e9));
                    app.main.postDelayed(this, 100);
                }
            }
        };
        app.main.post(clock);
        app.run(() -> {
            JSONObject pk = app.obj("GET", "/api/elections/" + eid + "/public-key", null);
            post(() -> mark("key", "active", "fingerprint " + J.str(pk, "fingerprint").substring(0, 16) + " · building NTT tables", null));
            Oylama V = app.schemeFor(pk);
            int[] bits = Codec.credentialBits(cred, V.lam);
            long te = System.nanoTime();
            post(() -> begin("encode", "vote bits at slot " + ((V.keybits) * V.mmax) + " onward; credential bits from slot " + (V.mbits * V.mmax), te));
            Ballot b = V.vote(bits, nu, Oylama.random(32), (s, d) -> {
                long ts = System.nanoTime();
                post(() -> begin(s, "", ts));
            });
            JSONObject ballot = BallotJson.toJson(b);
            String localTracker = Hash.hex(V.tracker(b));
            int kb = ballot.toString().length() / 1024;
            long tp = System.nanoTime();
            post(() -> begin("post", String.format(java.util.Locale.US, "%.2f MB of ciphertexts and proofs", kb / 1024.0), tp));
            JSONObject r = app.obj("POST", "/api/elections/" + eid + "/ballots", J.obj("ballot", ballot));
            J.put(r, "local_tracker", localTracker);
            return r;
        }, r -> {
            if (step != 3) {
                return;
            }
            begin("verify", "tracker " + J.str(r, "tracker").substring(0, 16) + " at position " + (J.num(r, "position", 0) + 1));
            mark("verify", "done", "both proofs verified by the board in " + J.dbl(r, "verify_ms", 0) + " ms · tracker matches: "
                + (J.str(r, "tracker").equals(J.str(r, "local_tracker")) ? "yes" : "no"), J.dbl(r, "verify_ms", 0) + " ms");
            receipt = J.obj("election", eid, "title", J.str(e, "title"), "tracker", J.str(r, "tracker"), "position", J.num(r, "position", 0),
                "posted", J.dbl(r, "posted", 0), "size_kb", J.dbl(r, "size_kb", 0));
            app.addReceipt(receipt);
            double total = (System.nanoTime() - t0) / 1e9;
            if (elapsed != null) {
                elapsed.setText(String.format(java.util.Locale.US, "%.1f s", total));
            }
            showReceipt(r, total);
            Ui.toast(act, "Ballot accepted by the bulletin board");
        }, err -> {
            if (current != null) {
                mark(current, "failed", message(err), null);
            }
            if (receiptBox != null) {
                receiptBox.removeAllViews();
                Ui.space(receiptBox, 10);
                receiptBox.addView(Ui.notice(act, R.drawable.ic_alert, "The ballot was not cast. " + message(err), Ui.DANGER, Ui.DANGER50));
                Ui.space(receiptBox, 10);
                receiptBox.addView(Ui.button(act, "Try again", Ui.OUTLINE, R.drawable.ic_refresh, v -> {
                    step = 2;
                    top();
                    draw();
                }), Ui.match());
            }
        });
    }

    void showReceipt(JSONObject r, double total) {
        if (receiptBox == null) {
            return;
        }
        receiptBox.removeAllViews();
        Ui.space(receiptBox, 10);
        LinearLayout c = Ui.card(act);
        c.setBackground(Ui.round(act, Ui.PRIMARY50, 18, Ui.PRIMARY100, 1));
        LinearLayout top = Ui.row(act);
        top.addView(Ui.iconTile(act, R.drawable.ic_check, Ui.SUCCESS, Ui.SUCCESS50, 44));
        top.addView(Ui.gap(act, 12));
        LinearLayout col = Ui.col(act);
        col.addView(Ui.h2(act, "Your ballot is on the board"));
        col.addView(Ui.small(act, String.format(java.util.Locale.US, "Cast in %.1f s · position %d · %.1f KB", total, J.num(r, "position", 0) + 1, J.dbl(r, "size_kb", 0)), Ui.MUTED));
        top.addView(col, Ui.weight(1));
        c.addView(top);
        Ui.space(c, 14);
        c.addView(Ui.small(act, "Ballot tracker", Ui.MUTED));
        String grouped = Ui.groupHex(J.str(r, "tracker"), 4);
        c.addView(Ui.mono(act, grouped.length() == 39 ? grouped.substring(0, 19) + "\n" + grouped.substring(20) : grouped, 18, Ui.TEXT));
        Ui.space(c, 10);
        c.addView(Ui.muted(act, "Save the tracker to find your ballot on the public bulletin board. It reveals nothing about your choice. Oylama does not store your choice anywhere, not even on this phone."));
        Ui.space(c, 12);
        String tracker = J.str(r, "tracker");
        c.addView(Ui.button(act, "Copy tracker", Ui.OUTLINE, R.drawable.ic_copy, v -> Ui.copy(act, "Tracker", tracker)), Ui.match());
        Ui.space(c, 8);
        c.addView(Ui.button(act, "See it on the board", Ui.OUTLINE, R.drawable.ic_search, v -> act.replace(new ElectionScreen(eid, "board"))), Ui.match());
        Ui.space(c, 8);
        c.addView(Ui.button(act, "Vote again", Ui.GHOST, R.drawable.ic_refresh, v -> {
            step = 0;
            top();
            choice = 0;
            receipt = null;
            draw();
        }), Ui.match());
        Ui.space(c, 6);
        c.addView(Ui.small(act, "Posted " + Ui.fmtDate(J.dbl(r, "posted", 0)), Ui.FAINT));
        receiptBox.addView(c);
    }

    View explainer() {
        LinearLayout c = Ui.card(act);
        c.addView(Ui.cardTitle(act, R.drawable.ic_shieldcheck, "What happens to your vote", null));
        Ui.space(c, 8);
        c.addView(Widgets.check(act, "done", "Encrypted here", "Your option becomes bits in plaintext slots and is encrypted on this phone.", null));
        c.addView(Widgets.check(act, "done", "Proven", "Both ciphertexts come with proofs that you know what you encrypted.", null));
        c.addView(Widgets.check(act, "done", "Cleansed", "At the tally only the last ballot of each registered credential survives.", null));
        c.addView(Widgets.check(act, "done", "Mixed and decrypted", "Two mix servers shuffle the survivors; two of three trustees decrypt.", null));
        Ui.space(c, 6);
        c.addView(Ui.notice(act, R.drawable.ic_mask, "Being watched? Cast the ballot the other person wants with a fake credential. It is accepted and silently discarded. Come back later with your real credential.", Ui.WARNING, 0xFFFFF7ED));
        return c;
    }
}

package org.oylama.app;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.oylama.node.J;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ElectionScreen extends Screen {
    final String eid;
    String tab;
    JSONObject e;
    Object extra;
    String lastSig;

    public ElectionScreen(String eid, String tab) {
        this.eid = eid;
        this.tab = tab == null ? "overview" : tab;
    }

    @Override
    public String title() {
        return e == null ? "Election" : J.str(e, "title");
    }

    @Override
    public String subtitle() {
        return e == null ? null : Ui.statusLabel(J.str(e, "status"));
    }

    String path(String rest) {
        return "/api/elections/" + eid + rest;
    }

    @Override
    protected void load() {
        final String t = tab;
        fetch(() -> {
            JSONObject view = app.obj("GET", path(""), null);
            Object more = null;
            switch (t) {
                case "board":
                    more = app.obj("GET", path("/board"), null);
                    break;
                case "roster":
                    more = app.obj("GET", path("/roster"), null);
                    break;
                case "voters":
                    more = app.arr("GET", path("/voters"), null);
                    break;
                case "activity":
                case "manage":
                    more = app.arr("GET", path("/events"), null);
                    break;
                default:
                    break;
            }
            return new Object[]{view, more};
        }, res -> {
            Object[] r = (Object[]) res;
            String sig = t + r[0].toString() + (r[1] == null ? "" : r[1].toString()) + app.credential(eid) + app.receipts().length();
            if (sig.equals(lastSig)) {
                return;
            }
            lastSig = sig;
            e = (JSONObject) r[0];
            extra = r[1];
            act.chrome(this);
            render();
        });
    }

    @Override
    public void onShow() {
        every(5000, () -> {
            if (!(act.getCurrentFocus() instanceof EditText)) {
                refresh();
            }
        });
    }

    void switchTab(String t) {
        if (!t.equals(tab)) {
            top();
        }
        tab = t;
        lastSig = null;
        refresh();
    }

    void render() {
        LinearLayout body = page();
        String status = J.str(e, "status");
        LinearLayout head = Ui.col(act);
        LinearLayout r1 = Ui.row(act);
        String org = J.str(e, "organization");
        r1.addView(Ui.eyebrow(act, org.isEmpty() ? "Oylama" : org), Ui.weight(1));
        r1.addView(Ui.statusChip(act, status));
        head.addView(r1);
        Ui.space(head, 6);
        head.addView(Ui.h1(act, J.str(e, "title")));
        String desc = J.str(e, "description");
        if (!desc.isEmpty()) {
            Ui.space(head, 6);
            head.addView(Ui.muted(act, desc));
        }
        body.addView(head);
        Ui.space(body, 14);
        List<View> actions = headerActions();
        for (View b : actions) {
            body.addView(b, Ui.match());
            Ui.space(body, 8);
        }
        if (status.equals("tallying") && J.o(e, "tally") != null) {
            JSONObject t = J.o(e, "tally");
            LinearLayout c = Ui.card(act);
            c.setBackground(Ui.ripple(act, Ui.VIOLET50, 16, 0xFFDCCBFB, 1));
            LinearLayout rr = Ui.row(act);
            rr.addView(Ui.iconTile(act, R.drawable.ic_activity, Ui.VIOLET, Ui.SURFACE, 36));
            rr.addView(Ui.gap(act, 10));
            LinearLayout col = Ui.col(act);
            col.addView(Ui.text(act, "Encrypted tally in progress", 14.5f, Ui.TEXT, true));
            col.addView(Ui.small(act, J.str(t, "label"), Ui.MUTED));
            rr.addView(col, Ui.weight(1));
            c.addView(rr);
            Ui.space(c, 10);
            int total = Math.max(1, J.num(t, "total", 1));
            c.addView(Ui.meter(act, new float[]{J.num(t, "done", 0) / (float) total}, new int[]{Ui.VIOLET}, 8));
            Ui.space(c, 6);
            c.addView(Ui.small(act, J.num(t, "done", 0) + " of " + J.num(t, "total", 0) + " openings · about " + Ui.duration(J.dbl(t, "eta", 0)) + " left", Ui.MUTED));
            c.setOnClickListener(v -> act.push(new TallyScreen(eid)));
            body.addView(c);
            Ui.space(body, 12);
        }
        LinearLayout tl = Ui.card(act);
        tl.setPadding(Ui.dp(act, 8), Ui.dp(act, 14), Ui.dp(act, 8), Ui.dp(act, 10));
        tl.addView(Widgets.timeline(act, e));
        body.addView(tl);
        Ui.space(body, 14);
        body.addView(tabs());
        Ui.space(body, 14);
        switch (tab) {
            case "board":
                board(body);
                break;
            case "roster":
                roster(body);
                break;
            case "results":
                results(body);
                break;
            case "trustees":
                trustees(body);
                break;
            case "manage":
                manage(body);
                break;
            case "voters":
                voters(body);
                break;
            case "audit":
                audit(body);
                break;
            case "activity":
                activity(body);
                break;
            default:
                overview(body);
                break;
        }
        show(body);
    }

    View tabs() {
        List<String[]> t = new ArrayList<>();
        t.add(new String[]{"overview", "Overview"});
        t.add(new String[]{"board", "Board " + J.num(e, "ballots", 0)});
        t.add(new String[]{"roster", "Roster " + J.num(e, "roster", 0)});
        t.add(new String[]{"results", "Results"});
        t.add(new String[]{"trustees", "Trustees"});
        if (app.role().equals("authority")) {
            t.add(new String[]{"manage", "Control center"});
        }
        if (app.role().equals("authority") || app.role().equals("registrar")) {
            t.add(new String[]{"voters", "Voters" + (J.num(e, "requested", 0) > 0 ? " " + J.num(e, "requested", 0) : "")});
        }
        t.add(new String[]{"audit", "Audit"});
        t.add(new String[]{"activity", "Activity"});
        LinearLayout row = Ui.row(act);
        View selected = null;
        for (String[] x : t) {
            boolean on = x[0].equals(tab);
            TextView chip = Ui.text(act, x[1], 13.5f, on ? Color.WHITE : Ui.TEXT2, on);
            chip.setBackground(Ui.ripple(act, on ? Ui.PRIMARY : Ui.SURFACE, 99, on ? Ui.PRIMARY : Ui.BORDER, 1));
            chip.setPadding(Ui.dp(act, 14), Ui.dp(act, 8), Ui.dp(act, 14), Ui.dp(act, 8));
            chip.setOnClickListener(v -> switchTab(x[0]));
            row.addView(chip);
            row.addView(Ui.gap(act, 8));
            if (on) {
                selected = chip;
            }
        }
        return Ui.hscroll(act, row, selected);
    }

    List<View> headerActions() {
        List<View> out = new ArrayList<>();
        String status = J.str(e, "status");
        String role = app.role();
        if (role.equals("voter")) {
            String key = Widgets.voterState(app, e, new String[1]);
            if (status.equals("voting") && key.equals("ready")) {
                out.add(Ui.button(act, app.voted(eid) ? "Vote again" : "Vote now", Ui.PRIMARY_BTN, R.drawable.ic_ballot, v -> act.push(new BoothScreen(eid, null))));
            }
            if (status.equals("registration") || status.equals("voting")) {
                out.add(Ui.button(act, "My credential", Ui.OUTLINE, R.drawable.ic_key, v -> act.push(new CredentialScreen(eid))));
            }
        }
        if (role.equals("trustee") && (status.equals("ceremony") || status.equals("closed")) && !tab.equals("trustees")) {
            out.add(Ui.button(act, status.equals("ceremony") ? "Key ceremony" : "Approve tally", Ui.PRIMARY_BTN, R.drawable.ic_key, v -> switchTab("trustees")));
        }
        if (role.equals("authority") && !tab.equals("manage")) {
            out.add(Ui.button(act, "Control center", Ui.PRIMARY_BTN, R.drawable.ic_settings, v -> switchTab("manage")));
        }
        if (role.equals("registrar") && (status.equals("registration") || status.equals("voting")) && !tab.equals("voters")) {
            out.add(Ui.button(act, "Voter desk", Ui.PRIMARY_BTN, R.drawable.ic_clipboard, v -> switchTab("voters")));
        }
        if (role.equals("auditor") && !tab.equals("audit")) {
            out.add(Ui.button(act, "Run the audit", Ui.PRIMARY_BTN, R.drawable.ic_scan, v -> switchTab("audit")));
        }
        if (status.equals("tallying")) {
            out.add(Ui.button(act, "Watch the tally", Ui.OUTLINE, R.drawable.ic_activity, v -> act.push(new TallyScreen(eid))));
        }
        if (status.equals("published") && !tab.equals("results")) {
            out.add(Ui.button(act, "See the verified result", Ui.OUTLINE, R.drawable.ic_chart, v -> switchTab("results")));
        }
        return out;
    }

    void overview(LinearLayout body) {
        String role = app.role();
        body.addView(rolePanel(role));
        Ui.space(body, 14);
        LinearLayout cands = Ui.card(act);
        cands.addView(Ui.cardTitle(act, R.drawable.ic_users, "Candidates and options", Ui.small(act, J.a(e, "candidates").length() + " on the ballot", Ui.MUTED)));
        Ui.space(cands, 10);
        for (JSONObject c : J.list(J.a(e, "candidates"))) {
            cands.addView(Widgets.candidate(act, c, null));
            Ui.space(cands, 8);
        }
        body.addView(cands);
        Ui.space(body, 14);
        LinearLayout facts = Ui.card(act);
        facts.addView(Ui.cardTitle(act, R.drawable.ic_info, "Election facts", null));
        Ui.space(facts, 8);
        facts.addView(Ui.kv(act, "Organization", J.str(e, "organization").isEmpty() ? "Not set" : J.str(e, "organization")));
        String opens = J.str(e, "opens_at"), closes = J.str(e, "closes_at");
        facts.addView(Ui.kv(act, "Voting window", (opens.isEmpty() ? "Opened by the authority" : Ui.fmtIso(opens)) + " → " + (closes.isEmpty() ? "closed by the authority" : Ui.fmtIso(closes))));
        facts.addView(Ui.kv(act, "Eligibility", J.str(e, "eligibility").equals("list") ? "Only voters on the registrar's list" : "Any signed-in voter can request a credential"));
        facts.addView(Ui.kv(act, "Credentials", J.bool(e, "auto_issue", true) ? "Issued automatically by the registrar service" : "Approved by the registrar"));
        facts.addView(Ui.kv(act, "Revoting", "Allowed. The last ballot with a valid credential counts."));
        facts.addView(Ui.kv(act, "Trustees", J.num(e, "threshold", 2) + " of " + J.num(e, "n_trustees", 3) + " must cooperate to decrypt"));
        Ui.space(facts, 8);
        facts.addView(Ui.small(act, "Board capacity", Ui.MUTED));
        Ui.space(facts, 6);
        facts.addView(Widgets.capacity(act, J.num(e, "capacity", 8), J.num(e, "roster", 0), J.num(e, "ballots", 0)));
        body.addView(facts);
        Ui.space(body, 14);
        JSONObject p = J.o(e, "params");
        LinearLayout crypto = Ui.card(act);
        crypto.addView(Ui.cardTitle(act, R.drawable.ic_shield, "Cryptography", Ui.chip(act, "post-quantum", Ui.PRIMARY50, Ui.PRIMARY600)));
        Ui.space(crypto, 8);
        crypto.addView(Ui.kv(act, "Encryption", "Packed BGV over Z_q[X]/(X^" + J.num(p, "ring_degree", 8192) + "+1)"));
        crypto.addView(Ui.kv(act, "Modulus", J.a(p, "primes").length() + " NTT primes, log q ≈ " + J.num(p, "log_q", 125) + " bits"));
        crypto.addView(Ui.kv(act, "Plaintext slots", "p = " + J.num(p, "plaintext_modulus", 65537) + ", " + J.num(p, "slots", 8192) + " slots"));
        crypto.addView(Ui.kv(act, "Credentials", J.num(p, "credential_bits", 128) + "-bit uniform strings; fake ones are identical in form"));
        crypto.addView(Ui.kv(act, "Ballot", "2 ciphertexts and 2 proofs of knowledge, about " + J.num(p, "ballot_kb", 688) + " KB"));
        crypto.addView(Ui.kv(act, "Proof challenges", "Weight κ = " + J.num(p, "challenge_weight", 23) + ", SHAKE256 Fiat–Shamir with aborts"));
        crypto.addView(Ui.kv(act, "Mix-net", J.num(p, "mix_servers", 2) + " verifiable lattice shuffles"));
        crypto.addView(Ui.kv(act, "Registrar", "ML-DSA-65 roster signature, ML-KEM-768 sealed delivery"));
        JSONObject key = J.o(e, "key");
        crypto.addView(Ui.kv(act, "Public key", key == null ? "after the key ceremony" : J.str(key, "fingerprint")));
        crypto.addView(Ui.kv(act, "Estimated tally", "about " + Ui.duration(J.dbl(p, "estimated_tally_seconds", 0)) + (app.backend().isLocal() ? " on this device" : " on the server")));
        body.addView(crypto);
    }

    View rolePanel(String role) {
        LinearLayout c = Ui.card(act);
        String status = J.str(e, "status");
        switch (role) {
            case "voter": {
                String[] label = new String[1];
                String key = Widgets.voterState(app, e, label);
                boolean regOpen = status.equals("registration") || status.equals("voting");
                int[] col = Widgets.chipColors(key);
                c.addView(Ui.cardTitle(act, R.drawable.ic_ballot, "Your participation", regOpen ? Ui.chip(act, label[0], col[0], col[1]) : null));
                Ui.space(c, 8);
                int mine = 0;
                JSONArray rec = app.receipts();
                for (int i = 0; i < rec.length(); i++) {
                    if (J.str(rec.optJSONObject(i), "election").equals(eid)) {
                        mine++;
                    }
                }
                c.addView(Widgets.check(act, key.equals("ready") ? "done" : regOpen ? "active" : "pending", "Credential",
                    key.equals("ready") ? "A credential is stored on this device" : regOpen ? "Ask the registrar for a sealed credential" : "Registration is not open", null));
                c.addView(Widgets.check(act, mine > 0 ? "done" : status.equals("voting") && key.equals("ready") ? "active" : "pending", "Encrypted ballot",
                    status.equals("voting") ? "Choose, encrypt on this phone, post anonymously" : status.equals("registration") ? "Voting has not opened yet" : "Voting is closed", null));
                c.addView(Widgets.check(act, mine > 0 && status.equals("published") ? "done" : "pending", "Verify",
                    mine > 0 ? mine + " tracker" + (mine > 1 ? "s" : "") + " saved; find them on the board" : "Your tracker lets you find your ballot on the board", null));
                Ui.space(c, 8);
                if (status.equals("voting") && key.equals("ready")) {
                    c.addView(Ui.button(act, mine > 0 ? "Vote again" : "Enter the voting booth", Ui.PRIMARY_BTN, R.drawable.ic_ballot, v -> act.push(new BoothScreen(eid, null))), Ui.match());
                    Ui.space(c, 8);
                }
                if (regOpen) {
                    c.addView(Ui.button(act, key.equals("ready") ? "Credential and fake credentials" : key.equals("none") ? "Get a credential" : "Open my credential",
                        key.equals("ready") ? Ui.OUTLINE : Ui.PRIMARY_BTN, key.equals("ready") ? R.drawable.ic_mask : R.drawable.ic_key, v -> act.push(new CredentialScreen(eid))), Ui.match());
                }
                if (status.equals("published")) {
                    c.addView(Ui.button(act, "See the verified result", Ui.PRIMARY_BTN, R.drawable.ic_chart, v -> switchTab("results")), Ui.match());
                }
                break;
            }
            case "authority": {
                c.addView(Ui.cardTitle(act, R.drawable.ic_building, "Authority", null));
                Ui.space(c, 6);
                c.addView(Ui.muted(act, nextStepText()));
                Ui.space(c, 10);
                View b = nextStepButton();
                if (b != null) {
                    c.addView(b, Ui.match());
                    Ui.space(c, 8);
                }
                c.addView(Ui.button(act, "Open the control center", Ui.OUTLINE, R.drawable.ic_settings, v -> switchTab("manage")), Ui.match());
                break;
            }
            case "registrar": {
                c.addView(Ui.cardTitle(act, R.drawable.ic_clipboard, "Registrar desk", null));
                Ui.space(c, 10);
                c.addView(Widgets.grid2(act, miniStat("Waiting", J.num(e, "requested", 0)), miniStat("Roster", J.num(e, "roster", 0))));
                Ui.space(c, 10);
                c.addView(Ui.button(act, "Open the voter desk", Ui.PRIMARY_BTN, R.drawable.ic_users, v -> switchTab("voters")), Ui.match());
                break;
            }
            case "trustee": {
                c.addView(Ui.cardTitle(act, R.drawable.ic_key, "Trustee", Ui.chip(act, J.num(e, "threshold", 2) + " of " + J.num(e, "n_trustees", 3), Ui.SURFACE2, Ui.MUTED)));
                Ui.space(c, 6);
                StringBuilder seats = new StringBuilder();
                String fp = null;
                for (JSONObject t : J.list(J.a(e, "trustees"))) {
                    if (J.str(t, "email").equals(app.email())) {
                        seats.append(seats.length() == 0 ? "" : ", ").append(J.num(t, "seat", 0));
                        fp = J.strOrNull(t, "fingerprint");
                    }
                }
                c.addView(Ui.muted(act, seats.length() == 0 ? "You hold no seat in this election." : "You hold seat " + seats + (fp != null ? ". Share fingerprint " + fp.substring(0, 16) : ".")));
                Ui.space(c, 10);
                c.addView(Ui.button(act, status.equals("ceremony") ? "Go to the key ceremony" : status.equals("closed") ? "Release my share" : "Trustee seats",
                    Ui.PRIMARY_BTN, R.drawable.ic_key, v -> switchTab("trustees")), Ui.match());
                break;
            }
            default: {
                c.addView(Ui.cardTitle(act, R.drawable.ic_scan, "Auditor", null));
                Ui.space(c, 6);
                c.addView(Ui.muted(act, "Recompute the roster digests and signature, re-verify every ballot proof and inspect the tally transcript."));
                Ui.space(c, 10);
                c.addView(Ui.button(act, "Run the audit", Ui.PRIMARY_BTN, R.drawable.ic_shieldcheck, v -> switchTab("audit")), Ui.match());
                Ui.space(c, 8);
                c.addView(Ui.button(act, "Bulletin board", Ui.OUTLINE, R.drawable.ic_box, v -> switchTab("board")), Ui.match());
                break;
            }
        }
        return c;
    }

    View miniStat(String label, int value) {
        LinearLayout c = Ui.col(act);
        c.setBackground(Ui.round(act, Ui.SURFACE2, 12, 0, 0));
        c.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
        c.addView(Ui.small(act, label, Ui.MUTED));
        c.addView(Ui.text(act, String.valueOf(value), 22, Ui.TEXT, true));
        return c;
    }

    String nextStepText() {
        switch (J.str(e, "status")) {
            case "ceremony":
                return J.num(e, "trustees_joined", 0) + " of " + J.num(e, "n_trustees", 3) + " trustees joined the key ceremony.";
            case "registration":
                return "The key is ready and voters can get credentials. Open voting when you are ready.";
            case "voting":
                return Ui.plural(J.num(e, "ballots", 0), "anonymous ballot", "anonymous ballots") + " on the board. Close voting to freeze the board and the roster.";
            case "closed":
                return J.num(e, "approvals", 0) + " of " + J.num(e, "threshold", 2) + " trustee approvals. The tally starts at the threshold.";
            case "tallying":
                return "The encrypted tally is running.";
            default:
                return "The verified result is public.";
        }
    }

    View nextStepButton() {
        switch (J.str(e, "status")) {
            case "ceremony":
                return actionButton("Fill seats with demo trustees", Ui.PRIMARY_BTN, R.drawable.ic_users, "/ceremony/auto", "Key ceremony complete. The threshold key is ready.");
            case "registration":
                return actionButton("Open voting", Ui.PRIMARY_BTN, R.drawable.ic_play, "/voting/open", "Voting is open");
            case "voting":
                return actionButton("Close voting", Ui.PRIMARY_BTN, R.drawable.ic_stop, "/voting/close", "Voting closed");
            case "closed": {
                TextView b = Ui.button(act, "Approve with demo trustees and start", Ui.PRIMARY_BTN, R.drawable.ic_unlock, null);
                b.setOnClickListener(v -> action(b, "POST", path("/tally/auto"), new JSONObject(), r -> {
                    Ui.toast(act, "Trustees approved. The tally is running.");
                    act.push(new TallyScreen(eid));
                }));
                return b;
            }
            case "tallying":
                return Ui.button(act, "Watch the tally", Ui.PRIMARY_BTN, R.drawable.ic_activity, v -> act.push(new TallyScreen(eid)));
            default:
                return Ui.button(act, "View results", Ui.PRIMARY_BTN, R.drawable.ic_chart, v -> switchTab("results"));
        }
    }

    TextView actionButton(String label, int kind, int icon, String rest, String done) {
        TextView b = Ui.button(act, label, kind, icon, null);
        b.setOnClickListener(v -> action(b, "POST", path(rest), new JSONObject(), r -> {
            Ui.toast(act, done);
            lastSig = null;
            refresh();
        }));
        return b;
    }

    void board(LinearLayout body) {
        JSONObject b = o(extra);
        JSONArray ballots = J.a(b, "ballots");
        LinearLayout cap = Ui.card(act);
        cap.addView(Ui.cardTitle(act, R.drawable.ic_layers, "Board capacity", null));
        Ui.space(cap, 10);
        cap.addView(Widgets.capacity(act, J.num(b, "capacity", 8), J.num(b, "roster", 0), ballots.length()));
        Ui.space(cap, 8);
        cap.addView(Ui.muted(act, "The tally loads every roster entry and every ballot into one packed ciphertext, sorts them under encryption and keeps only the last ballot of each registered credential."));
        body.addView(cap);
        Ui.space(body, 14);
        Set<String> mine = new HashSet<>();
        JSONArray rec = app.receipts();
        for (int i = 0; i < rec.length(); i++) {
            mine.add(J.str(rec.optJSONObject(i), "tracker"));
        }
        LinearLayout list = Ui.card(act);
        list.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 6));
        LinearLayout title = Ui.cardTitle(act, R.drawable.ic_box, "Anonymous ballots", Ui.small(act, ballots.length() + " posted", Ui.MUTED));
        title.setPadding(Ui.dp(act, 16), 0, Ui.dp(act, 16), Ui.dp(act, 8));
        list.addView(title);
        if (ballots.length() == 0) {
            TextView t = Ui.muted(act, J.str(e, "status").equals("voting") ? "Ballots appear here the moment they are posted." : "No ballots were posted.");
            t.setPadding(Ui.dp(act, 16), Ui.dp(act, 4), Ui.dp(act, 16), Ui.dp(act, 12));
            list.addView(t);
        }
        for (int i = 0; i < ballots.length(); i++) {
            JSONObject x = ballots.optJSONObject(i);
            list.addView(Ui.divider(act));
            LinearLayout row = Ui.row(act);
            row.setBackground(Ui.ripple(act, mine.contains(J.str(x, "tracker")) ? 0xFFFBFAFF : Ui.SURFACE, 0, 0, 0));
            row.setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12));
            TextView num = Ui.text(act, String.valueOf(J.num(x, "position", 0) + 1), 15, Ui.TEXT, true);
            row.addView(num, new LinearLayout.LayoutParams(Ui.dp(act, 28), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout col = Ui.col(act);
            LinearLayout tr = Ui.row(act);
            tr.addView(Ui.mono(act, Ui.groupHex(J.str(x, "tracker").substring(0, 16), 4) + "…", 13, Ui.TEXT2));
            if (mine.contains(J.str(x, "tracker"))) {
                tr.addView(Ui.gap(act, 6));
                tr.addView(Ui.chip(act, "yours", Ui.PRIMARY50, Ui.PRIMARY600));
            }
            col.addView(tr);
            col.addView(Ui.small(act, Ui.ago(J.dbl(x, "posted", 0)) + " · " + J.dbl(x, "size_kb", 0) + " KB", Ui.MUTED));
            row.addView(col, Ui.weight(1));
            row.addView(Ui.chip(act, "✓ " + J.dbl(x, "verify_ms", 0) + " ms", Ui.SUCCESS50, Ui.SUCCESS));
            int pos = J.num(x, "position", 0);
            row.setOnClickListener(v -> ballotDialog(pos));
            list.addView(row);
        }
        body.addView(list);
        Ui.space(body, 14);
        LinearLayout knows = Ui.card(act);
        knows.addView(Ui.cardTitle(act, R.drawable.ic_eyeoff, "What the board knows", null));
        Ui.space(knows, 8);
        knows.addView(Widgets.check(act, "done", "Two proofs per ballot", "Each ballot proves the sender knows what both ciphertexts encrypt.", null));
        knows.addView(Widgets.check(act, "done", "Anonymous channel", "No ballot is linked to a voter.", null));
        knows.addView(Widgets.check(act, "done", "Fake credentials accepted", "They look exactly like real ones on the board.", null));
        knows.addView(Widgets.check(act, "failed", "Which ballots count", "Nobody can tell until the encrypted cleansing runs.", null));
        body.addView(knows);
    }

    void ballotDialog(int pos) {
        LinearLayout v = Ui.col(act);
        int p = Ui.dp(act, 18);
        v.setPadding(p, p, p, p);
        v.addView(Ui.h2(act, "Ballot " + (pos + 1)));
        Ui.space(v, 10);
        TextView loading = Ui.muted(act, "Loading…");
        v.addView(loading);
        ScrollView sv = new ScrollView(act);
        sv.addView(v);
        AlertDialog dlg = new AlertDialog.Builder(act).setView(sv).setPositiveButton("Close", null).create();
        dlg.show();
        work(() -> app.obj("GET", path("/board/" + pos), null), d -> {
            v.removeView(loading);
            v.addView(Ui.small(act, "Tracker", Ui.MUTED));
            TextView tr = Ui.mono(act, Ui.groupHex(J.str(d, "tracker"), 4), 15, Ui.TEXT);
            v.addView(tr);
            Ui.space(v, 6);
            v.addView(Ui.smallButton(act, "Copy tracker", Ui.OUTLINE, R.drawable.ic_copy, x -> Ui.copy(act, "Tracker", J.str(d, "tracker"))), Ui.wrap());
            Ui.space(v, 10);
            v.addView(Ui.kv(act, "Posted", Ui.fmtDate(J.dbl(d, "posted", 0))));
            v.addView(Ui.kv(act, "Size", J.dbl(d, "size_kb", 0) + " KB"));
            v.addView(Ui.kv(act, "Board check", J.dbl(d, "verify_ms", 0) + " ms"));
            JSONObject ring = J.o(d, "ring");
            Ui.space(v, 10);
            v.addView(Ui.h3(act, "Ciphertexts, " + J.num(ring, "L", 5) + " residues × " + J.num(ring, "N", 8192) + " coefficients"));
            for (JSONObject c : J.list(J.a(d, "ciphertexts"))) {
                Ui.space(v, 8);
                LinearLayout cc = Ui.col(act);
                cc.setBackground(Ui.round(act, Ui.SURFACE2, 12, 0, 0));
                cc.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
                cc.addView(Ui.text(act, J.str(c, "name"), 13.5f, Ui.TEXT, true));
                cc.addView(Ui.kv(act, "u digest", J.str(c, "u_digest")));
                cc.addView(Ui.kv(act, "w digest", J.str(c, "w_digest")));
                StringBuilder prev = new StringBuilder();
                JSONArray pa = J.a(c, "preview");
                for (int i = 0; i < pa.length(); i++) {
                    prev.append(i == 0 ? "" : ", ").append(pa.optLong(i));
                }
                cc.addView(Ui.kv(act, "u coefficients", prev + ", …"));
                v.addView(cc);
            }
            Ui.space(v, 12);
            v.addView(Ui.h3(act, "Proofs of knowledge"));
            for (JSONObject pr : J.list(J.a(d, "proofs"))) {
                Ui.space(v, 8);
                LinearLayout cc = Ui.col(act);
                cc.setBackground(Ui.round(act, Ui.SURFACE2, 12, 0, 0));
                cc.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
                cc.addView(Ui.text(act, J.str(pr, "statement") + " opening · " + J.dbl(pr, "size_kb", 0) + " KB", 13.5f, Ui.TEXT, true));
                cc.addView(Ui.kv(act, "challenge seed", J.str(pr, "challenge_seed").substring(0, 32) + "…"));
                cc.addView(Ui.kv(act, "σ", String.format(Locale.US, "%,.1f", J.dbl(pr, "sigma", 0))));
                double mx = J.dbl(pr, "max_abs_z", 0), bound = J.dbl(pr, "bound", 1);
                cc.addView(Ui.kv(act, "‖z‖∞", String.format(Locale.US, "%,.0f of %,.0f", mx, bound)));
                Ui.space(cc, 4);
                cc.addView(Ui.meter(act, new float[]{(float) Math.min(1, mx / bound)}, new int[]{Ui.ACCENT}, 7));
                v.addView(cc);
            }
            Ui.space(v, 12);
            LinearLayout res = Ui.col(act);
            TextView verify = Ui.button(act, "Re-verify both proofs now", Ui.PRIMARY_BTN, R.drawable.ic_shieldcheck, null);
            verify.setOnClickListener(x -> action(verify, "POST", path("/board/" + pos + "/verify"), new JSONObject(), r -> {
                JSONObject vr = o(r);
                boolean ok = J.bool(vr, "proofs_ok", false) && J.bool(vr, "tracker_ok", false);
                res.removeAllViews();
                Ui.space(res, 10);
                res.addView(Ui.notice(act, ok ? R.drawable.ic_shieldcheck : R.drawable.ic_alert,
                    (J.bool(vr, "proofs_ok", false) ? "Both proofs verify" : "A proof failed") + " · tracker " + (J.bool(vr, "tracker_ok", false) ? "matches" : "does not match")
                        + " · recomputed in " + J.dbl(vr, "ms", 0) + " ms", ok ? Ui.SUCCESS : Ui.DANGER, ok ? Ui.SUCCESS50 : Ui.DANGER50));
            }));
            v.addView(verify, Ui.match());
            v.addView(res);
        });
    }

    void roster(LinearLayout body) {
        JSONObject r = o(extra);
        LinearLayout sig = Ui.card(act);
        sig.addView(Ui.cardTitle(act, R.drawable.ic_shieldcheck, "Registrar signature", null));
        Ui.space(sig, 8);
        if (J.isNull(r, "root")) {
            sig.addView(Ui.muted(act, "The registrar signs the roster after issuing the first credential."));
        } else {
            sig.addView(Ui.kv(act, "Algorithm", J.str(r, "alg")));
            sig.addView(Ui.kv(act, "Registrar key", J.str(r, "registrar_key")));
            sig.addView(Ui.kv(act, "Roster root", J.str(r, "root").substring(0, 32) + "…"));
            sig.addView(Ui.kv(act, "Signature", J.str(r, "signature_preview").substring(0, Math.min(32, J.str(r, "signature_preview").length())) + "… (" + J.num(r, "signature_bytes", 0) + " bytes)"));
            sig.addView(Ui.kv(act, "Covers", J.num(r, "signed_entries", 0) + " entries"));
        }
        body.addView(sig);
        Ui.space(body, 14);
        LinearLayout list = Ui.card(act);
        list.addView(Ui.cardTitle(act, R.drawable.ic_users, "Encrypted roster", Ui.small(act, J.a(r, "entries").length() + " entries", Ui.MUTED)));
        Ui.space(list, 6);
        List<JSONObject> entries = J.list(J.a(r, "entries"));
        if (entries.isEmpty()) {
            list.addView(Ui.muted(act, "Roster entries appear when the registrar issues credentials."));
        }
        for (JSONObject x : entries) {
            list.addView(Ui.divider(act));
            LinearLayout row = Ui.row(act);
            row.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
            row.addView(Ui.text(act, "#" + (J.num(x, "position", 0) + 1), 14, Ui.TEXT, true), new LinearLayout.LayoutParams(Ui.dp(act, 36), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout col = Ui.col(act);
            String name = J.str(x, "name");
            col.addView(Ui.text(act, name.isEmpty() ? J.str(x, "voter") : name, 13.5f, Ui.TEXT, true));
            if (!name.isEmpty()) {
                col.addView(Ui.small(act, J.str(x, "voter"), Ui.MUTED));
            }
            col.addView(Ui.mono(act, J.str(x, "digest").substring(0, 24) + "…", 11.5f, Ui.MUTED));
            row.addView(col, Ui.weight(1));
            row.addView(Ui.small(act, Ui.ago(J.dbl(x, "issued", 0)), Ui.FAINT));
            list.addView(row);
        }
        body.addView(list);
        Ui.space(body, 14);
        body.addView(Ui.notice(act, R.drawable.ic_info, "Each entry is an encryption of a uniformly random 128-bit credential. Anyone can see who registered, but not the credential.", Ui.PRIMARY, Ui.PRIMARY50));
    }

    void results(LinearLayout body) {
        String status = J.str(e, "status");
        JSONObject r = J.o(e, "results_full");
        if (!status.equals("published") || r == null) {
            if (status.equals("tallying")) {
                body.addView(Widgets.empty(act, R.drawable.ic_activity, "The tally is running", "Cleansing, mixing and decryption are in progress under encryption."));
                Ui.space(body, 10);
                body.addView(Ui.button(act, "Watch it live", Ui.PRIMARY_BTN, R.drawable.ic_activity, v -> act.push(new TallyScreen(eid))), Ui.match());
            } else if (status.equals("closed")) {
                body.addView(Widgets.empty(act, R.drawable.ic_key, "Waiting for the trustees", J.num(e, "approvals", 0) + " of " + J.num(e, "threshold", 2) + " trustees have released their shares."));
            } else {
                body.addView(Widgets.empty(act, R.drawable.ic_chart, "No result yet", "Results are published after voting closes and the trustees run the tally."));
            }
            return;
        }
        JSONArray cands = J.a(e, "candidates");
        JSONArray counts = J.a(r, "counts");
        int total = J.num(r, "total_valid", 0);
        int max = 0;
        for (int i = 0; i < counts.length(); i++) {
            max = Math.max(max, counts.optInt(i));
        }
        int winners = 0, wi = -1;
        for (int i = 0; i < counts.length(); i++) {
            if (counts.optInt(i) == max && max > 0) {
                winners++;
                wi = i;
            }
        }
        LinearLayout card = Ui.card(act);
        card.addView(Ui.cardTitle(act, R.drawable.ic_chart, "Verified result", Ui.small(act, Ui.ago(J.dbl(r, "published", 0)), Ui.MUTED)));
        Ui.space(card, 12);
        if (winners == 1) {
            LinearLayout w = Ui.row(act);
            w.addView(Ui.iconTile(act, R.drawable.ic_sparkles, Ui.SUCCESS, Ui.SUCCESS50, 40));
            w.addView(Ui.gap(act, 10));
            LinearLayout col = Ui.col(act);
            col.addView(Ui.small(act, "Most votes", Ui.MUTED));
            col.addView(Ui.h2(act, J.str(cands.optJSONObject(wi), "name")));
            w.addView(col, Ui.weight(1));
            card.addView(w);
            Ui.space(card, 10);
        } else if (winners > 1) {
            card.addView(Ui.notice(act, R.drawable.ic_info, "Tie between the leading options", Ui.PRIMARY, Ui.PRIMARY50));
            Ui.space(card, 10);
        }
        for (int i = 0; i < cands.length(); i++) {
            JSONObject c = cands.optJSONObject(i);
            int color = Ui.parseColor(J.str(c, "color"), Ui.PRIMARY);
            LinearLayout row = Ui.row(act);
            row.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
            row.addView(Ui.avatar(act, J.str(c, "name"), color, 40));
            row.addView(Ui.gap(act, 12));
            LinearLayout col = Ui.col(act);
            LinearLayout nm = Ui.row(act);
            nm.addView(Ui.text(act, J.str(c, "name"), 15, Ui.TEXT, true));
            if (winners == 1 && i == wi) {
                nm.addView(Ui.gap(act, 6));
                nm.addView(Ui.chip(act, "WINNER", Ui.SUCCESS50, Ui.SUCCESS));
            }
            col.addView(nm);
            Ui.space(col, 6);
            col.addView(Ui.meter(act, new float[]{total == 0 ? 0 : counts.optInt(i) / (float) total}, new int[]{color}, 11));
            row.addView(col, Ui.weight(1));
            row.addView(Ui.gap(act, 12));
            LinearLayout num = Ui.col(act);
            num.setGravity(Gravity.END);
            num.addView(Ui.text(act, String.valueOf(counts.optInt(i)), 22, Ui.TEXT, true));
            num.addView(Ui.small(act, Widgets.pctText(counts.optInt(i), total), Ui.MUTED));
            row.addView(num);
            card.addView(row);
        }
        body.addView(card);
        Ui.space(body, 14);
        body.addView(Widgets.grid2(act,
            Widgets.stat(act, "Valid votes", String.valueOf(total), "counted", R.drawable.ic_check, Ui.SUCCESS, Ui.SUCCESS50),
            Widgets.stat(act, "Ballots on board", String.valueOf(J.num(r, "board", 0)), J.num(r, "discarded", 0) + " removed under encryption", R.drawable.ic_box, Ui.PRIMARY, Ui.PRIMARY50),
            Widgets.stat(act, "Roster", String.valueOf(J.num(r, "roster", 0)), "registered voters", R.drawable.ic_users, Ui.ACCENT, Ui.ACCENT50),
            Widgets.stat(act, "Tally time", Ui.duration(J.dbl(r, "seconds", 0)), J.num(r, "openings", 0) + " masked openings", R.drawable.ic_clock, Ui.VIOLET, Ui.VIOLET50)));
        Ui.space(body, 14);
        LinearLayout slots = Ui.card(act);
        slots.addView(Ui.cardTitle(act, R.drawable.ic_grid, "Decrypted slots after cleansing and mixing", null));
        Ui.space(slots, 6);
        slots.addView(Ui.muted(act, "The extracted slots were shuffled by two mix servers before the trustees decrypted them. An empty slot is a roster entry, an unused position, a superseded revote or a ballot with a fake credential; which one is never revealed."));
        Ui.space(slots, 10);
        JSONArray votes = J.a(r, "votes");
        LinearLayout line = null;
        for (int i = 0; i < votes.length(); i++) {
            if (i % 4 == 0) {
                line = Ui.row(act);
                slots.addView(line, Ui.match());
                Ui.space(slots, 8);
            }
            int v = votes.optInt(i);
            JSONObject c = v >= 1 && v <= cands.length() ? cands.optJSONObject(v - 1) : null;
            TextView s = Ui.text(act, c == null ? "∅\nempty" : J.str(c, "name") + "\nvote", 11.5f, c == null ? Ui.FAINT : Color.WHITE, true);
            s.setGravity(Gravity.CENTER);
            s.setMaxLines(2);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            s.setBackground(c == null ? Ui.round(act, Ui.SURFACE2, 12, Ui.BORDER_STRONG, 1) : Ui.round(act, Ui.parseColor(J.str(c, "color"), Ui.PRIMARY), 12, 0, 0));
            s.setPadding(Ui.dp(act, 4), Ui.dp(act, 10), Ui.dp(act, 4), Ui.dp(act, 10));
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i % 4 != 0) {
                lp.leftMargin = Ui.dp(act, 8);
            }
            line.addView(s, lp);
        }
        if (line != null) {
            for (int i = votes.length() % 4; i != 0 && i < 4; i++) {
                LinearLayout.LayoutParams lp = Ui.weight(1);
                lp.leftMargin = Ui.dp(act, 8);
                line.addView(new View(act), lp);
            }
        }
        body.addView(slots);
        Ui.space(body, 14);
        LinearLayout checks = Ui.card(act);
        boolean verified = J.bool(r, "verified", false);
        checks.addView(Ui.cardTitle(act, R.drawable.ic_shieldcheck, "Transcript checks", Ui.chip(act, verified ? "all verified" : "failures", verified ? Ui.SUCCESS50 : Ui.DANGER50, verified ? Ui.SUCCESS : Ui.DANGER)));
        Ui.space(checks, 8);
        StringBuilder q = new StringBuilder();
        JSONArray quorum = J.a(r, "quorum");
        for (int i = 0; i < quorum.length(); i++) {
            q.append(i == 0 ? "" : " and ").append(quorum.optInt(i));
        }
        checks.addView(Widgets.check(act, J.num(r, "shares_ok", 0) == J.num(r, "shares", 0) ? "done" : "failed", "Decryption shares",
            J.num(r, "shares_ok", 0) + " of " + J.num(r, "shares", 0) + " shares from trustees " + q + " verified", null));
        checks.addView(Widgets.check(act, J.num(r, "masks_ok", 0) == J.num(r, "masks", 0) ? "done" : "failed", "Mask proofs",
            J.num(r, "masks_ok", 0) + " of " + J.num(r, "masks", 0) + " mask encryptions proven consistent", null));
        for (JSONObject m : J.list(J.a(r, "mix"))) {
            checks.addView(Widgets.check(act, J.bool(m, "ok", false) ? "done" : "failed", "Mix server " + J.num(m, "server", 0),
                String.format(Locale.US, "shuffle proof of %.1f MB %s", J.dbl(m, "bits", 0) / 8 / 1048576, J.bool(m, "ok", false) ? "verified" : "invalid"), null));
        }
        for (JSONObject d : J.list(J.a(r, "decryption"))) {
            checks.addView(Widgets.check(act, J.bool(d, "ok", false) ? "done" : "failed", "Trustee " + J.num(d, "trustee", 0) + " final decryption",
                String.format(Locale.US, "%.1f MB of shares %s", J.dbl(d, "bits", 0) / 8 / 1048576, J.bool(d, "ok", false) ? "verified" : "invalid"), null));
        }
        Ui.space(checks, 8);
        checks.addView(Ui.small(act, "Transcript " + J.dbl(r, "transcript_mb", 0) + " MB · digest " + J.str(r, "digest").substring(0, 24), Ui.MUTED));
        body.addView(checks);
        Ui.space(body, 14);
        body.addView(Ui.button(act, "Run an independent audit", Ui.PRIMARY_BTN, R.drawable.ic_scan, v -> switchTab("audit")), Ui.match());
    }

    void trustees(LinearLayout body) {
        String status = J.str(e, "status");
        String role = app.role();
        boolean iHold = false;
        for (JSONObject t : J.list(J.a(e, "trustees"))) {
            if (J.str(t, "email").equals(app.email())) {
                iHold = true;
            }
        }
        LinearLayout card = Ui.card(act);
        card.addView(Ui.cardTitle(act, R.drawable.ic_key, "Trustee seats", Ui.chip(act, J.num(e, "threshold", 2) + " of " + J.num(e, "n_trustees", 3) + " threshold", Ui.SURFACE2, Ui.MUTED)));
        Ui.space(card, 6);
        for (JSONObject t : J.list(J.a(e, "trustees"))) {
            card.addView(Ui.divider(act));
            LinearLayout row = Ui.row(act);
            row.setGravity(Gravity.TOP);
            row.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12));
            boolean joined = J.str(t, "status").equals("joined");
            if (joined) {
                row.addView(Ui.avatar(act, J.str(t, "name"), 0, 40));
            } else {
                TextView seat = Ui.text(act, String.valueOf(J.num(t, "seat", 0)), 15, Ui.FAINT, true);
                seat.setGravity(Gravity.CENTER);
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                gd.setColor(Ui.SURFACE3);
                seat.setBackground(gd);
                row.addView(seat, new LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40)));
            }
            row.addView(Ui.gap(act, 12));
            LinearLayout col = Ui.col(act);
            LinearLayout nm = Ui.row(act);
            nm.addView(Ui.text(act, joined ? J.str(t, "name") : "Seat " + J.num(t, "seat", 0) + " is open", 14.5f, Ui.TEXT, true));
            if (J.str(t, "email").equals(app.email())) {
                nm.addView(Ui.gap(act, 6));
                nm.addView(Ui.chip(act, "you", Ui.PRIMARY50, Ui.PRIMARY600));
            }
            col.addView(nm);
            String em = J.str(t, "email");
            col.addView(Ui.small(act, !em.isEmpty() ? em : status.equals("ceremony") ? "Any trustee can take this seat" : "", Ui.MUTED));
            if (!J.isNull(t, "fingerprint")) {
                col.addView(Ui.mono(act, "share " + J.str(t, "fingerprint").substring(0, 20), 11.5f, Ui.MUTED));
            }
            Ui.space(col, 6);
            LinearLayout chips = Ui.row(act);
            chips.addView(joined ? Ui.chip(act, "✓ joined " + Ui.ago(J.dbl(t, "joined", 0)), Ui.SUCCESS50, Ui.SUCCESS) : Ui.chip(act, "open", Ui.WARNING50, Ui.WARNING));
            if (!J.isNull(t, "approved")) {
                chips.addView(Ui.gap(act, 6));
                chips.addView(Ui.chip(act, "share released", Ui.VIOLET50, Ui.VIOLET));
            }
            col.addView(chips);
            int idx = J.num(t, "idx", 0);
            if (role.equals("trustee") && status.equals("ceremony") && !joined && !iHold && (em.isEmpty() || em.equals(app.email()))) {
                Ui.space(col, 8);
                TextView b = Ui.smallButton(act, "Take seat " + J.num(t, "seat", 0), Ui.PRIMARY_BTN, R.drawable.ic_key, null);
                b.setOnClickListener(v -> action(b, "POST", path("/trustees/" + idx + "/join"), new JSONObject(), r -> {
                    Ui.toast(act, "You joined the key ceremony");
                    lastSig = null;
                    refresh();
                }));
                col.addView(b, Ui.wrap());
            }
            if (role.equals("trustee") && status.equals("closed") && em.equals(app.email()) && J.isNull(t, "approved")) {
                Ui.space(col, 8);
                TextView b = Ui.smallButton(act, "Release my share", Ui.PRIMARY_BTN, R.drawable.ic_unlock, null);
                b.setOnClickListener(v -> action(b, "POST", path("/trustees/" + idx + "/approve"), new JSONObject(), r -> {
                    JSONObject view = o(r);
                    if (J.str(view, "status").equals("tallying")) {
                        Ui.toast(act, "Threshold reached. The tally has started.");
                        act.push(new TallyScreen(eid));
                    } else {
                        Ui.toast(act, "Share released. Waiting for another trustee.");
                        lastSig = null;
                        refresh();
                    }
                }));
                col.addView(b, Ui.wrap());
            }
            row.addView(col, Ui.weight(1));
            card.addView(row);
        }
        if ((role.equals("authority") || role.equals("trustee")) && (status.equals("ceremony") || status.equals("closed"))) {
            Ui.space(card, 6);
            card.addView(Ui.muted(act, status.equals("ceremony") ? "Testing alone? Let demo trustees take the remaining seats." : "Testing alone? Let demo trustees release their shares."));
            Ui.space(card, 8);
            if (status.equals("ceremony")) {
                card.addView(actionButton("Fill with demo trustees", Ui.OUTLINE, R.drawable.ic_users, "/ceremony/auto", "Key ceremony complete"), Ui.match());
            } else {
                TextView b = Ui.button(act, "Approve with demo trustees", Ui.OUTLINE, R.drawable.ic_users, null);
                b.setOnClickListener(v -> action(b, "POST", path("/tally/auto"), new JSONObject(), r -> act.push(new TallyScreen(eid))));
                card.addView(b, Ui.match());
            }
        }
        body.addView(card);
        Ui.space(body, 14);
        LinearLayout how = Ui.card(act);
        how.addView(Ui.cardTitle(act, R.drawable.ic_layers, "Replicated threshold sharing", null));
        Ui.space(how, 6);
        how.addView(Ui.muted(act, "The secret key is split into three random pieces, one for each set of trustees too small to decrypt. Any two trustees together hold all three pieces; one trustee alone holds a uniformly random value."));
        JSONObject key = J.o(e, "key");
        if (key != null) {
            Ui.space(how, 8);
            how.addView(Ui.kv(act, "Public key", J.str(key, "fingerprint")));
            how.addView(Ui.kv(act, "Generated", Ui.fmtDate(J.dbl(key, "created", 0)) + " in " + J.dbl(key, "seconds", 0) + "s"));
            how.addView(Ui.kv(act, "Key size", J.dbl(key, "size_kb", 0) + " KB"));
        }
        body.addView(how);
    }

    void manage(LinearLayout body) {
        String status = J.str(e, "status");
        String[] order = {"ceremony", "registration", "voting", "closed", "tallying", "published"};
        String[] labels = {"Key ceremony", "Registration", "Voting", "Closed", "Tally", "Published"};
        String[] details = {J.num(e, "trustees_joined", 0) + "/" + J.num(e, "n_trustees", 3) + " trustees", Ui.plural(J.num(e, "roster", 0), "credential", "credentials"),
            Ui.plural(J.num(e, "ballots", 0), "ballot", "ballots"), J.num(e, "approvals", 0) + "/" + J.num(e, "threshold", 2) + " approvals", "encrypted", "verified result"};
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(status)) {
                idx = i;
            }
        }
        LinearLayout pipe = Ui.row(act);
        View current = null;
        for (int i = 0; i < order.length; i++) {
            boolean done = i < idx || status.equals("published");
            boolean now = i == idx && !status.equals("published");
            LinearLayout cell = Ui.col(act);
            cell.setBackground(Ui.round(act, now ? Ui.PRIMARY50 : done ? Ui.SURFACE2 : Ui.SURFACE, 12, now ? Ui.PRIMARY : Ui.BORDER, now ? 1.5f : 1));
            cell.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
            cell.addView(Ui.text(act, "STEP " + (i + 1), 10.5f, Ui.FAINT, true));
            cell.addView(Ui.text(act, labels[i], 14, done ? Ui.MUTED : Ui.TEXT, true));
            cell.addView(Ui.small(act, details[i], Ui.MUTED));
            pipe.addView(cell, new LinearLayout.LayoutParams(Ui.dp(act, 128), ViewGroup.LayoutParams.WRAP_CONTENT));
            pipe.addView(Ui.gap(act, 8));
            if (now || (i == order.length - 1 && status.equals("published"))) {
                current = cell;
            }
        }
        body.addView(Ui.hscroll(act, pipe, current));
        Ui.space(body, 14);
        LinearLayout next = Ui.card(act);
        next.setBackground(Ui.round(act, Ui.PRIMARY50, 16, Ui.PRIMARY100, 1));
        next.addView(Ui.eyebrow(act, "Next step"));
        Ui.space(next, 4);
        next.addView(Ui.h2(act, nextTitle()));
        Ui.space(next, 4);
        next.addView(Ui.muted(act, nextStepText()));
        Ui.space(next, 12);
        View b = nextStepButton();
        if (b != null) {
            next.addView(b, Ui.match());
        }
        if (status.equals("ceremony") || status.equals("closed")) {
            Ui.space(next, 8);
            next.addView(Ui.button(act, "Trustee seats", Ui.OUTLINE, R.drawable.ic_key, v -> switchTab("trustees")), Ui.match());
        }
        body.addView(next);
        Ui.space(body, 14);
        LinearLayout decoys = Ui.card(act);
        decoys.addView(Ui.cardTitle(act, R.drawable.ic_mask, "Decoy ballots", Ui.small(act, J.num(e, "decoys", 0) + " posted", Ui.MUTED)));
        Ui.space(decoys, 6);
        decoys.addView(Ui.muted(act, "An authority may post ballots under freshly fabricated credentials. They are indistinguishable from real ballots and are removed by the encrypted cleansing, which hides how many coerced or fake ballots there really were."));
        Ui.space(decoys, 10);
        LinearLayout dr = Ui.row(act);
        TextView d1 = Ui.button(act, "Post 1 decoy", Ui.OUTLINE, R.drawable.ic_plus, null);
        d1.setOnClickListener(v -> action(d1, "POST", path("/decoys"), J.obj("count", 1), r -> {
            Ui.toast(act, "Decoy ballot posted");
            lastSig = null;
            refresh();
        }));
        Ui.enable(d1, status.equals("voting"));
        dr.addView(d1, Ui.weight(1));
        dr.addView(Ui.gap(act, 8));
        TextView d2 = Ui.button(act, "Post 2", Ui.OUTLINE, R.drawable.ic_plus, null);
        d2.setOnClickListener(v -> action(d2, "POST", path("/decoys"), J.obj("count", 2), r -> {
            Ui.toast(act, "Decoy ballots posted");
            lastSig = null;
            refresh();
        }));
        Ui.enable(d2, status.equals("voting"));
        dr.addView(d2, Ui.weight(1));
        decoys.addView(dr, Ui.match());
        Ui.space(decoys, 6);
        decoys.addView(Ui.small(act, status.equals("voting") ? (J.num(e, "capacity", 8) - J.num(e, "used", 0)) + " free entries on the board" : "Available while voting is open", Ui.FAINT));
        body.addView(decoys);
        Ui.space(body, 14);
        LinearLayout links = Ui.card(act);
        links.addView(Ui.cardTitle(act, R.drawable.ic_users, "Quick links", null));
        Ui.space(links, 10);
        links.addView(Ui.button(act, "Voters and registration", Ui.OUTLINE, R.drawable.ic_clipboard, v -> switchTab("voters")), Ui.match());
        Ui.space(links, 8);
        links.addView(Ui.button(act, "Bulletin board", Ui.OUTLINE, R.drawable.ic_box, v -> switchTab("board")), Ui.match());
        body.addView(links);
        Ui.space(body, 14);
        LinearLayout feed = Ui.card(act);
        feed.addView(Ui.cardTitle(act, R.drawable.ic_activity, "Recent activity", null));
        Ui.space(feed, 6);
        feed.addView(Widgets.feed(act, a(extra), false, 8));
        body.addView(feed);
        Ui.space(body, 14);
        LinearLayout danger = Ui.card(act);
        danger.setBackground(Ui.round(act, Ui.SURFACE, 16, 0xFFF5C2C2, 1));
        danger.addView(Ui.cardTitle(act, R.drawable.ic_trash, "Danger zone", null));
        Ui.space(danger, 6);
        danger.addView(Ui.muted(act, "Deleting removes the keys, roster, board and results of this election."));
        Ui.space(danger, 10);
        TextView del = Ui.button(act, "Delete election", Ui.DANGER_BTN, R.drawable.ic_trash, null);
        del.setOnClickListener(v -> new AlertDialog.Builder(act).setTitle("Delete this election?")
            .setMessage("“" + J.str(e, "title") + "” and all of its cryptographic material will be removed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dd, w) -> action(del, "DELETE", path(""), null, r -> {
                Ui.toast(act, "Election deleted");
                act.goHome();
            })).show());
        Ui.enable(del, !status.equals("tallying"));
        danger.addView(del, Ui.match());
        body.addView(danger);
    }

    String nextTitle() {
        switch (J.str(e, "status")) {
            case "ceremony":
                return "Waiting for trustees";
            case "registration":
                return "Open voting";
            case "voting":
                return "Voting is open";
            case "closed":
                return "Waiting for approvals";
            case "tallying":
                return "The tally is running";
            default:
                return "Result published";
        }
    }

    void voters(LinearLayout body) {
        String status = J.str(e, "status");
        boolean open = status.equals("registration") || status.equals("voting");
        JSONArray vs = a(extra);
        LinearLayout add = Ui.card(act);
        add.addView(Ui.cardTitle(act, R.drawable.ic_plus, "Add eligible voters", null));
        Ui.space(add, 10);
        EditText area = Ui.area(act, "one email per line or separated by commas");
        add.addView(area);
        Ui.space(add, 8);
        Switch issue = new Switch(act);
        issue.setText(open ? "Issue credentials immediately" : "Issue credentials immediately (after the key ceremony)");
        issue.setTextColor(Ui.TEXT2);
        issue.setChecked(open);
        issue.setEnabled(open);
        add.addView(issue);
        Ui.space(add, 8);
        TextView addBtn = Ui.button(act, "Add voters", Ui.PRIMARY_BTN, R.drawable.ic_plus, null);
        addBtn.setOnClickListener(v -> action(addBtn, "POST", path("/voters"), J.obj("emails", area.getText().toString(), "issue", issue.isChecked()), r -> {
            Ui.toast(act, "Voters added");
            lastSig = null;
            refresh();
        }));
        add.addView(addBtn, Ui.match());
        body.addView(add);
        Ui.space(body, 14);
        boolean anyPending = false;
        for (JSONObject v : J.list(vs)) {
            String s = J.str(v, "status");
            if (s.equals("requested") || s.equals("eligible")) {
                anyPending = true;
            }
        }
        TextView all = Ui.button(act, "Issue all pending credentials", Ui.SOFT, R.drawable.ic_send, null);
        all.setOnClickListener(v -> action(all, "POST", path("/voters/issue"), J.obj("all", true), r -> {
            Ui.toast(act, J.num(o(r), "issued", 0) + " credentials sealed and the roster signed");
            lastSig = null;
            refresh();
        }));
        Ui.enable(all, open && anyPending);
        body.addView(all, Ui.match());
        Ui.space(body, 14);
        LinearLayout list = Ui.card(act);
        list.addView(Ui.cardTitle(act, R.drawable.ic_users, "Voters", Ui.small(act, vs.length() + " listed", Ui.MUTED)));
        Ui.space(list, 6);
        if (vs.length() == 0) {
            list.addView(Ui.muted(act, J.str(e, "eligibility").equals("open") ? "Voters appear here when they request a credential." : "Add eligible voters above."));
        }
        for (JSONObject v : J.list(vs)) {
            list.addView(Ui.divider(act));
            LinearLayout row = Ui.row(act);
            row.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
            row.addView(Ui.avatar(act, J.str(v, "name"), 0, 36));
            row.addView(Ui.gap(act, 10));
            LinearLayout col = Ui.col(act);
            col.addView(Ui.text(act, J.str(v, "name"), 14, Ui.TEXT, true));
            col.addView(Ui.small(act, J.str(v, "email"), Ui.MUTED));
            StringBuilder when = new StringBuilder();
            if (!J.isNull(v, "roster_pos")) {
                when.append("roster #").append(J.num(v, "roster_pos", 0) + 1).append(" · ");
            }
            if (!J.isNull(v, "opened")) {
                when.append("opened ").append(Ui.ago(J.dbl(v, "opened", 0)));
            } else if (!J.isNull(v, "issued")) {
                when.append("sealed ").append(Ui.ago(J.dbl(v, "issued", 0)));
            } else if (!J.isNull(v, "requested")) {
                when.append("requested ").append(Ui.ago(J.dbl(v, "requested", 0)));
            }
            if (when.length() > 0) {
                col.addView(Ui.small(act, when.toString(), Ui.FAINT));
            }
            row.addView(col, Ui.weight(1));
            String s = J.str(v, "status");
            if ((s.equals("requested") || s.equals("eligible")) && open) {
                TextView b = Ui.smallButton(act, "Issue", Ui.PRIMARY_BTN, R.drawable.ic_send, null);
                String email = J.str(v, "email");
                b.setOnClickListener(x -> action(b, "POST", path("/voters/issue"), J.obj("email", email), r -> {
                    Ui.toast(act, "Credential sealed and roster signed");
                    lastSig = null;
                    refresh();
                }));
                row.addView(b);
            } else {
                int[] col2 = s.equals("opened") ? new int[]{Ui.SUCCESS50, Ui.SUCCESS} : s.equals("issued") ? new int[]{Ui.PRIMARY50, Ui.PRIMARY600}
                    : s.equals("requested") ? new int[]{Ui.WARNING50, Ui.WARNING} : new int[]{Ui.SURFACE2, Ui.MUTED};
                row.addView(Ui.chip(act, s.equals("issued") ? "sealed" : s, col2[0], col2[1]));
            }
            list.addView(row);
        }
        body.addView(list);
        Ui.space(body, 14);
        LinearLayout how = Ui.card(act);
        how.addView(Ui.cardTitle(act, R.drawable.ic_mail, "How a credential is delivered", null));
        Ui.space(how, 8);
        how.addView(Widgets.check(act, "done", "Draw", "The registrar draws a uniform 128-bit credential.", null));
        how.addView(Widgets.check(act, "done", "Encrypt", "It encrypts the credential under the election key and appends it to the public roster.", null));
        how.addView(Widgets.check(act, "done", "Sign", "It signs the new roster root with ML-DSA-65.", null));
        how.addView(Widgets.check(act, "done", "Seal", "It seals the plaintext credential to the voter's device key with ML-KEM-768 and AES-256-GCM, then forgets it.", null));
        body.addView(how);
    }

    void audit(LinearLayout body) {
        LinearLayout card = Ui.card(act);
        card.addView(Ui.cardTitle(act, R.drawable.ic_scan, "Independent audit", null));
        Ui.space(card, 6);
        card.addView(Ui.muted(act, "The audit recomputes the roster digests and the registrar signature, re-verifies every proof on the board and checks the published tally transcript."));
        Ui.space(card, 12);
        LinearLayout out = Ui.col(act);
        TextView run = Ui.button(act, "Run full audit", Ui.PRIMARY_BTN, R.drawable.ic_shieldcheck, null);
        run.setOnClickListener(v -> action(run, "POST", path("/audit"), new JSONObject(), r -> {
            JSONObject res = o(r);
            out.removeAllViews();
            Ui.space(out, 12);
            boolean ok = J.bool(res, "ok", false);
            out.addView(Ui.notice(act, ok ? R.drawable.ic_shieldcheck : R.drawable.ic_alert, (ok ? "Every check passed" : "Some checks failed") + " · completed in " + J.dbl(res, "seconds", 0) + "s",
                ok ? Ui.SUCCESS : Ui.DANGER, ok ? Ui.SUCCESS50 : Ui.DANGER50));
            Ui.space(out, 8);
            for (JSONObject c : J.list(J.a(res, "checks"))) {
                out.addView(Widgets.check(act, J.bool(c, "ok", false) ? "done" : "failed", J.str(c, "name"), J.str(c, "detail"), null));
            }
        }));
        card.addView(run, Ui.match());
        card.addView(out);
        body.addView(card);
        Ui.space(body, 14);
        LinearLayout what = Ui.card(act);
        what.addView(Ui.cardTitle(act, R.drawable.ic_info, "What is checked", null));
        Ui.space(what, 8);
        what.addView(Widgets.check(act, "pending", "Joint public key", "Produced by the key ceremony", null));
        what.addView(Widgets.check(act, "pending", "Encrypted roster", "Every ciphertext digest and the ML-DSA-65 signature", null));
        what.addView(Widgets.check(act, "pending", "Bulletin board", "Both proofs of every ballot, trackers and uniqueness", null));
        what.addView(Widgets.check(act, "pending", "Board capacity", "Of the packed evaluation", null));
        what.addView(Widgets.check(act, "pending", "Tally transcript", "Cleansing shares, both shuffles, final decryption", null));
        what.addView(Widgets.check(act, "pending", "Counts", "Recomputed from the decrypted slots", null));
        body.addView(what);
    }

    void activity(LinearLayout body) {
        LinearLayout feed = Ui.card(act);
        JSONArray ev = a(extra);
        feed.addView(Ui.cardTitle(act, R.drawable.ic_activity, "Election activity", Ui.small(act, ev.length() + " events", Ui.MUTED)));
        Ui.space(feed, 6);
        feed.addView(Widgets.feed(act, ev, false, 80));
        body.addView(feed);
    }
}

package org.oylama.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.oylama.node.J;

import java.util.ArrayList;
import java.util.List;

public class HomeScreen extends Screen {
    @Override
    public String title() {
        return app.role().equals("voter") ? "Home" : Roles.label(app.role()) + " dashboard";
    }

    @Override
    public String subtitle() {
        return J.str(app.user(), "email");
    }

    @Override
    public String tab() {
        return "home";
    }

    @Override
    protected void load() {
        fetch(() -> {
            JSONArray els = app.arr("GET", "/api/elections", null);
            JSONArray activity = app.role().equals("authority") ? app.arr("GET", "/api/activity", null) : new JSONArray();
            return new Object[]{els, activity};
        }, res -> {
            Object[] r = (Object[]) res;
            String sig = r[0].toString() + r[1].toString() + app.receipts().length();
            if (sig.equals(lastSig)) {
                return;
            }
            lastSig = sig;
            render((JSONArray) r[0], (JSONArray) r[1]);
        });
    }

    private String lastSig;

    @Override
    public void onShow() {
        every(8000, this::refresh);
    }

    void render(JSONArray arr, JSONArray activity) {
        List<JSONObject> els = J.list(arr);
        LinearLayout body = page();
        body.addView(greeting());
        Ui.space(body, 16);
        switch (app.role()) {
            case "authority":
                authority(body, els, activity);
                break;
            case "registrar":
                registrar(body, els);
                break;
            case "trustee":
                trustee(body, els);
                break;
            case "auditor":
                auditor(body, els);
                break;
            default:
                voter(body, els);
                break;
        }
        show(body);
    }

    View greeting() {
        LinearLayout c = Ui.col(act);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF4F46E5, 0xFF3730A3, 0xFF0E8C8B});
        g.setCornerRadius(Ui.dp(act, 20));
        c.setBackground(g);
        int p = Ui.dp(act, 18);
        c.setPadding(p, p, p, p);
        LinearLayout top = Ui.row(act);
        top.addView(Ui.iconTile(act, Roles.icon(app.role()), Color.WHITE, 0x33FFFFFF, 40));
        top.addView(Ui.gap(act, 12));
        LinearLayout col = Ui.col(act);
        TextView eb = Ui.text(act, Roles.label(app.role()).toUpperCase() + (app.backend().isLocal() ? " · ON THIS DEVICE" : " · " + app.backend().label()), 11, 0xFFC7D2FE, true);
        eb.setLetterSpacing(0.06f);
        col.addView(eb);
        String name = J.str(app.user(), "name");
        String greet = name.matches(".*\\b(Authority|Registrar|Auditor)\\b.*") ? name : name.replaceFirst("^(?i)(prof|dr|mr|mrs|ms)\\.?\\s+", "").split(" ")[0];
        col.addView(Ui.text(act, "Hello, " + greet, 22, Color.WHITE, true));
        top.addView(col, Ui.weight(1));
        c.addView(top);
        Ui.space(c, 10);
        String text;
        switch (app.role()) {
            case "authority":
                text = "Create elections, choose the candidates and the size of the encrypted board, then move each election through the key ceremony, registration, voting and the verifiable tally.";
                break;
            case "registrar":
                text = "Admit voters, draw their random credentials, encrypt each one into the public roster and seal the plaintext to the voter's device with ML-KEM-768.";
                break;
            case "trustee":
                text = "The election key is split among three trustees. Any two can decrypt, one alone learns nothing. Join key ceremonies and release your share for tallies.";
                break;
            case "auditor":
                text = "Everything Oylama publishes can be re-checked: ballot proofs, the roster signature, the cleansing transcript, both shuffles and the final decryption.";
                break;
            default:
                text = "Your ballot is encrypted on this phone, posted anonymously, and only counted if it carries a valid credential. You can always vote again: the last valid ballot counts.";
                break;
        }
        c.addView(Ui.text(act, text, 13.5f, 0xE6FFFFFF, false));
        return c;
    }

    void list(LinearLayout body, String title, String sub, List<JSONObject> items, int emptyIcon, String emptyTitle, String emptyText) {
        body.addView(Widgets.section(act, title, sub));
        Ui.space(body, 10);
        if (items.isEmpty()) {
            body.addView(Widgets.empty(act, emptyIcon, emptyTitle, emptyText));
        } else {
            for (JSONObject e : items) {
                body.addView(Widgets.electionCard(act, e));
                Ui.space(body, 12);
            }
        }
        Ui.space(body, 12);
    }

    void voter(LinearLayout body, List<JSONObject> els) {
        List<JSONObject> open = new ArrayList<>(), soon = new ArrayList<>(), done = new ArrayList<>(), ready = new ArrayList<>();
        for (JSONObject e : els) {
            String s = J.str(e, "status");
            if (s.equals("registration") || s.equals("voting")) {
                open.add(e);
                if (s.equals("voting") && Widgets.voterState(app, e, new String[1]).equals("ready") && !app.voted(J.str(e, "id"))) {
                    ready.add(e);
                }
            } else if (s.equals("ceremony")) {
                soon.add(e);
            } else {
                done.add(e);
            }
        }
        if (!ready.isEmpty()) {
            LinearLayout c = Ui.card(act);
            c.setBackground(Ui.round(act, Ui.PRIMARY50, 16, Ui.PRIMARY100, 1));
            LinearLayout r = Ui.row(act);
            r.addView(Ui.iconTile(act, R.drawable.ic_ballot, Ui.PRIMARY, Ui.SURFACE, 44));
            r.addView(Ui.gap(act, 12));
            LinearLayout col = Ui.col(act);
            col.addView(Ui.h3(act, ready.size() == 1 ? "A ballot is waiting for you" : ready.size() + " ballots are waiting for you"));
            StringBuilder t = new StringBuilder();
            for (JSONObject e : ready) {
                t.append(t.length() == 0 ? "" : " · ").append(J.str(e, "title"));
            }
            col.addView(Ui.small(act, t.toString(), Ui.MUTED));
            r.addView(col, Ui.weight(1));
            c.addView(r);
            Ui.space(c, 12);
            String eid = J.str(ready.get(0), "id");
            c.addView(Ui.button(act, "Open the voting booth", Ui.PRIMARY_BTN, R.drawable.ic_right, v -> act.push(new BoothScreen(eid, null))), Ui.match());
            body.addView(c);
            Ui.space(body, 16);
        }
        list(body, "Open elections", "Get a credential from the registrar, then cast your encrypted ballot.", open, R.drawable.ic_ballot,
            "No open elections", "When an authority opens registration or voting it appears here.");
        JSONArray receipts = app.receipts();
        LinearLayout rc = Ui.card(act);
        rc.addView(Ui.cardTitle(act, R.drawable.ic_receipt, "Your receipts", Ui.smallButton(act, "All", Ui.GHOST, 0, v -> act.openTab("receipts"))));
        Ui.space(rc, 8);
        if (receipts.length() == 0) {
            rc.addView(Ui.muted(act, "After you vote, your ballot tracker is kept here so you can find it on the bulletin board."));
        }
        for (int i = 0; i < Math.min(3, receipts.length()); i++) {
            JSONObject r = receipts.optJSONObject(i);
            LinearLayout row = Ui.row(act);
            row.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6));
            LinearLayout col = Ui.col(act);
            col.addView(Ui.text(act, J.str(r, "title"), 13.5f, Ui.TEXT, true));
            col.addView(Ui.mono(act, Ui.groupHex(J.str(r, "tracker").substring(0, 16), 4) + "…", 12, Ui.MUTED));
            row.addView(col, Ui.weight(1));
            row.addView(Ui.small(act, Ui.ago(J.dbl(r, "posted", 0)), Ui.FAINT));
            String tr = J.str(r, "tracker");
            row.setOnClickListener(v -> act.push(new TrackScreen(tr, false)));
            rc.addView(row);
        }
        body.addView(rc);
        Ui.space(body, 14);
        LinearLayout pressure = Ui.card(act);
        pressure.setBackground(Ui.round(act, 0xFFFFF7ED, 16, 0xFFF7DDB0, 1));
        pressure.addView(Ui.cardTitle(act, R.drawable.ic_mask, "Under pressure?", null));
        Ui.space(pressure, 6);
        pressure.addView(Ui.muted(act, "If someone demands your voting credential, open your credential page and generate a fake credential. It looks identical, the bulletin board accepts ballots made with it, and the encrypted cleansing silently discards them. Later, vote with your real credential."));
        body.addView(pressure);
        Ui.space(body, 20);
        list(body, "Upcoming", "These elections are still in their key ceremony.", soon, R.drawable.ic_clock, "Nothing upcoming", "New elections show up here first.");
        list(body, "Closed and results", "Tallies run under encryption and are verifiable.", done, R.drawable.ic_chart, "No results yet", "Published results will be listed here.");
    }

    void authority(LinearLayout body, List<JSONObject> els, JSONArray activity) {
        int published = 0, voting = 0, registration = 0, ballots = 0, roster = 0;
        for (JSONObject e : els) {
            String s = J.str(e, "status");
            if (s.equals("published")) {
                published++;
            }
            if (s.equals("voting")) {
                voting++;
            }
            if (s.equals("registration")) {
                registration++;
            }
            ballots += J.num(e, "ballots", 0);
            roster += J.num(e, "roster", 0);
        }
        body.addView(Widgets.grid2(act,
            Widgets.stat(act, "Elections", String.valueOf(els.size()), published + " published", R.drawable.ic_list, Ui.PRIMARY, Ui.PRIMARY50),
            Widgets.stat(act, "Open for voting", String.valueOf(voting), registration + " in registration", R.drawable.ic_ballot, Ui.SUCCESS, Ui.SUCCESS50),
            Widgets.stat(act, "Encrypted ballots", String.valueOf(ballots), "anonymous, on all boards", R.drawable.ic_box, Ui.VIOLET, Ui.VIOLET50),
            Widgets.stat(act, "Registered voters", String.valueOf(roster), "in encrypted rosters", R.drawable.ic_users, Ui.ACCENT, Ui.ACCENT50)));
        Ui.space(body, 14);
        body.addView(Ui.button(act, "Create a new election", Ui.PRIMARY_BTN, R.drawable.ic_plus, v -> act.openTab("new")), Ui.match());
        Ui.space(body, 20);
        body.addView(Widgets.section(act, "Elections", "Tap an election to open its control center."));
        Ui.space(body, 10);
        if (els.isEmpty()) {
            body.addView(Widgets.empty(act, R.drawable.ic_building, "No elections yet", "Create your first election to get started."));
        }
        for (JSONObject e : els) {
            body.addView(adminRow(e));
            Ui.space(body, 10);
        }
        Ui.space(body, 10);
        LinearLayout feed = Ui.card(act);
        feed.addView(Ui.cardTitle(act, R.drawable.ic_activity, "Recent activity", null));
        Ui.space(feed, 6);
        feed.addView(Widgets.feed(act, activity, true, 12));
        body.addView(feed);
    }

    View adminRow(JSONObject e) {
        LinearLayout c = Ui.card(act);
        c.setBackground(Ui.ripple(act, Ui.SURFACE, 16, Ui.BORDER, 1));
        LinearLayout top = Ui.row(act);
        LinearLayout col = Ui.col(act);
        col.addView(Ui.text(act, J.str(e, "title"), 15, Ui.TEXT, true));
        col.addView(Ui.small(act, J.str(e, "organization") + " · " + J.a(e, "candidates").length() + " options", Ui.MUTED));
        top.addView(col, Ui.weight(1));
        top.addView(Ui.statusChip(act, J.str(e, "status")));
        c.addView(top);
        Ui.space(c, 10);
        c.addView(Widgets.capacity(act, J.num(e, "capacity", 8), J.num(e, "roster", 0), J.num(e, "ballots", 0)));
        String status = J.str(e, "status");
        String detail;
        if (status.equals("ceremony")) {
            detail = J.num(e, "trustees_joined", 0) + " of " + J.num(e, "n_trustees", 3) + " trustees joined";
        } else if (status.equals("closed")) {
            detail = J.num(e, "approvals", 0) + " of " + J.num(e, "threshold", 2) + " trustee approvals";
        } else if (status.equals("tallying") && J.o(e, "tally") != null) {
            JSONObject t = J.o(e, "tally");
            detail = "Tally " + Math.round(100.0 * J.num(t, "done", 0) / Math.max(1, J.num(t, "total", 1))) + "% · " + Ui.duration(J.dbl(t, "eta", 0)) + " left";
        } else {
            detail = J.num(e, "threshold", 2) + " of " + J.num(e, "n_trustees", 3) + " trustee threshold";
        }
        Ui.space(c, 8);
        LinearLayout foot = Ui.row(act);
        foot.addView(Ui.small(act, detail, Ui.MUTED), Ui.weight(1));
        String eid = J.str(e, "id");
        foot.addView(Ui.smallButton(act, "Manage", Ui.OUTLINE, R.drawable.ic_settings, v -> act.push(new ElectionScreen(eid, "manage"))));
        c.addView(foot);
        c.setOnClickListener(v -> act.push(new ElectionScreen(eid, "manage")));
        return c;
    }

    void registrar(LinearLayout body, List<JSONObject> els) {
        int pending = 0, roster = 0;
        List<JSONObject> active = new ArrayList<>(), other = new ArrayList<>();
        for (JSONObject e : els) {
            pending += J.num(e, "requested", 0);
            roster += J.num(e, "roster", 0);
            String s = J.str(e, "status");
            if (s.equals("registration") || s.equals("voting")) {
                active.add(e);
            } else {
                other.add(e);
            }
        }
        body.addView(Widgets.grid2(act,
            Widgets.stat(act, "Pending requests", String.valueOf(pending), "waiting for a credential", R.drawable.ic_mail, pending > 0 ? Ui.WARNING : Ui.PRIMARY, pending > 0 ? Ui.WARNING50 : Ui.PRIMARY50),
            Widgets.stat(act, "Roster entries", String.valueOf(roster), "encrypted credentials", R.drawable.ic_users, Ui.ACCENT, Ui.ACCENT50)));
        Ui.space(body, 18);
        list(body, "Registration desks", "Registration stays open while voting runs.", active, R.drawable.ic_clipboard, "No election is registering voters",
            "Registration opens after the trustees finish the key ceremony.");
        list(body, "Other elections", null, other, R.drawable.ic_box, "Nothing else", "");
    }

    void trustee(LinearLayout body, List<JSONObject> els) {
        List<JSONObject> mine = new ArrayList<>(), openSeats = new ArrayList<>(), approve = new ArrayList<>();
        int seats = 0;
        for (JSONObject e : els) {
            JSONObject me = J.o(e, "me");
            JSONArray s = me == null ? new JSONArray() : J.a(me, "seats");
            if (s.length() > 0) {
                mine.add(e);
                seats += s.length();
                if (J.str(e, "status").equals("closed")) {
                    approve.add(e);
                }
            }
            if (J.str(e, "status").equals("ceremony") && J.num(e, "trustees_joined", 0) < J.num(e, "n_trustees", 3)) {
                openSeats.add(e);
            }
        }
        body.addView(Widgets.grid2(act,
            Widgets.stat(act, "Your seats", String.valueOf(seats), mine.size() + " elections", R.drawable.ic_key, Ui.PRIMARY, Ui.PRIMARY50),
            Widgets.stat(act, "Tallies to approve", String.valueOf(approve.size()), "voting closed", R.drawable.ic_unlock, approve.isEmpty() ? Ui.PRIMARY : Ui.VIOLET, approve.isEmpty() ? Ui.PRIMARY50 : Ui.VIOLET50)));
        Ui.space(body, 14);
        if (!approve.isEmpty()) {
            JSONObject first = approve.get(0);
            LinearLayout need = Ui.card(act);
            need.setBackground(Ui.round(act, Ui.WARNING50, 16, 0xFFF6DDB0, 1));
            need.addView(Ui.cardTitle(act, R.drawable.ic_unlock, "Your approval is needed", null));
            Ui.space(need, 4);
            need.addView(Ui.muted(act, J.str(first, "title") + " closed and needs " + J.num(first, "threshold", 2) + " trustees to release their decryption shares."));
            Ui.space(need, 10);
            String fid = J.str(first, "id");
            need.addView(Ui.button(act, "Review and approve", Ui.PRIMARY_BTN, R.drawable.ic_unlock, v -> act.push(new ElectionScreen(fid, "trustees"))), Ui.match());
            body.addView(need);
            Ui.space(body, 14);
        }
        list(body, "Key ceremonies with open seats", "Take a seat. When all three seats are filled the threshold key is generated.", openSeats,
            R.drawable.ic_key, "No open seats", "All key ceremonies are complete.");
        list(body, "Elections where you hold a seat", null, mine, R.drawable.ic_key, "You hold no seats yet", "Join a key ceremony to become a trustee.");
    }

    void auditor(LinearLayout body, List<JSONObject> els) {
        int boards = 0, ballots = 0, published = 0, tallying = 0;
        for (JSONObject e : els) {
            if (J.num(e, "ballots", 0) > 0) {
                boards++;
            }
            ballots += J.num(e, "ballots", 0);
            if (J.str(e, "status").equals("published")) {
                published++;
            }
            if (J.str(e, "status").equals("tallying")) {
                tallying++;
            }
        }
        body.addView(Widgets.grid2(act,
            Widgets.stat(act, "Bulletin boards", String.valueOf(boards), Ui.plural(ballots, "ballot", "ballots") + " to verify", R.drawable.ic_box, Ui.PRIMARY, Ui.PRIMARY50),
            Widgets.stat(act, "Published results", String.valueOf(published), tallying + " tallies running", R.drawable.ic_chart, Ui.SUCCESS, Ui.SUCCESS50)));
        Ui.space(body, 14);
        LinearLayout track = Ui.card(act);
        track.addView(Ui.cardTitle(act, R.drawable.ic_search, "Track a ballot", null));
        Ui.space(track, 8);
        EditText in = Ui.input(act, "Paste a tracker", true);
        track.addView(in);
        Ui.space(track, 8);
        track.addView(Ui.button(act, "Find", Ui.OUTLINE, R.drawable.ic_search, v -> act.push(new TrackScreen(in.getText().toString(), false))), Ui.match());
        body.addView(track);
        Ui.space(body, 18);
        list(body, "Audit desk", "Open an election and run the full audit.", els, R.drawable.ic_scan, "Nothing to audit", "");
    }
}

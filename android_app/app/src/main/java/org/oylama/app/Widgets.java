package org.oylama.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.oylama.node.J;

import java.util.List;
import java.util.Locale;

final class Widgets {
    private Widgets() {
    }

    static String voterState(OylamaApp app, JSONObject e, String[] out) {
        String stored = app.credential(J.str(e, "id"));
        JSONObject me = J.o(e, "me");
        JSONObject v = me == null ? null : J.o(me, "voter");
        if (stored != null) {
            out[0] = app.voted(J.str(e, "id")) ? "Voted · credential on this device" : "Credential on this device";
            return "ready";
        }
        if (v == null) {
            out[0] = J.str(e, "eligibility").equals("list") ? "Eligibility list" : "Not registered";
            return "none";
        }
        String st = J.str(v, "status");
        switch (st) {
            case "requested":
                out[0] = "Waiting for registrar";
                break;
            case "eligible":
                out[0] = "Eligible, not issued";
                break;
            case "issued":
                out[0] = "Sealed credential waiting";
                break;
            case "opened":
                out[0] = "Credential opened";
                break;
            default:
                out[0] = st;
        }
        return st;
    }

    static int[] chipColors(String key) {
        switch (key) {
            case "ready":
                return new int[]{Ui.SUCCESS50, Ui.SUCCESS};
            case "requested":
            case "eligible":
                return new int[]{Ui.WARNING50, Ui.WARNING};
            case "issued":
            case "opened":
                return new int[]{Ui.PRIMARY50, Ui.PRIMARY600};
            default:
                return new int[]{Ui.SURFACE2, Ui.MUTED};
        }
    }

    static void chipRow(MainActivity a, LinearLayout parent, List<View> chips) {
        Ui.Flow r = Ui.flow(a, 6);
        for (View c : chips) {
            r.addView(c);
        }
        parent.addView(r, Ui.match());
    }

    static View capacity(MainActivity a, int cap, int roster, int ballots) {
        LinearLayout l = Ui.col(a);
        float c = Math.max(1, cap);
        l.addView(Ui.meter(a, new float[]{roster / c, ballots / c}, new int[]{Ui.SKY, Ui.PRIMARY}, 9));
        Ui.space(l, 6);
        LinearLayout legend = Ui.row(a);
        legend.addView(dot(a, Ui.SKY));
        legend.addView(Ui.small(a, " " + roster + " roster   ", Ui.MUTED));
        legend.addView(dot(a, Ui.PRIMARY));
        legend.addView(Ui.small(a, " " + ballots + " ballots   ", Ui.MUTED));
        legend.addView(dot(a, Ui.SURFACE3));
        legend.addView(Ui.small(a, " " + Math.max(0, cap - roster - ballots) + " free of " + cap, Ui.MUTED));
        l.addView(legend);
        return l;
    }

    static View dot(MainActivity a, int color) {
        View v = new View(a);
        v.setBackground(Ui.round(a, color, 3, 0, 0));
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 10), Ui.dp(a, 10)));
        return v;
    }

    static View electionCard(MainActivity a, JSONObject e) {
        OylamaApp app = a.app;
        String eid = J.str(e, "id");
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.ripple(a, Ui.SURFACE, 16, Ui.BORDER, 1));
        card.setClipToOutline(true);
        JSONArray cands = J.a(e, "candidates");
        int c1 = Ui.parseColor(J.str(cands.optJSONObject(0), "color"), Ui.PRIMARY);
        int c2 = cands.length() > 1 ? Ui.parseColor(J.str(cands.optJSONObject(1), "color"), c1) : c1;
        View strip = new View(a);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{c1, c2});
        strip.setBackground(g);
        card.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 6)));
        LinearLayout body = Ui.col(a);
        int p = Ui.dp(a, 16);
        body.setPadding(p, Ui.dp(a, 14), p, Ui.dp(a, 12));
        LinearLayout top = Ui.row(a);
        TextView org = Ui.small(a, J.str(e, "organization").isEmpty() ? "Oylama" : J.str(e, "organization"), Ui.MUTED);
        top.addView(org, Ui.weight(1));
        top.addView(Ui.statusChip(a, J.str(e, "status")));
        body.addView(top);
        Ui.space(body, 8);
        body.addView(Ui.text(a, J.str(e, "title"), 17, Ui.TEXT, true));
        String desc = J.str(e, "description");
        if (!desc.isEmpty()) {
            Ui.space(body, 4);
            TextView d = Ui.muted(a, desc);
            d.setMaxLines(2);
            d.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(d);
        }
        java.util.ArrayList<View> chips = new java.util.ArrayList<>();
        String status = J.str(e, "status");
        if (app.role().equals("voter") && (status.equals("registration") || status.equals("voting"))) {
            String[] label = new String[1];
            String key = voterState(app, e, label);
            int[] col = chipColors(key);
            chips.add(Ui.chip(a, label[0], col[0], col[1]));
        }
        JSONObject t = J.o(e, "tally");
        if (status.equals("tallying") && t != null && J.num(t, "total", 0) > 0) {
            chips.add(Ui.chip(a, Math.round(100.0 * J.num(t, "done", 0) / J.num(t, "total", 1)) + "% · " + Ui.duration(J.dbl(t, "eta", 0)) + " left", Ui.VIOLET50, Ui.VIOLET));
        }
        if (status.equals("published")) {
            JSONObject r = J.o(e, "results");
            if (r != null) {
                JSONArray counts = J.a(r, "counts");
                int max = 0, winners = 0, wi = -1;
                for (int i = 0; i < counts.length(); i++) {
                    max = Math.max(max, counts.optInt(i));
                }
                for (int i = 0; i < counts.length(); i++) {
                    if (counts.optInt(i) == max && max > 0) {
                        winners++;
                        wi = i;
                    }
                }
                String label = winners == 1 ? J.str(cands.optJSONObject(wi), "name") + " leads" : Ui.plural(J.num(r, "total_valid", 0), "valid vote", "valid votes");
                chips.add(Ui.chip(a, label, Ui.PRIMARY50, Ui.PRIMARY600));
            }
        }
        if (status.equals("ceremony")) {
            chips.add(Ui.chip(a, J.num(e, "trustees_joined", 0) + "/" + J.num(e, "n_trustees", 3) + " trustees", Ui.WARNING50, Ui.WARNING));
        }
        if (status.equals("closed")) {
            chips.add(Ui.chip(a, J.num(e, "approvals", 0) + "/" + J.num(e, "threshold", 2) + " approvals", Ui.WARNING50, Ui.WARNING));
        }
        if (!chips.isEmpty()) {
            Ui.space(body, 10);
            chipRow(a, body, chips);
        }
        Ui.space(body, 12);
        body.addView(capacity(a, J.num(e, "capacity", 8), J.num(e, "roster", 0), J.num(e, "ballots", 0)));
        card.addView(body);
        LinearLayout foot = Ui.row(a);
        foot.setBackgroundColor(Ui.SURFACE2);
        foot.setPadding(p, Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
        FrameLayout avatars = new FrameLayout(a);
        int n = Math.min(5, cands.length());
        for (int i = 0; i < n; i++) {
            JSONObject c = cands.optJSONObject(i);
            TextView av = Ui.avatar(a, J.str(c, "name"), Ui.parseColor(J.str(c, "color"), Ui.PRIMARY), 30);
            GradientDrawable bg = (GradientDrawable) av.getBackground();
            bg.setStroke(Ui.dp(a, 2), Color.WHITE);
            FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30));
            fl.leftMargin = Ui.dp(a, 23) * i;
            avatars.addView(av, fl);
        }
        foot.addView(avatars, new LinearLayout.LayoutParams(Ui.dp(a, 23) * Math.max(0, n - 1) + Ui.dp(a, 30), Ui.dp(a, 30)));
        foot.addView(Ui.small(a, "  " + cands.length() + " options", Ui.MUTED), Ui.weight(1));
        foot.addView(primaryAction(a, e));
        card.addView(foot);
        card.setOnClickListener(v -> a.push(new ElectionScreen(eid, null)));
        return card;
    }

    static View primaryAction(MainActivity a, JSONObject e) {
        OylamaApp app = a.app;
        String eid = J.str(e, "id");
        String status = J.str(e, "status");
        switch (app.role()) {
            case "voter": {
                String[] lbl = new String[1];
                String key = voterState(app, e, lbl);
                if (status.equals("voting") && key.equals("ready")) {
                    return Ui.smallButton(a, app.voted(eid) ? "Vote again" : "Vote", Ui.PRIMARY_BTN, R.drawable.ic_ballot, v -> a.push(new BoothScreen(eid, null)));
                }
                if (status.equals("registration") || status.equals("voting")) {
                    return Ui.smallButton(a, "Credential", Ui.OUTLINE, R.drawable.ic_key, v -> a.push(new CredentialScreen(eid)));
                }
                if (status.equals("published")) {
                    return Ui.smallButton(a, "Results", Ui.OUTLINE, R.drawable.ic_chart, v -> a.push(new ElectionScreen(eid, "results")));
                }
                if (status.equals("tallying")) {
                    return Ui.smallButton(a, "Watch tally", Ui.OUTLINE, R.drawable.ic_activity, v -> a.push(new TallyScreen(eid)));
                }
                return Ui.smallButton(a, "Details", Ui.GHOST, 0, v -> a.push(new ElectionScreen(eid, null)));
            }
            case "authority":
                return Ui.smallButton(a, "Manage", Ui.OUTLINE, R.drawable.ic_settings, v -> a.push(new ElectionScreen(eid, "manage")));
            case "registrar":
                return Ui.smallButton(a, "Voters", Ui.OUTLINE, R.drawable.ic_clipboard, v -> a.push(new ElectionScreen(eid, "voters")));
            case "trustee":
                if (status.equals("ceremony")) {
                    return Ui.smallButton(a, "Key ceremony", Ui.PRIMARY_BTN, R.drawable.ic_key, v -> a.push(new ElectionScreen(eid, "trustees")));
                }
                if (status.equals("closed")) {
                    return Ui.smallButton(a, "Approve", Ui.PRIMARY_BTN, R.drawable.ic_unlock, v -> a.push(new ElectionScreen(eid, "trustees")));
                }
                return Ui.smallButton(a, "Seats", Ui.OUTLINE, R.drawable.ic_key, v -> a.push(new ElectionScreen(eid, "trustees")));
            case "auditor":
                return Ui.smallButton(a, "Audit", Ui.OUTLINE, R.drawable.ic_scan, v -> a.push(new ElectionScreen(eid, "audit")));
            default:
                return Ui.smallButton(a, "Details", Ui.GHOST, 0, v -> a.push(new ElectionScreen(eid, null)));
        }
    }

    static LinearLayout section(MainActivity a, String title, String sub) {
        LinearLayout l = Ui.col(a);
        l.addView(Ui.h2(a, title));
        if (sub != null) {
            Ui.space(l, 2);
            l.addView(Ui.muted(a, sub));
        }
        return l;
    }

    static View stat(MainActivity a, String label, String value, String detail, int icon, int fg, int bg) {
        LinearLayout c = Ui.card(a);
        c.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        LinearLayout top = Ui.row(a);
        top.addView(Ui.small(a, label, Ui.MUTED), Ui.weight(1));
        top.addView(Ui.iconTile(a, icon, fg, bg, 30));
        c.addView(top);
        TextView v = Ui.text(a, value, 24, Ui.TEXT, true);
        c.addView(v);
        c.addView(Ui.small(a, detail, Ui.MUTED));
        return c;
    }

    static LinearLayout grid2(MainActivity a, View... cells) {
        LinearLayout g = Ui.col(a);
        for (int i = 0; i < cells.length; i += 2) {
            LinearLayout r = Ui.row(a);
            r.setGravity(Gravity.TOP);
            r.addView(cells[i], Ui.weight(1));
            r.addView(Ui.gap(a, 10));
            if (i + 1 < cells.length) {
                r.addView(cells[i + 1], Ui.weight(1));
            } else {
                r.addView(new View(a), Ui.weight(1));
            }
            g.addView(r, Ui.match());
            if (i + 2 < cells.length) {
                Ui.space(g, 10);
            }
        }
        return g;
    }

    static View empty(MainActivity a, int icon, String title, String text) {
        LinearLayout c = Ui.card(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(Ui.dp(a, 20), Ui.dp(a, 26), Ui.dp(a, 20), Ui.dp(a, 26));
        c.addView(Ui.iconTile(a, icon, Ui.PRIMARY, Ui.PRIMARY50, 52));
        Ui.space(c, 10);
        TextView t = Ui.h3(a, title);
        t.setGravity(Gravity.CENTER);
        c.addView(t, Ui.match());
        if (text != null && !text.isEmpty()) {
            Ui.space(c, 4);
            TextView d = Ui.muted(a, text);
            d.setGravity(Gravity.CENTER);
            c.addView(d, Ui.match());
        }
        return c;
    }

    static View feed(MainActivity a, JSONArray events, boolean withTitle, int limit) {
        LinearLayout l = Ui.col(a);
        if (events.length() == 0) {
            l.addView(Ui.muted(a, "No activity yet."));
            return l;
        }
        for (int i = 0; i < Math.min(limit, events.length()); i++) {
            JSONObject ev = events.optJSONObject(i);
            LinearLayout r = Ui.row(a);
            r.setGravity(Gravity.TOP);
            r.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
            String kind = J.str(ev, "kind");
            int color = kind.equals("published") ? Ui.SUCCESS : kind.equals("ballot") ? Ui.PRIMARY : kind.equals("decoy") ? Ui.VIOLET : kind.equals("key") ? Ui.WARNING : Ui.ACCENT;
            View d = dot(a, color);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(a, 8), Ui.dp(a, 8));
            dl.topMargin = Ui.dp(a, 6);
            dl.rightMargin = Ui.dp(a, 10);
            r.addView(d, dl);
            LinearLayout col = Ui.col(a);
            String msg = (withTitle && !J.str(ev, "title").isEmpty() ? J.str(ev, "title") + " · " : "") + J.str(ev, "message");
            col.addView(Ui.text(a, msg, 13.5f, Ui.TEXT2, false));
            col.addView(Ui.small(a, Ui.fmtDate(J.dbl(ev, "ts", 0)), Ui.FAINT));
            r.addView(col, Ui.weight(1));
            l.addView(r);
            if (i + 1 < Math.min(limit, events.length())) {
                l.addView(Ui.divider(a));
            }
        }
        return l;
    }

    static View timeline(MainActivity a, JSONObject e) {
        String[] order = {"ceremony", "registration", "voting", "closed", "tallying", "published"};
        String[] labels = {"Key ceremony", "Registration", "Voting", "Closed", "Tally", "Published"};
        String status = J.str(e, "status");
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(status)) {
                idx = i;
            }
        }
        LinearLayout r = Ui.row(a);
        r.setGravity(Gravity.TOP);
        JSONObject phases = J.o(e, "phases");
        for (int i = 0; i < order.length; i++) {
            boolean done = i < idx || status.equals("published");
            boolean now = i == idx && !status.equals("published");
            LinearLayout cell = Ui.col(a);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView circle = Ui.text(a, done ? "✓" : String.valueOf(i + 1), 12.5f, done ? Color.WHITE : now ? Ui.PRIMARY : Ui.FAINT, true);
            circle.setGravity(Gravity.CENTER);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(done ? Ui.PRIMARY : Ui.SURFACE);
            gd.setStroke(Ui.dp(a, 2), done || now ? Ui.PRIMARY : Ui.BORDER_STRONG);
            circle.setBackground(gd);
            cell.addView(circle, new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30)));
            Ui.space(cell, 6);
            TextView lb = Ui.text(a, labels[i], 11.5f, now ? Ui.PRIMARY600 : Ui.TEXT2, now);
            lb.setGravity(Gravity.CENTER);
            cell.addView(lb);
            double ts = phases == null ? 0 : J.dbl(phases, order[i], 0);
            TextView when = Ui.text(a, ts > 0 ? Ui.ago(ts) : " ", 10.5f, Ui.FAINT, false);
            when.setGravity(Gravity.CENTER);
            cell.addView(when);
            r.addView(cell, new LinearLayout.LayoutParams(Ui.dp(a, 84), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return Ui.hscroll(a, r, r.getChildAt(Math.min(idx, r.getChildCount() - 1)));
    }

    static LinearLayout check(MainActivity a, String state, String title, String detail, String time) {
        LinearLayout r = Ui.row(a);
        r.setGravity(Gravity.TOP);
        int p = Ui.dp(a, 10);
        r.setPadding(p, p, p, p);
        int bg = Ui.SURFACE3, fg = Ui.FAINT;
        String glyph = "";
        switch (state) {
            case "done":
                bg = Ui.SUCCESS50;
                fg = Ui.SUCCESS;
                glyph = "✓";
                break;
            case "active":
                bg = Ui.PRIMARY;
                fg = Color.WHITE;
                glyph = "•";
                r.setBackground(Ui.round(a, Ui.PRIMARY50, 12, 0, 0));
                break;
            case "failed":
                bg = Ui.DANGER50;
                fg = Ui.DANGER;
                glyph = "✕";
                break;
            default:
                break;
        }
        TextView ic = Ui.text(a, glyph, 13, fg, true);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(bg);
        ic.setBackground(gd);
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(a, 26), Ui.dp(a, 26)));
        r.addView(Ui.gap(a, 10));
        LinearLayout col = Ui.col(a);
        col.addView(Ui.text(a, title, 14, state.equals("pending") ? Ui.FAINT : Ui.TEXT2, true));
        if (detail != null && !detail.isEmpty()) {
            col.addView(Ui.small(a, detail, Ui.MUTED));
        }
        r.addView(col, Ui.weight(1));
        if (time != null && !time.isEmpty()) {
            r.addView(Ui.small(a, time, Ui.FAINT));
        }
        return r;
    }

    static View candidate(MainActivity a, JSONObject c, String right) {
        LinearLayout row = Ui.row(a);
        row.setBackground(Ui.round(a, Ui.SURFACE, 14, Ui.BORDER, 1));
        row.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        int color = Ui.parseColor(J.str(c, "color"), Ui.PRIMARY);
        View bar = new View(a);
        bar.setBackground(Ui.round(a, color, 3, 0, 0));
        row.addView(bar, new LinearLayout.LayoutParams(Ui.dp(a, 4), Ui.dp(a, 40)));
        row.addView(Ui.gap(a, 10));
        row.addView(Ui.avatar(a, J.str(c, "name"), color, 42));
        row.addView(Ui.gap(a, 12));
        LinearLayout col = Ui.col(a);
        col.addView(Ui.text(a, J.str(c, "name"), 15.5f, Ui.TEXT, true));
        String party = J.str(c, "party");
        col.addView(Ui.small(a, party.isEmpty() ? "Option " + J.num(c, "idx", 0) : party, Ui.MUTED));
        String bio = J.str(c, "bio");
        if (!bio.isEmpty()) {
            TextView b = Ui.small(a, bio, Ui.MUTED);
            b.setPadding(0, Ui.dp(a, 3), 0, 0);
            col.addView(b);
        }
        row.addView(col, Ui.weight(1));
        if (right != null) {
            row.addView(Ui.text(a, right, 18, Ui.TEXT, true));
        }
        return row;
    }

    static String pctText(int v, int total) {
        return total <= 0 ? "0%" : String.format(Locale.US, "%.1f%%", 100.0 * v / total);
    }
}

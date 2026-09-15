package org.oylama.app;

import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.oylama.node.J;

import java.util.ArrayList;
import java.util.List;

public class CreateScreen extends Screen {
    static final String[] COLORS = {"#4F46E5", "#0EA5A4", "#DB2777", "#EA580C", "#059669", "#7C3AED", "#2563EB", "#CA8A04"};
    String title = "", org = "", desc = "", voters = "";
    int capacity = 8;
    boolean list;
    boolean autoIssue = true;
    final List<String[]> cands = new ArrayList<>();
    final String[] trustees = {"", "", ""};
    double spo = 0.45;

    public CreateScreen() {
        cands.add(new String[]{"", "", COLORS[0]});
        cands.add(new String[]{"", "", COLORS[1]});
    }

    @Override
    public String title() {
        return "New election";
    }

    @Override
    public String tab() {
        return "new";
    }

    @Override
    protected void load() {
        if (!app.role().equals("authority")) {
            LinearLayout body = page();
            body.addView(Ui.notice(act, R.drawable.ic_info, "Only an election authority can create elections. Switch roles from the account menu.", Ui.WARNING, Ui.WARNING50));
            show(body);
            return;
        }
        fetch(() -> app.obj("GET", "/api/system", null), res -> {
            spo = J.dbl((JSONObject) res, "seconds_per_opening", 0.45);
            draw();
        });
    }

    TextWatcher watch(java.util.function.Consumer<String> set) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                set.accept(s.toString());
            }

            public void afterTextChanged(Editable s) {
            }
        };
    }

    EditText in(String hint, String value, java.util.function.Consumer<String> set) {
        EditText e = Ui.input(act, hint, false);
        e.setText(value);
        e.addTextChangedListener(watch(set));
        return e;
    }

    void draw() {
        LinearLayout body = page();
        body.addView(Ui.eyebrow(act, "New election"));
        Ui.space(body, 4);
        body.addView(Ui.h1(act, "Create an election"));
        Ui.space(body, 4);
        body.addView(Ui.muted(act, "Define the ballot, the size of the encrypted board and who may vote. The trustees generate the key right after you create it."));
        Ui.space(body, 16);
        LinearLayout basics = Ui.card(act);
        basics.addView(Ui.cardTitle(act, R.drawable.ic_info, "Basics", null));
        Ui.space(basics, 12);
        basics.addView(Ui.field(act, "Title", in("City Council Election 2026", title, s -> title = s)));
        Ui.space(basics, 10);
        basics.addView(Ui.field(act, "Organization", in("Oylama City", org, s -> org = s)));
        Ui.space(basics, 10);
        EditText d = Ui.area(act, "What is this election about?");
        d.setText(desc);
        d.addTextChangedListener(watch(s -> desc = s));
        basics.addView(Ui.field(act, "Description", d));
        body.addView(basics);
        Ui.space(body, 14);
        LinearLayout opts = Ui.card(act);
        opts.addView(Ui.cardTitle(act, R.drawable.ic_users, "Candidates and options", Ui.small(act, cands.size() + " of 8", Ui.MUTED)));
        Ui.space(opts, 12);
        for (int i = 0; i < cands.size(); i++) {
            final int idx = i;
            String[] c = cands.get(i);
            LinearLayout row = Ui.row(act);
            TextView swatch = new TextView(act);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.parseColor(c[2], Ui.PRIMARY));
            g.setCornerRadius(Ui.dp(act, 10));
            swatch.setBackground(g);
            swatch.setOnClickListener(v -> {
                int k = 0;
                for (int j = 0; j < COLORS.length; j++) {
                    if (COLORS[j].equalsIgnoreCase(c[2])) {
                        k = j;
                    }
                }
                c[2] = COLORS[(k + 1) % COLORS.length];
                draw();
            });
            row.addView(swatch, new LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 46)));
            row.addView(Ui.gap(act, 8));
            LinearLayout col = Ui.col(act);
            col.addView(in(i == 0 ? "Name, e.g. Leyla Aydın" : "Name", c[0], s -> c[0] = s));
            Ui.space(col, 6);
            col.addView(in("Party or description", c[1], s -> c[1] = s));
            row.addView(col, Ui.weight(1));
            row.addView(Ui.gap(act, 4));
            android.widget.ImageView del = Ui.icon(act, R.drawable.ic_trash, cands.size() <= 2 ? Ui.BORDER_STRONG : Ui.MUTED, 20);
            del.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8));
            del.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 38), Ui.dp(act, 38)));
            del.setOnClickListener(v -> {
                if (cands.size() > 2) {
                    cands.remove(idx);
                    draw();
                }
            });
            row.addView(del);
            opts.addView(row, Ui.match());
            Ui.space(opts, 12);
        }
        LinearLayout ob = Ui.row(act);
        TextView add = Ui.button(act, "Add option", Ui.OUTLINE, R.drawable.ic_plus, v -> {
            if (cands.size() < 8) {
                cands.add(new String[]{"", "", COLORS[cands.size() % COLORS.length]});
                draw();
            }
        });
        Ui.enable(add, cands.size() < 8);
        ob.addView(add, Ui.weight(1));
        ob.addView(Ui.gap(act, 8));
        ob.addView(Ui.button(act, "Yes / No", Ui.SOFT, R.drawable.ic_zap, v -> {
            cands.clear();
            cands.add(new String[]{"Yes", "In favour", "#10B981"});
            cands.add(new String[]{"No", "Against", "#EF4444"});
            draw();
        }), Ui.weight(1));
        opts.addView(ob, Ui.match());
        body.addView(opts);
        Ui.space(body, 14);
        LinearLayout board = Ui.card(act);
        board.addView(Ui.cardTitle(act, R.drawable.ic_layers, "Encrypted board", null));
        Ui.space(board, 12);
        LinearLayout seg = Ui.row(act);
        seg.setBackground(Ui.round(act, Ui.SURFACE2, 12, Ui.BORDER, 1));
        seg.setPadding(Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3));
        for (int c : new int[]{4, 8, 16}) {
            boolean on = c == capacity;
            TextView t = Ui.text(act, c + " entries", 13.5f, on ? Ui.TEXT : Ui.MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(act, 9), 0, Ui.dp(act, 9));
            if (on) {
                t.setBackground(Ui.round(act, Ui.SURFACE, 9, Ui.BORDER, 1));
            }
            t.setOnClickListener(v -> {
                capacity = c;
                draw();
            });
            seg.addView(t, Ui.weight(1));
        }
        board.addView(seg, Ui.match());
        Ui.space(board, 10);
        int plan = capacity == 4 ? 206 : capacity == 8 ? 342 : 529;
        int stages = capacity == 4 ? 3 : capacity == 8 ? 6 : 10;
        board.addView(Ui.muted(act, "Registered voters and ballots, including revotes and ballots with fake credentials, share one packed ciphertext. " + capacity
            + " entries fit, for example, " + capacity / 2 + " registered voters with " + capacity / 2 + " ballots. The Batcher sorting network has " + stages + " stages."));
        Ui.space(board, 10);
        board.addView(Ui.notice(act, R.drawable.ic_clock, "A full board of " + capacity + " entries takes about " + Ui.duration(plan * spo + 8) + " to tally "
            + (app.backend().isLocal() ? "on this phone" : "on the server") + ": " + plan + " masked openings with verified shares from two trustees.", Ui.PRIMARY, Ui.PRIMARY50));
        body.addView(board);
        Ui.space(body, 14);
        LinearLayout elig = Ui.card(act);
        elig.addView(Ui.cardTitle(act, R.drawable.ic_clipboard, "Eligibility and registration", null));
        Ui.space(elig, 12);
        LinearLayout seg2 = Ui.row(act);
        seg2.setBackground(Ui.round(act, Ui.SURFACE2, 12, Ui.BORDER, 1));
        seg2.setPadding(Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3));
        String[][] modes = {{"open", "Open to any voter"}, {"list", "Eligibility list"}};
        for (String[] m : modes) {
            boolean on = (m[0].equals("list")) == list;
            TextView t = Ui.text(act, m[1], 13.5f, on ? Ui.TEXT : Ui.MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(act, 9), 0, Ui.dp(act, 9));
            if (on) {
                t.setBackground(Ui.round(act, Ui.SURFACE, 9, Ui.BORDER, 1));
            }
            t.setOnClickListener(v -> {
                list = m[0].equals("list");
                draw();
            });
            seg2.addView(t, Ui.weight(1));
        }
        elig.addView(seg2, Ui.match());
        if (list) {
            Ui.space(elig, 10);
            EditText va = Ui.area(act, "alice@example.com, bob@example.com");
            va.setText(voters);
            va.addTextChangedListener(watch(s -> voters = s));
            elig.addView(Ui.field(act, "Eligible voters", va));
        }
        Ui.space(elig, 10);
        Switch sw = new Switch(act);
        sw.setText("Issue credentials automatically");
        sw.setTextColor(Ui.TEXT2);
        sw.setChecked(autoIssue);
        sw.setOnCheckedChangeListener((b, on) -> autoIssue = on);
        elig.addView(sw, Ui.match());
        elig.addView(Ui.small(act, "When off, a registrar approves every request by hand.", Ui.MUTED));
        body.addView(elig);
        Ui.space(body, 14);
        LinearLayout tr = Ui.card(act);
        tr.addView(Ui.cardTitle(act, R.drawable.ic_key, "Trustees", Ui.chip(act, "2 of 3 threshold", Ui.SURFACE2, Ui.MUTED)));
        Ui.space(tr, 6);
        tr.addView(Ui.muted(act, "Optionally reserve seats for specific trustees. Empty seats can be taken by any trustee or filled with demo trustees."));
        for (int i = 0; i < 3; i++) {
            final int k = i;
            Ui.space(tr, 10);
            tr.addView(Ui.field(act, "Seat " + (i + 1), in("any trustee", trustees[i], s -> trustees[k] = s)));
        }
        body.addView(tr);
        Ui.space(body, 16);
        TextView go = Ui.button(act, "Create election", Ui.PRIMARY_BTN, R.drawable.ic_check, null);
        go.setOnClickListener(v -> {
            JSONArray cj = new JSONArray();
            for (String[] c : cands) {
                if (!c[0].trim().isEmpty()) {
                    cj.put(J.obj("name", c[0].trim(), "party", c[1].trim(), "color", c[2]));
                }
            }
            JSONArray tj = new JSONArray();
            for (String t : trustees) {
                tj.put(J.obj("email", t.trim()));
            }
            JSONObject payload = J.obj("title", title, "organization", org, "description", desc, "candidates", cj, "capacity", capacity,
                "eligibility", list ? "list" : "open", "voters", voters, "auto_issue", autoIssue, "trustees", tj);
            action(go, "POST", "/api/elections", payload, r -> {
                Ui.toast(act, "Election created. Next: the key ceremony.");
                act.setRoot(new HomeScreen());
                act.push(new ElectionScreen(J.str(o(r), "id"), "manage"));
            });
        });
        body.addView(go, Ui.match());
        show(body);
    }
}

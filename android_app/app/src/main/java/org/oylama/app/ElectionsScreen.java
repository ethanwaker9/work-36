package org.oylama.app;

import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.oylama.node.J;

import java.util.List;

public class ElectionsScreen extends Screen {
    private String filter = "all";
    private JSONArray data;

    static final String[][] FILTERS = {{"all", "All"}, {"ceremony", "Key ceremony"}, {"registration", "Registration"}, {"voting", "Voting"},
        {"closed", "Closed"}, {"tallying", "Tallying"}, {"published", "Published"}};

    @Override
    public String title() {
        return "Elections";
    }

    @Override
    public String subtitle() {
        return app.backend().isLocal() ? "On this device" : app.backend().label();
    }

    @Override
    public String tab() {
        return "elections";
    }

    @Override
    protected void load() {
        fetch(() -> app.arr("GET", "/api/elections", null), res -> {
            data = (JSONArray) res;
            draw();
        });
    }

    void draw() {
        LinearLayout body = page();
        List<JSONObject> els = J.list(data);
        LinearLayout chips = Ui.row(act);
        for (String[] f : FILTERS) {
            int n = 0;
            for (JSONObject e : els) {
                if (f[0].equals("all") || J.str(e, "status").equals(f[0])) {
                    n++;
                }
            }
            boolean on = f[0].equals(filter);
            TextView chip = Ui.text(act, f[1] + "  " + n, 13, on ? Ui.PRIMARY600 : Ui.TEXT2, on);
            chip.setBackground(Ui.ripple(act, on ? Ui.PRIMARY50 : Ui.SURFACE, 99, on ? Ui.PRIMARY : Ui.BORDER, 1));
            chip.setPadding(Ui.dp(act, 14), Ui.dp(act, 8), Ui.dp(act, 14), Ui.dp(act, 8));
            chip.setOnClickListener(v -> {
                filter = f[0];
                draw();
            });
            chips.addView(chip);
            chips.addView(Ui.gap(act, 8));
        }
        body.addView(Ui.hscroll(act, chips));
        Ui.space(body, 14);
        int shown = 0;
        for (JSONObject e : els) {
            if (filter.equals("all") || J.str(e, "status").equals(filter)) {
                body.addView(Widgets.electionCard(act, e));
                Ui.space(body, 12);
                shown++;
            }
        }
        if (shown == 0) {
            body.addView(Widgets.empty(act, R.drawable.ic_search, "No elections match", filter.equals("all") ? "Nothing here yet." : "Nothing is in this phase."));
        }
        if (app.role().equals("authority")) {
            Ui.space(body, 4);
            body.addView(Ui.button(act, "New election", Ui.PRIMARY_BTN, R.drawable.ic_plus, v -> act.openTab("new")), Ui.match());
        }
        show(body);
    }
}

package org.oylama.app;

import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.oylama.node.J;

public class TrackScreen extends Screen {
    String query;
    final boolean receiptsTab;
    LinearLayout results;

    public TrackScreen(String tracker, boolean receiptsTab) {
        this.query = tracker == null ? "" : tracker;
        this.receiptsTab = receiptsTab;
    }

    @Override
    public String title() {
        return receiptsTab ? "My receipts" : "Track a ballot";
    }

    @Override
    public String tab() {
        return receiptsTab ? "receipts" : "track";
    }

    @Override
    protected void load() {
        LinearLayout body = page();
        body.addView(Ui.muted(act, "A tracker is a SHAKE256 digest of the ballot's ciphertexts and proof challenges. It proves your ballot is on the board and says nothing about how you voted."));
        Ui.space(body, 14);
        LinearLayout find = Ui.card(act);
        find.addView(Ui.cardTitle(act, R.drawable.ic_search, "Find a ballot", null));
        Ui.space(find, 10);
        EditText in = Ui.input(act, "Paste a tracker", true);
        in.setText(query);
        find.addView(in);
        Ui.space(find, 10);
        find.addView(Ui.button(act, "Find ballot", Ui.PRIMARY_BTN, R.drawable.ic_search, v -> search(in.getText().toString())), Ui.match());
        results = Ui.col(act);
        find.addView(results);
        body.addView(find);
        Ui.space(body, 14);
        JSONArray receipts = app.receipts();
        LinearLayout rc = Ui.card(act);
        rc.addView(Ui.cardTitle(act, R.drawable.ic_receipt, "Receipts on this device", receipts.length() == 0 ? null
            : Ui.smallButton(act, "Clear", Ui.GHOST, R.drawable.ic_trash, v -> new AlertDialog.Builder(act).setTitle("Clear all receipts?")
            .setMessage("Trackers stored on this device will be removed. Ballots stay on the boards.").setNegativeButton("Cancel", null)
            .setPositiveButton("Clear", (d, w) -> {
                app.clearReceipts();
                refresh();
            }).show())));
        Ui.space(rc, 6);
        if (receipts.length() == 0) {
            rc.addView(Ui.muted(act, "Every ballot you cast from this device leaves a receipt here."));
        }
        for (int i = 0; i < receipts.length(); i++) {
            JSONObject r = receipts.optJSONObject(i);
            rc.addView(Ui.divider(act));
            LinearLayout row = Ui.row(act);
            row.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
            LinearLayout col = Ui.col(act);
            col.addView(Ui.text(act, J.str(r, "title"), 14, Ui.TEXT, true));
            col.addView(Ui.mono(act, Ui.groupHex(J.str(r, "tracker").substring(0, 20), 4) + "…", 12, Ui.MUTED));
            col.addView(Ui.small(act, "position " + (J.num(r, "position", 0) + 1) + " · " + Ui.ago(J.dbl(r, "posted", 0)), Ui.FAINT));
            row.addView(col, Ui.weight(1));
            String tracker = J.str(r, "tracker");
            row.addView(Ui.smallButton(act, "Copy", Ui.GHOST, R.drawable.ic_copy, v -> Ui.copy(act, "Tracker", tracker)));
            row.addView(Ui.smallButton(act, "Check", Ui.OUTLINE, 0, v -> {
                in.setText(tracker);
                search(tracker);
            }));
            rc.addView(row);
        }
        body.addView(rc);
        Ui.space(body, 14);
        LinearLayout note = Ui.card(act);
        note.addView(Ui.cardTitle(act, R.drawable.ic_info, "Receipts and coercion", null));
        Ui.space(note, 6);
        note.addView(Ui.muted(act, "A receipt shows that a ballot reached the board, not whether it counts. A ballot made with a fake credential produces an equally valid-looking receipt, so nobody can use receipts to check how or whether you really voted."));
        body.addView(note);
        show(body);
        if (!query.isEmpty()) {
            search(query);
        }
    }

    void search(String text) {
        query = text;
        String clean = text.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        results.removeAllViews();
        Ui.space(results, 12);
        if (clean.length() < 8) {
            results.addView(Ui.notice(act, R.drawable.ic_info, "Enter at least 8 hexadecimal characters.", Ui.WARNING, Ui.WARNING50));
            return;
        }
        results.addView(Ui.muted(act, "Searching…"));
        app.run(() -> app.arr("GET", "/api/track/" + clean, null), rows -> {
            results.removeAllViews();
            Ui.space(results, 12);
            if (rows.length() == 0) {
                results.addView(Ui.notice(act, R.drawable.ic_alert, "No ballot with this tracker. Check that you copied it completely.", Ui.DANGER, Ui.DANGER50));
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                LinearLayout c = Ui.col(act);
                c.setBackground(Ui.round(act, Ui.SUCCESS50, 12, 0xFFBDE9D6, 1));
                c.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
                c.addView(Ui.text(act, "✓ Found on the bulletin board of " + J.str(r, "title"), 14, Ui.TEXT, true));
                c.addView(Ui.small(act, "Position " + (J.num(r, "position", 0) + 1) + " · posted " + Ui.fmtDate(J.dbl(r, "posted", 0)) + " · " + Ui.statusLabel(J.str(r, "status")), Ui.MUTED));
                c.addView(Ui.mono(act, Ui.groupHex(J.str(r, "tracker"), 4), 12, Ui.TEXT2));
                Ui.space(c, 8);
                String eid = J.str(r, "election");
                c.addView(Ui.smallButton(act, "Open the board", Ui.OUTLINE, R.drawable.ic_box, v -> act.push(new ElectionScreen(eid, "board"))), Ui.wrap());
                results.addView(c);
                Ui.space(results, 8);
            }
        }, e -> {
            results.removeAllViews();
            Ui.space(results, 12);
            results.addView(Ui.notice(act, R.drawable.ic_alert, message(e), Ui.DANGER, Ui.DANGER50));
        });
    }
}

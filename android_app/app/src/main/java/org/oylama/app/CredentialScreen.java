package org.oylama.app;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.oylama.crypto.Codec;
import org.oylama.node.J;

public class CredentialScreen extends Screen {
    final String eid;
    JSONObject e;
    JSONObject c;
    boolean reveal;
    String fake;
    boolean fakeReveal = true;

    public CredentialScreen(String eid) {
        this.eid = eid;
    }

    @Override
    public String title() {
        return "Your credential";
    }

    @Override
    public String subtitle() {
        return e == null ? null : J.str(e, "title");
    }

    @Override
    protected void load() {
        fetch(() -> new Object[]{app.obj("GET", "/api/elections/" + eid, null), app.obj("GET", "/api/elections/" + eid + "/credential", null)}, res -> {
            Object[] r = (Object[]) res;
            e = (JSONObject) r[0];
            c = (JSONObject) r[1];
            act.chrome(this);
            draw();
        });
    }

    @Override
    public void onShow() {
        every(4000, () -> {
            if (c != null && J.str(c, "status").equals("requested")) {
                refresh();
            }
        });
    }

    void draw() {
        LinearLayout body = page();
        String status = J.str(e, "status");
        boolean regOpen = status.equals("registration") || status.equals("voting");
        String stored = app.credential(eid);
        if (!app.role().equals("voter")) {
            body.addView(Ui.notice(act, R.drawable.ic_info, "Credentials belong to voters. Switch to the voter role from the account menu to request one.", Ui.WARNING, Ui.WARNING50));
        } else if (stored != null) {
            body.addView(credCard(stored, reveal, "Roster entry " + (J.isNull(c, "roster_pos") ? "" : "#" + (J.num(c, "roster_pos", 0) + 1)) + " · stored on this device", false));
            Ui.space(body, 12);
            if (status.equals("voting")) {
                body.addView(Ui.button(act, "Vote with this credential", Ui.PRIMARY_BTN, R.drawable.ic_ballot, v -> act.replace(new BoothScreen(eid, null))), Ui.match());
                Ui.space(body, 8);
            }
            body.addView(Ui.button(act, "Forget on this device", Ui.GHOST, R.drawable.ic_trash, v -> new AlertDialog.Builder(act)
                .setTitle("Forget the credential on this device?")
                .setMessage("You can open the sealed envelope again later to restore it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", (d, w) -> {
                    app.forgetCredential(eid);
                    refresh();
                }).show()), Ui.match());
            Ui.space(body, 10);
            body.addView(Ui.notice(act, R.drawable.ic_shield, "Keep this credential private. Anyone who has it can vote for you, but you can always vote again later and only your last ballot counts. If you are pressured, give away a fake credential instead.", Ui.PRIMARY, Ui.PRIMARY50));
        } else {
            String st = J.str(c, "status");
            if (st.equals("none") || st.equals("eligible")) {
                LinearLayout card = Ui.card(act);
                card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                card.addView(Ui.iconTile(act, R.drawable.ic_mail, Ui.PRIMARY, Ui.PRIMARY50, 56));
                Ui.space(card, 10);
                TextView t = Ui.h2(act, st.equals("eligible") ? "You are on the eligibility list" : "Request your voting credential");
                t.setGravity(android.view.Gravity.CENTER);
                card.addView(t, Ui.match());
                Ui.space(card, 6);
                TextView d = Ui.muted(act, "The registrar generates a random credential, adds its encryption to the public roster and seals the credential to this account's device key.");
                d.setGravity(android.view.Gravity.CENTER);
                card.addView(d, Ui.match());
                Ui.space(card, 14);
                if (regOpen) {
                    TextView req = Ui.button(act, "Request credential", Ui.PRIMARY_BTN, R.drawable.ic_send, null);
                    req.setOnClickListener(v -> action(req, "POST", "/api/elections/" + eid + "/credential/request", new JSONObject(), r -> {
                        Ui.toast(act, J.str(o(r), "status").equals("issued") ? "The registrar sealed your credential" : "Request sent to the registrar");
                        refresh();
                    }));
                    card.addView(req, Ui.match());
                } else {
                    card.addView(Ui.statusChip(act, status));
                    Ui.space(card, 6);
                    card.addView(Ui.small(act, status.equals("ceremony") ? "Registration opens after the key ceremony." : "Registration has closed.", Ui.MUTED));
                }
                body.addView(card);
            } else if (st.equals("requested")) {
                body.addView(Widgets.empty(act, R.drawable.ic_clock, "Waiting for the registrar", "Your request from " + Ui.ago(J.dbl(c, "requested", 0)) + " is in the registrar's queue. This page updates when your sealed credential arrives."));
            } else {
                JSONObject env = J.o(c, "envelope");
                LinearLayout card = Ui.card(act);
                card.setBackground(Ui.round(act, 0xFFFAFBFF, 18, Ui.BORDER_STRONG, 1.5f));
                LinearLayout top = Ui.row(act);
                top.addView(Ui.iconTile(act, R.drawable.ic_mail, Ui.PRIMARY, Ui.PRIMARY50, 44));
                top.addView(Ui.gap(act, 12));
                LinearLayout col = Ui.col(act);
                col.addView(Ui.h3(act, "A sealed credential is waiting"));
                col.addView(Ui.small(act, "Sealed " + Ui.fmtDate(J.dbl(env, "sealed", 0)) + " for device key " + J.str(env, "device_key"), Ui.MUTED));
                top.addView(col, Ui.weight(1));
                card.addView(top);
                Ui.space(card, 10);
                card.addView(Ui.chip(act, J.str(env, "kem") + " + " + J.str(env, "aead"), Ui.PRIMARY50, Ui.PRIMARY600));
                Ui.space(card, 10);
                card.addView(Ui.kv(act, "Encapsulation", J.str(env, "kem_ct_preview") + "…"));
                card.addView(Ui.kv(act, "Ciphertext", J.num(env, "blob_bytes", 0) + " bytes"));
                card.addView(Ui.kv(act, "Roster entry", "#" + (J.num(c, "roster_pos", 0) + 1) + " · " + J.str(c, "roster_digest").substring(0, 20)));
                Ui.space(card, 12);
                TextView open = Ui.button(act, "Open on this device", Ui.PRIMARY_BTN, R.drawable.ic_unlock, null);
                open.setOnClickListener(v -> action(open, "POST", "/api/elections/" + eid + "/credential/open", new JSONObject(), r -> {
                    app.saveCredential(eid, J.str(o(r), "credential"));
                    Ui.toast(act, "Envelope opened. Your credential is stored on this device.");
                    refresh();
                }));
                card.addView(open, Ui.match());
                Ui.space(card, 8);
                card.addView(Ui.small(act, "Decapsulation uses this account's ML-KEM-768 device key. In this demo the node keeps the device key for convenience.", Ui.FAINT));
                body.addView(card);
            }
        }
        Ui.space(body, 16);
        LinearLayout shield = Ui.card(act);
        shield.addView(Ui.cardTitle(act, R.drawable.ic_mask, "Coercion shield: fake credentials", Ui.chip(act, "under pressure", Ui.WARNING50, Ui.WARNING)));
        Ui.space(shield, 8);
        shield.addView(Ui.muted(act, "If someone forces you to reveal your credential or to vote in front of them, generate a fake credential here. It has exactly the same format as a real one and nothing on the bulletin board can distinguish them. Ballots cast with it are accepted and then removed during the encrypted cleansing. Nothing about fake credentials is stored."));
        Ui.space(shield, 12);
        if (fake != null) {
            shield.addView(credCard(fake, fakeReveal, "Hand this over instead of your real one", true));
            Ui.space(shield, 10);
            shield.addView(Ui.button(act, "Generate another", Ui.OUTLINE, R.drawable.ic_refresh, v -> {
                fake = Codec.randomCredentialText();
                fakeReveal = true;
                draw();
            }), Ui.match());
            if (status.equals("voting")) {
                Ui.space(shield, 8);
                String f = fake;
                shield.addView(Ui.button(act, "Cast a ballot with it", Ui.OUTLINE, R.drawable.ic_ballot, v -> act.replace(new BoothScreen(eid, f))), Ui.match());
            }
        } else {
            shield.addView(Ui.button(act, "Generate a fake credential", Ui.ACCENT_BTN, R.drawable.ic_mask, v -> {
                fake = Codec.randomCredentialText();
                fakeReveal = true;
                draw();
            }), Ui.match());
        }
        body.addView(shield);
        Ui.space(body, 16);
        LinearLayout how = Ui.card(act);
        how.addView(Ui.cardTitle(act, R.drawable.ic_info, "How your credential works", null));
        Ui.space(how, 8);
        how.addView(Widgets.check(act, "done", "Real credentials", "Match one encrypted entry of the roster; a fake one matches none.", null));
        how.addView(Widgets.check(act, "done", "Encrypted cleansing", "All ballots and roster entries are sorted by encrypted credential; only the last ballot directly followed by a roster entry is kept.", null));
        how.addView(Widgets.check(act, "done", "Nothing leaks", "Nobody, including the authority and the trustees, learns which ballots were removed or why.", null));
        if (c != null && !J.str(c, "status").equals("none")) {
            Ui.space(how, 8);
            how.addView(Ui.kv(act, "Status", J.str(c, "status")));
            if (!J.isNull(c, "requested")) {
                how.addView(Ui.kv(act, "Requested", Ui.fmtDate(J.dbl(c, "requested", 0))));
            }
            if (!J.isNull(c, "issued")) {
                how.addView(Ui.kv(act, "Sealed", Ui.fmtDate(J.dbl(c, "issued", 0))));
            }
            if (!J.isNull(c, "opened")) {
                how.addView(Ui.kv(act, "Opened", Ui.fmtDate(J.dbl(c, "opened", 0))));
            }
        }
        body.addView(how);
        show(body);
    }

    View credCard(String text, boolean shown, String sub, boolean isFake) {
        LinearLayout card = Ui.col(act);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF312E81, 0xFF4F46E5, 0xFF0EA5A4});
        g.setCornerRadius(Ui.dp(act, 18));
        card.setBackground(g);
        int p = Ui.dp(act, 18);
        card.setPadding(p, p, p, p);
        card.setElevation(Ui.dp(act, 4));
        LinearLayout top = Ui.row(act);
        top.addView(Ui.icon(act, R.drawable.ic_key, Color.WHITE, 16));
        top.addView(Ui.gap(act, 6));
        top.addView(Ui.text(act, "Oylama voting credential", 12.5f, Color.WHITE, true), Ui.weight(1));
        top.addView(Ui.text(act, "128-bit", 12, 0xCCFFFFFF, false));
        card.addView(top);
        Ui.space(card, 18);
        String shownText = shown ? text : text.replaceAll("[0-9A-F]", "•");
        if (shownText.length() == 39) {
            shownText = shownText.substring(0, 19) + "\n" + shownText.substring(20);
        }
        TextView t = Ui.text(act, shownText, 19, Color.WHITE, true);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.06f);
        t.setLineSpacing(Ui.dp(act, 4), 1);
        card.addView(t);
        Ui.space(card, 14);
        card.addView(Ui.text(act, sub, 12, 0xDDFFFFFF, false));
        Ui.space(card, 12);
        LinearLayout row = Ui.row(act);
        TextView rv = Ui.smallButton(act, shown ? "Hide" : "Reveal", Ui.GHOST, shown ? R.drawable.ic_eyeoff : R.drawable.ic_eye, v -> {
            if (isFake) {
                fakeReveal = !fakeReveal;
            } else {
                reveal = !reveal;
            }
            draw();
        });
        style(rv);
        row.addView(rv);
        row.addView(Ui.gap(act, 8));
        TextView cp = Ui.smallButton(act, "Copy", Ui.GHOST, R.drawable.ic_copy, v -> Ui.copy(act, "Credential", text));
        style(cp);
        row.addView(cp);
        card.addView(row);
        return card;
    }

    void style(TextView b) {
        b.setTextColor(Color.WHITE);
        b.setBackground(Ui.ripple(act, 0x29FFFFFF, 10, 0x40FFFFFF, 1));
        if (b.getCompoundDrawables()[0] != null) {
            b.getCompoundDrawables()[0].setTint(Color.WHITE);
        }
    }
}

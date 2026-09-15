package org.oylama.app;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.oylama.node.J;

public class LoginScreen extends Screen {
    private String role = "voter";
    private final TextView[] roleTiles = new TextView[5];
    private TextView roleDesc;
    private TextView submit;
    private EditText email;

    static final String[][] PROVIDERS = {
        {"google", "Google", "alex.morgan@gmail.com", "sam.rivera@gmail.com"},
        {"microsoft", "Microsoft", "jordan.lee@outlook.com", "casey.kim@hotmail.com"},
        {"apple", "Apple", "taylor.brooks@icloud.com", "hidden-relay-7f2@privaterelay.appleid.com"},
        {"passkey", "Passkey", "this.device@oylama.demo"}
    };

    static final String[][] QUICK = {
        {"voter", "alice@oylama.demo", "Alice Johnson", "Voter with a credential"},
        {"voter", "", "", "New voter"},
        {"authority", "authority@oylama.demo", "Election Authority", "Authority"},
        {"registrar", "registrar@oylama.demo", "City Registrar", "Registrar"},
        {"trustee", "ayla.demir@trustees.oylama.demo", "Prof. Ayla Demir", "Trustee"},
        {"auditor", "auditor@oylama.demo", "Independent Auditor", "Auditor"}
    };

    @Override
    public String title() {
        return "Sign in";
    }

    @Override
    public boolean chrome() {
        return false;
    }

    @Override
    protected void load() {
        LinearLayout body = Ui.col(act);
        body.addView(hero());
        LinearLayout wrap = Ui.col(act);
        int p = Ui.dp(act, 16);
        wrap.setPadding(p, p, p, Ui.dp(act, 28));
        wrap.addView(signInCard());
        Ui.space(wrap, 14);
        wrap.addView(serverCard());
        Ui.space(wrap, 14);
        TextView foot = Ui.small(act, "Demo mode · any email or username with any password signs you in. Google, Microsoft, Apple and passkey sign-in are simulated.", Ui.FAINT);
        foot.setGravity(Gravity.CENTER);
        wrap.addView(foot);
        body.addView(wrap);
        show(body);
    }

    View hero() {
        LinearLayout h = Ui.col(act);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF4F46E5, 0xFF312E81, 0xFF0E7C7B});
        float r = Ui.dp(act, 28);
        g.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        h.setBackground(g);
        int p = Ui.dp(act, 22);
        h.setPadding(p, Ui.dp(act, 26), p, Ui.dp(act, 26));
        LinearLayout brand = Ui.row(act);
        ImageView logo = new ImageView(act);
        logo.setImageResource(R.drawable.ic_logo);
        brand.addView(logo, new LinearLayout.LayoutParams(Ui.dp(act, 48), Ui.dp(act, 48)));
        brand.addView(Ui.text(act, "Oylama", 22, Color.WHITE, true));
        h.addView(brand);
        Ui.space(h, 18);
        TextView eb = Ui.text(act, "ONLINE ELECTIONS FOR THE POST-QUANTUM ERA", 11, 0xFFC7D2FE, true);
        eb.setLetterSpacing(0.08f);
        h.addView(eb);
        Ui.space(h, 8);
        TextView title = Ui.text(act, "Vote from anywhere. No one can read, buy or force your vote.", 27, Color.WHITE, true);
        title.setLetterSpacing(-0.02f);
        h.addView(title);
        Ui.space(h, 10);
        h.addView(Ui.text(act, "Oylama encrypts every ballot with lattice cryptography, lets a pressured voter hand over a fake credential that looks exactly like the real one, and removes invalid ballots while everything stays encrypted.", 14.5f, 0xE6FFFFFF, false));
        Ui.space(h, 16);
        View[] feats = {
            feat(R.drawable.ic_shield, "Post-quantum", "Packed lattice encryption, ML-KEM-768, ML-DSA-65"),
            feat(R.drawable.ic_mask, "Coercion-resistant", "Fake credentials cast ballots that never count"),
            feat(R.drawable.ic_key, "Threshold trust", "Two of three trustees must cooperate"),
            feat(R.drawable.ic_scan, "Verifiable", "Every proof can be re-checked by anyone")
        };
        h.addView(Widgets.grid2(act, feats));
        return h;
    }

    View feat(int icon, String t, String d) {
        LinearLayout c = Ui.col(act);
        c.setBackground(Ui.round(act, 0x1FFFFFFF, 14, 0x33FFFFFF, 1));
        c.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
        LinearLayout r = Ui.row(act);
        r.addView(Ui.icon(act, icon, Color.WHITE, 16));
        r.addView(Ui.gap(act, 6));
        r.addView(Ui.text(act, t, 13, Color.WHITE, true));
        c.addView(r);
        c.addView(Ui.text(act, d, 11.5f, 0xCCFFFFFF, false));
        return c;
    }

    View signInCard() {
        LinearLayout c = Ui.card(act);
        c.setPadding(Ui.dp(act, 18), Ui.dp(act, 18), Ui.dp(act, 18), Ui.dp(act, 18));
        c.addView(Ui.h2(act, "Sign in"));
        Ui.space(c, 4);
        c.addView(Ui.muted(act, "Pick the part you play in the election. You can switch roles later from the account menu."));
        Ui.space(c, 14);
        LinearLayout roles = Ui.row(act);
        for (int i = 0; i < Roles.ALL.length; i++) {
            final String r = Roles.ALL[i];
            TextView tile = Ui.text(act, Roles.label(r), 11.5f, Ui.MUTED, true);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 9));
            tile.setCompoundDrawablePadding(Ui.dp(act, 4));
            tile.setOnClickListener(v -> setRole(r));
            roleTiles[i] = tile;
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i > 0) {
                lp.leftMargin = Ui.dp(act, 6);
            }
            roles.addView(tile, lp);
        }
        c.addView(roles, Ui.match());
        Ui.space(c, 10);
        roleDesc = Ui.muted(act, "");
        roleDesc.setMinLines(2);
        c.addView(roleDesc);
        Ui.space(c, 12);
        email = Ui.input(act, "you@example.com", false);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        c.addView(Ui.field(act, "Email or username", email));
        Ui.space(c, 10);
        EditText password = Ui.input(act, "Anything works in demo mode", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setTypeface(Typeface.DEFAULT);
        c.addView(Ui.field(act, "Password", password));
        Ui.space(c, 14);
        submit = Ui.button(act, "Sign in as Voter", Ui.PRIMARY_BTN, R.drawable.ic_right, v -> login(email.getText().toString(), "password", null, null, submit));
        c.addView(submit, Ui.match());
        setRole(role);
        c.addView(divider("or continue with"));
        LinearLayout oauth1 = Ui.row(act);
        LinearLayout oauth2 = Ui.row(act);
        for (int i = 0; i < PROVIDERS.length; i++) {
            final String[] pr = PROVIDERS[i];
            int icon = pr[0].equals("google") ? R.drawable.ic_brand_google : pr[0].equals("microsoft") ? R.drawable.ic_brand_microsoft
                : pr[0].equals("apple") ? R.drawable.ic_brand_apple : R.drawable.ic_finger;
            TextView b = Ui.button(act, pr[1], Ui.OUTLINE, 0, v -> oauth(pr));
            android.graphics.drawable.Drawable d = act.getDrawable(icon).mutate();
            int s = Ui.dp(act, 18);
            d.setBounds(0, 0, s, s);
            if (pr[0].equals("passkey")) {
                d.setTint(Ui.TEXT2);
            }
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(Ui.dp(act, 8));
            LinearLayout target = i < 2 ? oauth1 : oauth2;
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i % 2 == 1) {
                lp.leftMargin = Ui.dp(act, 10);
            }
            target.addView(b, lp);
        }
        c.addView(oauth1, Ui.match());
        Ui.space(c, 10);
        c.addView(oauth2, Ui.match());
        c.addView(divider("one-click demo accounts"));
        Ui.Flow quick = Ui.flow(act, 8);
        for (int i = 0; i < QUICK.length; i++) {
            final String[] q = QUICK[i];
            TextView chip = Ui.text(act, q[3], 12.5f, Ui.TEXT2, false);
            chip.setBackground(Ui.ripple(act, Ui.SURFACE2, 99, Ui.BORDER_STRONG, 1));
            chip.setPadding(Ui.dp(act, 12), Ui.dp(act, 7), Ui.dp(act, 12), Ui.dp(act, 7));
            android.graphics.drawable.Drawable d = act.getDrawable(Roles.icon(q[0])).mutate();
            int s = Ui.dp(act, 15);
            d.setBounds(0, 0, s, s);
            d.setTint(Ui.PRIMARY);
            chip.setCompoundDrawables(d, null, null, null);
            chip.setCompoundDrawablePadding(Ui.dp(act, 6));
            chip.setOnClickListener(v -> login(q[1], "demo", q[2], q[0], chip));
            quick.addView(chip);
        }
        c.addView(quick, Ui.match());
        return c;
    }

    View divider(String label) {
        LinearLayout r = Ui.row(act);
        r.setPadding(0, Ui.dp(act, 16), 0, Ui.dp(act, 12));
        r.addView(Ui.divider(act), new LinearLayout.LayoutParams(0, Ui.dp(act, 1), 1));
        TextView t = Ui.small(act, "  " + label + "  ", Ui.FAINT);
        r.addView(t);
        r.addView(Ui.divider(act), new LinearLayout.LayoutParams(0, Ui.dp(act, 1), 1));
        return r;
    }

    View serverCard() {
        LinearLayout c = Ui.card(act);
        LinearLayout r = Ui.row(act);
        r.addView(Ui.iconTile(act, R.drawable.ic_server, app.backend().isLocal() ? Ui.ACCENT : Ui.SKY, app.backend().isLocal() ? Ui.ACCENT50 : Ui.SKY50, 40));
        r.addView(Ui.gap(act, 12));
        LinearLayout col = Ui.col(act);
        col.addView(Ui.text(act, "Election server", 14.5f, Ui.TEXT, true));
        col.addView(Ui.small(act, app.backend().isLocal() ? "This device: a complete Oylama node runs on your phone" : "Oylama server at " + app.backend().label(), Ui.MUTED));
        r.addView(col, Ui.weight(1));
        r.addView(Ui.smallButton(act, "Change", Ui.OUTLINE, 0, v -> act.push(new SettingsScreen())));
        c.addView(r);
        if (app.seeding) {
            Ui.space(c, 10);
            c.addView(Ui.notice(act, R.drawable.ic_sparkles, "Preparing demo elections with real keys, credentials and ballots…", Ui.PRIMARY, Ui.PRIMARY50));
        }
        return c;
    }

    void setRole(String r) {
        role = r;
        for (int i = 0; i < Roles.ALL.length; i++) {
            boolean on = Roles.ALL[i].equals(r);
            TextView t = roleTiles[i];
            t.setTextColor(on ? Ui.PRIMARY600 : Ui.MUTED);
            t.setBackground(Ui.ripple(act, on ? Ui.PRIMARY50 : Ui.SURFACE, 12, on ? Ui.PRIMARY : Ui.BORDER, on ? 1.5f : 1));
            android.graphics.drawable.Drawable d = act.getDrawable(Roles.icon(Roles.ALL[i])).mutate();
            int s = Ui.dp(act, 22);
            d.setBounds(0, 0, s, s);
            d.setTint(on ? Ui.PRIMARY : Ui.MUTED);
            t.setCompoundDrawables(null, d, null, null);
        }
        roleDesc.setText(Roles.description(r));
        submit.setText("Sign in as " + Roles.label(r));
    }

    void login(String emailText, String provider, String name, String forcedRole, View button) {
        JSONObject body = J.obj("email", emailText, "password", "demo", "role", forcedRole == null ? role : forcedRole, "provider", provider, "name", name);
        Busy.on(button);
        app.run(() -> app.backend().call("POST", "/api/auth/login", body, null), res -> {
            Busy.off(button);
            JSONObject s = (JSONObject) res;
            app.saveSession(s);
            Ui.toast(act, "Welcome, " + J.str(J.o(s, "user"), "name"));
            act.goHome();
        }, e -> {
            Busy.off(button);
            Ui.toast(act, message(e));
        });
    }

    void oauth(String[] pr) {
        LinearLayout v = Ui.col(act);
        int p = Ui.dp(act, 20);
        v.setPadding(p, p, p, Ui.dp(act, 8));
        ImageView logo = new ImageView(act);
        int icon = pr[0].equals("google") ? R.drawable.ic_brand_google : pr[0].equals("microsoft") ? R.drawable.ic_brand_microsoft
            : pr[0].equals("apple") ? R.drawable.ic_brand_apple : R.drawable.ic_finger;
        logo.setImageResource(icon);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40));
        ll.gravity = Gravity.CENTER_HORIZONTAL;
        v.addView(logo, ll);
        Ui.space(v, 8);
        TextView t = Ui.h3(act, pr[0].equals("passkey") ? "Use a passkey" : "Sign in with " + pr[1]);
        t.setGravity(Gravity.CENTER);
        v.addView(t, Ui.match());
        TextView s = Ui.small(act, "to continue to Oylama as " + Roles.label(role).toLowerCase(), Ui.MUTED);
        s.setGravity(Gravity.CENTER);
        v.addView(s, Ui.match());
        Ui.space(v, 14);
        AlertDialog[] dlg = new AlertDialog[1];
        String typed = email.getText().toString().trim();
        java.util.ArrayList<String> accounts = new java.util.ArrayList<>();
        if (typed.contains("@")) {
            accounts.add(typed);
        }
        for (int i = 2; i < pr.length; i++) {
            accounts.add(pr[i]);
        }
        for (String acc : accounts) {
            LinearLayout row = Ui.row(act);
            row.setBackground(Ui.ripple(act, Ui.SURFACE, 12, Ui.BORDER, 1));
            row.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
            String display = acc.split("@")[0].replaceAll("[._\\-]+", " ");
            row.addView(Ui.avatar(act, display, 0, 34));
            row.addView(Ui.gap(act, 10));
            LinearLayout col = Ui.col(act);
            StringBuilder nice = new StringBuilder();
            for (String w : display.split(" ")) {
                if (!w.isEmpty()) {
                    nice.append(nice.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
                }
            }
            col.addView(Ui.text(act, nice.toString(), 14, Ui.TEXT, true));
            col.addView(Ui.small(act, acc, Ui.MUTED));
            row.addView(col, Ui.weight(1));
            row.addView(Ui.icon(act, R.drawable.ic_chevron, Ui.FAINT, 16));
            row.setOnClickListener(x -> {
                if (dlg[0] != null) {
                    dlg[0].dismiss();
                }
                login(acc, pr[0], null, null, submit);
            });
            LinearLayout.LayoutParams rl = Ui.match();
            rl.bottomMargin = Ui.dp(act, 8);
            v.addView(row, rl);
        }
        Ui.space(v, 6);
        EditText other = Ui.input(act, "name@example.com", false);
        v.addView(Ui.field(act, "Use another account", other));
        Ui.space(v, 10);
        TextView go = Ui.button(act, "Continue", Ui.PRIMARY_BTN, 0, x -> {
            if (dlg[0] != null) {
                dlg[0].dismiss();
            }
            login(other.getText().toString(), pr[0], null, null, submit);
        });
        v.addView(go, Ui.match());
        Ui.space(v, 8);
        TextView note = Ui.small(act, "Simulated sign-in for the demo. No data leaves this Oylama node.", Ui.FAINT);
        note.setGravity(Gravity.CENTER);
        v.addView(note, Ui.match());
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.addView(v);
        dlg[0] = new AlertDialog.Builder(act).setView(sv).create();
        dlg[0].show();
    }
}

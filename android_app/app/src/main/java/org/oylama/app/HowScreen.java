package org.oylama.app;

import android.widget.LinearLayout;

public class HowScreen extends Screen {
    @Override
    public String title() {
        return "How Oylama works";
    }

    @Override
    public String tab() {
        return "how";
    }

    @Override
    protected void load() {
        LinearLayout body = page();
        body.addView(Ui.eyebrow(act, "How it works"));
        Ui.space(body, 4);
        body.addView(Ui.h1(act, "How Oylama keeps an online election honest"));
        Ui.space(body, 6);
        body.addView(Ui.muted(act, "Oylama is a post-quantum, coercion-resistant voting scheme. Ballots are lattice ciphertexts, voters can defeat a coercer with fake credentials, and the tally removes invalid ballots without ever decrypting them."));
        Ui.space(body, 16);
        LinearLayout who = Ui.card(act);
        who.addView(Ui.cardTitle(act, R.drawable.ic_users, "Who does what", null));
        Ui.space(who, 8);
        String[][] people = {
            {"authority", "Election authority", "Creates elections and moves them through the phases, and may post decoy ballots."},
            {"registrar", "Registrar", "Admits voters, builds the encrypted roster, signs it with ML-DSA-65 and seals credentials with ML-KEM-768."},
            {"trustee", "Trustees", "Take seats in the key ceremony and release their shares for the tally. Two of three are needed."},
            {"voter", "Voters", "Get credentials, encrypt and prove ballots on their device, revote and generate fake credentials."},
            {"auditor", "Auditors", "Inspect boards, rosters and transcripts and rerun every check."}
        };
        for (String[] p : people) {
            LinearLayout r = Ui.row(act);
            r.setGravity(android.view.Gravity.TOP);
            r.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
            r.addView(Ui.iconTile(act, Roles.icon(p[0]), Ui.PRIMARY, Ui.PRIMARY50, 36));
            r.addView(Ui.gap(act, 12));
            LinearLayout col = Ui.col(act);
            col.addView(Ui.text(act, p[1], 14.5f, Ui.TEXT, true));
            col.addView(Ui.small(act, p[2], Ui.MUTED));
            r.addView(col, Ui.weight(1));
            who.addView(r);
        }
        body.addView(who);
        Ui.space(body, 14);
        LinearLayout steps = Ui.card(act);
        steps.addView(Ui.cardTitle(act, R.drawable.ic_list, "The election, step by step", null));
        Ui.space(steps, 8);
        String[][] s = {
            {"Key ceremony", "Three trustees receive a replicated sharing of a packed BGV secret key over Z_q[X]/(X^8192+1). Any two can decrypt, one alone cannot."},
            {"Registration", "The registrar draws a random 128-bit credential for each voter, appends its encryption to the public roster, signs the roster and seals the credential to the voter."},
            {"Voting", "The phone encrypts the chosen option and the credential into two ciphertexts and proves knowledge of both openings with Fiat–Shamir proofs with aborts. The ballot is posted anonymously; the board checks the proofs."},
            {"Cleansing", "All ballots and roster entries are packed into one ciphertext and sorted by encrypted credential with a Batcher network. An entry stays valid only when its successor has the same credential and is the roster record."},
            {"Mixing and decryption", "The cleansed vote slots are shuffled by two mix servers with lattice shuffle proofs, then decrypted by two trustees whose decryption shares carry proofs."},
            {"Audit", "Anyone can re-verify ballot proofs, the roster signature, every decryption share of the cleansing, both shuffles and the final counts."}
        };
        for (int i = 0; i < s.length; i++) {
            steps.addView(Widgets.check(act, "done", (i + 1) + ". " + s[i][0], s[i][1], null));
        }
        body.addView(steps);
        Ui.space(body, 14);
        LinearLayout cr = Ui.card(act);
        cr.addView(Ui.cardTitle(act, R.drawable.ic_mask, "Coercion resistance", null));
        Ui.space(cr, 8);
        cr.addView(Ui.muted(act, "Suppose someone demands your credential or watches you vote. On your credential page you generate a fake credential. It is a uniform 128-bit string, exactly like a real one, so nobody can tell them apart."));
        Ui.space(cr, 8);
        cr.addView(Ui.muted(act, "Ballots made with it are accepted by the board. During the tally they find no matching roster entry and are gated to an empty vote under encryption. Nobody learns that it happened, not even how many such ballots there were, because authorities also post decoys and revotes are removed the same way."));
        Ui.space(cr, 8);
        cr.addView(Ui.muted(act, "Later, with your real credential, you vote as you want. The last valid ballot per credential counts."));
        body.addView(cr);
        Ui.space(body, 14);
        LinearLayout pk = Ui.card(act);
        pk.addView(Ui.cardTitle(act, R.drawable.ic_zap, "Packed evaluation", null));
        Ui.space(pk, 8);
        pk.addView(Ui.muted(act, "With plaintext modulus p = 65537 the ring splits into 8192 slots, so one ciphertext carries 8192 bits. Each multiplication of two encrypted bit vectors is a masked opening: every trustee adds an encryption of a random mask, the quorum decrypts the masked value with verified shares, and the product is rebuilt from public values. One distributed decryption serves 8192 binary gates at once."));
        body.addView(pk);
        Ui.space(body, 14);
        body.addView(Ui.notice(act, R.drawable.ic_info, "Sign-in is simulated: any email or username with any password works, and the Google, Microsoft, Apple and passkey buttons let you pick an account without leaving the node.", Ui.PRIMARY, Ui.PRIMARY50));
        show(body);
    }
}

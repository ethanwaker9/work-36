package org.oylama.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.oylama.node.J;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TallyScreen extends Screen {
    final String eid;
    JSONObject e;
    final List<JSONObject> lines = new ArrayList<>();
    int since;
    String lastStatus;

    public TallyScreen(String eid) {
        this.eid = eid;
    }

    static final class Ring extends View {
        float fraction;
        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();

        Ring(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(Ui.SURFACE3);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float stroke = Math.min(w, h) * 0.085f;
            track.setStrokeWidth(stroke);
            arc.setStrokeWidth(stroke);
            box.set(stroke, stroke, w - stroke, h - stroke);
            arc.setShader(new LinearGradient(0, 0, w, h, 0xFF6366F1, 0xFF0EA5A4, Shader.TileMode.CLAMP));
            canvas.drawArc(box, 0, 360, false, track);
            canvas.drawArc(box, -90, 360 * Math.max(0.004f, fraction), false, arc);
        }
    }

    @Override
    public String title() {
        return "Encrypted tally";
    }

    @Override
    public String subtitle() {
        return e == null ? null : J.str(e, "title");
    }

    @Override
    protected void load() {
        fetch(() -> new Object[]{app.obj("GET", "/api/elections/" + eid, null), app.obj("GET", "/api/elections/" + eid + "/tally?since=" + since, null)}, res -> {
            Object[] r = (Object[]) res;
            e = (JSONObject) r[0];
            absorb((JSONObject) r[1]);
            act.chrome(this);
            draw((JSONObject) r[1]);
        });
    }

    void absorb(JSONObject data) {
        JSONArray log = J.a(data, "log");
        for (int i = 0; i < log.length(); i++) {
            JSONObject l = log.optJSONObject(i);
            if (J.num(l, "id", 0) > since) {
                lines.add(l);
                since = J.num(l, "id", 0);
            }
        }
        while (lines.size() > 400) {
            lines.remove(0);
        }
    }

    @Override
    public void onShow() {
        act.keepScreenOn(true);
        every(1200, () -> {
            if (e == null) {
                return;
            }
            app.run(() -> app.obj("GET", "/api/elections/" + eid + "/tally?since=" + since, null), d -> {
                if (!isShown()) {
                    return;
                }
                absorb(d);
                String st = J.str(d, "status");
                if (!st.equals("tallying") && st.equals(lastStatus)) {
                    return;
                }
                draw(d);
            }, err -> {
            });
        });
    }

    @Override
    public void onHide() {
        act.keepScreenOn(false);
    }

    void draw(JSONObject data) {
        String status = J.str(data, "status");
        lastStatus = status;
        JSONObject t = J.o(data, "tally");
        if (t == null) {
            t = new JSONObject();
        }
        LinearLayout body = page();
        if (!status.equals("tallying") && !status.equals("published") && !status.equals("closed")) {
            body.addView(Widgets.empty(act, R.drawable.ic_clock, "The tally has not started", "Voting must close and the trustees must release their shares first."));
            show(body);
            return;
        }
        int done = J.num(t, "done", 0), total = J.num(t, "total", 0);
        if (status.equals("published")) {
            JSONObject r = J.o(data, "results");
            LinearLayout c = Ui.card(act);
            c.setBackground(Ui.round(act, Ui.PRIMARY50, 18, Ui.PRIMARY100, 1));
            LinearLayout top = Ui.row(act);
            top.addView(Ui.iconTile(act, R.drawable.ic_check, Ui.SUCCESS, Ui.SUCCESS50, 46));
            top.addView(Ui.gap(act, 12));
            LinearLayout col = Ui.col(act);
            col.addView(Ui.h2(act, "Result published"));
            col.addView(Ui.small(act, Ui.plural(J.num(r, "total_valid", 0), "valid vote", "valid votes") + " from " + Ui.plural(J.num(r, "board", 0), "ballot", "ballots") + " · " + J.num(r, "openings", 0)
                + " masked openings · " + Ui.duration(J.dbl(r, "seconds", 0)), Ui.MUTED));
            top.addView(col, Ui.weight(1));
            c.addView(top);
            Ui.space(c, 12);
            c.addView(Ui.button(act, "See the verified result", Ui.PRIMARY_BTN, R.drawable.ic_chart, v -> act.replace(new ElectionScreen(eid, "results"))), Ui.match());
            body.addView(c);
        } else if (status.equals("closed")) {
            LinearLayout c = Ui.card(act);
            boolean failed = J.str(t, "state").equals("failed");
            c.addView(Ui.notice(act, R.drawable.ic_alert, failed ? "The tally stopped: " + J.str(t, "error") : "Waiting for trustee approvals.", failed ? Ui.DANGER : Ui.WARNING, failed ? Ui.DANGER50 : Ui.WARNING50));
            if (app.role().equals("authority") || app.role().equals("trustee")) {
                Ui.space(c, 10);
                TextView b = Ui.button(act, "Approve with demo trustees", Ui.PRIMARY_BTN, R.drawable.ic_unlock, null);
                b.setOnClickListener(v -> action(b, "POST", "/api/elections/" + eid + "/tally/auto", new JSONObject(), r -> {
                    Ui.toast(act, "Tally started");
                    lines.clear();
                    since = 0;
                    refresh();
                }));
                c.addView(b, Ui.match());
            }
            body.addView(c);
        } else {
            LinearLayout c = Ui.card(act);
            LinearLayout top = Ui.row(act);
            FrameLayout ringBox = new FrameLayout(act);
            Ring ring = new Ring(act);
            ring.fraction = total > 0 ? done / (float) total : 0;
            ringBox.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout val = Ui.col(act);
            val.setGravity(Gravity.CENTER);
            TextView pct = Ui.text(act, (total > 0 ? Math.round(100.0 * done / total) : 0) + "%", 24, Ui.TEXT, true);
            pct.setGravity(Gravity.CENTER);
            val.addView(pct);
            TextView of = Ui.small(act, done + " / " + total, Ui.MUTED);
            of.setGravity(Gravity.CENTER);
            val.addView(of);
            ringBox.addView(val, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            top.addView(ringBox, new LinearLayout.LayoutParams(Ui.dp(act, 116), Ui.dp(act, 116)));
            top.addView(Ui.gap(act, 16));
            LinearLayout col = Ui.col(act);
            col.addView(Ui.eyebrow(act, J.str(t, "state").equals("queued") ? "Starting" : "Now"));
            Ui.space(col, 4);
            col.addView(Ui.text(act, J.str(t, "label").isEmpty() ? "Preparing" : J.str(t, "label"), 15, Ui.TEXT, true));
            Ui.space(col, 8);
            col.addView(Ui.small(act, "About " + Ui.duration(J.dbl(t, "eta", 0)) + " left", Ui.MUTED));
            JSONArray q = J.a(t, "quorum");
            StringBuilder qs = new StringBuilder();
            for (int i = 0; i < q.length(); i++) {
                qs.append(i == 0 ? "" : " and ").append(q.optInt(i) + 1);
            }
            col.addView(Ui.small(act, "Quorum: trustees " + qs, Ui.MUTED));
            col.addView(Ui.small(act, Ui.plural(J.num(t, "board", 0), "ballot", "ballots") + ", " + Ui.plural(J.num(t, "roster", 0), "roster entry", "roster entries"), Ui.MUTED));
            top.addView(col, Ui.weight(1));
            c.addView(top);
            Ui.space(c, 12);
            c.addView(Ui.meter(act, new float[]{total > 0 ? done / (float) total : 0}, new int[]{Ui.PRIMARY}, 8));
            if (app.backend().isLocal()) {
                Ui.space(c, 10);
                c.addView(Ui.notice(act, R.drawable.ic_zap, "Running on this phone. Oylama keeps the screen on while you watch and continues in the background with a notification.", Ui.ACCENT, Ui.ACCENT50));
            }
            body.addView(c);
        }
        Ui.space(body, 14);
        JSONObject st = J.o(t, "stats");
        body.addView(Widgets.grid2(act,
            Widgets.stat(act, "Masked openings", String.valueOf(J.num(st, "batches", 0)), "of " + total, R.drawable.ic_unlock, Ui.PRIMARY, Ui.PRIMARY50),
            Widgets.stat(act, "Packed multiplications", String.valueOf(J.num(st, "mults", 0)), (J.num(st, "mults", 0) * 8192) + " binary gates", R.drawable.ic_zap, Ui.VIOLET, Ui.VIOLET50),
            Widgets.stat(act, "Shares verified", String.valueOf(J.num(st, "shares_ok", 0)), "of " + J.num(st, "shares", 0) + " decryption shares", R.drawable.ic_shieldcheck, Ui.SUCCESS, Ui.SUCCESS50),
            Widgets.stat(act, "Mask proofs", String.valueOf(J.num(st, "masks_ok", 0)), "of " + J.num(st, "masks", 0) + " verified", R.drawable.ic_layers, Ui.ACCENT, Ui.ACCENT50)));
        Ui.space(body, 14);
        LinearLayout pipe = Ui.card(act);
        double started = J.dbl(t, "started", 0);
        double finished = J.dbl(t, "finished", 0);
        String el = started > 0 ? "elapsed " + Ui.duration((finished > 0 ? finished : System.currentTimeMillis() / 1000.0) - started) : null;
        pipe.addView(Ui.cardTitle(act, R.drawable.ic_list, "Pipeline", el == null ? null : Ui.small(act, el, Ui.MUTED)));
        Ui.space(pipe, 6);
        boolean failed = J.str(t, "state").equals("failed");
        for (JSONObject s : J.list(J.a(t, "steps"))) {
            String state = J.str(s, "state");
            if (state.equals("active") && failed) {
                state = "failed";
            }
            double s0 = J.dbl(s, "started", 0), s1 = J.dbl(s, "finished", 0);
            String time = s0 > 0 && !state.equals("pending") ? Ui.duration((s1 > 0 ? s1 : state.equals("active") ? System.currentTimeMillis() / 1000.0 : s0) - s0) : null;
            pipe.addView(Widgets.check(act, state, J.str(s, "label"), J.str(s, "detail"), time));
        }
        body.addView(pipe);
        Ui.space(body, 14);
        LinearLayout console = Ui.col(act);
        console.setBackground(Ui.round(act, Ui.INK, 16, 0, 0));
        int p = Ui.dp(act, 14);
        console.setPadding(p, p, p, p);
        LinearLayout head = Ui.row(act);
        head.addView(Ui.icon(act, R.drawable.ic_server, 0xFFCDD3F5, 16));
        head.addView(Ui.gap(act, 8));
        head.addView(Ui.text(act, "Tally log", 13.5f, Color.WHITE, true), Ui.weight(1));
        head.addView(Ui.text(act, lines.size() + " lines", 11.5f, 0xFF6B73A8, false));
        console.addView(head);
        Ui.space(console, 8);
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        int from = Math.max(0, lines.size() - 60);
        for (int i = from; i < lines.size(); i++) {
            JSONObject l = lines.get(i);
            String msg = J.str(l, "message");
            boolean err = J.str(l, "level").equals("error");
            boolean ok = msg.contains("verified") || msg.contains("published") || msg.contains("Verified");
            TextView tv = Ui.text(act, fmt.format(new Date((long) (J.dbl(l, "ts", 0) * 1000))) + "  " + msg, 11.5f, err ? 0xFFFF8E8E : ok ? 0xFF7EE2B8 : 0xFFCDD3F5, false);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 2));
            console.addView(tv);
        }
        if (lines.isEmpty()) {
            console.addView(Ui.text(act, "Waiting for the first log line…", 11.5f, 0xFF6B73A8, false));
        }
        body.addView(console);
        Ui.space(body, 14);
        LinearLayout read = Ui.card(act);
        read.addView(Ui.cardTitle(act, R.drawable.ic_info, "Reading the pipeline", null));
        Ui.space(read, 6);
        read.addView(Ui.muted(act, "Sort: a Batcher network of " + J.num(t, "stages", 0) + " stages orders every entry by encrypted credential, then board position. "
            + "Deduplicate: an entry survives only if the next entry has the same credential and is the roster record. "
            + "Gate: votes of all other entries are multiplied by an encrypted zero. "
            + "Mix and decrypt: the surviving slots are shuffled twice and decrypted by the quorum."));
        body.addView(read);
        show(body);
    }
}

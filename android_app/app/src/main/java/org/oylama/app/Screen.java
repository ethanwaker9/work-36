package org.oylama.app;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.oylama.node.ApiException;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

public abstract class Screen {
    protected MainActivity act;
    protected OylamaApp app;
    private FrameLayout holder;
    private int savedScroll;
    private boolean shown;
    private final Runnable[] ticker = new Runnable[1];

    void attach(MainActivity a) {
        act = a;
        app = a.app;
    }

    public abstract String title();

    public String subtitle() {
        return null;
    }

    public String tab() {
        return null;
    }

    public boolean chrome() {
        return true;
    }

    protected abstract void load();

    public final View build() {
        holder = new FrameLayout(act);
        holder.setBackgroundColor(Ui.BG);
        showLoading();
        load();
        return holder;
    }

    void showLoading() {
        FrameLayout f = new FrameLayout(act);
        ProgressBar pb = new ProgressBar(act);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        f.addView(pb, new FrameLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40), Gravity.CENTER));
        holder.removeAllViews();
        holder.addView(f, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    protected LinearLayout page() {
        LinearLayout body = Ui.col(act);
        int p = Ui.dp(act, 16);
        body.setPadding(p, p, p, Ui.dp(act, 32));
        return body;
    }

    protected void show(LinearLayout body) {
        if (holder == null) {
            return;
        }
        int keep = savedScroll;
        if (holder.getChildCount() > 0 && holder.getChildAt(0) instanceof ScrollView) {
            keep = holder.getChildAt(0).getScrollY();
        }
        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        sv.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.removeAllViews();
        holder.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final int y = keep;
        sv.post(() -> sv.scrollTo(0, y));
        sv.getViewTreeObserver().addOnScrollChangedListener(() -> savedScroll = sv.getScrollY());
    }

    protected void top() {
        savedScroll = 0;
        if (holder != null && holder.getChildCount() > 0 && holder.getChildAt(0) instanceof ScrollView) {
            holder.getChildAt(0).scrollTo(0, 0);
        }
    }

    protected void showView(View v) {
        if (holder == null) {
            return;
        }
        holder.removeAllViews();
        holder.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    protected void showError(Exception e) {
        LinearLayout body = page();
        body.addView(Ui.notice(act, R.drawable.ic_alert, message(e), Ui.DANGER, Ui.DANGER50));
        Ui.space(body, 12);
        Ui.add(body, Ui.button(act, "Try again", Ui.OUTLINE, R.drawable.ic_refresh, v -> refresh()));
        show(body);
    }

    public static String message(Exception e) {
        if (e instanceof ApiException) {
            return e.getMessage();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public void refresh() {
        if (holder != null) {
            load();
        }
    }

    protected <T> void work(Callable<T> fn, Consumer<T> ok) {
        app.run(fn, v -> {
            if (act != null && !act.isFinishing()) {
                ok.accept(v);
            }
        }, e -> {
            if (act == null || act.isFinishing()) {
                return;
            }
            if (e instanceof ApiException && ((ApiException) e).status == 401 && app.session() != null) {
                app.clearSession();
                Ui.toast(act, "Your session ended. Please sign in again.");
                act.showLogin();
                return;
            }
            Ui.toast(act, message(e));
        });
    }

    protected void fetch(Callable<?> fn, Consumer<Object> ok) {
        app.run(fn::call, v -> {
            if (act != null && !act.isFinishing()) {
                ok.accept(v);
            }
        }, e -> {
            if (act == null || act.isFinishing()) {
                return;
            }
            if (e instanceof ApiException && ((ApiException) e).status == 401 && app.session() != null) {
                app.clearSession();
                act.showLogin();
                return;
            }
            showError(e);
        });
    }

    protected void action(View button, String method, String path, JSONObject body, Consumer<Object> ok) {
        Busy.on(button);
        app.run(() -> app.call(method, path, body), v -> {
            Busy.off(button);
            if (act != null && !act.isFinishing()) {
                ok.accept(v);
            }
        }, e -> {
            Busy.off(button);
            if (act == null || act.isFinishing()) {
                return;
            }
            Ui.toast(act, message(e));
        });
    }

    void shown() {
        shown = true;
        onShow();
    }

    void hidden() {
        shown = false;
        if (ticker[0] != null) {
            app.main.removeCallbacks(ticker[0]);
            ticker[0] = null;
        }
        onHide();
    }

    protected boolean isShown() {
        return shown;
    }

    protected void every(long ms, Runnable r) {
        if (ticker[0] != null) {
            app.main.removeCallbacks(ticker[0]);
        }
        Runnable t = new Runnable() {
            @Override
            public void run() {
                if (!shown) {
                    return;
                }
                r.run();
                app.main.postDelayed(this, ms);
            }
        };
        ticker[0] = t;
        app.main.postDelayed(t, ms);
    }

    public void onShow() {
    }

    public void onHide() {
    }

    static JSONObject o(Object v) {
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }

    static JSONArray a(Object v) {
        return v instanceof JSONArray ? (JSONArray) v : new JSONArray();
    }

    static final class Busy {
        static void on(View v) {
            if (v == null) {
                return;
            }
            v.setEnabled(false);
            v.setAlpha(0.6f);
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                t.setTag(R.id.busy_label, t.getText());
                t.setText("Working…");
            }
        }

        static void off(View v) {
            if (v == null) {
                return;
            }
            v.setEnabled(true);
            v.setAlpha(1f);
            if (v instanceof TextView && v.getTag(R.id.busy_label) != null) {
                ((TextView) v).setText((CharSequence) v.getTag(R.id.busy_label));
            }
        }
    }
}

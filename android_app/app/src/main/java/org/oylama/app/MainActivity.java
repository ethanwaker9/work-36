package org.oylama.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.json.JSONObject;
import org.oylama.node.ApiException;
import org.oylama.node.J;

import java.util.ArrayList;

public class MainActivity extends Activity {
    OylamaApp app;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private FrameLayout content;
    private final ArrayList<Screen> stack = new ArrayList<>();

    static final String[][] NAV_VOTER = {{"home", "Home", "home"}, {"elections", "Elections", "ballot"}, {"receipts", "Receipts", "receipt"}, {"track", "Track", "search"}};
    static final String[][] NAV_AUTHORITY = {{"home", "Dashboard", "home"}, {"elections", "Elections", "list"}, {"new", "New", "plus"}, {"how", "How it works", "book"}};
    static final String[][] NAV_REGISTRAR = {{"home", "Dashboard", "home"}, {"elections", "Elections", "list"}, {"track", "Track", "search"}, {"how", "How it works", "book"}};
    static final String[][] NAV_TRUSTEE = {{"home", "Dashboard", "home"}, {"elections", "Elections", "list"}, {"how", "How it works", "book"}};
    static final String[][] NAV_AUDITOR = {{"home", "Dashboard", "home"}, {"elections", "Elections", "list"}, {"track", "Track", "search"}, {"how", "How it works", "book"}};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (OylamaApp) getApplication();
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        topBar = Ui.row(this);
        topBar.setBackgroundColor(Ui.SURFACE);
        topBar.setElevation(Ui.dp(this, 1));
        topBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 60)));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        bottomBar = Ui.row(this);
        bottomBar.setBackgroundColor(Ui.SURFACE);
        bottomBar.setElevation(Ui.dp(this, 8));
        root.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64)));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        boot();
    }

    void boot() {
        showSplash();
        app.ensureSeeded(() -> {
            JSONObject s = app.session();
            if (s == null) {
                showLogin();
                return;
            }
            app.run(() -> app.obj("GET", "/api/auth/me", null), me -> {
                JSONObject ses = app.session();
                if (ses != null) {
                    J.put(ses, "user", me);
                    app.saveSession(ses);
                }
                goHome();
            }, e -> {
                if (e instanceof ApiException && ((ApiException) e).status == 0) {
                    goHome();
                    Ui.toast(this, e.getMessage());
                    return;
                }
                app.clearSession();
                showLogin();
            });
        });
    }

    void showSplash() {
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        LinearLayout l = Ui.col(this);
        l.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        l.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120)));
        TextView name = Ui.h1(this, "Oylama");
        name.setGravity(Gravity.CENTER);
        Ui.add(l, name);
        TextView sub = Ui.muted(this, app.backend().isLocal() ? "Preparing the on-device election node" : "Connecting to " + app.backend().label());
        sub.setGravity(Gravity.CENTER);
        Ui.space(l, 6);
        Ui.add(l, sub);
        content.removeAllViews();
        content.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void showLogin() {
        setRoot(new LoginScreen());
    }

    public void goHome() {
        setRoot(new HomeScreen());
    }

    public void openTab(String tab) {
        switch (tab) {
            case "elections":
                setRoot(new ElectionsScreen());
                break;
            case "receipts":
                setRoot(new TrackScreen(null, true));
                break;
            case "track":
                setRoot(new TrackScreen(null, false));
                break;
            case "new":
                setRoot(new CreateScreen());
                break;
            case "how":
                setRoot(new HowScreen());
                break;
            default:
                goHome();
                break;
        }
    }

    public void setRoot(Screen s) {
        Screen top = top();
        if (top != null) {
            top.hidden();
        }
        stack.clear();
        stack.add(s);
        render();
    }

    public void push(Screen s) {
        Screen top = top();
        if (top != null) {
            top.hidden();
        }
        stack.add(s);
        render();
    }

    public void replace(Screen s) {
        Screen top = top();
        if (top != null) {
            top.hidden();
            stack.remove(stack.size() - 1);
        }
        stack.add(s);
        render();
    }

    public void pop() {
        if (stack.size() <= 1) {
            return;
        }
        Screen top = top();
        top.hidden();
        stack.remove(stack.size() - 1);
        render();
    }

    Screen top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    void render() {
        Screen s = top();
        s.attach(this);
        View v = s.build();
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        chrome(s);
        s.shown();
    }

    public void keepScreenOn(boolean on) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    void chrome(Screen s) {
        boolean on = s.chrome() && app.session() != null;
        topBar.setVisibility(on ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            return;
        }
        topBar.removeAllViews();
        if (stack.size() > 1) {
            ImageView back = Ui.icon(this, R.drawable.ic_left, Ui.TEXT, 24);
            back.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
            back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
            back.setBackground(Ui.ripple(this, 0x00FFFFFF, 22, 0, 0));
            back.setOnClickListener(v -> pop());
            topBar.addView(back);
        } else {
            ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.ic_logo);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
            logo.setLayoutParams(lp);
            topBar.addView(logo);
        }
        LinearLayout titles = Ui.col(this);
        titles.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        TextView t = Ui.h3(this, s.title());
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.add(titles, t);
        String sub = s.subtitle();
        if (sub != null) {
            TextView st = Ui.small(this, sub, Ui.MUTED);
            st.setSingleLine(true);
            st.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Ui.add(titles, st);
        }
        topBar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView mode = Ui.chip(this, app.backend().isLocal() ? "On device" : "Server", app.backend().isLocal() ? Ui.ACCENT50 : Ui.SKY50,
            app.backend().isLocal() ? Ui.ACCENT : Ui.SKY);
        mode.setOnClickListener(v -> push(new SettingsScreen()));
        topBar.addView(mode);
        topBar.addView(Ui.gap(this, 8));
        JSONObject user = app.user();
        TextView av = Ui.avatar(this, J.str(user, "name"), 0, 36);
        av.setOnClickListener(this::menu);
        topBar.addView(av);
        bottomBar.removeAllViews();
        String[][] nav;
        switch (app.role()) {
            case "authority":
                nav = NAV_AUTHORITY;
                break;
            case "registrar":
                nav = NAV_REGISTRAR;
                break;
            case "trustee":
                nav = NAV_TRUSTEE;
                break;
            case "auditor":
                nav = NAV_AUDITOR;
                break;
            default:
                nav = NAV_VOTER;
                break;
        }
        String active = s.tab();
        for (String[] item : nav) {
            LinearLayout cell = Ui.col(this);
            cell.setGravity(Gravity.CENTER);
            boolean on2 = item[0].equals(active);
            int color = on2 ? Ui.PRIMARY : Ui.FAINT;
            LinearLayout pill = Ui.row(this);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
            if (on2) {
                pill.setBackground(Ui.round(this, Ui.PRIMARY50, 99, 0, 0));
            }
            pill.addView(Ui.icon(this, iconRes(item[2]), color, 22));
            cell.addView(pill);
            TextView label = Ui.text(this, item[1], 11.5f, on2 ? Ui.PRIMARY600 : Ui.MUTED, on2);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Ui.dp(this, 3), 0, 0);
            cell.addView(label);
            cell.setBackground(Ui.ripple(this, 0x00FFFFFF, 0, 0, 0));
            cell.setOnClickListener(v -> openTab(item[0]));
            bottomBar.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    int iconRes(String name) {
        int id = getResources().getIdentifier("ic_" + name, "drawable", getPackageName());
        return id == 0 ? R.drawable.ic_info : id;
    }

    void menu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        JSONObject u = app.user();
        m.add(0, 1, 0, J.str(u, "name") + " · " + Roles.label(app.role())).setEnabled(false);
        m.add(0, 2, 1, J.str(u, "email")).setEnabled(false);
        int order = 10;
        for (String r : Roles.ALL) {
            if (!r.equals(app.role())) {
                m.add(1, 100 + order, order, "Switch to " + Roles.label(r));
            }
            order++;
        }
        m.add(2, 3, 50, "Election server");
        m.add(2, 4, 51, "How Oylama works");
        m.add(2, 5, 52, "Sign out");
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id >= 100) {
                switchRole(Roles.ALL[id - 110]);
            } else if (id == 3) {
                push(new SettingsScreen());
            } else if (id == 4) {
                push(new HowScreen());
            } else if (id == 5) {
                signOut();
            }
            return true;
        });
        pm.show();
    }

    void switchRole(String role) {
        JSONObject u = app.user();
        JSONObject body = J.obj("email", J.str(u, "email"), "name", J.str(u, "name"), "provider", J.str(u, "provider"), "role", role);
        app.run(() -> app.backend().call("POST", "/api/auth/login", body, null), res -> {
            app.saveSession((JSONObject) res);
            Ui.toast(this, "Signed in as " + Roles.label(role).toLowerCase());
            goHome();
        }, e -> Ui.toast(this, Screen.message(e)));
    }

    public void signOut() {
        String token = app.token();
        app.run(() -> app.backend().call("POST", "/api/auth/logout", new JSONObject(), token), r -> {
        }, e -> {
        });
        app.clearSession();
        showLogin();
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            pop();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Screen s = top();
        if (s != null) {
            s.hidden();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Screen s = top();
        if (s != null && s.act != null && !s.isShown()) {
            s.shown();
        }
    }

    static int color(String hex) {
        return Ui.parseColor(hex, Color.GRAY);
    }
}

package org.oylama.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Ui {
    public static final int BG = 0xFFF5F6FB;
    public static final int SURFACE = 0xFFFFFFFF;
    public static final int SURFACE2 = 0xFFF1F3F9;
    public static final int SURFACE3 = 0xFFE9ECF5;
    public static final int BORDER = 0xFFE2E5EF;
    public static final int BORDER_STRONG = 0xFFCFD4E3;
    public static final int TEXT = 0xFF0E1330;
    public static final int TEXT2 = 0xFF2C3354;
    public static final int MUTED = 0xFF5F6788;
    public static final int FAINT = 0xFF8B92AD;
    public static final int PRIMARY = 0xFF4F46E5;
    public static final int PRIMARY600 = 0xFF4338CA;
    public static final int PRIMARY50 = 0xFFEEF0FF;
    public static final int PRIMARY100 = 0xFFE0E3FF;
    public static final int ACCENT = 0xFF0EA5A4;
    public static final int ACCENT50 = 0xFFE6F7F7;
    public static final int SUCCESS = 0xFF059669;
    public static final int SUCCESS50 = 0xFFE7F8F1;
    public static final int WARNING = 0xFFC26A06;
    public static final int WARNING50 = 0xFFFFF5E3;
    public static final int DANGER = 0xFFDC2626;
    public static final int DANGER50 = 0xFFFDECEC;
    public static final int VIOLET = 0xFF7C3AED;
    public static final int VIOLET50 = 0xFFF3EDFF;
    public static final int SKY = 0xFF0284C7;
    public static final int SKY50 = 0xFFE6F4FB;
    public static final int INK = 0xFF0D1024;

    public static final int PRIMARY_BTN = 0;
    public static final int OUTLINE = 1;
    public static final int GHOST = 2;
    public static final int DANGER_BTN = 3;
    public static final int ACCENT_BTN = 4;
    public static final int SOFT = 5;
    public static final int SUCCESS_BTN = 6;

    static final int[] PALETTE = {0xFF4F46E5, 0xFF0EA5A4, 0xFF7C3AED, 0xFFDB2777, 0xFFEA580C, 0xFF059669, 0xFF2563EB, 0xFFCA8A04};

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(Context c, int color, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) {
            g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        }
        return g;
    }

    public static Drawable ripple(Context c, int color, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable mask = round(c, Color.WHITE, radiusDp, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(0x220E1330), round(c, color, radiusDp, stroke, strokeDp), mask);
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static <T extends View> T add(ViewGroup parent, T child, LinearLayout.LayoutParams p) {
        parent.addView(child, p);
        return child;
    }

    public static <T extends View> T add(ViewGroup parent, T child) {
        if (parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
            parent.addView(child, match());
        } else {
            parent.addView(child, wrap());
        }
        return child;
    }

    public static View gap(Context c, int dpv) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, dpv), dp(c, dpv)));
        return v;
    }

    public static void space(ViewGroup parent, int dpv) {
        parent.addView(gap(parent.getContext(), dpv));
    }

    public static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, Context c, int l, int t, int r, int b) {
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    public static TextView medium(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static TextView h1(Context c, CharSequence s) {
        TextView t = text(c, s, 26, TEXT, true);
        t.setLetterSpacing(-0.02f);
        return t;
    }

    public static TextView h2(Context c, CharSequence s) {
        TextView t = text(c, s, 19, TEXT, true);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    public static TextView h3(Context c, CharSequence s) {
        return text(c, s, 16, TEXT, true);
    }

    public static TextView body(Context c, CharSequence s) {
        return text(c, s, 14.5f, TEXT2, false);
    }

    public static TextView muted(Context c, CharSequence s) {
        return text(c, s, 13.5f, MUTED, false);
    }

    public static TextView small(Context c, CharSequence s, int color) {
        return text(c, s, 12.5f, color, false);
    }

    public static TextView mono(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    public static TextView eyebrow(Context c, String s) {
        TextView t = text(c, s.toUpperCase(Locale.ROOT), 11.5f, PRIMARY, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    public static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(color));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    public static FrameLayout iconTile(Context c, int res, int fg, int bg, int sizeDp) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(round(c, bg, sizeDp * 0.28f, 0, 0));
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(fg));
        int s = dp(c, sizeDp * 0.52f);
        f.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return f;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(round(c, SURFACE, 16, BORDER, 1));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static LinearLayout cardTitle(Context c, int iconRes, String title, View right) {
        LinearLayout r = row(c);
        if (iconRes != 0) {
            add(r, icon(c, iconRes, TEXT2, 18));
            r.addView(gap(c, 8));
        }
        add(r, h3(c, title), weight(1));
        if (right != null) {
            add(r, right);
        }
        return r;
    }

    public static TextView button(Context c, String label, int kind, int iconRes, View.OnClickListener on) {
        TextView b = medium(c, label, 14.5f, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        int bg, fg, stroke = 0;
        float sw = 0;
        switch (kind) {
            case OUTLINE:
                bg = SURFACE;
                fg = TEXT2;
                stroke = BORDER_STRONG;
                sw = 1;
                break;
            case GHOST:
                bg = 0x00FFFFFF;
                fg = TEXT2;
                break;
            case DANGER_BTN:
                bg = DANGER50;
                fg = DANGER;
                stroke = 0xFFF6CACA;
                sw = 1;
                break;
            case ACCENT_BTN:
                bg = ACCENT;
                fg = Color.WHITE;
                break;
            case SOFT:
                bg = PRIMARY50;
                fg = PRIMARY600;
                break;
            case SUCCESS_BTN:
                bg = SUCCESS;
                fg = Color.WHITE;
                break;
            default:
                bg = PRIMARY;
                fg = Color.WHITE;
                break;
        }
        b.setTextColor(fg);
        b.setBackground(ripple(c, bg, 12, stroke, sw));
        int ph = dp(c, 16), pv = dp(c, 12);
        b.setPadding(ph, pv, ph, pv);
        if (iconRes != 0) {
            Drawable d = c.getDrawable(iconRes).mutate();
            int s = dp(c, 18);
            d.setBounds(0, 0, s, s);
            d.setTint(fg);
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(c, 8));
        }
        b.setOnClickListener(on);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    public static TextView smallButton(Context c, String label, int kind, int iconRes, View.OnClickListener on) {
        TextView b = button(c, label, kind, iconRes, on);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int ph = dp(c, 12), pv = dp(c, 8);
        b.setPadding(ph, pv, ph, pv);
        return b;
    }

    public static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.5f);
    }

    public static TextView chip(Context c, String label, int bg, int fg) {
        TextView t = text(c, label, 12, fg, true);
        t.setBackground(round(c, bg, 99, 0, 0));
        int ph = dp(c, 9), pv = dp(c, 3);
        t.setPadding(ph, pv, ph, pv);
        t.setSingleLine(true);
        return t;
    }

    public static String statusLabel(String s) {
        switch (s) {
            case "ceremony":
                return "Key ceremony";
            case "registration":
                return "Registration";
            case "voting":
                return "Voting open";
            case "closed":
                return "Voting closed";
            case "tallying":
                return "Tallying";
            case "published":
                return "Result published";
            default:
                return s;
        }
    }

    public static TextView statusChip(Context c, String s) {
        int bg = SURFACE3, fg = MUTED;
        switch (s) {
            case "ceremony":
                bg = WARNING50;
                fg = WARNING;
                break;
            case "registration":
                bg = SKY50;
                fg = SKY;
                break;
            case "voting":
                bg = SUCCESS50;
                fg = SUCCESS;
                break;
            case "tallying":
                bg = VIOLET50;
                fg = VIOLET;
                break;
            case "published":
                bg = PRIMARY50;
                fg = PRIMARY600;
                break;
            default:
                break;
        }
        return chip(c, "●  " + statusLabel(s), bg, fg);
    }

    public static int parseColor(String s, int def) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return def;
        }
    }

    public static int colorFor(String s) {
        int h = 0;
        for (char ch : (s == null ? "" : s).toCharArray()) {
            h = h * 31 + ch;
        }
        return PALETTE[Math.floorMod(h, PALETTE.length)];
    }

    public static String initials(String name) {
        if (name == null) {
            return "?";
        }
        String[] parts = name.replaceAll("[^\\p{L}\\p{N}\\s.]", " ").split("[\\s.]+");
        StringBuilder sb = new StringBuilder();
        String first = null, last = null;
        for (String p : parts) {
            if (p.isEmpty() || p.matches("(?i)prof|dr|mr|ms|mrs")) {
                continue;
            }
            if (first == null) {
                first = p;
            }
            last = p;
        }
        if (first == null) {
            return "?";
        }
        sb.append(Character.toUpperCase(first.charAt(0)));
        if (last != first) {
            sb.append(Character.toUpperCase(last.charAt(0)));
        }
        return sb.toString();
    }

    public static TextView avatar(Context c, String name, int color, int sizeDp) {
        TextView t = text(c, initials(name), sizeDp * 0.36f, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color == 0 ? colorFor(name) : color);
        t.setBackground(g);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return t;
    }

    public static EditText input(Context c, String hint, boolean mono) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(TEXT);
        e.setHintTextColor(FAINT);
        e.setBackground(round(c, SURFACE, 12, BORDER_STRONG, 1));
        int ph = dp(c, 14), pv = dp(c, 12);
        e.setPadding(ph, pv, ph, pv);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (mono) {
            e.setTypeface(Typeface.MONOSPACE);
        }
        return e;
    }

    public static EditText area(Context c, String hint) {
        EditText e = input(c, hint, false);
        e.setSingleLine(false);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }

    public static LinearLayout field(Context c, String label, View input) {
        LinearLayout l = col(c);
        add(l, medium(c, label, 13, TEXT2));
        space(l, 6);
        add(l, input);
        return l;
    }

    public static View meter(Context c, float[] parts, int[] colors, int heightDp) {
        LinearLayout l = row(c);
        l.setBackground(round(c, SURFACE3, 99, 0, 0));
        l.setClipToOutline(true);
        float used = 0;
        for (int i = 0; i < parts.length; i++) {
            View v = new View(c);
            v.setBackgroundColor(colors[i]);
            l.addView(v, new LinearLayout.LayoutParams(0, dp(c, heightDp), Math.max(0, parts[i])));
            used += Math.max(0, parts[i]);
        }
        View rest = new View(c);
        l.addView(rest, new LinearLayout.LayoutParams(0, dp(c, heightDp), Math.max(0, 1f - used)));
        l.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return l;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(BORDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    public static HorizontalScrollView hscroll(Context c, View child) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(child);
        return h;
    }

    public static HorizontalScrollView hscroll(Context c, View child, View focus) {
        HorizontalScrollView h = hscroll(c, child);
        if (focus != null) {
            h.post(() -> {
                if (focus.getRight() > h.getWidth()) {
                    h.scrollTo(focus.getRight() - h.getWidth() + dp(c, 40), 0);
                }
            });
        }
        return h;
    }

    public static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    public static Flow flow(Context c, int gapDp) {
        return new Flow(c, dp(c, gapDp));
    }

    public static class Flow extends ViewGroup {
        final int gap;

        Flow(Context c, int gap) {
            super(c);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            boolean bounded = MeasureSpec.getMode(wSpec) != MeasureSpec.UNSPECIFIED;
            int max = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, line = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) {
                    continue;
                }
                v.measure(bounded ? MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST) : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int w = v.getMeasuredWidth();
                if (bounded && x > 0 && x + w > max) {
                    y += line + gap;
                    x = 0;
                    line = 0;
                }
                widest = Math.max(widest, x + w);
                x += w + gap;
                line = Math.max(line, v.getMeasuredHeight());
            }
            int width = widest + getPaddingLeft() + getPaddingRight();
            int height = y + line + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(resolveSize(width, wSpec), resolveSize(height, hSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) {
                    continue;
                }
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    y += line + gap;
                    x = 0;
                    line = 0;
                }
                v.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
                x += w + gap;
                line = Math.max(line, h);
            }
        }
    }

    public static LinearLayout kv(Context c, String k, CharSequence v) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.TOP);
        TextView kt = small(c, k, MUTED);
        add(r, kt, lp(dp(c, 120), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView vt = text(c, v, 13.5f, TEXT2, false);
        vt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add(r, vt, weight(1));
        r.setPadding(0, dp(c, 5), 0, dp(c, 5));
        return r;
    }

    public static LinearLayout notice(Context c, int iconRes, CharSequence msg, int fg, int bg) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.TOP);
        r.setBackground(round(c, bg, 12, 0, 0));
        int p = dp(c, 12);
        r.setPadding(p, p, p, p);
        add(r, icon(c, iconRes, fg, 18));
        r.addView(gap(c, 10));
        add(r, text(c, msg, 13.5f, TEXT2, false), weight(1));
        return r;
    }

    public static void copy(Activity a, String label, String value) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, value));
        toast(a, label + " copied");
    }

    public static void toast(Activity a, String msg) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }

    public static String fmtDate(double ts) {
        if (ts <= 0) {
            return "";
        }
        return new SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(new Date((long) (ts * 1000)));
    }

    public static String fmtIso(String iso) {
        if (iso == null || iso.isEmpty()) {
            return "";
        }
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(iso);
            return new SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    public static String ago(double ts) {
        if (ts <= 0) {
            return "";
        }
        double s = Math.max(0, System.currentTimeMillis() / 1000.0 - ts);
        if (s < 45) {
            return "just now";
        }
        if (s < 3600) {
            return Math.round(s / 60) + " min ago";
        }
        if (s < 86400) {
            return Math.round(s / 3600) + " h ago";
        }
        return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date((long) (ts * 1000)));
    }

    public static String duration(double sec) {
        long s = Math.max(0, Math.round(sec));
        if (s < 60) {
            return s + "s";
        }
        long m = s / 60;
        if (m < 60) {
            return m + "m " + String.format(Locale.US, "%02d", s % 60) + "s";
        }
        return (m / 60) + "h " + String.format(Locale.US, "%02d", m % 60) + "m";
    }

    public static String groupHex(String h, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length(); i += n) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(h, i, Math.min(h.length(), i + n));
        }
        return sb.toString();
    }

    public static String pct(double a, double b) {
        if (b <= 0) {
            return "0%";
        }
        return Math.round(1000 * a / b) / 10.0 + "%";
    }
}

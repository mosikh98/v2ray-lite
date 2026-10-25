package com.lite.xray;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SettingsActivity extends Activity {
    private final List<Runnable> refreshers = new ArrayList<>();
    private LinearLayout root;
    private float dp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("تنظیمات");
        dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);

        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sv.addView(root);
        setContentView(sv);

        addRow("DNS", () -> {
            String[] ips = Settings.dnsList(this);
            return Settings.DNS_NAMES[Settings.dnsIndex(this)] + "  (" + TextUtils.join(", ", ips) + ")";
        }, this::pickDns);
        addRow("MTU", () -> String.valueOf(Settings.mtu(this)), this::pickMtu);
        addRow("روش پینگ", () -> Settings.PING_NAMES[Settings.pingMethod(this)], this::pickPing);
        addRow("آدرس تست (برای تأخیر واقعی)", () -> Settings.pingUrl(this), this::pickUrl);
        addRow("زمان انتظار پینگ", () -> Settings.timeoutSec(this) + " ثانیه", this::pickTimeout);

        TextView note = new TextView(this);
        note.setText("تغییرات DNS و MTU از اتصال بعدی اعمال می‌شود.");
        note.setTextSize(12);
        note.setAlpha(0.7f);
        note.setPadding(0, pad, 0, 0);
        root.addView(note);
    }

    private void addRow(String title, Supplier<String> value, Runnable click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * dp);
        r.setPadding(0, p, 0, p);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        final TextView v = new TextView(this);
        v.setTextSize(13);
        v.setAlpha(0.7f);
        r.addView(t);
        r.addView(v);
        r.setOnClickListener(x -> click.run());
        root.addView(r);
        Runnable ref = () -> v.setText(value.get());
        refreshers.add(ref);
        ref.run();
    }

    private void refreshAll() {
        for (Runnable r : refreshers) r.run();
    }

    private void pickDns() {
        String[] items = new String[Settings.DNS_NAMES.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = Settings.DNS_NAMES[i]
                    + (Settings.DNS_IPS[i].length > 0 ? "   " + TextUtils.join(", ", Settings.DNS_IPS[i]) : "");
        }
        new AlertDialog.Builder(this).setTitle("DNS")
                .setSingleChoiceItems(items, Settings.dnsIndex(this), (d, w) -> {
                    d.dismiss();
                    if (w == Settings.DNS_NAMES.length - 1) {
                        input("DNS سفارشی", "مثلاً 1.1.1.1, 8.8.8.8", Settings.dnsCustom(this), s -> {
                            Settings.putStr(this, "dns_custom", s);
                            Settings.putInt(this, "dns", w);
                            refreshAll();
                        });
                    } else {
                        Settings.putInt(this, "dns", w);
                        refreshAll();
                    }
                }).show();
    }

    private void pickMtu() {
        String[] items = new String[Settings.MTUS.length];
        int cur = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = String.valueOf(Settings.MTUS[i]);
            if (Settings.MTUS[i] == Settings.mtu(this)) cur = i;
        }
        new AlertDialog.Builder(this).setTitle("MTU")
                .setSingleChoiceItems(items, cur, (d, w) -> {
                    Settings.putInt(this, "mtu", Settings.MTUS[w]);
                    d.dismiss();
                    refreshAll();
                }).show();
    }

    private void pickPing() {
        new AlertDialog.Builder(this).setTitle("روش پینگ")
                .setSingleChoiceItems(Settings.PING_NAMES, Settings.pingMethod(this), (d, w) -> {
                    Settings.putInt(this, "ping_method", w);
                    d.dismiss();
                    refreshAll();
                }).show();
    }

    private void pickUrl() {
        new AlertDialog.Builder(this).setTitle("آدرس تست")
                .setSingleChoiceItems(Settings.URL_NAMES, Settings.urlIndex(this), (d, w) -> {
                    d.dismiss();
                    if (w == Settings.URL_NAMES.length - 1) {
                        input("آدرس تست سفارشی", "http://example.com/generate_204",
                                Settings.urlCustom(this), s -> {
                                    Settings.putStr(this, "ping_url_custom", s);
                                    Settings.putInt(this, "ping_url", w);
                                    refreshAll();
                                });
                    } else {
                        Settings.putInt(this, "ping_url", w);
                        refreshAll();
                    }
                }).show();
    }

    private void pickTimeout() {
        String[] items = new String[Settings.TIMEOUTS.length];
        int cur = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = Settings.TIMEOUTS[i] + " ثانیه";
            if (Settings.TIMEOUTS[i] == Settings.timeoutSec(this)) cur = i;
        }
        new AlertDialog.Builder(this).setTitle("زمان انتظار پینگ")
                .setSingleChoiceItems(items, cur, (d, w) -> {
                    Settings.putInt(this, "ping_timeout", Settings.TIMEOUTS[w]);
                    d.dismiss();
                    refreshAll();
                }).show();
    }

    private interface Callback {
        void on(String s);
    }

    private void input(String title, String hint, String initial, Callback cb) {
        final EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(initial);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        int p = (int) (20 * dp);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(p, p / 2, p, 0);
        box.addView(e, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("ذخیره", (d, w) -> cb.on(e.getText().toString().trim()))
                .setNegativeButton("لغو", null).show();
    }
}

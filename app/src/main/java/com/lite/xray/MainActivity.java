package com.lite.xray;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;

    private final List<Store.Server> servers = new ArrayList<>();
    private final Map<String, String> subNames = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BaseAdapter adapter;
    private Button btn, pingBtn, speedBtn;
    private TextView status, mon;
    private final Monitor monitor = new Monitor();
    private String selectedId = "";
    private ExecutorService fastPool, realPool;
    private SpeedTest speed;
    private boolean pinging = false;
    private int pingGen = 0;
    private float dp;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 800);
        }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button addBtn = barButton("افزودن");
        pingBtn = barButton("پینگ");
        speedBtn = barButton("سرعت");
        Drawable ic = getDrawable(R.drawable.ic_speed);
        if (ic != null) {
            int sz = (int) (16 * dp);
            ic.setBounds(0, 0, sz, sz);
            speedBtn.setCompoundDrawablesRelative(ic, null, null, null);
            speedBtn.setCompoundDrawablePadding((int) (3 * dp));
        }
        Button subBtn = barButton("اشتراک");
        Button setBtn = barButton("تنظیمات");
        bar.addView(addBtn);
        bar.addView(pingBtn);
        bar.addView(speedBtn);
        bar.addView(subBtn);
        bar.addView(setBtn);
        root.addView(bar);

        mon = new TextView(this);
        mon.setTextSize(12);
        mon.setTypeface(Typeface.MONOSPACE);
        mon.setSingleLine(true);
        mon.setAlpha(0.75f);
        mon.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        mon.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        mon.setText("CPU —   RAM —");
        root.addView(mon);

        ListView list = new ListView(this);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return servers.size();
            }

            @Override
            public Object getItem(int i) {
                return servers.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                return rowView(servers.get(i));
            }
        };
        list.setAdapter(adapter);
        list.setEmptyView(null);
        list.setOnItemClickListener((p, v, pos, id) -> select(servers.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            itemMenu(servers.get(pos));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setPadding(0, pad, 0, pad / 2);
        root.addView(status);

        btn = new Button(this);
        btn.setOnClickListener(v -> onToggle());
        root.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        addBtn.setOnClickListener(v -> addMenu());
        pingBtn.setOnClickListener(v -> togglePing());
        speedBtn.setOnClickListener(v -> toggleSpeed());
        subBtn.setOnClickListener(v -> manageSubs());
        setBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        pingGen++;
        if (fastPool != null) fastPool.shutdownNow();
        if (realPool != null) realPool.shutdownNow();
        if (speed != null) speed.cancel();
        super.onDestroy();
    }

    private Button barButton(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding((int) (4 * dp), b.getPaddingTop(), (int) (4 * dp), b.getPaddingBottom());
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    // ------------------------------------------------------------ data

    private void reload() {
        servers.clear();
        servers.addAll(Store.servers(this));
        selectedId = Store.selected(this);
        subNames.clear();
        for (Store.Sub s : Store.subs(this)) subNames.put(s.id, s.name);
        adapter.notifyDataSetChanged();
    }

    private void saveServers() {
        Store.saveServers(this, servers);
        adapter.notifyDataSetChanged();
    }

    private Store.Server selectedServer() {
        for (Store.Server s : servers) if (s.id.equals(selectedId)) return s;
        if (!servers.isEmpty()) {
            selectedId = servers.get(0).id;
            Store.setSelected(this, selectedId);
            return servers.get(0);
        }
        return null;
    }

    private int addLinks(List<String> links, String group) {
        Set<String> have = new HashSet<>();
        for (Store.Server x : servers) have.add(x.link);
        int n = 0;
        for (String l : links) {
            if (!have.add(l)) continue;
            Store.Server s = new Store.Server();
            s.id = Store.newId();
            s.link = l;
            s.group = group;
            s.name = ConfigBuilder.name(l);
            servers.add(s);
            n++;
        }
        if (n > 0) saveServers();
        return n;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ list rows

    private View rowView(Store.Server s) {
        int p = (int) (10 * dp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(p, p, p, p);
        boolean sel = s.id.equals(selectedId);
        if (sel) row.setBackgroundColor(0x2233AA66);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(this);
        t1.setText((sel ? "● " : "") + s.name);
        t1.setTextSize(15);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);

        String proto = s.link.startsWith("{") ? "json" : s.link.substring(0, s.link.indexOf("://"));
        String grp = s.group.isEmpty() ? "دستی" : subNames.containsKey(s.group) ? subNames.get(s.group) : "اشتراک";
        TextView t2 = new TextView(this);
        t2.setText(proto + " · " + grp);
        t2.setTextSize(11);
        t2.setAlpha(0.6f);

        col.addView(t1);
        col.addView(t2);

        // results in one horizontal line under the name:  tcp 44   icmp 58   real 115
        LinearLayout pc = new LinearLayout(this);
        pc.setOrientation(LinearLayout.HORIZONTAL);
        pc.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pc.setPadding(0, (int) (2 * dp), 0, 0);
        boolean any = false;
        any |= addPingLine(pc, "tcp", s.tcp);
        any |= addPingLine(pc, "icmp", s.icmp);
        any |= addPingLine(pc, "real", s.real);
        if (any) col.addView(pc);

        // speed results:  ↓ 12.4 Mbps   ↑ 3.1 Mbps
        LinearLayout sc = new LinearLayout(this);
        sc.setOrientation(LinearLayout.HORIZONTAL);
        sc.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        boolean sany = false;
        sany |= addSpeedItem(sc, "↓", s.dl);
        sany |= addSpeedItem(sc, "↑", s.ul);
        if (sany) col.addView(sc);

        row.addView(col);
        return row;
    }

    private boolean addSpeedItem(LinearLayout parent, String arrow, double v) {
        if (v == -1) return false;
        TextView t = new TextView(this);
        t.setTextSize(11);
        t.setSingleLine(true);
        if (v == -3) {
            t.setText(arrow + " …");
        } else if (v == -2) {
            t.setText(arrow + " ✕");
            t.setTextColor(0xFFD32F2F);
        } else {
            t.setText(String.format(java.util.Locale.US, "%s %.1f Mbps", arrow, v));
            t.setTextColor(v >= 10 ? 0xFF2E7D32 : v >= 3 ? 0xFFEF6C00 : 0xFFD32F2F);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (14 * dp);
        parent.addView(t, lp);
        return true;
    }

    /** one small "label value" item; nothing is added while the test has not run yet */
    private boolean addPingLine(LinearLayout parent, String label, long v) {
        if (v == -1) return false;
        TextView t = new TextView(this);
        t.setTextSize(11);
        t.setSingleLine(true);
        if (v == -3) {
            t.setText(label + " …");
        } else if (v == -2) {
            t.setText(label + " ✕");
            t.setTextColor(0xFFD32F2F);
        } else {
            t.setText(label + " " + v);
            t.setTextColor(v < 300 ? 0xFF2E7D32 : v < 800 ? 0xFFEF6C00 : 0xFFD32F2F);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (14 * dp);
        parent.addView(t, lp);
        return true;
    }

    private void select(Store.Server s) {
        if (s.id.equals(selectedId)) return;
        selectedId = s.id;
        Store.setSelected(this, selectedId);
        adapter.notifyDataSetChanged();
        if (XrayVpnService.running) reconnect();
    }

    private void itemMenu(final Store.Server s) {
        new AlertDialog.Builder(this).setTitle(s.name)
                .setItems(new String[]{"پینگ این سرور", "تست سرعت این سرور", "کپی لینک", "حذف"}, (d, w) -> {
                    if (w == 0) pingOne(s);
                    else if (w == 1) startSpeed(Collections.singletonList(s));
                    else if (w == 2) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("link", s.link));
                        toast("کپی شد");
                    } else {
                        servers.remove(s);
                        if (s.id.equals(selectedId)) {
                            selectedId = "";
                            Store.setSelected(this, "");
                        }
                        saveServers();
                    }
                }).show();
    }

    // ------------------------------------------------------------ connect

    private void onToggle() {
        if (XrayVpnService.starting) return;
        if (XrayVpnService.running) {
            startService(new Intent(this, XrayVpnService.class).setAction("stop"));
            return;
        }
        Store.Server s = selectedServer();
        if (s == null) {
            toast("اول یک سرور یا اشتراک اضافه کن");
            return;
        }
        try {
            ConfigBuilder.build(s.link);
        } catch (Exception e) {
            XrayVpnService.lastError = "کانفیگ نامعتبر: " + e.getMessage();
            refresh();
            return;
        }
        Intent i = VpnService.prepare(this);
        if (i != null) startActivityForResult(i, REQ_VPN);
        else startVpn();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN && res == RESULT_OK) startVpn();
    }

    private void startVpn() {
        Store.Server s = selectedServer();
        if (s == null) return;
        startService(new Intent(this, XrayVpnService.class)
                .setAction("start")
                .putExtra("cfg", s.link));
    }

    private void reconnect() {
        startService(new Intent(this, XrayVpnService.class).setAction("stop"));
        ui.postDelayed(this::startVpn, 900);
    }

    private void refresh() {
        mon.setText(monitor.sample());
        boolean r = XrayVpnService.running;
        boolean st = XrayVpnService.starting;
        btn.setText(r ? "قطع اتصال" : st ? "در حال اتصال…" : "اتصال");
        String err = XrayVpnService.lastError;
        String log = XrayVpnService.lastLog;
        if (log.length() > 200) log = log.substring(log.length() - 200);
        Store.Server s = null;
        for (Store.Server x : servers) if (x.id.equals(selectedId)) s = x;
        if (r) status.setText("متصل ✓  " + (s == null ? "" : s.name) + (log.isEmpty() ? "" : "\n" + log));
        else if (st) status.setText("در حال اتصال…");
        else if (!err.isEmpty()) status.setText("خطا: " + err);
        else status.setText("قطع");
    }

    // ------------------------------------------------------------ ping

    /** TCP, ICMP and real delay run at the same time for every server (real delay is limited to 3 xray at once). */
    private void togglePing() {
        if (pinging) {
            pingGen++;
            if (fastPool != null) fastPool.shutdownNow();
            if (realPool != null) realPool.shutdownNow();
            finishPing(false);
            return;
        }
        if (servers.isEmpty()) return;
        if (speed != null) {
            toast("تست سرعت در حال اجراست");
            return;
        }
        pinging = true;
        pingBtn.setText("توقف");
        final List<Store.Server> targets = new ArrayList<>(servers);
        for (Store.Server s : targets) {
            s.tcp = -3;
            s.icmp = -3;
            s.real = -3;
        }
        adapter.notifyDataSetChanged();

        final int gen = ++pingGen;
        final Context app = getApplicationContext();
        final ExecutorService fast = Executors.newFixedThreadPool(12);
        final ExecutorService real = Executors.newFixedThreadPool(3);
        fastPool = fast;
        realPool = real;
        final AtomicInteger left = new AtomicInteger(targets.size() * 3);
        for (final Store.Server s : targets) {
            submitPing(fast, gen, left, app, s, 0);
            submitPing(fast, gen, left, app, s, 1);
            submitPing(real, gen, left, app, s, 2);
        }
        fast.shutdown();
        real.shutdown();
    }

    private void submitPing(ExecutorService pool, final int gen, final AtomicInteger left,
                            final Context app, final Store.Server s, final int method) {
        pool.execute(() -> {
            long r = Net.ping(app, s.link, method);
            if (gen != pingGen) return;
            if (method == 0) s.tcp = r;
            else if (method == 1) s.icmp = r;
            else s.real = r;
            ui.post(() -> {
                if (gen != pingGen) return;
                adapter.notifyDataSetChanged();
                if (left.decrementAndGet() == 0) finishPing(true);
            });
        });
    }

    private void finishPing(boolean done) {
        pinging = false;
        pingBtn.setText("پینگ");
        boolean realOk = false;
        for (Store.Server s : servers) {
            if (s.tcp == -3) s.tcp = -1;
            if (s.icmp == -3) s.icmp = -1;
            if (s.real == -3) s.real = -1;
            if (s.real >= 0) realOk = true;
        }
        adapter.notifyDataSetChanged();
        if (!realOk && !servers.isEmpty() && !Net.lastError.isEmpty()) toastLong(Net.lastError);
        if (done && Settings.autoSort(this)) sortByPing();
    }

    private void pingOne(final Store.Server s) {
        final Context app = getApplicationContext();
        s.tcp = -3;
        s.icmp = -3;
        s.real = -3;
        adapter.notifyDataSetChanged();
        for (int m = 0; m < 3; m++) {
            final int method = m;
            new Thread(() -> {
                long r = Net.ping(app, s.link, method);
                if (method == 0) s.tcp = r;
                else if (method == 1) s.icmp = r;
                else s.real = r;
                ui.post(() -> {
                    adapter.notifyDataSetChanged();
                    if (method == 2 && r < 0 && !Net.lastError.isEmpty()) toastLong(Net.lastError);
                });
            }).start();
        }
    }

    /** real delay first, then servers that only answered TCP, untested ones keep their order */
    private static long sortKey(Store.Server s) {
        if (s.real >= 0) return s.real;
        if (s.tcp >= 0) return 1000000 + s.tcp;
        return Long.MAX_VALUE;
    }

    private void sortByPing() {
        Collections.sort(servers, (a, b) -> Long.compare(sortKey(a), sortKey(b)));
        saveServers();
    }

    // ------------------------------------------------------------ speed test

    /** the speed button: tests every server one by one, results appear in the rows; press again to stop */
    private void toggleSpeed() {
        if (speed != null) {
            speed.cancel();
            return;
        }
        if (servers.isEmpty()) return;
        startSpeed(new ArrayList<>(servers));
    }

    private void startSpeed(final List<Store.Server> targets) {
        if (speed != null) {
            toast("تست سرعت در حال اجراست");
            return;
        }
        if (pinging) {
            toast("اول پینگ تموم بشه");
            return;
        }
        final SpeedTest st = new SpeedTest();
        speed = st;
        speedBtn.setText("توقف");
        for (Store.Server s : targets) {
            s.dl = -1;
            s.ul = -1;
        }
        adapter.notifyDataSetChanged();
        final Context app = getApplicationContext();
        final int mb = Settings.speedMb(this);
        final String connected = XrayVpnService.running ? selectedId : "";
        new Thread(() -> {
            st.runAll(app, targets, connected, mb, () -> ui.post(() -> adapter.notifyDataSetChanged()));
            ui.post(() -> {
                if (speed == st) {
                    speed = null;
                    speedBtn.setText("سرعت");
                }
                for (Store.Server s : servers) {
                    if (s.dl == -3) s.dl = -1;
                    if (s.ul == -3) s.ul = -1;
                }
                adapter.notifyDataSetChanged();
            });
        }, "speed-test").start();
    }

    // ------------------------------------------------------------ add / import

    private void addMenu() {
        new AlertDialog.Builder(this).setTitle("افزودن")
                .setItems(new String[]{
                        "از کلیپ‌بورد (لینک سرور یا اشتراک)",
                        "ورود دستی لینک / JSON",
                        "افزودن لینک اشتراک",
                        "به‌روزرسانی همه اشتراک‌ها"}, (d, w) -> {
                    switch (w) {
                        case 0:
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData cd = cm.getPrimaryClip();
                            if (cd == null || cd.getItemCount() == 0) {
                                toast("کلیپ‌بورد خالیه");
                            } else {
                                importText(cd.getItemAt(0).coerceToText(this).toString());
                            }
                            break;
                        case 1:
                            manualDialog();
                            break;
                        case 2:
                            subDialog(null, "");
                            break;
                        default:
                            List<Store.Sub> all = Store.subs(this);
                            if (all.isEmpty()) toast("اشتراکی نداری");
                            else updateSubs(all);
                    }
                }).show();
    }

    private void manualDialog() {
        final EditText e = new EditText(this);
        e.setHint("vless:// vmess:// trojan:// ss://  یا JSON");
        e.setMinLines(4);
        e.setGravity(Gravity.TOP);
        e.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout box = padded(e);
        new AlertDialog.Builder(this).setTitle("ورود دستی").setView(box)
                .setPositiveButton("افزودن", (d, w) -> importText(e.getText().toString()))
                .setNegativeButton("لغو", null).show();
    }

    private LinearLayout padded(View... views) {
        int p = (int) (20 * dp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, p / 2, p, 0);
        for (View v : views) {
            box.addView(v, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return box;
    }

    private void importText(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            toast("چیزی برای افزودن نیست");
            return;
        }
        if (t.startsWith("{")) {
            try {
                ConfigBuilder.build(t);
            } catch (Exception e) {
                toast("JSON نامعتبر");
                return;
            }
            toast(addLinks(Collections.singletonList(t), "") + " سرور اضافه شد");
            return;
        }
        if ((t.startsWith("http://") || t.startsWith("https://")) && !t.contains("\n")) {
            subDialog(null, t);
            return;
        }
        List<String> links = Net.parseLinks(t);
        List<String> good = new ArrayList<>();
        for (String l : links) {
            try {
                ConfigBuilder.build(l);
                good.add(l);
            } catch (Exception ignored) {
            }
        }
        if (good.isEmpty()) {
            toast("لینک معتبری پیدا نشد");
            return;
        }
        toast(addLinks(good, "") + " سرور اضافه شد");
    }

    // ------------------------------------------------------------ subscriptions

    /** add (existing == null) or edit a subscription */
    private void subDialog(final Store.Sub existing, String prefillUrl) {
        final EditText name = new EditText(this);
        name.setHint("نام (اختیاری)");
        final EditText url = new EditText(this);
        url.setHint("https://...");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        if (existing != null) {
            name.setText(existing.name);
            url.setText(existing.url);
        } else {
            url.setText(prefillUrl);
        }
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "افزودن اشتراک" : "ویرایش اشتراک")
                .setView(padded(name, url))
                .setPositiveButton(existing == null ? "افزودن" : "ذخیره", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        toast("آدرس نامعتبر");
                        return;
                    }
                    String n = name.getText().toString().trim();
                    String host = Uri.parse(u).getHost();
                    if (n.isEmpty()) n = host != null ? host : "اشتراک";

                    List<Store.Sub> subs = Store.subs(this);
                    Store.Sub target = null;
                    if (existing != null) {
                        for (Store.Sub x : subs) if (x.id.equals(existing.id)) target = x;
                    }
                    boolean refetch = true;
                    if (target == null) {
                        target = new Store.Sub();
                        target.id = Store.newId();
                        subs.add(target);
                    } else {
                        refetch = !target.url.equals(u);
                    }
                    target.url = u;
                    target.name = n;
                    Store.saveSubs(this, subs);
                    subNames.put(target.id, target.name);
                    adapter.notifyDataSetChanged();
                    if (refetch) updateSubs(Collections.singletonList(target));
                    else toast("ذخیره شد");
                })
                .setNegativeButton("لغو", null).show();
    }

    private void manageSubs() {
        final List<Store.Sub> subs = Store.subs(this);
        String[] names = new String[subs.size() + 1];
        for (int i = 0; i < subs.size(); i++) names[i] = subs.get(i).name;
        names[subs.size()] = "＋ افزودن اشتراک جدید";
        new AlertDialog.Builder(this).setTitle("اشتراک‌ها").setItems(names, (d, w) -> {
            if (w == subs.size()) {
                subDialog(null, "");
                return;
            }
            final Store.Sub s = subs.get(w);
            new AlertDialog.Builder(this).setTitle(s.name).setMessage(s.url)
                    .setPositiveButton("ویرایش", (d2, w2) -> subDialog(s, ""))
                    .setNeutralButton("به‌روزرسانی", (d2, w2) -> updateSubs(Collections.singletonList(s)))
                    .setNegativeButton("حذف", (d2, w2) -> deleteSub(s)).show();
        }).show();
    }

    private void deleteSub(Store.Sub sub) {
        List<Store.Sub> subs = Store.subs(this);
        for (Iterator<Store.Sub> it = subs.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(sub.id)) it.remove();
        }
        Store.saveSubs(this, subs);
        for (Iterator<Store.Server> it = servers.iterator(); it.hasNext(); ) {
            if (it.next().group.equals(sub.id)) it.remove();
        }
        subNames.remove(sub.id);
        saveServers();
        toast("اشتراک حذف شد");
    }

    private void updateSubs(final List<Store.Sub> subs) {
        toast("در حال به‌روزرسانی…");
        new Thread(() -> {
            int ok = 0, fail = 0, total = 0;
            for (final Store.Sub s : subs) {
                try {
                    final List<String> links = Net.parseLinks(Net.fetch(s.url));
                    if (links.isEmpty()) {
                        fail++;
                        continue;
                    }
                    ok++;
                    total += links.size();
                    ui.post(() -> replaceGroup(s, links));
                } catch (Exception e) {
                    fail++;
                }
            }
            final String msg = "اشتراک: " + ok + " موفق، " + fail + " ناموفق، " + total + " سرور";
            ui.post(() -> toast(msg));
        }).start();
    }

    private void replaceGroup(Store.Sub sub, List<String> links) {
        String selLink = null;
        for (Store.Server x : servers) if (x.id.equals(selectedId)) selLink = x.link;
        for (Iterator<Store.Server> it = servers.iterator(); it.hasNext(); ) {
            if (it.next().group.equals(sub.id)) it.remove();
        }
        addLinks(links, sub.id);
        if (selLink != null) {
            for (Store.Server x : servers) {
                if (x.link.equals(selLink)) {
                    selectedId = x.id;
                    Store.setSelected(this, selectedId);
                    break;
                }
            }
        }
        subNames.put(sub.id, sub.name);
        saveServers();
    }
}

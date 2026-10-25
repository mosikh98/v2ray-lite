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
    private Button btn, pingBtn;
    private TextView status;
    private String selectedId = "";
    private ExecutorService pingPool;
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
        Button sortBtn = barButton("مرتب");
        Button setBtn = barButton("تنظیمات");
        bar.addView(addBtn);
        bar.addView(pingBtn);
        bar.addView(sortBtn);
        bar.addView(setBtn);
        root.addView(bar);

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
        sortBtn.setOnClickListener(v -> sortByPing());
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
        if (pingPool != null) pingPool.shutdownNow();
        super.onDestroy();
    }

    private Button barButton(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(13);
        b.setAllCaps(false);
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

        TextView t3 = new TextView(this);
        t3.setTextSize(13);
        long ping = s.ping;
        if (ping == -3) {
            t3.setText("…");
        } else if (ping == -2) {
            t3.setText("✕");
            t3.setTextColor(0xFFD32F2F);
        } else if (ping >= 0) {
            t3.setText(ping + " ms");
            t3.setTextColor(ping < 300 ? 0xFF2E7D32 : ping < 800 ? 0xFFEF6C00 : 0xFFD32F2F);
        }
        row.addView(col);
        row.addView(t3);
        return row;
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
                .setItems(new String[]{"پینگ این سرور", "کپی لینک", "حذف"}, (d, w) -> {
                    if (w == 0) pingOne(s);
                    else if (w == 1) {
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

    private void togglePing() {
        if (pinging) {
            pingGen++;
            if (pingPool != null) pingPool.shutdownNow();
            finishPing();
            return;
        }
        if (servers.isEmpty()) return;
        pinging = true;
        pingBtn.setText("توقف");
        for (Store.Server s : servers) s.ping = -3;
        adapter.notifyDataSetChanged();

        final int gen = ++pingGen;
        final Context app = getApplicationContext();
        int m = Settings.pingMethod(this);
        final ExecutorService pool = Executors.newFixedThreadPool(m == 2 ? 3 : m == 1 ? 8 : 16);
        pingPool = pool;
        final List<Store.Server> targets = new ArrayList<>(servers);
        final AtomicInteger left = new AtomicInteger(targets.size());
        for (final Store.Server s : targets) {
            pool.execute(() -> {
                long r = Net.ping(app, s.link);
                if (gen != pingGen) return;
                s.ping = r;
                ui.post(() -> {
                    if (gen != pingGen) return;
                    adapter.notifyDataSetChanged();
                    if (left.decrementAndGet() == 0) finishPing();
                });
            });
        }
        pool.shutdown();
    }

    private void finishPing() {
        pinging = false;
        pingBtn.setText("پینگ");
        boolean any = false;
        for (Store.Server s : servers) {
            if (s.ping == -3) s.ping = -1;
            if (s.ping >= 0) any = true;
        }
        adapter.notifyDataSetChanged();
        if (!any && !servers.isEmpty() && Settings.pingMethod(this) == 2 && !Net.lastError.isEmpty()) {
            toastLong(Net.lastError);
        }
    }

    private void pingOne(final Store.Server s) {
        final Context app = getApplicationContext();
        s.ping = -3;
        adapter.notifyDataSetChanged();
        new Thread(() -> {
            long r = Net.ping(app, s.link);
            s.ping = r;
            ui.post(() -> {
                adapter.notifyDataSetChanged();
                if (r < 0 && Settings.pingMethod(this) == 2 && !Net.lastError.isEmpty()) {
                    toastLong(Net.lastError);
                }
            });
        }).start();
    }

    private void sortByPing() {
        Collections.sort(servers, (a, b) -> {
            boolean ao = a.ping >= 0, bo = b.ping >= 0;
            if (ao && bo) return Long.compare(a.ping, b.ping);
            if (ao) return -1;
            if (bo) return 1;
            return 0;
        });
        saveServers();
    }

    // ------------------------------------------------------------ add / import

    private void addMenu() {
        new AlertDialog.Builder(this).setTitle("افزودن")
                .setItems(new String[]{
                        "از کلیپ‌بورد (لینک سرور یا اشتراک)",
                        "ورود دستی لینک / JSON",
                        "افزودن لینک اشتراک",
                        "به‌روزرسانی همه اشتراک‌ها",
                        "مدیریت اشتراک‌ها"}, (d, w) -> {
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
                            addSubDialog("");
                            break;
                        case 3:
                            List<Store.Sub> all = Store.subs(this);
                            if (all.isEmpty()) toast("اشتراکی نداری");
                            else updateSubs(all);
                            break;
                        default:
                            manageSubs();
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
            addSubDialog(t);
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

    private void addSubDialog(String prefillUrl) {
        final EditText name = new EditText(this);
        name.setHint("نام (اختیاری)");
        final EditText url = new EditText(this);
        url.setHint("https://...");
        url.setText(prefillUrl);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        new AlertDialog.Builder(this).setTitle("افزودن اشتراک").setView(padded(name, url))
                .setPositiveButton("افزودن", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        toast("آدرس نامعتبر");
                        return;
                    }
                    Store.Sub s = new Store.Sub();
                    s.id = Store.newId();
                    s.url = u;
                    String n = name.getText().toString().trim();
                    String host = Uri.parse(u).getHost();
                    s.name = !n.isEmpty() ? n : (host != null ? host : "اشتراک");
                    List<Store.Sub> subs = Store.subs(this);
                    subs.add(s);
                    Store.saveSubs(this, subs);
                    subNames.put(s.id, s.name);
                    updateSubs(Collections.singletonList(s));
                })
                .setNegativeButton("لغو", null).show();
    }

    private void manageSubs() {
        final List<Store.Sub> subs = Store.subs(this);
        if (subs.isEmpty()) {
            toast("اشتراکی نداری");
            return;
        }
        String[] names = new String[subs.size()];
        for (int i = 0; i < names.length; i++) names[i] = subs.get(i).name;
        new AlertDialog.Builder(this).setTitle("اشتراک‌ها").setItems(names, (d, w) -> {
            final Store.Sub s = subs.get(w);
            new AlertDialog.Builder(this).setTitle(s.name).setMessage(s.url)
                    .setPositiveButton("به‌روزرسانی", (d2, w2) -> updateSubs(Collections.singletonList(s)))
                    .setNegativeButton("حذف", (d2, w2) -> deleteSub(s))
                    .setNeutralButton("بستن", null).show();
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

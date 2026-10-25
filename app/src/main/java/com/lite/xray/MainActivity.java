package com.lite.xray;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;

    private EditText input;
    private Button btn;
    private TextView status;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        input = new EditText(this);
        input.setHint("vless:// vmess:// trojan:// ss://  or Xray JSON");
        input.setMinLines(5);
        input.setGravity(Gravity.TOP);
        input.setText(prefs.getString("cfg", ""));
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        btn = new Button(this);
        btn.setOnClickListener(v -> onToggle());
        root.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setPadding(0, pad, 0, 0);
        root.addView(status);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void onToggle() {
        if (XrayVpnService.running) {
            startService(new Intent(this, XrayVpnService.class).setAction("stop"));
            return;
        }
        String text = input.getText().toString().trim();
        try {
            ConfigBuilder.build(text);
        } catch (Exception e) {
            XrayVpnService.lastError = "invalid config: " + e.getMessage();
            refresh();
            return;
        }
        prefs.edit().putString("cfg", text).apply();
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
        startService(new Intent(this, XrayVpnService.class)
                .setAction("start")
                .putExtra("cfg", prefs.getString("cfg", "")));
    }

    private void refresh() {
        boolean r = XrayVpnService.running;
        btn.setText(r ? "Disconnect" : "Connect");
        String err = XrayVpnService.lastError;
        if (r) status.setText("Connected");
        else if (!err.isEmpty()) status.setText("Error: " + err);
        else status.setText("Disconnected");
    }
}

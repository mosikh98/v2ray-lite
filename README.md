# XrayLite

اپ اندروید خیلی سبک (فقط Java، بدون AndroidX) دور هسته‌ی Xray.

- لینک `vless://` `vmess://` `trojan://` `ss://` یا JSON کامل Xray رو پیست کن و Connect بزن.
- مسیر ترافیک: VpnService → hev-socks5-tunnel (tun2socks) → Xray (SOCKS 127.0.0.1:10808) → سرور.
- هسته‌ی Xray و tun2socks موقع بیلد توی GitHub Actions دانلود/کامپایل میشن (چیزی باینری توی ریپو نیست).

## بیلد
1. این پوشه رو push کن روی یه ریپوی گیتهاب (branch: main).
2. تب Actions → workflow «Build APK» خودکار اجرا میشه (یا Run workflow).
3. بعد از اتمام، از بخش Artifacts فایل `XrayLite-apk` رو دانلود کن.

APK با کلید debug ساین میشه؛ برای انتشار جدی با کلید خودت ساین کن.
فقط arm64-v8a ساپورت میشه.

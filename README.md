# XrayLite

اپ اندروید خیلی سبک (فقط Java، بدون AndroidX) دور هسته‌ی Xray.

## امکانات
- چند سرور + لینک اشتراک (subscription) با به‌روزرسانی دستی
- لینک `vless://` `vmess://` `trojan://` `ss://` یا JSON کامل Xray
- تنظیمات: DNS (لیست آماده + سفارشی)، MTU، روش پینگ (TCP / ICMP / تأخیر واقعی)، آدرس تست، timeout
- مرتب‌سازی بر اساس پینگ

مسیر ترافیک: VpnService → hev-socks5-tunnel → Xray (SOCKS 127.0.0.1:10808) → سرور.

## بیلد
Push کن روی گیتهاب (branch: main) → تب Actions → «Build APK» → Artifacts.
هسته‌ی Xray و tun2socks موقع بیلد دانلود/کامپایل میشن؛ باینری توی ریپو نیست.
APK با کلید debug ساین میشه. فقط arm64-v8a.

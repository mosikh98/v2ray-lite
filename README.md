# XrayLite

اپ اندروید خیلی سبک (فقط Java، بدون AndroidX) دور هسته‌ی Xray.

## امکانات
- چند سرور + لینک اشتراک (subscription) با به‌روزرسانی دستی
- لینک `vless://` `vmess://` `trojan://` `ss://` یا JSON کامل Xray
- تنظیمات: DNS (لیست آماده + سفارشی)، MTU، روش پینگ (TCP / ICMP / تأخیر واقعی)، آدرس تست، timeout
- مرتب‌سازی بر اساس پینگ
- فرگمنت و «فرگمنت در فرگمنت» (دو outbound از نوع freedom که با dialerProxy زنجیر میشن)
- پشتیبانی از VLESS Encryption (رشته‌ی mlkem768x25519plus... بدون تغییر به Xray داده میشه) و `pqv` برای reality

مسیر ترافیک: VpnService → hev-socks5-tunnel → Xray (SOCKS 127.0.0.1:10808) → سرور.

## بیلد
Push کن روی گیتهاب (branch: main) → تب Actions → «Build APK» → Artifacts.
هسته‌ی Xray و tun2socks موقع بیلد دانلود/کامپایل میشن؛ باینری توی ریپو نیست.
APK با کلید debug ساین میشه. فقط arm64-v8a.

## امضا
کلید امضا توی ریپو نیست؛ از GitHub Secrets خونده میشه:
- `KEYSTORE_B64` : فایل کلید (p12) به‌صورت base64
- `KEYSTORE_PASSWORD` : رمز کلید (alias ثابت: xraylite)

با کلید ثابت، هر نسخه‌ی جدید روی قبلی نصب میشه. versionCode از شماره‌ی اجرای Actions گرفته میشه.

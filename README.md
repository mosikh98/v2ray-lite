# XrayLite

اپ اندروید خیلی سبک (فقط Java، بدون AndroidX و کتابخانه‌ی اضافه) دور هسته‌ی [Xray](https://github.com/XTLS/Xray-core).

## امکانات
- **چند سرور و اشتراک (subscription):** لینک `vless://` `vmess://` `trojan://` `ss://` یا JSON کامل Xray؛ افزودن از کلیپ‌بورد، ورود دستی، لینک اشتراک (با ویرایش، به‌روزرسانی و حذف)
- **VLESS Encryption (mlkem768x25519plus)**، reality (+`pqv`)، tls، ws، grpc، httpupgrade و **xhttp** (با پارامتر `extra`)
- **فرگمنت** و **فرگمنت در فرگمنت** (دولایه) با packets / length / interval قابل تنظیم
- **پینگ:** سه تست هم‌زمان برای هر سرور (TCP، ICMP، real delay از داخل Xray)؛ هر کدوم ۵ تلاش با سقف ۱ ثانیه و میانگین؛ نتیجه کنار هر سرور
- **مرتب‌سازی خودکار** بعد از پینگ (از تنظیمات قابل خاموش شدن)
- **تست سرعت** دانلود و آپلود برای همه‌ی سرورها (یکی‌یکی، نتیجه کنار هر سرور: `↓ 12.4 Mbps  ↑ 3.1 Mbps`)؛ حجم ۲ تا ۱۰ مگابایت از تنظیمات؛ نگه داشتن روی یک سرور ← تست فقط همون سرور
- **تنظیمات:** DNS (لیست آماده یا سفارشی)، MTU، آدرس تست real delay، فرگمنت، حجم تست سرعت، عیب‌یابی
- نمایشگر زنده‌ی CPU و RAM (از `/proc`، تقریباً بدون هزینه)

مسیر ترافیک: `VpnService → hev-socks5-tunnel → Xray (SOCKS 127.0.0.1:10808) → سرور`

## بیلد و ریلیز
با هر push روی `main`، GitHub Actions:
1. آخرین Xray-core رسمی (android arm64) رو دانلود می‌کنه
2. tun2socks (hev-socks5-tunnel) رو با NDK می‌سازه
3. APK رو بیلد و امضا می‌کنه
4. یک **Release** با نام `v1.0.<شماره‌ی اجرا>` می‌سازه و APK رو بهش اضافه می‌کنه

APK رو از بخش **Releases** ریپو دانلود کن (یا از Actions ← Artifacts).
اگه ساخت Release خطای دسترسی داد: Settings ← Actions ← General ← Workflow permissions ← *Read and write*.

## امضا (مهم)
کلید امضا توی ریپو **نیست**؛ از GitHub Secrets خونده میشه. دو تا secret بساز (Settings ← Secrets and variables ← Actions):

| نام | مقدار |
|---|---|
| `KEYSTORE_B64` | فایل کلید (p12) به‌صورت base64 |
| `KEYSTORE_PASSWORD` | رمز کلید (alias ثابت: `xraylite`) |

بدون این دو secret بیلد عمداً خطا میده. با کلید ثابت، هر نسخه روی قبلی نصب میشه (`versionCode` از شماره‌ی اجرای Actions میاد و همیشه بالا میره).
از کلید یه نسخه‌ی پشتیبان نگه دار؛ با گم شدنش دیگه آپدیتی روی اپ نصب‌شده نمی‌شینه.

## ساختار
```
app/src/main/java/com/lite/xray/
  MainActivity      صفحه‌ی اصلی (لیست سرورها، پینگ، سرعت، اشتراک‌ها)
  SettingsActivity  تنظیمات
  XrayVpnService    VPN + راه‌اندازی Xray و tun2socks
  ConfigBuilder     لینک ← کانفیگ Xray (فرگمنت، xhttp، encryption ...)
  Net / SpeedTest   پینگ و تست سرعت
  Diag / Monitor    عیب‌یابی و نمایشگر CPU/RAM
native/hev-jni.c.in  پل JNI برای tun2socks (CI جایگزین فایل اصلی می‌کنه)
```

# PulseChat

Native Android messaging app (Kotlin, Jetpack Compose, Material 3, Firebase).
The Android project is in [`pulsechat/`](pulsechat/); full documentation is in
[`pulsechat/README.md`](pulsechat/README.md).

> **This is not a web app.** There is no `package.json`, no web build and no `dist/` folder, so
> npm / Capacitor commands (`npm run build`, `npx cap add android`, `npx cap sync`) do not apply.
> Capacitor wraps web pages; it would produce an empty shell, not PulseChat.

## Get the APK (no setup)

1. Open the **Actions** tab of this repository → workflow **Android CI** → the latest successful
   run on `main`.
2. Under **Artifacts**, download **pulsechat-debug-apk** (you must be signed in to GitHub). Unzip it
   and copy `app-debug.apk` to your phone.
3. Open the file on the phone and allow installing apps from that source when Android asks.

CI debug builds are signed with a key generated for each run, so uninstall the previous CI build
before installing a newer one.

## Build it yourself (Windows, macOS, Linux)

1. Install the latest stable **Android Studio**.
2. **File → Open** and select the **`pulsechat`** folder (not the repository root).
3. Wait for the Gradle sync to finish, then press **Run ▶** (phone or emulator), or use
   **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The APK is written to
   `pulsechat/app/build/outputs/apk/debug/app-debug.apk`.

The first debug build creates `pulsechat/debug.keystore` automatically (it is git-ignored), and no
`.env` file is needed. Details: [Build and run](pulsechat/README.md#build-and-run).

## Before first use

The app uses the Firebase project in `pulsechat/app/google-services.json`. Creating accounts only
works after the one-time console setup (enable **Email/Password** sign-in, deploy the rules); see
[Firebase setup](pulsechat/README.md#firebase-setup-required).

---

## بالعربية: التشغيل السريع

- هذا تطبيق Android أصلي (Kotlin + Jetpack Compose) موجود في مجلد `pulsechat`، وليس تطبيق ويب.
  لذلك لا تنطبق عليه أوامر npm وCapacitor مثل `npm run build` و`npx cap sync`.
- **أسهل طريقة:** تبويب **Actions**، ثم **Android CI**، ثم آخر تشغيل ناجح على `main`. حمّل
  **pulsechat-debug-apk** من قسم Artifacts، وفك الضغط، ثم ثبّت `app-debug.apk` على الهاتف.
- **البناء بنفسك:** افتح مجلد **`pulsechat`** (وليس المجلد الرئيسي) في Android Studio، وانتظر انتهاء
  Gradle sync، ثم اضغط **Run ▶** أو **Build → Build APK(s)**.
- قبل إنشاء الحسابات يجب تفعيل **Email/Password** في Firebase Authentication ونشر قواعد الحماية
  (انظر [Firebase setup](pulsechat/README.md#firebase-setup-required)).

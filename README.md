# PulseChat

Native Android messaging app (Kotlin, Jetpack Compose, Material 3, Firebase).
The Android project is in [`pulsechat/`](pulsechat/); full documentation is in
[`pulsechat/README.md`](pulsechat/README.md).

> **The Android app is native, not a web app.** Capacitor commands (`npx cap add android`,
> `npx cap sync`) do not apply: Capacitor wraps web pages and would produce an empty shell, not
> PulseChat. The repository root has no `package.json`; build the Android app with Gradle/Android Studio.

**Web client:** [`web/`](web/) is a separate PulseChat web app (TypeScript, Firebase JS SDK). It
uses the **same** Firebase project, Firestore database, accounts and security rules as the Android
app, with real-time sync between the two. Setup guide: [`web/README.md`](web/README.md).

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

- تطبيق Android أصلي (Kotlin + Jetpack Compose) موجود في مجلد `pulsechat`، وليس تطبيق ويب.
  لذلك لا تنطبق عليه أوامر Capacitor مثل `npx cap add android` و`npx cap sync`.
- **تطبيق الويب:** مجلد [`web/`](web/) يحتوي عميل ويب منفصلاً يستخدم نفس مشروع Firebase ونفس قاعدة
  البيانات والحسابات وقواعد الأمان، مع مزامنة فورية مع Android. الدليل في [`web/README.md`](web/README.md).
- **أسهل طريقة:** تبويب **Actions**، ثم **Android CI**، ثم آخر تشغيل ناجح على `main`. حمّل
  **pulsechat-debug-apk** من قسم Artifacts، وفك الضغط، ثم ثبّت `app-debug.apk` على الهاتف.
- **البناء بنفسك:** افتح مجلد **`pulsechat`** (وليس المجلد الرئيسي) في Android Studio، وانتظر انتهاء
  Gradle sync، ثم اضغط **Run ▶** أو **Build → Build APK(s)**.
- قبل إنشاء الحسابات يجب تفعيل **Email/Password** في Firebase Authentication ونشر قواعد الحماية
  (انظر [Firebase setup](pulsechat/README.md#firebase-setup-required)).

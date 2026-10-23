# تقرير تحسينات PulseChat — 2026

**التاريخ:** 2026-10-23  
**الفرع:** `arena/ca9ee0a2-pulsechat`  
**النطاق:** تطبيق Android في `pulsechat/` — الحركة والتفاعلات الدقيقة، واجهة Material 3، الأداء وبدء التشغيل، والأمان.

> **حالة التحقق بصراحة:** بيئة العمل الحالية لا تحتوي على Java/Android SDK، لذلك لم يتم تشغيل Gradle محليًا. تم إجراء فحوصات ثابتة (مراجعة المراجع، `git diff --check`، تحليل TOML/XML، مقارنة توازن الأقواس مع HEAD، والتأكد من عدم بقاء `collectAsState` العادي). يبقى **GitHub CI هو الحكم النهائي** عبر: `assembleDebug`، `testDebugUnitTest`، `assembleRelease`، `assembleOptimized`.

---

## ملخص تنفيذي

هذه دفعة تحسين عملية ومباشرة على الكود الحالي، لا إعادة كتابة. الفكرة المركزية: جعل الحركة «فيزيائية» ومتسقة عبر Tokens واحدة، ربط الشاشات الأساسية بعناصر مشتركة، تقليل إعادة التركيب والعمل على الخيط الرئيسي عند الإقلاع، وتشديد الأمان في النقاط التي يمكن إغلاقها دون كسر تدفقات المنتج الحالية (التسجيل قبل المصادقة، المجموعات الكبيرة، إقران الأجهزة).

تم اختيار **Compose BOM `2025.12.00`** بدل أحدث BOM من 2026 كقرار محافظ واعٍ: المشروع على Kotlin 2.2.10 / AGP 9.1.1 ولا توجد إمكانية ترجمة محلية في هذه البيئة، فتم اختيار قفزة كبيرة لكن أقل مخاطرة من 2024.09.00 إلى عائلة Compose 1.10 / Material 3 1.4.

---

## 1) الأنيميشن والتفاعلات الدقيقة Micro-interactions

### 1.1 نظام حركة موحّد: Tweens للتلاشي، وSprings لما يتبع الإصبع

الملف: `ui/theme/Tokens.kt` — أُضيفت إلى `Motion` أربع مواصفات Spring عامة وثابت للضغط:

```kotlin
fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 380f)
fun <T> pop():     FiniteAnimationSpec<T> = spring(dampingRatio = 0.58f, stiffness = 460f)
fun <T> settle():  FiniteAnimationSpec<T> = spring(dampingRatio = 1f,    stiffness = 320f)
fun <T> swipe():   FiniteAnimationSpec<T> = spring(dampingRatio = 0.75f, stiffness = 420f)
const val PRESS_SCALE = 0.94f
```

- `spatial()` للتنقل في الفراغ: حدود العناصر المشتركة والتقاط PiP.
- `pop()` لتأكيدات صغيرة مرحة: هبوط فقاعة الرسالة التي أرسلتها للتو.
- `settle()` بلا تجاوز Overshoot: نمو الملحن Composer لأسطر متعددة.
- `swipe()` للعودة بعد السحب وإفلات الضغط.
- بقيت Tweens الحالية (`EnterEasing/ExitEasing/StandardEasing`) للتلاشي وانزلاقات الشاشات حيث التوقيت الثابت أقرأ بصريًا.

### 1.2 الاهتزاز اللمسي Haptics — مركزي ودلالي

ملف جديد: `ui/common/Haptics.kt`

```kotlin
val haptics = rememberPulseHaptics()
haptics.tick()              // لمسة خفيفة: أزرار المكالمة، بدء التسجيل
haptics.confirm()           // اكتمال: إرسال رسالة/صوتية
haptics.longPress()         // فتح قائمة بالضغط المطوّل على فقاعة
haptics.thresholdCrossed()  // تجاوز عتبة السحب للرد
Modifier.pressScale(interactionSource) // انكماش 0.94 أثناء الضغط وعودة بنابض
```

يطبّق في: إرسال Composer (زر/لوحة مفاتيح/إرسال صوتي)، بدء/حذف التسجيل، الضغط المطوّل على الفقاعة، عتبة Swipe-to-Reply (نقرة واحدة عند التسلّح فقط)، وأزرار المكالمة مع `pressScale`. النظام يحترم إعداد «تعليقات اللمس» في الهاتف تلقائيًا.

### 1.3 العناصر المشتركة Shared Elements

ملف جديد: `ui/navigation/SharedElement.kt` + تعديل `ui/navigation/PulseNavHost.kt`

```kotlin
// في PulseNavHost
SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        NavHost(...) {
            composable(Routes.HOME) {
                CompositionLocalProvider(LocalNavVisibilityScope provides this) { /* ... */ }
            }
            // chat / media / info بنفس النمط
        }
    }
}

// في أي شاشة مشاركة
Modifier.sharedPulseBounds("avatar:<conversationId>")
Modifier.sharedPulseBounds("media:<messageId>")
```

ما يطير فعليًا الآن:
- الصورة الرمزية: قائمة المحادثات (`ChatsTab`) ← ترويسة المحادثة (`ChatScreen`) ← معلومات المحادثة (`ConversationInfoScreen`) عبر `UserAvatar(sharedKey = conversationId)`.
- الصورة داخل الفقاعة (`MessageBubble`) ← عارض الوسائط (`MediaViewerScreen`).

`sharedPulseBounds` يتحول إلى No-op بأمان خارج الوجهات المزوّدة بالنطاق، وفي وضع Lite. كما أصبح انتقال العودة `popEnterTransition` متناظرًا مع الدخول (تلاشٍ + انزلاق من الحافة المقابلة) بدل تلاشٍ فقط، مع احترام وضع Lite.

### 1.4 الفقاعة والملحن والمكالمة

- `ui/chat/MessageBubble.kt`: سحب للرد يتبع الإصبع بـ `snap()` أثناء السحب ثم يعود بـ `Motion.swipe()` محتفظًا بالسرعة؛ رسائلي الطازجة فقط (أقل من 2.5 ثانية، وليس Lite/تقليل حركة) تهبط بـ Pop من 0.92 حول زاويتها السفلية؛ وسم `BubbleCallbacks` بـ `@Stable` لدعم تخطي إعادة التركيب.
- `ui/chat/Composer.kt`: نمو حقل الإدخال عبر `.animateContentSize(Motion.settle())`.
- `ui/chat/ChatScreen.kt`: لوحة الإيموجي تدخل/تخرج بـ `AnimatedVisibility` (تمدد عمودي + تلاشٍ).
- `ui/calls/CallScreen.kt`: معاينة الذات PiP قابلة للسحب، منعّمة بـ `animateIntOffsetAsState(Motion.spatial())`، تلتقط لأقرب حافة أفقية وتبقى داخل الشاشة؛ أزرار المكالمة تحصل على اهتزاز + انكماش عند الضغط.

---

## 2) واجهة Material 3 ونظام التصميم

- `ui/theme/Color.kt` + `ui/theme/Theme.kt`: سلّم حاويات لونية أفتح وأدق للوضع الفاتح (GraphiteLight ladder) مع ربطه في `LightColorScheme` لرفع فصل الطبقات والتباين دون تغيير هوية العلامة في `PulseColors`.
- ألوان ديناميكية Dynamic Color اختيارية ومعطلة افتراضيًا:

```kotlin
@Composable
fun PulseChatTheme(
    dynamicColor: Boolean = false, // Android 12+؛ للهيكل Chrome فقط عند التفعيل لاحقًا
    ...
)
```

يبقى قرار التفعيل للمستخدم منتجًا/إعدادًا مستقبليًا؛ ألوان العلامة الداخلية لا تستبدل.
- الزوايا الحالية في `Radii/PulseShapes` كانت منضبطة أصلًا؛ ركّزت الدفعة على جعل الفقاعة والمكالمة تنافس تجارب Telegram/WhatsApp عبر الحركة، الخلفية المحيطية في المكالمة، PiP القابل للسحب، والعناصر المشتركة، بدل إعادة رسم كل شيء بصريًا بما يخاطر بكسر لقطات/تدفقات حالية.

---

## 3) الأداء وسلاسة العرض وبدء التشغيل

### 3.1 دورة الحياة والاستقرار التركيبي

- تحويل شامل في `main` من `collectAsState` إلى `collectAsStateWithLifecycle` (21 ملفًا تستورد النسخة الواعية بدورة الحياة؛ لا بقايا للعادية) وتحويل `initial =` إلى `initialValue =` حيث لزم. النتيجة: توقف الجمع عندما لا تكون الشاشة مرئية بما يكفي، بدل عمل خلفي يستهلك البطارية ويعيد التركيب بلا داعٍ.
- `app/compose-stability.conf` + كتلة `composeCompiler` في `app/build.gradle.kts` (ملف استقرار + تقارير/مقاييس)، و`@Stable` على `BubbleCallbacks` و`PulseHaptics`؛ ما يمكن تخطيه بأمان سيتخطاه المترجم.
- مفاتيح LazyColumn والفصل الحالي للحالات سريعة التغير في شاشة المحادثة كانا موجودين من قبل؛ حُفظا ولم تتم إعادة كتابتهما بلا فائدة.

### 3.2 الإقلاع والذاكرة

- `MainActivity`: شاشة بداية رسمية عبر `androidx.core:core-splashscreen` و`Theme.PulseChat.Splash` تبقى حتى أول إطار Compose جاهز (`contentReady`).
- `AppContainer.initialize()`: يبقى تثبيت App Check متزامنًا، ثم ينتقل ربط الكائنات الكسولة (Firestore/Repositories/CallManager) إلى `appScope.launch` بعيدًا عن الخيط الرئيسي في `Application.onCreate`.
- `PulseChatApplication` (Coil): ذاكرة صور بحد 18%، قرص 128MB في مجلد `image_cache` (الاسم ثابت لأن `StorageManager` يعتمد عليه)، و`crossfade(200)`.
- Baseline/Startup Profiles يدوية: `app/src/main/baseline-prof.txt` و`app/src/main/startup-prof.txt` مع `androidx.profileinstaller`. هذه ملفات مبدئية مكتوبة يدويًا؛ التوليد الرسمي بماكرو بنشمارك على جهاز يبقى عمل متابعة على CI/جهاز فعلي.
- تفعيل R8 وتصغير الموارد في الإصدار: `isMinifyEnabled` + `isShrinkResources` (تم فحص غياب `getIdentifier` الذي يكسر التصغير عادة).

---

## 4) الأمان وحماية البيانات

### 4.1 قفل التطبيق والمفاتيح

- `security/AppLockManager.kt`: تفضيل `BIOMETRIC_STRONG` عند توفره (Android 11+) والرجوع إلى WEAK فقط عند غياب الأقوى؛ سماح العودة من شاشات خارجية مثل المنتقي حتى 5 دقائق؛ إغلاق المسار القديم لاعتماد الجهاز على وضع **Fail-Closed** بدل فتح صامت عند غياب قفل شاشة أو Intent، مع بقاء منفذ «تسجيل الخروج» في واجهة القفل لإعادة التعيين.
- `MainActivity`: تفعيل `FLAG_SECURE` أثناء تمكين قفل التطبيق (حماية من لقطات الشاشة ومعاينة Recents).
- `security/DeviceKeyManager.kt`: أي سقوط احتياطي يخزّن مفتاحًا خامًا في التفضيلات أصبح **DEBUG فقط** ويفشل مغلقًا في الإصدار؛ مفاتيح AES تحصل على `setInvalidatedByBiometricEnrollment(true)` و`setUnlockedDeviceRequired(true)` (API 28+)؛ عند `KeyPermanentlyInvalidatedException` تُحذف خانة الـKeystore ويُطلب إعادة إقران عند تسجيل الدخول التالي. ملاحظة: المفاتيح المنشأة في إصدارات أقدم تحتفظ بمعلماتها القديمة حتى إعادة إنشائها.

### 4.2 الشبكة وقواعد Firebase وR8

- `res/xml/network_security_config.xml` مربوط الآن فعليًا من `AndroidManifest.xml`.
- `firestore.rules`: إضافة رفض صريح شامل في النهاية لأي مسار غير مُدار بقواعد خاصة.
- `storage.rules`: تقييد وسائط المحادثة بأنواع MIME عملية `(image|video|audio|application|text)/.*`.
- `app/proguard-rules.pro`: استبدال قالب التعليقات بقواعد عملية لمشروع Firebase/Room/Serialization/WebRTC مع الحفاظ على ما يحتاجه الانعكاس فعليًا، وإزالة سجلات التصحيح في الإصدار حيث يناسب.

### ما لم يتغير عمدًا

- الرسائل ليست مشفرة طرفًا لطرف بعد؛ النص موجود في Firestore كما كان. تشفير Signal Protocol وSQLCipher خارطة طريق، وليسا جزءًا من هذه الدفعة ولا ينبغي ادعاؤهما تسويقيًا.
- لم تُقيّد قراءات ما قبل المصادقة لـ `usernames/uniqueIds` لأن التسجيل يحتاجها، ولم يُفرض سقف 4KB على CSV المشاركين لأن حد المجموعة 256 عضوًا يتجاوزه.

---

## خريطة الملفات الأهم

| المنطقة | الملفات |
|---|---|
| الحركة | `ui/theme/Tokens.kt`, `ui/common/Haptics.kt`, `ui/navigation/SharedElement.kt`, `ui/navigation/PulseNavHost.kt` |
| الدردشة | `ui/chat/MessageBubble.kt`, `ui/chat/Composer.kt`, `ui/chat/ChatScreen.kt`, `ui/components/UserAvatar.kt`, `ui/misc/MediaViewerScreen.kt` |
| المكالمات | `ui/calls/CallScreen.kt` |
| الثيم | `ui/theme/Color.kt`, `ui/theme/Theme.kt` |
| الإقلاع/الأداء | `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AppContainer.kt`, `PulseChatApplication.kt`, `MainActivity.kt`, `baseline-prof.txt`, `startup-prof.txt`, `compose-stability.conf` |
| الأمان | `security/AppLockManager.kt`, `security/DeviceKeyManager.kt`, `AndroidManifest.xml`, `res/xml/network_security_config.xml`, `firestore.rules`, `storage.rules`, `app/proguard-rules.pro` |

## قائمة يدوية قبل الإصدار (خارج الكود)

1. نشر `firestore.rules` و`storage.rules` بعد نجاح اختبارات القواعد، ثم اختبار تسجيل مستخدم جديد ورفع وسائط مجموعة كبيرة.
2. في Firebase Console: فرض App Check تدريجيًا مع مراقبة الرفض، وتقييد مفاتيح API بحزم Android/Web المناسبة.
3. اختبار نسخة Release موقعة على جهاز حقيقي: مكالمات، إشعارات، قفل بصمة قوية/ضعيفة، تغيير تسجيل البصمة (يجب أن يطلب إعادة إقران)، فتح وسائط محادثة مقفلة، ولقطة شاشة أثناء قفل التطبيق.
4. قياس بدء التشغيل البارد قبل/بعد بماكرو بنشمارك، وتوليد Baseline Profile رسمي ليحل محل الملف اليدوي تدريجيًا.
5. قرار منتج: هل يظهر خيار «ألوان النظام الديناميكية» في الإعدادات؟ الكود جاهز ومعطل افتراضيًا.

## خارطة طريق لاحقة

- تشفير طرف لطرف كامل للرسائل والوسائط ومفاتيح المجموعات، ثم SQLCipher لقاعدة Room.
- تنقّل آمن الأنواع Type-safe Navigation تدريجيًا بدل السلاسل.
- تقوية تفويض Storage عبر مطالبات مخصصة/دوال خادم؛ قاعدة Firestore مسماة حاليًا تمنع فحوصًا موثوقة عبر الخدمات وبيانات الرفع الوصفية ما تزال يعلنها العميل.
- توليد Baseline Profiles تلقائيًا في CI على جهاز مُدار.

# PulseChat R8 (release) rules.
#
# Goal: shrink + obfuscate everything that is reachable only from app code, while keeping the
# small set of classes that frameworks instantiate by name/reflection (Room codegen, Moshi
# codegen adapters, Firebase, WebRTC JNI, WorkManager workers). Library consumer rules already
# cover most third-party needs; the rules below cover PulseChat's own reflection/naming needs.
# Upload app/build/outputs/mapping/release/mapping.txt with every Play release so crash reports
# can be de-obfuscated. Line numbers are kept (with the source file name hidden) for that reason.

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable,*Annotation*
-renamesourcefileattribute SourceFile

# --------------------------------------------------------------------- Kotlin / coroutines
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }

# --------------------------------------------------------------------- enums on the wire
# MessageType / MessageStatus / TransferState (and friends) are stored by enum *name* in Room and
# mapped to Firestore fields, so the constant names must never be renamed.
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# --------------------------------------------------------------------- Room (KSP codegen)
-keep class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep class **_Impl {
    <init>(...);
    *;
}

# --------------------------------------------------------------------- Moshi (KSP codegen)
-keep class **JsonAdapter {
    <init>(...);
}
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# --------------------------------------------------------------------- Firebase / Firestore
# PulseChat maps Firestore documents manually (data/remote), but any field/getter annotated for
# Firestore must keep its name so document keys stay stable across obfuscated builds.
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
    @com.google.firebase.firestore.DocumentId <fields>;
    @com.google.firebase.firestore.DocumentId <methods>;
}
-keep class com.example.data.remote.** { *; }

# --------------------------------------------------------------------- WebRTC (Stream, JNI)
-keep class org.webrtc.** { *; }

# --------------------------------------------------------------------- WorkManager
-keep class * extends androidx.work.Worker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --------------------------------------------------------------------- Parcelable / Serializable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    private static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --------------------------------------------------------------------- release logging
# Verbose/debug/info logs (and their string building) are stripped from release builds; warnings
# and errors stay, as they carry failure context without payload data.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# --------------------------------------------------------------------- optional crypto providers (OkHttp)
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

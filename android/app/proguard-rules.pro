# Gitsync.md ProGuard/R8 Rules
# Keep all Kotlin metadata for reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }

# Keep data classes
-keepclassmembers class * {
    *** *(...);
}

# Keep Hilt generated code
-keep class dagger.hilt.** { *; }
-keep interface dagger.hilt.** { *; }

# Keep Room database entities
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep Compose
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }

# Keep Material3
-keep class androidx.compose.material3.** { *; }
-keep interface androidx.compose.material3.** { *; }

# Keep navigation compose
-keep class androidx.navigation.compose.** { *; }

# Keep StateFlow and reactive classes
-keep class kotlinx.coroutines.flow.** { *; }

# Keep our git services and models
-keep class com.bontecou.syncmd.services.** { *; }
-keep class com.bontecou.syncmd.data.** { *; }
-keep class com.bontecou.syncmd.ui.viewmodels.** { *; }
# Persisted Gson move payloads must survive shrinking and retain their schema across updates.
-keep class com.bontecou.syncmd.storage.RepositoryMoveMetadata { *; }
-keep class com.bontecou.syncmd.onboarding.GuideTarget { *; }

# Keep parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep Kotlin extension functions
-keepclassmembers class kotlin.** {
    kotlin.jvm.internal.ReflectionFactory *;
}

# Keep names for debugging (remove -printmapping to reduce output)
-verbose
-printmapping build/outputs/mapping/release/mapping.txt
-printseeds build/outputs/seeds/release/seeds.txt

# Optimization settings
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# JGit references optional JVM-only APIs not present on Android
# Translation bundle class and field names must match the packaged .properties
# resources: JGit looks them up reflectively, including when starting a push.
-keep class * extends org.eclipse.jgit.nls.TranslationBundle { *; }
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# JSch selects cryptographic implementations by class name in its algorithm
# registry. Bouncy Castle also loads provider mappings reflectively on Android.
-keep class com.jcraft.jsch.jce.** { *; }
-keep class com.jcraft.jsch.bc.** { *; }
-keep class com.jcraft.jsch.jzlib.** { *; }
-keep class * extends com.jcraft.jsch.KeyExchange { *; }
-keep class com.jcraft.jsch.UserAuthNone { *; }
-keep class com.jcraft.jsch.UserAuthPublicKey { *; }
-keep class com.jcraft.jsch.CipherNone { *; }
-keep class org.bouncycastle.** { *; }
# BC's unused LDAP certificate-store adapters reference desktop JNDI APIs.
-dontwarn javax.naming.**

# Logging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

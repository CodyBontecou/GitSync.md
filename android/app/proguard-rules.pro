# Preserve Hilt generated classes
-keep,allowobfuscation @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep,allowobfuscation @dagger.hilt.android.qualifiers.ActivityContext class *

# Preserve JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve Retrofit
-keep class * extends com.squareup.okhttp3.Interceptor
-keepclasseswithmembernames class * {
    @retrofit2.* <methods>;
}

# Preserve model classes with Gson
-keep class com.bontecou.syncmd.data.models.** { *; }

# Preserve coroutines
-keep class kotlinx.coroutines.** { *; }

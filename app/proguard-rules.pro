# ProGuard/R8 rules for the release build type (see app/build.gradle.kts).
#
# Libraries in use (kotlinx.serialization, OkHttp, Room, WorkManager, Compose) ship consumer rules;
# keep app-defined serialization DTOs intact under R8 minification.

-keepattributes *Annotation*
-keepclassmembers class com.example.lastmeeting.data.api.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.lastmeeting.data.api.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

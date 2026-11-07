# ProGuard/R8 rules for the release build type (see app/build.gradle.kts).
#
# Deliberately empty: release builds currently run with isMinifyEnabled = false, so R8 never
# reads this file. It exists so the build type has its conventional rules slot and enabling
# minification does not fail on a missing file.
#
# The libraries in use (kotlinx.serialization, OkHttp, Room, WorkManager, Compose) ship their
# own consumer rules, so only app-specific reflection or JNI entry points belong here.

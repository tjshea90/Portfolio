# R8 for the release build - see the note on `isMinifyEnabled` in build.gradle.kts.
#
# OPTIMISE AND SHRINK, BUT DO NOT RENAME. R8's speed comes from inlining, devirtualising, class
# merging and dead-code removal - above all in Jetpack Compose, whose libraries ship rules
# that let R8 strip their debug/tracing paths. Obfuscation (renaming) adds nothing to speed on
# a sideloaded app; it would only turn the crash log Tj sends back into `a.b.c(Unknown)`.
-dontobfuscate

# Real file names and line numbers in the crash log's stack traces.
-keepattributes SourceFile,LineNumberTable

# The app uses no reflection, no JavaScript bridge, no Serializable/Parcelable and no
# resource-name lookups (checked 2026-09-22), so nothing of its own needs a keep rule.
# Kotlin, coroutines, Compose and AndroidX all ship their own consumer rules in their AARs.

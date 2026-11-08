# Phase 17J: R8 rules for release builds (isMinifyEnabled = true, isShrinkResources = true).
#
# WHY THESE RULES EXIST
# Gson reads and writes our data classes by FIELD NAME (the app uses no @SerializedName). R8 would rename those fields
# ("userId" -> "a"), and the JSON would then silently come back empty - only in release builds, and the unit tests
# (which run unminified) would never notice. The same names are also written to the phone's own files (the evidence
# vault index, the Safety Circle alert copy, the community outbox, private places), so they must not change between
# versions either.

# Keep the generic types, annotations and line numbers that reflection, Retrofit and crash reports need.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Every field of every class of ours keeps its name (this includes enum constants, which Gson maps by name).
-keepclassmembers class com.safesphere.app.** { <fields>; }

# FOUND BY RUNNING THE MINIFIED BUILD (second finding, same walk): the line above is NOT enough for a class that only
# Gson ever fills (nothing in our code constructs it). R8 merged `ApiCallHandler.ErrorBody` into an unrelated class and its
# `message` / `code` fields vanished, so the server's own words and codes were silently dropped in release only
# (a duplicate Safety Circle number showed "That conflicts with existing data" instead of "This phone number is already in
# your Safety Circle"; AGE_NOT_CONFIRMED / PROFILE_NEEDED style codes would have been lost too). Our classes are therefore
# kept as classes (their NAMES may still be obfuscated); this costs a little shrinking of our own code, none of the libraries'.
-keep,allowobfuscation class com.safesphere.app.**

# The Retrofit API interfaces and their request/response classes are used only through reflection.
-keep class com.safesphere.app.**.data.remote.** { *; }

# FOUND BY RUNNING THE MINIFIED BUILD (releaseCheck on the emulator, 2026-11-08): without these two rules R8 strips the
# generic signature of our `object : TypeToken<List<...>>() {}` classes and the app CRASHES AT LAUNCH ("TypeToken must be
# created with a type argument") the first time the evidence / recovery-key stores are built. `assembleRelease` and the
# unit tests cannot see this.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Retrofit, OkHttp and Coil bring their own consumer rules (Retrofit 2.11 keeps its annotated interface methods and the
# coroutine Continuation).

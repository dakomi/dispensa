# Preservation of data entities for GSON serialization/deserialization (Backup/Restore)
-keep class eu.frigo.dispensa.data.** { *; }

# Sync classes — keep all sync infrastructure from R8 shrinking
-keep class eu.frigo.dispensa.sync.** { *; }

# Gson serialization keep rules for SyncChange / SyncBlob (field names must survive R8)
-keepclassmembers class eu.frigo.dispensa.sync.SyncChange { *; }
-keepclassmembers class eu.frigo.dispensa.sync.SyncBlob { *; }

# GSON specific rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.annotations.SerializedName

# ── Google API Client Library (Drive) ────────────────────────────────────────
# R8 class-merging optimisation can make GenericJson and its subclasses abstract,
# breaking the reflection-based Gson deserialization of Drive API responses with
# "IllegalArgumentException: key error" / "unable to create new instance of class
# X because it is abstract" errors.  Keep the full google-api-client and
# google-api-services-drive class hierarchies intact.
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }

# Keep all fields annotated with @Key — these drive Gson field-name mapping in
# GenericData / GenericJson subclasses; stripping them produces "key error" at runtime.
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}

# Keep concrete subclasses of GenericJson so Gson can instantiate them.
-keep class * extends com.google.api.client.json.GenericJson { *; }

# Apache HttpClient (bundled via Google Drive SDK) references desktop-Java classes
# (javax.naming.*, org.ietf.jgss.*) that do not exist on Android.  Suppress the
# R8 missing-class errors so the build does not fail.
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
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
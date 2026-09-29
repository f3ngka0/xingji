-keep class com.amap.api.location.** { *; }
# Gson serializes these API request/response fields reflectively.
-keep class com.tripshare.app.data.remote.** { *; }
# Tink references optional static analysis annotations that have no runtime role.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

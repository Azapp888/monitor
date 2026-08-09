# 保持模型类不被混淆
-keep class com.venue.monitor.data.** { *; }
# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# 企微考试 R8 混淆规则（v1.8 代码保护）

# ---- 注解属性（Room/KSP 需要） ----
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# ---- Room（实体/DAO/数据库由反射+生成代码使用） ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * { @androidx.room.* <methods>; }

# ---- 2FA / 密钥（安全逻辑保持类名可读，避免误裁） ----
-keep class com.qweexam.app.auth.** { *; }

# ---- 协程（防御性，官方 AAR 自带规则） ----
-dontwarn kotlinx.coroutines.**

# 注：Manifest 中引用的 Activity / Service 由 AGP 自动保留（exported 组件），无需手动 keep

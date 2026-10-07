# ============================================================
# kotlinx.serialization ProGuard / R8 规则
# 迁移背景：用 kotlinx.serialization 替代 Android 自带的 org.json。
# release 构建开启 minify(R8)，必须保留所有 @Serializable 领域模型
# 及其编译期生成的序列化器（Companion.serializer / *Serializer），
# 否则运行时会抛 MissingFieldException / SerializationException 或
# NoSuchFieldException（序列化器被混淆/裁剪）。
# ============================================================

# 保留注解、内部类与泛型签名（kotlinx.serialization 在运行时按类型查找序列化器，
# 依赖 @Serializable 注解与 Companion 的泛型信息）。
-keepattributes *Annotation*, InnerClasses, Signature

# 保留 kotlinx.serialization 运行时与编译器生成的代码。
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# 保留 com.mcp 下所有 @Serializable 类的 Companion 对象（持有 serializer 单例）。
-keepclassmembers class com.mcp.** {
    *** Companion;
}

# 保留 serializer() 入口（运行时按类型查找序列化器）。
-keepclasseswithmembers class com.mcp.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 保留手写/生成的 Serializer 单例（实现 KSerializer 的类）。
-keepclassmembers class com.mcp.** {
    *** Serializer;
}

# 保留 @Serializable 标注的类本身及其成员，避免被整体移除或混淆破坏字段名映射。
# 覆盖本次迁移的模型（跨模块，库模块的 consumer rules 之外仍在此兜底）：
#   com.mcp.core.chat.ChatMessage / ToolCallData   (:core)
#   com.mcp.deepseek.DeepSeekApi.ChatSession       (:llm)
#   com.mcp.TodoTask.TodoTask / TodoStatus(enum)   (:app)
#   com.mcp.skill.SkillDefinition                  (:app)
-keepclasseswithmembers @kotlinx.serialization.Serializable class * {
    *;
}

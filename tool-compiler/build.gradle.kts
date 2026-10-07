plugins {
    // 纯 Kotlin/JVM 模块：不依赖 Android SDK，可在桌面直接跑测试 / demo，也能被 :app 引用。
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
}

group = "com.mcp"
version = "0.1.0"

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // 健壮的 HTML 解析（解析搜索结果的事实标准），替代脆弱的正则
    implementation("org.jsoup:jsoup:1.22.2")
    // 成熟的 HTTP 客户端：透明 gzip、连接池、按 Content-Type 解码字符集、
    // followSslRedirects(false) 从根上避免 https→http 降级的 TLS 明文错误
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.20")
}

kotlin {
    // 本机仅有 Java 20，且 Gradle 不会自动下载 JDK；
    // 用运行 Gradle 的 JDK(20) 作为编译 JDK，但把字节码目标锁到 JVM 17，保证 Android 也能消费。
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // 让 @Tool 注解方法在运行时反射能拿到参数名（否则只能靠 @Param(name=) 显式指定）
        freeCompilerArgs.add("-java-parameters")
    }
}

// 对齐 Java 编译目标到 17（application 插件默认会用运行 JVM=20，需显式降下来与 Kotlin 一致）
java {
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    // 演示入口：编译工具并打印喂给 LLM 的定义，再模拟一次调用
    mainClass.set("com.mcp.toolbox.demo.DemoKt")
}

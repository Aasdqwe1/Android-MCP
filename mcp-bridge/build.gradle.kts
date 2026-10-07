plugins {
    // 纯 Kotlin/JVM 模块：不依赖 Android SDK，可独立跑协议层单元测试。
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

group = "com.mcp"
version = "0.1.0"

dependencies {
    // 复用工具注册表 / 分发器 / MCP schema 编译器
    api(project(":tool-compiler"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.20")
}

kotlin {
    compilerOptions {
        // 与 tool-compiler 一致：字节码目标锁 JVM 17，保证 Android 可消费。
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_17
}

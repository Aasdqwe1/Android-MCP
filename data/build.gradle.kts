plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // kotlinx.serialization：SessionEventLog 里的事件溯源模型（SessionLogHeader / SessionLogEvent）
    // 需在 data 模块内生成序列化器；版本由根 build.gradle.kts 统一声明。
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mcp.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
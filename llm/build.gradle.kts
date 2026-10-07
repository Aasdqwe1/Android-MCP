plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mcp.llm"
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
    api(project(":tool-compiler"))
    implementation(project(":core"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 单元测试：SSEPatcher 的 DeepSeek SSE 还原逻辑（token 用量解析等）。
    // org.json 必须用真实实现——Android 的 android.jar 里是 stub（方法体为空/返回默认值），
    // 本地 JVM 单测若走 stub，JSONObject 解析会得到空结果，测不出真实行为。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
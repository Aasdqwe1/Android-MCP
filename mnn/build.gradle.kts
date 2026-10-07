plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mcp.mnn"
    compileSdk = 36

    defaultConfig {
        // MNN 官方支持 Android 4.3+（API 18），无需像 :litert 那样抬到 26。
        // 与 app 的 minSdk=24 对齐，避免 manifest 合并失败（library 的 minSdk
        // 高于 app 时 AGP 会直接报错）。低版本设备由运行时检查兜底。
        minSdk = 24
        ndk {
            // 只保留 arm64：MNN Chat 只提供该 ABI 的预编译库，且目标设备即 arm64
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 预编译 .so 直接打进 APK；不参与编译，无需 CMake/NDK 工具链
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // LLMClient 抽象在 :llm；MnnClient 实现它
    implementation(project(":llm"))
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Gson：MNN native 接收的 ModelConfig JSON 由 Gson 形态的字段名决定，
    // 与 MNN Chat 保持一致，避免手写序列化漏字段。
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

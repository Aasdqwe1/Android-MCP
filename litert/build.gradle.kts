plugins {
        id("com.android.library")
        id("org.jetbrains.kotlin.android")
    }
    
    android {
        namespace = "com.mcp.litert"
        compileSdk = 36
    
        defaultConfig {
            // LiteRT-LM 官方要求 API 26+（现有项目 minSdk=24，本模块单独抬升）
            minSdk = 26
        }
    
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    
    dependencies {
        // LLMClient 抽象在 :llm；LiteRtClient 实现它
        implementation(project(":llm"))
        implementation(project(":core"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    
        // LiteRT-LM 官方 Kotlin API（Google Maven，含 native 库）
        api("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    
        testImplementation("junit:junit:4.13.2")
    }
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
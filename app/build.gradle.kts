plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // kotlinx.serialization：与根 build.gradle.kts 的 Kotlin 版本(2.1.20)保持一致。
    // 注意：序列化插件版本由根目录统一声明，这里 apply 即可，不要另写版本号。
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mcp"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    // 显式声明 assets 源目录，确保 skills 子目录被打包到 APK
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

    defaultConfig {
        applicationId = "com.mcp1"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    // 统一 CI 构建签名：使用提交进仓库的固定 keystore，避免每次 CI runner 自动生成
    // 新 debug key 导致签名变化、用户无法覆盖安装。
    // 注意：该 keystore 仅用于预发布/debug 分发，发布正式版请改用私密 release 密钥。
    signingConfigs {
        getByName("debug") {
            storeFile = file("ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                abiFilters.add("arm64-v8a")
            }
        }
        debug {
            ndk {
                abiFilters.add("arm64-v8a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 打包排除：BouncyCastle 系列 jar 在 META-INF/versions/9 下各带一份
    // OSGI-INF/MANIFEST.MF（多版本 jar 的 OSGi 元数据），彼此同名冲突。
    // 这些文件只服务 OSGi 容器，Android 用不到，排除即可。
    // 同理排除各类签名文件（.SF/.RSA/.DSA），避免「无效签名」类打包错误。
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
    }

    buildFeatures {
        viewBinding = false
    }

    // 本地 JVM 单测：android.util.Log 等桩方法返回默认值（否则 JsEngine 单测会抛 "not mocked"）
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

}

dependencies {
    // 核心会话编排、技能仓库与提示词组合器
    implementation(project(":core"))
    implementation(project(":data"))
    // LLM 后端与 DeepSeek 协议层
    implementation(project(":llm"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.14.0")
    // material 1.14.0 起不再传递依赖 documentfile；本项目 SAF 能力直接使用它，显式声明
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // 协程：发消息改用 Coroutine + Flow（PoW 后台求解带进度、SSE 流式收集）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // XZ decoder used to unpack the fixed Debian rootfs on API 24+.
    implementation("org.tukaani:xz:1.10")
    // 内嵌 HTTP 服务器（桌面端 Web 客户端）：静态资源 + JSON API + SSE，与 ChatBridge 同进程
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // BouncyCastle：运行时生成自签名证书（Web 服务 HTTPS 需要）。
    // 只用到 bcpkix（证书构造）+ bcprov（RSA/摘要，由 bcpkix 传递引入）。
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // 安全加密：Token 加密存储于 Android Keystore（AES-256-GCM）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // 二维码生成：微信登录扫码用（ZXing core 纯算法，无相机权限）
    implementation("com.google.zxing:core:3.5.3")
    // 工具编译器：自定义 MCP 工具（DSL + 注解），自动生成喂给 LLM 的工具定义并分发执行
    implementation(project(":tool-compiler"))
    // MCP 双向服务：本地工具发射（Server）+ 远程 MCP 工具接收（Client）
    implementation(project(":mcp-bridge"))
    // LiteRT-LM 本地模型后端（离线推理）
    implementation(project(":litert"))
    // MNN-LLM 本地模型后端（复用 MNN Chat 预编译 native 库）
    implementation(project(":mnn"))
    // kotlinx.serialization-json：结构化 JSON 解析（替代 org.json，类型安全、编译期检查、无反射）。
    // 版本与 tool-compiler 对齐为 1.8.0（序列化插件 2.1.20 兼容）。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    // Mozilla Rhino：run_code 的 JS 分支 in-JVM 执行引擎（P1-C；替代 PRoot+Python 跨进程 IPC）
    implementation("org.mozilla:rhino:1.7.15")
    testImplementation("junit:junit:4.13.2")
}
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
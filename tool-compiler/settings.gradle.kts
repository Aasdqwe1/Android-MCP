// 独立构建用 settings：仅包含 tool-compiler 自身（不含 :app，故无需 Android SDK）。
// 用法：仓库根目录执行  ./gradlew -c tool-compiler/settings.gradle.kts run
// 正式集成进 Android 工程时，由根 settings.gradle.kts 的 include(":tool-compiler") 管理，本文件不参与。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// 依赖仓库（settings 级，避免在各 build.gradle.kts 里重复声明，兼容 FAIL_ON_PROJECT_REPOS）
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "tool-compiler"

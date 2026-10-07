pluginManagement {
    repositories {
        // 阿里云镜像优先：同步了 Central / Google / Plugin Portal，
        // 在 CI 共享出口 IP 被 Maven Central 限流（HTTP 429）时仍可正常拉取。
        // 放最前，官方源兜底。
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 同上：阿里云镜像优先，官方源兜底。
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // kept for potential future dependencies
    }
}

rootProject.name = "AgentToolbox"
include(":app")
include(":core")
include(":data")
include(":llm")
include(":tool-compiler")
include(":mcp-bridge")
include(":litert")
include(":mnn")

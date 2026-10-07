pluginManagement {
    repositories {
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

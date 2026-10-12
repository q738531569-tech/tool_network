// 根设置：声明插件仓库与参与构建的 6 个模块（core-router 源码保留、不参与构建）
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
    }
}

rootProject.name = "network-toolbox"

// 已实现模块（M1 设备发现/诊断 / M2 抓包 / M3 海康 ISAPI / M5 AI Agent），共 include 6 个
include(":app")
include(":core-common")
include(":core-network")
include(":core-capture")
include(":core-isapi")
include(":core-agent")
// M4 路由器适配已放弃：core-router 源码保留在目录中，不再参与构建

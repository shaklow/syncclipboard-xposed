@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// foojay-resolver-convention removed: JDK 21 toolchain preinstalled in build env

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
    }
}

include(
    ":shell",
    ":app",
    ":bridge",
    ":xposed",
    ":common",
)

rootProject.name = "clipboard-xposed"

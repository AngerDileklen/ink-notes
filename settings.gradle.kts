pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("vendor/maven"); content { includeGroup("dev.hanvon.ink") } }
        google()
        mavenCentral()
    }
}
rootProject.name = "eink-notes"
include(":app")

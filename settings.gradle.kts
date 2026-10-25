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
        google()
        mavenCentral()
    }
}

rootProject.name = "dubi-tv"
include(":app")
// Stand-in apps (fake YouTube, Netflix, ...) for exercising the dock on an emulator; never shipped.
include(":stubapps")
project(":stubapps").projectDir = file("tools/stubapps")

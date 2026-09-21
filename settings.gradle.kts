pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "lynx"

include(":core:model")
include(":host:session")
include(":host:adb")
include(":host:daemon")
include(":host:network")
include(":host:database")
include(":apps:cli")

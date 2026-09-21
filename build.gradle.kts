plugins {
    kotlin("jvm") version "2.3.0" apply false
}

group = "dev.lynx"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

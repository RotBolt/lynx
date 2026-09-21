plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
            kotlin.srcDir("src/main/kotlin")
        }
        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
            kotlin.srcDir("src/test/kotlin")
        }
        val jvmMain by getting
        val jvmTest by getting
    }
}

dependencies {
    // Keep the legacy JVM test task available for existing Gradle invocations.
    add("jvmTestImplementation", kotlin("test"))
}

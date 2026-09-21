plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
    }
    macosArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies { api(project(":core:model")) }
        }
        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
        // The existing socket/SSLEngine implementation remains a JVM backend.
        val jvmMain by getting {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                implementation(project(":host:daemon"))
                implementation(project(":host:adb"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
                implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
                implementation("io.netty:netty-all:4.1.115.Final")
            }
        }
        val jvmTest by getting {
            kotlin.srcDir("src/test/kotlin")
            dependencies { implementation(kotlin("test")) }
        }
        val posixMain by creating {
            dependsOn(commonMain)
            dependencies { implementation(project(":host:native")) }
        }
        val macosArm64Main by getting { dependsOn(posixMain) }
        val linuxX64Main by getting { dependsOn(posixMain) }
        val mingwX64Main by getting { dependsOn(commonMain) }
        val posixTest by creating {
            dependsOn(commonTest)
            dependencies { implementation(project(":host:native")) }
        }
        val macosArm64Test by getting { dependsOn(posixTest) }
        val linuxX64Test by getting { dependsOn(posixTest) }
    }
}

// Preserve the repository-wide `./gradlew test` regression gate after moving
// the JVM backend into a KMP source set.
tasks.register("test") { dependsOn("jvmTest") }

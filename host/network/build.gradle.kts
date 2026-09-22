plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val linuxNativeLibDir = providers.environmentVariable("LYNX_LINUX_LIB_DIR").orNull
val allowLinuxSharedUndefined = providers.environmentVariable("LYNX_LINUX_ALLOW_SHLIB_UNDEFINED").orNull == "true"

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
    }
    macosArm64()
    linuxX64 {
        binaries.all {
            linuxNativeLibDir?.let { linkerOpts("-L$it") }
            if (allowLinuxSharedUndefined) linkerOpts("-Wl,--allow-shlib-undefined")
        }
        compilations.getByName("main").cinterops {
            create("lynxSsl") {
                defFile(project.file("src/nativeInterop/cinterop/lynx-ssl-linux.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
                if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
                    compilerOpts("-I/opt/homebrew/opt/openssl@3/include")
                }
            }
            create("lynxH2") {
                defFile(project.file("src/nativeInterop/cinterop/lynx-h2-linux.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
                if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
                    compilerOpts("-I/opt/homebrew/include")
                }
            }
            create("lynxZlib") {
                defFile(project.file("src/nativeInterop/cinterop/lynx-zlib-linux.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
            }
            create("lynxBrotli") {
                defFile(project.file("src/nativeInterop/cinterop/lynx-brotli-linux.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
                if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
                    compilerOpts("-I/opt/homebrew/include")
                }
            }
        }
    }
    macosArm64 {
        compilations.getByName("main").cinterops {
            create("lynxSsl") {
                defFile(project.file("src/nativeInterop/cinterop/lynx-ssl-macos.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
            }
            create("lynxH2") {
                defFile(project.file("src/nativeInterop/cinterop/lynx-h2-macos.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
            }
            create("lynxZlib") {
                defFile(project.file("src/nativeInterop/cinterop/lynx-zlib-macos.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
            }
            create("lynxBrotli") {
                defFile(project.file("src/nativeInterop/cinterop/lynx-brotli-macos.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
            }
        }
    }
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

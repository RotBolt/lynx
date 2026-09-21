plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val linuxNativeLibDir = providers.environmentVariable("LYNX_LINUX_LIB_DIR").orNull
val allowLinuxSharedUndefined = providers.environmentVariable("LYNX_LINUX_ALLOW_SHLIB_UNDEFINED").orNull == "true"

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
        binaries {
            executable {
                mainClass.set("dev.lynx.cli.MainKt")
            }
        }
    }
    macosArm64 {
        binaries { executable { baseName = "lynx"; entryPoint = "dev.lynx.nativecli.main" } }
    }
    linuxX64 {
        binaries {
            all {
                linuxNativeLibDir?.let { linkerOpts("-L$it") }
                if (allowLinuxSharedUndefined) linkerOpts("-Wl,--allow-shlib-undefined")
            }
            executable { baseName = "lynx"; entryPoint = "dev.lynx.nativecli.main" }
        }
    }
    mingwX64 {
        binaries { executable { baseName = "lynx"; entryPoint = "dev.lynx.nativecli.main" } }
    }

    sourceSets {
        val commonMain by getting
        val commonTest by getting
        val jvmMain by getting {
            dependencies {
                implementation(project(":core:model"))
                implementation(project(":host:session"))
                implementation(project(":host:daemon"))
                implementation(project(":host:adb"))
                implementation(project(":host:network"))
                implementation(project(":host:database"))
                implementation("com.google.code.gson:gson:2.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        val jvmTest by getting {
            dependencies { implementation(kotlin("test")) }
        }

        val nativeMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/nativeMain/kotlin")
            dependencies {
                implementation(project(":core:model"))
                implementation(project(":host:native"))
                implementation(project(":host:network"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }

        macosArm64Main.get().apply {
            dependsOn(nativeMain)
        }
        linuxX64Main.get().apply {
            dependsOn(nativeMain)
        }
        mingwX64Main.get().apply {
            dependsOn(nativeMain)
        }

        val nativeTest by creating {
            dependsOn(commonTest)
            kotlin.srcDir("src/nativeTest/kotlin")
            dependencies { implementation(kotlin("test")) }
        }

        macosArm64Test.get().dependsOn(nativeTest)
        linuxX64Test.get().dependsOn(nativeTest)
        mingwX64Test.get().dependsOn(nativeTest)
    }
}

// Keep the repository-wide JVM regression gate after migrating the CLI module
// to KMP; native tests are target-specific tasks below.
tasks.register("test") { dependsOn("jvmTest") }

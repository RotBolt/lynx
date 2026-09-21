plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val linuxNativeLibDir = providers.environmentVariable("LYNX_LINUX_LIB_DIR").orNull
val allowLinuxSharedUndefined = providers.environmentVariable("LYNX_LINUX_ALLOW_SHLIB_UNDEFINED").orNull == "true"

kotlin {
    macosArm64()
    linuxX64()
    mingwX64()

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
        }
    }
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
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies { api(project(":core:model")) }
        }
        val posixMain by creating { dependsOn(commonMain) }
        val macosArm64Main by getting { dependsOn(posixMain) }
        val linuxX64Main by getting { dependsOn(posixMain) }
        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
        val posixTest by creating { dependsOn(commonTest) }
        val macosArm64Test by getting { dependsOn(posixTest) }
        val linuxX64Test by getting { dependsOn(posixTest) }
    }
}

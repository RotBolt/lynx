plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val linuxNativeLibDir = providers.environmentVariable("LYNX_LINUX_LIB_DIR").orNull
val allowLinuxSharedUndefined = providers.environmentVariable("LYNX_LINUX_ALLOW_SHLIB_UNDEFINED").orNull == "true"

kotlin {
    macosArm64 {
        binaries { executable { baseName = "lynx"; entryPoint = "dev.lynx.nativecli.main" } }
    }
    linuxX64 {
        binaries {
            all {
                linuxNativeLibDir?.let { linkerOpts("-L$it") }
                if (allowLinuxSharedUndefined) linkerOpts("-Wl,--allow-shlib-undefined")
            }
            executable {
                baseName = "lynx"
                entryPoint = "dev.lynx.nativecli.main"
            }
        }
    }
    mingwX64 {
        binaries { executable { baseName = "lynx"; entryPoint = "dev.lynx.nativecli.main" } }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core:model"))
                implementation(project(":host:native"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        val commonTest by getting { dependencies { implementation(kotlin("test")) } }
    }
}

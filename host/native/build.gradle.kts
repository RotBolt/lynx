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

    linuxX64 {
        binaries.all {
            linuxNativeLibDir?.let { linkerOpts("-L$it") }
            if (allowLinuxSharedUndefined) linkerOpts("-Wl,--allow-shlib-undefined")
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

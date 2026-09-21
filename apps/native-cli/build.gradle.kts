plugins {
    kotlin("multiplatform")
}

kotlin {
    macosArm64 {
        binaries { executable { entryPoint = "dev.lynx.nativecli.main" } }
    }
    linuxX64 {
        binaries { executable { entryPoint = "dev.lynx.nativecli.main" } }
    }
    mingwX64 {
        binaries { executable { entryPoint = "dev.lynx.nativecli.main" } }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core:model"))
                implementation(project(":host:native"))
            }
        }
        val commonTest by getting { dependencies { implementation(kotlin("test")) } }
    }
}

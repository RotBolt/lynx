plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    macosArm64 {
        binaries { executable { baseName = "lynx"; entryPoint = "dev.lynx.nativecli.main" } }
    }
    linuxX64 {
        binaries { executable { baseName = "lynx"; entryPoint = "dev.lynx.nativecli.main" } }
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

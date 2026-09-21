plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

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
        }
    }
    linuxX64 {
        compilations.getByName("main").cinterops {
            create("lynxSsl") {
                defFile(project.file("src/nativeInterop/cinterop/lynx-ssl-linux.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
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
    }
}

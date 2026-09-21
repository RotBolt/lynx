plugins {
    kotlin("multiplatform")
}

kotlin {
    macosArm64()
    linuxX64()
    mingwX64()

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

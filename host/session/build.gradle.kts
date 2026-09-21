plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

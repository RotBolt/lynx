plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.lynx.cli.MainKt")
    applicationName = "lynx"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":host:session"))
    implementation(project(":host:daemon"))
    implementation(project(":host:adb"))
    implementation(project(":host:network"))
    implementation(project(":host:database"))
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

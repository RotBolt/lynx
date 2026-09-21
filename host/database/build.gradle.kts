plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":host:daemon"))
    implementation(project(":host:adb"))
    implementation("org.xerial:sqlite-jdbc:3.51.3.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
tasks.test { useJUnitPlatform() }

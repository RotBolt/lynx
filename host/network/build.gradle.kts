plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":host:daemon"))
    implementation(project(":host:adb"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    // Bouncy Castle provides portable certificate generation for the local TLS MITM.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // Protocol codecs only; proxy lifecycle and evidence models remain Lynx-owned.
    implementation("io.netty:netty-all:4.1.115.Final")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
